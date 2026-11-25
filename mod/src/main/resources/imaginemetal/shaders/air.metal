// Imagine Metal: the air pass (Build B1). Appended to the terrain library after shadow.metal (Engine.terrainEpilogue), so it has
// lighting.metal's ImFrame, imAirFog, imSkyColor and the tone curve.
//
// One full-screen draw over the opaque world, right before the translucent pass (imssr.m im_under_capture), reading the depth
// copy taken there: Photon's air fog and Reimagined's fade into the sky at the edge of the render distance, once per screen
// pixel. The terrain's opaque fragments used to do both themselves, for every fragment of every small leaf triangle (the
// benchmark's "sky and air": 3 ms at the jungle spot). Mobs get the same air now; the translucent pass (water, glass) still
// does its own, as it is drawn after this.
//
// The picture is already through the tone curve and the grade (display-encoded) when this runs, and the haze belongs before
// them. The curve has d = 1 (ImagineFrame TM_D), so it can be taken back out exactly per channel: x^a = c y / (1 - b y). The
// light is brought back, the air laid over it, the curve applied again; the grade is given again only to the share the air
// added. A pixel the air doesn't touch comes out as it went in.
struct ImAirParams {
	float4x4 clipInv;  // clip (Minecraft's, y up; depth 0..1 reversed) -> camera-relative world, this frame's terrain transform
	float4x4 clip;
	float4 size;       // xy output pixels, z distant ambient occlusion strength, w 1 = distant contact shadows
};

struct ImAirOut {
	float4 pos [[position]];
};

vertex ImAirOut im_air_vs(uint vid [[vertex_id]]) {
	ImAirOut o;
	float2 q = float2((vid << 1) & 2, vid & 2);
	o.pos = float4(q * 2.0 - 1.0, 0.0, 1.0);
	return o;
}

// Perspective depth is reversed. Convert to camera-forward units so both effects use block-scale thresholds.
static float imAirForwardDepth(float d, constant ImAirParams &a) {
	return 1.0 / max(a.clipInv[2].w * d + a.clipInv[3].w, 1e-6);
}

// Distant crease shading, the way Complementary shades Distant Horizons' terrain (deferred1 DoAmbientOcclusionDH): around each
// pixel, pairs of depth samples on opposite sides about 0.8 blocks out (a world-scale radius, so a block's step reads the same
// near and far). A pair on one flat face cancels (one side nearer, the other as much farther); where both sides stand in front of
// the pixel it sits in a crease, at the foot of a step or in a gap, and darkens. Samples far in front (another hill's edge) count
// as open again, so silhouettes get no halo. The response is steep, as theirs: flat faces untouched, creases down to half.
// The s1125 replacement for s1121's two-pixel cross, which caught only the narrowest creases (ridge relief 5.9 vs DH's 8.7).
static float imAirCreaseAo(depth2d<float> depth, float center, float2 pixel, constant ImFrame &f, constant ImAirParams &a) {
	float rpx = 0.4 * a.size.y * abs(a.clip[1][1]) / max(center, 1.0);  // (0.8 blocks in pixels)
	if (rpx < 1.0) return 1.0;
	float dith = imDither(pixel, f);
	int2 limit = int2(a.size.xy) - 1;
	float open = 0.0;
	for (uint i = 0; i < 4; i++) {
		float t = (float(i) + dith) * 0.25;
		float ang = (fract(t * 1.41421356) + dith + 0.0382 * float(i)) * 6.2831853;
		float2 o = float2(cos(ang), sin(ang)) * (t * rpx);
		float d1 = 0.9 * (center - imAirForwardDepth(depth.read(uint2(clamp(int2(pixel + o), int2(0), limit))), a));
		float d2 = 0.9 * (center - imAirForwardDepth(depth.read(uint2(clamp(int2(pixel - o), int2(0), limit))), a));
		open += saturate(saturate(0.5 - d1) + saturate(0.5 - d2) + saturate(0.25 * d1 - 0.5) + saturate(0.25 * d2 - 0.5));
	}
	float s = smoothstep(0.0, 1.0, open * 0.25);
	float ao = pow(max(s, 1e-4), 10.7 * a.size.z);  // (7.5 at the default AO strength 0.7, as theirs at 100 %)
	return mix(0.35, ao, ao);  // (a full crease keeps a third of its light: theirs is half, in light before their tone curve, which ours is applied after)
}

