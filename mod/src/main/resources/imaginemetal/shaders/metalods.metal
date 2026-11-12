// MetaLODS: Imagine Metal's far terrain as voxel LODs (Voxy-style), drawn into the deferred lighting's G-buffer in the same
// pass as the real terrain (terrain.metal own_fs_g writes the same four attachments), so the far land is lit, shadowed, hazed
// and graded by the same im_defer_fs as everything near.
//
// Geometry: pages of up to 256 quads (two uints each, MetaMesher's layout) in one shared arena; each page belongs to one
// column and one face direction. One instanced draw: an instance per visible page, 4 corners per quad (index buffer
// {0 1 2 2 1 3} + 4q), the vertex stage reads its quad by vertex pulling.
#include <metal_stdlib>
using namespace metal;

struct MLFrame {
	float4x4 clip;     // camera-relative world -> clip (Minecraft's, y up, reversed Z): the terrain's own transform this frame
	int4 camBlock;     // the camera's block (floor), w unused
	float4 camFrac;    // the camera's position minus camBlock; w unused
	float4 jitter;     // ImFrame.taa: xy the TAA's sub-pixel offset (clip units), z 1 = on, w the render scale
	int4 mask;         // where the real terrain is drawn: x, z the first chunk, size (chunks per side), words per row
	float4 params;     // x 1 = hide the LODs under the real terrain, y the reach (blocks), zw unused
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
	                   // Imagine Metal's material byte, 16 leaves, 17 water
	uint constant;     // a constant tint (0xRRGGBB)
};

struct MLOut {
	float4 pos [[position]];
	float2 uv;                 // on the face's plane, in blocks (wrapped every 1024)
	float3 rel;                // camera-relative position (blocks)
	uint id [[flat]];
	uint face [[flat]];
	uint light [[flat]];       // sky | block << 4
	uint biome [[flat]];
};

vertex MLOut ml_vs(uint vid [[vertex_id]], uint iid [[instance_id]], constant MLFrame &f [[buffer(0)]],
	device const uint *visible [[buffer(1)]], device const PageInfo *pages [[buffer(2)]], device const uint2 *quads [[buffer(3)]]) {
	MLOut o;
	uint page = visible[iid];
	PageInfo pi = pages[page];
	uint count = (pi.packed >> 8) & 511u;
	uint q = vid >> 2, c = vid & 3u;
	o.id = 0u;
	o.face = 0u;
	o.light = 0u;
	o.biome = 0u;
	o.uv = float2(0.0);
	o.rel = float3(0.0);
	if (q >= count) {
		o.pos = float4(0.0, 0.0, -2.0, 1.0);  // (past the page's quads: outside the clip volume)
		return o;
	}
	uint level = pi.packed & 15u, face = (pi.packed >> 4) & 7u;
	uint2 qd = quads[page * 256u + q];
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
	int3 org = int3(pi.ox, pi.oy, pi.oz);
	float3 rel = float3(org - f.camBlock.xyz) + p * s - f.camFrac.xyz;
	o.pos = f.clip * float4(rel, 1.0);
	o.pos.y = -o.pos.y;  // the backend's row order (as terrain.metal's own_vs)
	if (f.jitter.z > 0.5) o.pos.xy += float2(f.jitter.x, -f.jitter.y) * o.pos.w;
	float3 wb = float3(org & int3(1023)) + p * s;
	o.uv = face < 2u ? wb.zy : face < 4u ? wb.xz : wb.xy;
	o.rel = rel;
	o.id = qd.y & 0xFFFFu;
	o.biome = (qd.y >> 16) & 255u;
	o.light = qd.y >> 24;
	o.face = face;
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
	if (f.params.x > 0.5 && f.mask.z > 0) {
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
	uint tc = kind == 1u ? bt.x : kind == 2u ? bt.y : kind == 3u ? bt.z : kind == 4u ? bt.w : kind == 5u ? m.constant : 0xFFFFFFu;
	float3 color = tex.rgb * mlRgb(tc);
	if (water) color *= 0.85;
	uint mat = (m.flags >> 8) & 255u;
	uint axis = in.face;  // (the G-buffer's axis numbering is ours: +x -x +y -y +z -z)
	float3 n = float3(axis == 0u ? 1.0 : axis == 1u ? -1.0 : 0.0, axis == 2u ? 1.0 : axis == 3u ? -1.0 : 0.0, axis == 4u ? 1.0 : axis == 5u ? -1.0 : 0.0);
	float2 lq = float2(float((in.light >> 4) & 15u), float(in.light & 15u)) / 15.0;
	MLGOut o;
	o.g1 = float4(saturate(color), float(axis << 1) / 255.0);
	o.g2 = float4(saturate(tex.rgb), float(mat) / 255.0);
	o.g3 = float4((floor(saturate(lq) * 4095.0 + 0.5) * 16.0 + 8.0) / 65535.0, mlOctEnc(n));
	o.g4 = in.pos.z;
	return o;
}
