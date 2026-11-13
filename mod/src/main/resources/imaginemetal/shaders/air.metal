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
	float4 size;       // xy the target's size in pixels; z MetaLODS' far fog start (blocks), w its density (0: none)
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

fragment float4 im_air_fs(ImAirOut in [[stage_in]], float4 dst [[color(0)]], depth2d<float> depth [[texture(0)]],
	constant ImFrame &f [[buffer(0)]], constant ImAirParams &a [[buffer(1)]]) {
	if (f.sunDir.w < 0.5 || f.flags.y != 0 || f.flags.z == 0) return dst;
	float d = depth.read(uint2(in.pos.xy));
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
	float farFog = 0.0;
	if (a.size.w > 0.0 && dist > 24.0) {
		float yw = f.fogParams.y + p.y;
		float hf = clamp(exp2(-(max(yw, 40.0) - 64.0) / 48.0), 0.35, 2.2);
		farFog = (1.0 - exp(-(dist - 24.0) / 700.0 * a.size.w * hf)) * saturate(f.fogParams.z);
	}
	if (dist < 6.0 && border <= 0.001 && farFog <= 0.001) return dst;
	float3 T = float3(1.0);
	float3 S = imAirFog(p, 1.0, f, T);
	float3 o = max(dst.rgb, 0.0);
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
		float3 hd = normalize(float3(p.x, max(p.y, 0.02 * dist), p.z));
		o = mix(o, pow(imPresent(imSkyColor(hd, f, false), f), 1.0 / 2.2), farFog);
	}
	if (border > 0.001) o = mix(o, pow(imPresent(imSkyColor(p / max(dist, 1e-3), f, false), f), 1.0 / 2.2), border);
	return float4(o, dst.a);
}