// Returns a multiplier for linear light (the caller applies it to the display-encoded picture).
static float imAirFarRelief(depth2d<float> depth, float3 p, float center, float2 pixel, float dist,
	constant ImFrame &f, constant ImAirParams &a) {
	// (in where the near SSAO (bloom.metal, 20 to 48 blocks) runs out; out where a block is under two pixels and the haze is thick)
	float weight = smoothstep(28.0, 60.0, dist) * (1.0 - smoothstep(420.0, 640.0, dist)) * f.fogParams.z;
	if (weight <= 0.001 || (a.size.z <= 0.0 && a.size.w <= 0.0)) return 1.0;
	float lit = 1.0;
	if (a.size.z > 0.0) lit *= imAirCreaseAo(depth, center, pixel, f, a);
	// Short sun shadows from the depth buffer (Complementary's GetLODShadows): steps toward the light, shadowed where something
	// stands less than five blocks in front of the ray. The shadow maps already shade most of this; this adds the block-scale
	// shadows the far map's texels are too coarse for, so it is kept to a third of the light.
	float sun = f.nightMiddle.w * (1.0 - f.lightColor.w);
	if (a.size.w > 0.0 && sun > 0.05 && f.lightDir.y > 0.08) {
		float3 origin = p - p * (0.32 / dist);
		float4 projected = a.clip * float4(origin, 1.0);
		float4 ray = a.clip * float4(f.lightDir.xyz, 0.0);
		float start = 0.2 + imDither(pixel + 17.0, f);
		float shadow = 1.0;
		// (8 steps of 2.5 blocks, as theirs step: 12 of 2 cost about as much again as the creases for the same shadows)
		for (uint i = 0; i < 8; i++) {
			float4 q = projected + ray * (2.5 * (float(i) + start));
			if (q.w <= 0.05) break;
			float2 ndc = q.xy / q.w + (f.taa.z > 0.5 ? f.taa.xy : float2(0.0));
			float2 uv = ndc * 0.5 + 0.5;
			if (any(uv <= 0.0) || any(uv >= 1.0)) break;
			float d = depth.read(uint2(uv * a.size.xy));
			float behind = q.w - imAirForwardDepth(d, a);
			shadow *= 1.0 - smoothstep(0.05, 0.3, behind) * (1.0 - smoothstep(4.0, 5.0, behind));
			if (shadow < 0.01) break;
		}
		lit *= 1.0 - 0.35 * sun * (1.0 - shadow);
	}
	return mix(1.0, lit, weight);
}

