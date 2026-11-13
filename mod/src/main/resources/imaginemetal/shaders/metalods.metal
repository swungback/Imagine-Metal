// MetaLODS: Imagine Metal's far terrain as voxel LODs (Voxy-style), drawn into the deferred lighting's G-buffer in the same
// pass as the real terrain (terrain.metal own_fs_g writes the same four attachments), so the far land is lit, shadowed, hazed
// and graded by the same im_defer_fs as everything near.
//
// Geometry: pages of up to 256 quads (two uints each, MetaMesher's layout) in one shared arena; each page belongs to one
// column and one face direction. One instanced draw: an instance per visible page, 4 corners per quad (index buffer
// {0 1 2 2 1 3} + 4q), the vertex stage reads its quad by vertex pulling.
#include <metal_stdlib>
using namespace metal;

// (0: the fragment stage hides what lies in chunks the real terrain draws. Off for the columns wholly outside them: a stage that
// can't discard keeps the GPU's hidden-surface removal fully on, so only the visible LOD pixels are shaded)
constant bool ML_MASK [[function_constant(0)]];

struct MLFrame {
	float4x4 clip;     // camera-relative world -> clip (Minecraft's, y up, reversed Z): the terrain's own transform this frame
	int4 camBlock;     // the camera's block (floor), w unused
	float4 camFrac;    // the camera's position minus camBlock; w unused
	float4 jitter;     // ImFrame.taa: xy the TAA's sub-pixel offset (clip units), z 1 = on, w the render scale
	int4 mask;         // where the real terrain is drawn: x, z the first chunk, size (chunks per side), words per row
	float4 params;     // x 1 = hide the LODs under the real terrain, y the reach (blocks), z 1 = debug: tint by level, w the sea level (0: none)
};

struct PageInfo {
	int ox, oy, oz;    // the column's low corner in blocks (oy: the dimension's lowest y)
	uint packed;       // bits 0-3 level, 4-6 face, 8-16 quads in the page
};

struct Model {
	float4 topUv;      // the top sprite's rectangle in the block atlas (u0 v0 u1 v1; all 0: none, the average colour)
	float4 sideUv;
	uint topAvg, sideAvg;  // their average colours (0xRRGGBB, as stored)
	uint flags;        // bits 0-2 the top's tint, 3-5 the side's (1 grass, 2 foliage, 3 water, 4 dry foliage, 5 constant), 8-15
	                   // Imagine Metal's material byte, 16 leaves, 17 water, 18-20 the side overlay's tint
	uint fixedTint;    // a constant tint (0xRRGGBB)
	float4 overUv;     // (fix7) the side's tinted overlay (the grass block's fringe; all 0: none)
};

struct MLOut {
	float4 pos [[position]];
	float2 uv;                 // on the face's plane, in blocks (wrapped every 1024)
	float3 rel;                // camera-relative position (blocks)
	uint id [[flat]];
	uint face [[flat]];
	uint light [[flat]];       // sky | block << 4
	uint biome [[flat]];
	uint level [[flat]];
	uint ao [[flat]];          // the quad's corner shading (MetaMesher: corner u + 2 v at bits 2 (u + 2 v), 3 = open)
	float2 qv;                 // where on the quad, in its voxels (u along its width, v along its height)
};

