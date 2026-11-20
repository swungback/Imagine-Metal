// Imagine Metal: the sun/moon shadow map's casters.
//
// Appended after mcopt's terrain library (mcopt/own/terrain.metal), so it reads the terrain's own buffers the way own_vs does:
// the units OwnTerrain.shadowCull listed for the light (lists of record ids, or Inst entries with FAT), their quads in the
// arena (compact vertices, or packed quad records with OWN_QREC). Only the default path of own_vs: solid and cutout units, no
// translucent order, no per-quad lists. Function constants: COMPACT (1), FAT (2), as the terrain library was built.
//
// The map: two cascades, each an orthographic view along the light, camera-relative, snapped to a world-fixed texel grid on the
// Java side (imaginemetal.Shadows), so a block casts the same texels wherever the player stands. No warp: a warp re-centred on the
// player re-rasterizes every caster as they walk, which made leaf shadows shimmer. Standard depth (0 nearest the light, cleared to 1).

struct ImShadowFrame {
	float4x4 lightClip;   // camera-relative world -> light clip (orthographic, before the warp)
	int4 camBlock;        // xyz floor(camera)
	float4 camOffset;     // xyz camera block - camera position (the terrain's CameraOffset)
	float4 reserved;      // x which casters (Build C2): 0 as C1 (no small plants), 1 all but plants, 2 plants only (their own slice);
	                      // y Minecraft's GameTime (the waving's clock, as the terrain's Globals have it)
	uint listBase;
	uint pad0, pad1, pad2;
};

struct ImShadowOut {
	float4 pos [[position]];
	float2 uv;
	uint fill [[flat]];  // (fix8) a culled tree's outer leaf face with its holes filled (terrain.metal): it casts whole
};

static Vtx imShadowFetch(uint vi, device const void *arenaRaw) {
	Vtx v;
	if (OWN_REC && COMPACT) {
		v = ownQrec(arenaRaw, vi >> 2, vi & 3); // (vi = record id * 4 + corner)
	} else if (COMPACT) {
		CVtx c = ((device const CVtx *) arenaRaw)[vi];
		v.pos = packed_float3(OWN_POS3(arenaRaw, c.px, c.py, c.pz));
		v.color = c.color;
		v.uv0 = packed_float2(float2(c.u, c.v) / 65536.0);
		v.uv2 = short2(c.lu, c.lv);
	} else {
		v = ((device const Vtx *) arenaRaw)[vi];
	}
	return v;
}

vertex ImShadowOut im_shadow_vs(uint vid [[vertex_id]], uint iid [[instance_id]], constant ImShadowFrame &f [[buffer(20)]],
	device const void *arenaRaw [[buffer(16)]], device const Section *sections [[buffer(17)]], device const Rec *recs [[buffer(18)]],
	device const uint *lists [[buffer(19)]]) {
	ImShadowOut o;
	o.fill = 0u;
	uint quadStart, meta;
	int3 origin;
	uint matOff = 0xffffffffu;
	if (FAT) {
		Inst e = ((device const Inst *) lists)[f.listBase + iid];
		quadStart = e.quadStart;
		meta = e.meta;
		origin = int3(e.x, e.y, e.z);
	} else {
		Rec rec = recs[lists[f.listBase + iid]];
		Section s = sections[rec.slot];
		quadStart = rec.quadStart;
		meta = rec.meta;
		origin = int3(s.x, s.y, s.z);
		matOff = rec.pad0;
	}
	uint quad = vid >> 2, corner = vid & 3;
	if (quad > (meta & 63)) {
		o.pos = float4(0, 0, 2, 1); // past the unit's quads: outside the clip volume
		o.uv = float2(0);
		return o;
	}
	// Plants: quads that wave but aren't leaves (1111) or vines (0101): grass, flowers, ferns, crops (their upper corners) and a
	// tall plant's upper half (1010, Build C2). Mode 0 (as C1): small plants cast none (their thin shadows lay detached beside
	// them, "ghost copies": the receivers' bias for big flat faces pushed them off the plant's foot), tall plants' tops cast here.
	// Mode 1: no plants (they have their own slice). Mode 2: plants only, swaying as they do on screen (terrain.metal).
	uint mode = uint(f.reserved.x + 0.5);
	bool plant = false;
	if (matOff != 0xffffffffu) {
		uint m = ((device const uchar *) arenaRaw)[matOff + quad];
		uint wc = (m >> 4) & 15u, cls = m & 15u;
		plant = cls != 4u && cls != 6u && wc != 0u && wc != 15u && wc != 13u && wc != 5u;
		o.fill = 0u;  // (fix9: 1101 is a bushy plane now, not a filled face)
		bool skip = mode == 0u ? plant && wc != 10u : mode == 1u ? plant : !plant;
		// (fix9b: a bushy leaf plane (1101, BushyLeaves) casts none: the tree's own leaf faces do; the planes in the maps were
		// 1-2 ms of the shadow passes in a forest)
		if (cls != 4u && cls != 6u && wc == 13u) skip = true;
		if (skip) {
			o.pos = float4(0, 0, 2, 1);
			o.uv = float2(0);
			return o;
		}
		if (plant && mode == 2u && (wc == 10u || ((wc >> corner) & 1u) != 0u)) {
			// (the breeze of terrain.metal's own_vs, the same numbers: the shadow sways with the plant)
			Vtx w0 = imShadowFetch((quadStart + quad) * 4 + corner, arenaRaw);
			float3 wp = float3(origin & 1023) + float3(w0.pos);
			float tw = f.reserved.y * 1200.0;
			float ph = wp.x * 0.55 + wp.z * 0.38;
			float3 d = float3(sin(tw * 1.6 + ph) * 0.7 + sin(tw * 2.9 + wp.z * 1.3) * 0.3,
				sin(tw * 2.1 + wp.x * 0.9 + wp.z * 0.7) * 0.15,
				cos(tw * 1.3 + ph * 0.8) * 0.6 + sin(tw * 3.3 + wp.x * 1.1) * 0.3) * 0.065;
			float3 pos = float3(w0.pos) + d + float3(origin - f.camBlock.xyz) + f.camOffset.xyz;
			o.pos = f.lightClip * float4(pos, 1.0);
			o.uv = float2(w0.uv0);
			return o;
		}
	} else if (mode == 2u) {
		o.pos = float4(0, 0, 2, 1);  // (no material bytes: nothing is known to be a plant)
		o.uv = float2(0);
		return o;
	}
	Vtx v = imShadowFetch((quadStart + quad) * 4 + corner, arenaRaw);
	float3 pos = float3(v.pos) + float3(origin - f.camBlock.xyz) + f.camOffset.xyz;
	o.pos = f.lightClip * float4(pos, 1.0);
	o.uv = float2(v.uv0);
	return o;
}

// cutout casters (leaves, plants, glass panes' frames...): holes where the texture is see-through
fragment void im_shadow_cutout_fs(ImShadowOut in [[stage_in]], texture2d<float> atlas [[texture(0)]], sampler s [[sampler(0)]]) {
	if (in.fill == 0u && atlas.sample(s, in.uv, level(0.0)).a < 0.5) discard_fragment();
}