fragment float4 im_air_fs(ImAirOut in [[stage_in]], float4 dst [[color(0)]], depth2d<float> depth [[texture(0)]],
	constant ImFrame &f [[buffer(0)]], constant ImAirParams &a [[buffer(1)]]) {
	float d = depth.read(uint2(in.pos.xy));
	if (f.lodFog.y > 0.5) {
		// Magenta means no opaque depth, not "white fog". Near translucents are suppressed.
		if (d <= 0.0) return float4(1.0, 0.0, 1.0, 1.0);
		if (f.lodFog.y < 2.5) return dst;
		float2 dc = in.pos.xy / a.size.xy * 2.0 - 1.0;
		if (f.taa.z > 0.5) dc -= f.taa.xy;
		float4 dw = a.clipInv * float4(dc, d, 1.0);
		float grey = saturate(log2(1.0 + length(dw.xyz / dw.w)) / 12.0);
		return float4(float3(grey), 1.0);
	}
	if (f.sunDir.w < 0.5 || f.flags.y != 0 || f.flags.z == 0) return dst;
	if (d <= 0.0) return dst;  // (the sky and the clouds: they are the air's far end already)
	// (the picture's rows run as Minecraft's clip space does, y up: imReflect and the bloom read it the same way; the terrain's
	// sub-pixel offset taken back out)
	float2 ndc = in.pos.xy / a.size.xy * 2.0 - 1.0;
	if (f.taa.z > 0.5) ndc -= f.taa.xy;
	float4 w = a.clipInv * float4(ndc, d, 1.0);
	float3 p = w.xyz / w.w;
	float dist = length(p);
	float bf = dist / max(f.fogParams.x, 16.0);
	bf *= bf;
	bf *= bf;
	bf *= bf;
	bf *= bf;
	float border = 1.0 - exp(-3.0 * bf);
	// MetaLODS' far fog (as Distant Horizons' far fog): past the real terrain's edge the land fades into the horizon's colour,
	// growing with the square of the distance into the LODs' reach, so the far LODs read as distance, not as blocks (Jack: Gael's
	// with DH "looks better cus of fog in distance", it "hides the ugly lods")
	// (fix5, after Jack's DH + Gael screenshots 16.02-16.04: there the haze is already there ~100 blocks out and builds ridge
	// after ridge, thicker low down (valleys and rivers full of it, hilltops clearer). So: aerial perspective over near land and
	// LODs alike (no step at the hand-over), from 24 blocks, exponential with distance (700 blocks per e-fold at strength 1),
	// denser below ~64 and thinner up high, by the height of the point seen; none in caves (fogParams.z = 1 - cave))
	float farFog = imLodFogAmount(p, f);
	if (dist < 6.0 && border <= 0.001 && farFog <= 0.001) return dst;
	float3 T = float3(1.0);
	float3 S = imAirFog(p, 1.0, f, T);
	float3 o = max(dst.rgb, 0.0);
	// Shade the opaque surface before blending the haze over it. Real terrain uses the same distances.
	if (f.flags.x == 0 && f.lodFog.x > 0.0 && f.water.z < 0.5 && dist > 28.0 && dist < 640.0) {
		float relief = imAirFarRelief(depth, p, 1.0 / w.w, in.pos.xy, dist, f, a);
		// (a linear-light multiplier on a display-encoded picture: its 1/2.2 power; light given off past white keeps its glow)
		o *= mix(pow(relief, 1.0 / 2.2), 1.0, smoothstep(0.98, 1.4, max(max(o.r, o.g), o.b)));
	}
	if (any(T < 0.9995) || any(S > 1e-5)) {
		float3 y = pow(o, 2.2);
		float4 t = f.tonemap;
		float3 yn;
		if (abs(t.y - 1.0) < 1e-3) {
			// (what's past the curve's white is light given off, added after the curve: it only dims with the air)
			float3 yc = min(y, 0.97);
			float3 over = y - yc;
			float3 x = pow(max(t.w * yc / max(1.0 - t.z * yc, 1e-4), 0.0), 1.0 / t.x);
			yn = imTonemap(x * T + S * f.minLight.w, t);
			yn = mix(yn, imGrade(yn, f), 1.0 - dot(T, float3(1.0 / 3.0)));
			yn = max(yn, 0.0) + over * T;
		} else {
			yn = y * T + max(imGrade(imTonemap(S * f.minLight.w, t), f), 0.0);
		}
		o = pow(max(yn, 0.0), 1.0 / 2.2);
	}
	if (farFog > 0.001) {
		// (the horizon's colour in this direction: the land seen from above fades to the sky low down, not to the sky under it)
		o = mix(o, imLodFogColor(p, f), farFog);
	}
	if (border > 0.001) o = mix(o, pow(imPresent(imSkyColor(p / max(dist, 1e-3), f, false), f), 1.0 / 2.2), border);
	return float4(o, dst.a);
}