vertex MLOut ml_vs(uint vid [[vertex_id]], uint iid [[instance_id]], constant MLFrame &f [[buffer(0)]],
	device const uint *visible [[buffer(1)]], device const PageInfo *pages [[buffer(2)]], device const uchar *quads [[buffer(3)]],
	device const Model *models [[buffer(4)]]) {
	MLOut o;
	uint page = visible[iid];
	PageInfo pi = pages[page];
	uint count = (pi.packed >> 8) & 511u;
	uint q = vid >> 2, c = vid & 3u;
	o.id = 0u;
	o.face = 0u;
	o.light = 0u;
	o.biome = 0u;
	o.level = 0u;
	o.ao = 0xFFu;
	o.qv = float2(0.0);
	o.uv = float2(0.0);
	o.rel = float3(0.0);
	if (q >= count) {
		o.pos = float4(0.0, 0.0, -2.0, 1.0);  // (past the page's quads: outside the clip volume)
		return o;
	}
	uint level = pi.packed & 15u, face = (pi.packed >> 4) & 7u;
	// (a page: 256 quads of 8 bytes, then a byte of corner shading each: MetaArena.PAGE_BYTES)
	device const uchar *pg = quads + page * 2304u;
	uint2 qd = ((device const uint2 *) pg)[q];
	float x = float(qd.x & 31u), z = float((qd.x >> 5) & 31u), y = float((qd.x >> 10) & 511u);
	float w = float(((qd.x >> 22) & 31u) + 1u), h = float(((qd.x >> 27) & 31u) + 1u);
	float u = float(c & 1u), v = float(c >> 1);
	float3 p;
	switch (face) {
		case 0u: p = float3(x + 1.0, y + v * h, z + u * w); break;
		case 1u: p = float3(x, y + v * h, z + u * w); break;
		case 2u: p = float3(x + u * w, y + 1.0, z + v * h); break;
		case 3u: p = float3(x + u * w, y, z + v * h); break;
		case 4u: p = float3(x + u * w, y + v * h, z + 1.0); break;
		default: p = float3(x + u * w, y + v * h, z); break;
	}
	float s = float(1u << level);
	// (water's top at 8/9 of a block, as the real water's surface: a full block stood 1/9 higher, and where the near terrain hands
	// over to the LODs the gap under that edge showed the LOD seabed as a dark line across lakes (Jack's 16.43.12, 16.43.45))
	int3 org = int3(pi.ox, pi.oy, pi.oz);
	if (face != 3u && (v > 0.5 || face == 2u) && ((models[qd.y & 0xFFFFu].flags >> 17) & 1u) != 0u) {
		// (seam fix: a coarser level's water voxel s blocks tall had its top on its own boundary, at 64 for the sea at every level
		// from 1 up, a block above the real surface (62 + 8/9) and level 0's; where two levels met, the step's side drew a dark
		// line straight across lakes and the sea (Jack's 17.24.36). A water top whose voxel holds the sea's surface goes to it)
		float top = float(org.y) + p.y * s, sea = f.params.w;
		if (sea > 0.0 && top >= sea && top - s < sea) p.y = (sea - 1.0 / 9.0 - float(org.y)) / s;
		else p.y -= (1.0 / 9.0) / s;
	}
	float3 rel = float3(org - f.camBlock.xyz) + p * s - f.camFrac.xyz;
	o.pos = f.clip * float4(rel, 1.0);
	o.pos.y = -o.pos.y;  // the backend's row order (as terrain.metal's own_vs)
	// (reversed Z: a hair farther, 0.05 % of the distance, so in a chunk the real terrain draws only part of (the LODs stay until it
	// draws the whole chunk) its faces win over the LOD's copy of the same blocks instead of flickering with them)
	o.pos.z *= 0.9995;
	if (f.jitter.z > 0.5) o.pos.xy += float2(f.jitter.x, -f.jitter.y) * o.pos.w;
	float3 wb = float3(org & int3(1023)) + p * s;
	o.uv = face < 2u ? wb.zy : face < 4u ? wb.xz : wb.xy;
	o.rel = rel;
	o.id = qd.y & 0xFFFFu;
	o.biome = (qd.y >> 16) & 255u;
	o.light = qd.y >> 24;
	o.face = face;
	o.level = level;
	o.ao = pg[2048u + q];
	o.qv = float2(u * w, v * h);
	return o;
}

struct MLGOut {
	float4 g1 [[color(1)]];
	float4 g2 [[color(2)]];
	float4 g3 [[color(3)]];
	float g4 [[color(4)]];
};

static inline float2 mlOctEnc(float3 n) {
	n /= (abs(n.x) + abs(n.y) + abs(n.z));
	float2 o = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
	return o * 0.5 + 0.5;
}

static inline float3 mlRgb(uint c) {
	return float3(float((c >> 16) & 255u), float((c >> 8) & 255u), float(c & 255u)) / 255.0;
}

