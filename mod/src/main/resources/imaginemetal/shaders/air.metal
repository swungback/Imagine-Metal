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

static float imAirCrease(float nearA, float nearB) {
	float front = max(nearA, nearB);
	float back = -min(nearA, nearB);
	float local = smoothstep(0.18, 0.68, front) * (1.0 - smoothstep(2.7, 5.5, front));
	return local * (1.0 - smoothstep(0.32, 1.3, back));
}

static float imAirFarRelief(depth2d<float> depth, float3 p, float center, float2 pixel, float dist,
	constant ImFrame &f, constant ImAirParams &a) {
	float weight = smoothstep(54.0, 96.0, dist) * (1.0 - smoothstep(270.0, 410.0, dist)) * f.fogParams.z;
	if (weight <= 0.001 || (a.size.z <= 0.0 && a.size.w <= 0.0)) return 1.0;
	float shade = 0.0;
	if (a.size.z > 0.0) {
		int2 q = int2(pixel), limit = int2(a.size.xy) - 1;
		int r = dist < 145.0 ? 2 : 1;
		float x0 = imAirForwardDepth(depth.read(uint2(clamp(q - int2(r, 0), int2(0), limit))), a);
		float x1 = imAirForwardDepth(depth.read(uint2(clamp(q + int2(r, 0), int2(0), limit))), a);
		float y0 = imAirForwardDepth(depth.read(uint2(clamp(q - int2(0, r), int2(0), limit))), a);
		float y1 = imAirForwardDepth(depth.read(uint2(clamp(q + int2(0, r), int2(0), limit))), a);
		float crease = max(imAirCrease(center - x0, center - x1), imAirCrease(center - y0, center - y1));
		shade += 0.48 * crease * a.size.z;
	}
	float sun = f.nightMiddle.w * (1.0 - f.lightColor.w);
	if (a.size.w > 0.0 && sun > 0.05 && f.lightDir.y > 0.08) {
		float3 origin = p - p * (0.32 / dist);
		float4 projected = a.clip * float4(origin, 1.0);
		float4 ray = a.clip * float4(f.lightDir.xyz, 0.0);
		const float steps[6] = {1.25, 2.7, 4.8, 7.5, 11.5, 17.5};
		for (uint i = 0; i < 6; i++) {
			float4 q = projected + ray * steps[i];
			if (q.w <= 0.05) break;
			float2 ndc = q.xy / q.w + (f.taa.z > 0.5 ? f.taa.xy : float2(0.0));
			float2 uv = ndc * 0.5 + 0.5;
			if (any(uv <= 0.0) || any(uv >= 1.0)) break;
			float d = depth.read(uint2(uv * a.size.xy));
			float behind = q.w - imAirForwardDepth(d, a);
			if (behind > 0.24 && behind < 3.7) {
				shade += 0.25 * sun * smoothstep(0.24, 0.68, behind) * (1.0 - smoothstep(2.4, 3.7, behind))
					* (1.0 - 0.08 * float(i));
				break;
			}
		}
	}
	return 1.0 - weight * shade;
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
	if (f.flags.x == 0 && f.lodFog.x > 0.0 && f.water.z < 0.5 && dist > 54.0 && dist < 410.0) {
		float relief = imAirFarRelief(depth, p, 1.0 / w.w, in.pos.xy, dist, f, a);
		o *= mix(relief, 1.0, smoothstep(0.98, 1.4, max(max(o.r, o.g), o.b)));
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