fragment MLGOut ml_fs(MLOut in [[stage_in]], constant MLFrame &f [[buffer(0)]], device const Model *models [[buffer(4)]],
	device const uint4 *biomes [[buffer(5)]], constant uint *mask [[buffer(6)]], texture2d<float> atlas [[texture(16)]],
	sampler smp [[sampler(14)]]) {
	// where the real terrain is drawn, it shows instead (the LODs there are only the estimate of what it holds)
	if (ML_MASK && f.params.x > 0.5 && f.mask.z > 0) {
		int2 wb = f.camBlock.xz + int2(floor(in.rel.xz + f.camFrac.xz));
		int2 ch = (wb - (wb & int2(15))) / 16 - f.mask.xy;  // (floor division by 16, negatives too)
		if (ch.x >= 0 && ch.y >= 0 && ch.x < f.mask.z && ch.y < f.mask.z) {
			uint bit = uint(ch.y) * uint(f.mask.w) * 32u + uint(ch.x);
			if (((mask[bit >> 5] >> (bit & 31u)) & 1u) != 0u) discard_fragment();
		}
	}
	Model m = models[in.id];
	bool flat = in.face == 2u || in.face == 3u;
	float4 r = flat ? m.topUv : m.sideUv;
	uint kind = flat ? (m.flags & 7u) : ((m.flags >> 3) & 7u);
	float3 avg = mlRgb(flat ? m.topAvg : m.sideAvg);
	float4 tex = float4(avg, 1.0);
	if (r.z > r.x) {
		float2 t = fract(in.uv);
		if (!flat) t.y = 1.0 - t.y;
		// (the face's mip from the unwrapped coordinates: fract's jumps don't count)
		float2 texels = (r.zw - r.xy) * float2(atlas.get_width(), atlas.get_height());
		float2 dx = dfdx(in.uv) * texels, dy = dfdy(in.uv) * texels;
		float lod = 0.5 * log2(max(max(dot(dx, dx), dot(dy, dy)), 1e-8));
		float2 st = mix(r.xy, r.zw, t);
		float4 smpd = atlas.sample(smp, st, level(clamp(lod, 0.0, 4.0)));
		// (far off: the average, so no mip can bleed in a neighbour's colours)
		float far = smoothstep(2.5, 4.5, lod);
		tex = float4(mix(smpd.rgb, avg, far), mix(smpd.a, 1.0, far));
	}
	bool leaves = (m.flags & (1u << 16)) != 0u, water = (m.flags & (1u << 17)) != 0u;
	if (tex.a < 0.5) tex.rgb = avg * (leaves ? 0.72 : 1.0);  // (leaves' holes: the canopy's shade inside)
	uint4 bt = biomes[in.biome];
	uint tc = kind == 1u ? bt.x : kind == 2u ? bt.y : kind == 3u ? bt.z : kind == 4u ? bt.w : kind == 5u ? m.fixedTint : 0xFFFFFFu;
	float3 color = tex.rgb * mlRgb(tc);
	if (!flat && m.overUv.z > m.overUv.x) {
		// (fix7: the grass block's side, its tinted overlay laid on as the game does: the strip along its top took the side
		// texture's own pale default green, Jack's 13.55.21 "grass color on sides doesnt match")
		uint ok = (m.flags >> 18) & 7u;
		uint otc = ok == 1u ? bt.x : ok == 2u ? bt.y : ok == 3u ? bt.z : ok == 4u ? bt.w : 0xFFFFFFu;
		float2 t = fract(in.uv);
		t.y = 1.0 - t.y;
		float2 ot = (m.overUv.zw - m.overUv.xy) * float2(atlas.get_width(), atlas.get_height());
		float2 dx = dfdx(in.uv) * ot, dy = dfdy(in.uv) * ot;
		float olod = 0.5 * log2(max(max(dot(dx, dx), dot(dy, dy)), 1e-8));
		float4 ov = atlas.sample(smp, mix(m.overUv.xy, m.overUv.zw, t), level(clamp(olod, 0.0, 4.0)));
		color = mix(color, ov.rgb * mlRgb(otc), ov.a);
	}
	// (fix7: ambient occlusion, Jack's "corner darkening" on the far land as DH + Gael have it: each voxel of the quad shaded
	// between its four corners' values, vanilla's smooth-light steps)
	if (in.ao != 0xFFu) {
		float2 t = fract(in.qv);
		float4 k = float4(float(in.ao & 3u), float((in.ao >> 2) & 3u), float((in.ao >> 4) & 3u), float((in.ao >> 6) & 3u));
		k = 0.5 + k * (0.5 / 3.0) + (k * (3.0 - k)) * (0.05 / 2.0);  // (0 0.5, 1 0.72, 2 0.88, 3 1)
		float a = mix(mix(k.x, k.y, t.x), mix(k.z, k.w, t.x), t.y);
		color *= a;
	}
	if (water) color *= 0.85;
	// (fix5: each LOD block a little lighter or darker than its neighbours, as Distant Horizons' noise texture (Jack's 16.04.19):
	// one flat colour per face read as plastic. +-7 % per block of its level, not on water)
	if (!water) {
		float3 fn = float3(in.face == 0u ? 1.0 : in.face == 1u ? -1.0 : 0.0, in.face == 2u ? 1.0 : in.face == 3u ? -1.0 : 0.0,
			in.face == 4u ? 1.0 : in.face == 5u ? -1.0 : 0.0);
		int3 wbk = f.camBlock.xyz + int3(floor(in.rel + f.camFrac.xyz - fn * 0.02));
		int3 vq = wbk >> int3(in.level);
		uint hsh = uint(vq.x) * 73856093u ^ uint(vq.y) * 19349663u ^ uint(vq.z) * 83492791u;
		hsh = (hsh ^ (hsh >> 13)) * 1274126177u;
		color *= 0.93 + 0.14 * float(hsh >> 24) / 255.0;
	}
	if (!water) {
		// (fix7: the far land less saturated and a little darker, as DH + Gael's (Jack's 16.02-16.04): ours read a flat
		// yellow-green. Eased in over the 384 blocks past the real terrain's edge, so the hand-over itself shows no change of
		// colour; 20 % less saturation and 6 % darker at most)
		float edge = float(f.mask.z) * 8.0;
		float k = smoothstep(edge, edge + 384.0, length(in.rel.xz));
		float l = dot(color, float3(0.2126, 0.7152, 0.0722));
		color = mix(color, float3(l), 0.2 * k) * (1.0 - 0.06 * k);
	}
	if (f.params.z > 0.5) {
		// (debug: each level its own colour: 0 red, 1 orange, 2 yellow, 3 green, 4 cyan, 5 blue)
		const float3 lc[6] = {float3(1.0, 0.3, 0.3), float3(1.0, 0.6, 0.2), float3(1.0, 1.0, 0.3), float3(0.3, 1.0, 0.3), float3(0.3, 1.0, 1.0), float3(0.4, 0.4, 1.0)};
		color = mix(color, lc[min(in.level, 5u)], 0.6);
	}
	uint mat = (m.flags >> 8) & 255u;
	if (water && in.face == 2u) {
		// (LOD water's top: shaded as the real water is by the deferred lighting (terrain.metal im_defer_fs, material 0x80: a
		// class-0 byte with bit 7, which no block has). Its colour as the real water's surface (the biome's colour toward one
		// turquoise, terrain.metal's water branch), not the texture tinted and lit as ground: that read cyan beside the real
		// water's deep blue (Jack's 17.24.36, 17.26.15))
		float3 wc = mlRgb(bt.z);
		wc.g = max(wc.g, 0.39);
		float3 wn = wc / max(max(wc.r, max(wc.g, wc.b)), 1e-3);
		wn = mix(wn, float3(0.3, 0.9, 0.85), 0.5);
		float tl = mix(0.62, dot(tex.rgb, float3(0.299, 0.587, 0.114)), 0.35);
		color = wn * (0.34 + 0.3 * tl);
		mat = 0x80u;
	}
	uint axis = in.face;  // (the G-buffer's axis numbering is ours: +x -x +y -y +z -z)
	float3 n = float3(axis == 0u ? 1.0 : axis == 1u ? -1.0 : 0.0, axis == 2u ? 1.0 : axis == 3u ? -1.0 : 0.0, axis == 4u ? 1.0 : axis == 5u ? -1.0 : 0.0);
	float2 lq = float2(float((in.light >> 4) & 15u), float(in.light & 15u)) / 15.0;
	// (a floor on the sky light: real chunks' air under a thick canopy has sky light 0, and the coarser levels' merged leaves
	// turn faces toward it, which the lighting drew pitch black (Jack's 15.42.23 "whats the black"). Caves are filled in, so
	// what is left under 10 is canopy and overhang shade, which from afar reads as shade, not as a cave)
	lq.y = max(lq.y, 10.0 / 15.0);
	MLGOut o;
	o.g1 = float4(saturate(color), float(axis << 1) / 255.0);
	o.g2 = float4(saturate(tex.rgb), float(mat) / 255.0);
	o.g3 = float4((floor(saturate(lq) * 4095.0 + 0.5) * 16.0 + 8.0) / 65535.0, mlOctEnc(n));
	o.g4 = in.pos.z;
	return o;
}
