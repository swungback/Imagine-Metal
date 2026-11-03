// Imagine Metal: the sky (imsky.m, imaginemetal.Sky). Compiled after lighting.metal (ImFrame, imSkyColor).
//
// Drawn once per frame into the level's picture at the start of Minecraft's sky pass, in place of its flat sky disc and sunset fan
// (SkyRendererMixin): one triangle over the screen, every pixel the sky colour in its view direction. Minecraft's sun, moon and
// stars are drawn over it as usual, then the level. About 0.1 ms on an M1 at 1440x900.

struct ImSkyOut {
	float4 pos [[position]];
	float2 ndc;
};

vertex ImSkyOut im_sky_vs(uint vid [[vertex_id]]) {
	float2 p = float2((vid << 1) & 2, vid & 2);
	ImSkyOut o;
	o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0);
	o.ndc = p * 2.0 - 1.0;
	return o;
}

// rot: clip -> view direction (the inverse of the camera's rotation and projection, no translation)
fragment float4 im_sky_fs(ImSkyOut in [[stage_in]], constant ImFrame &f [[buffer(0)]], constant float4x4 &rot [[buffer(1)]]) {
	// (the backend flips vertex y so its images keep GL's row order: this pixel is where clip y = -ndc.y lands)
	float4 q = rot * float4(in.ndc.x, -in.ndc.y, 0.5, 1.0);
	float3 d = normalize(q.xyz / q.w);
	// (through the same exposure, tone curve and grade as the terrain, as Reimagined's sky goes through its tonemap with the rest)
	return float4(pow(imPresent(imSkyColor(d, f, true), f), 1.0 / 2.2), 1.0);
}

// ---- clouds (Complementary Reimagined's blocky clouds, lib/atmospherics/clouds/reimaginedClouds.glsl) and the aurora ----
// Minecraft's own cloud map (one texel 12 blocks) at Minecraft's cloud height, each cell traced as a block with rounded edges and
// a rounded top and bottom (SixthSurge's rounded coordinates); lit from the top down, darker where more cloud lies toward the
// light; blended toward the sky behind with distance. Drawn after Minecraft's sun, moon and stars, so clouds pass in front of them.

constant float IM_CLOUD_STRETCH = 4.0;   // half the clouds' thickness (blocks)
constant float IM_CLOUD_ROUND = 0.125;

static inline float imCloudMap(texture2d<float> ct, sampler s, float2 texel) {
	float2 c = texel + 0.5;
	float2 sg = sign(c);
	c = abs(c) + 1.0;
	float2 i = floor(c), fr = c - i;
	fr = smoothstep(0.5 - IM_CLOUD_ROUND, 0.5 + IM_CLOUD_ROUND, fr);
	c = (i + fr - 0.5) * sg;
	return ct.sample(s, c / float2(ct.get_width(), ct.get_height())).r;
}

static float4 imClouds(float3 d, constant ImFrame &f, texture2d<float> ct, sampler s, float dither) {
	float camY = f.fogParams.y, alt = f.cloudPos.z;
	float hi = alt + IM_CLOUD_STRETCH, lo = alt - IM_CLOUD_STRETCH;
	if (abs(d.y) < 1e-4) return float4(0.0);
	float tLo = (lo - camY) / d.y, tHi = (hi - camY) / d.y;
	float tMin = max(min(tLo, tHi), 0.0), tMax = max(tLo, tHi);
	if (tMax <= 0.0) return float4(0.0);
	const float reach = 4000.0;
	tMax = min(tMax, reach / max(length(d.xz), 1e-3));
	if (tMax <= tMin) return float4(0.0);
	int count = clamp(int((tMax - tMin) / 8.0), 12, 40);
	float stepLen = (tMax - tMin) / float(count);
	float t = tMin + stepLen * dither;
	float rain = f.dayUp.w, sunVis = f.nightMiddle.w, noon = f.dayDown.w, shadowTime = f.nightDown.w, sunFactor = f.dayMiddle.w;
	for (int i = 0; i < count; i++, t += stepLen) {
		float3 p = d * t;  // camera-relative
		float y = camY + p.y;
		float2 texel = f.cloudPos.xy + p.xz / 12.0;
		float th = saturate(abs(alt - y) / IM_CLOUD_STRETCH);
		th = th * th;
		th = th * th;
		th = th * th;
		if (imCloudMap(ct, s, texel) <= th * 0.5 + 0.25) continue;
		// lit from the top down, and less where more cloud lies toward the light (two looks along it)
		float shade = pow(max(1.0 - (hi - y) / (IM_CLOUD_STRETCH * 2.0), 0.0), 0.8);
		float shadeM = 1.0 - shade * shade;
		float2 toLight = f.lightDir.xz * (0.08 * shadowTime);  // (Reimagined's: in cloud-map texels)
		float light = 2.0;
		light -= imCloudMap(ct, s, texel + toLight * (1.0 + dither)) * shadeM;
		light -= imCloudMap(ct, s, texel + toLight * (1.0 + 2.0 * dither)) * shadeM;
		float VdotS = dot(d, f.sunDir.xyz);
		float vs = max(sunVis > 0.5 ? VdotS : -VdotS, 0.0);
		shade = (vs * shadowTime * 0.25 + 0.5 * shade + 0.08) * light;
		float3 rainCol = mix(f.nightMiddle.rgb, f.dayMiddle.rgb, sunFactor);
		float3 amb = mix(f.ambientUp.rgb * (sunVis * sunVis * (0.55 + float3(0.2, 0.1, 0.0) * noon) + 0.35), rainCol * 0.5, rain) * (1.0 - 0.25 * rain);
		float3 lit = mix(f.lightColor.rgb * 1.3, rainCol * 0.45, noon * rain);
		float3 col = amb * 0.95 * (1.0 - 0.35 * shade) + lit * 0.9 * (0.1 + shade);
		float dxz = length(p.xz);
		float ratio = saturate((reach - dxz) / reach);
		float fogF = ratio * ratio * 0.707;
		col = mix(imSkyColor(d, f, false), col, fogF * 0.707);
		float a = sqrt(min(ratio, 0.75) * 1.33333);
		return float4(col, a);
	}
	return float4(0.0);
}

// Reimagined's aurora (lib/atmospherics/auroraBorealis.glsl, its second style): curtains of noise on a plane above, pink-violet
// below, green at their height. At night in cold biomes (f.aurora.x its visibility).
static float3 imAurora(float3 d, constant ImFrame &f, texture2d<float> nt, sampler s, float dither) {
	float u = saturate(d.y * 1.5 - 0.225);
	float vis = u * (2.0 - u) * f.aurora.x * (1.0 - d.y * 0.9);
	if (vis <= 0.0) return float3(0.0);
	float2 w = d.xz / max(d.y, 0.05);
	float3 acc = float3(0.0);
	const int N = 16;
	for (int i = 0; i < N; i++) {
		float cur = (float(i) + dither + 5.0) / float(N + 5);
		cur *= cur;
		float2 pp = w * (0.8 + cur) * 11.0 + f.aurora.zw;
		if (f.auroraLow.w > 0.5) pp = floor(pp);  // (Reimagined's pixel style: curtains in blocky steps)
		pp *= 0.0007;
		float n = nt.sample(s, pp).r;
		n = 1.0 - 2.0 * abs(n - 0.5);
		n *= n;
		n *= n;
		n *= n;
		n *= n;
		n *= nt.sample(s, pp * 3.0 + f.aurora.y).b;
		n *= nt.sample(s, pp * 5.0 - f.aurora.y).b;
		float cm = 1.0 - cur;
		float cm2 = cm * cm;
		acc += n * cm * mix(f.auroraLow.rgb, f.auroraHigh.rgb, cm2 * cm2);
	}
	return acc * ((f.auroraLow.w > 0.5 ? 1.3 : 1.8) * f.auroraHigh.w * vis / float(N));
}

fragment float4 im_clouds_fs(ImSkyOut in [[stage_in]], constant ImFrame &f [[buffer(0)]], constant float4x4 &rot [[buffer(1)]],
	texture2d<float> clouds [[texture(0)]], texture2d<float> noise [[texture(1)]], sampler rs [[sampler(0)]]) {
	float4 q = rot * float4(in.ndc.x, -in.ndc.y, 0.5, 1.0);
	float3 d = normalize(q.xyz / q.w);
	float dither = fract(52.9829189 * fract(dot(in.pos.xy, float2(0.06711056, 0.00583715))));
	float3 aurora = f.aurora.x > 0.0 ? imAurora(d, f, noise, rs, dither) : float3(0.0);
	float4 c = f.cloudPos.w > 0.5 ? imClouds(d, f, clouds, rs, dither) : float4(0.0);
	c.rgb += aurora * 0.1;
	// premultiplied: the clouds over what's there, the aurora added behind them
	float3 col = pow(imPresent(c.rgb, f), 1.0 / 2.2) * c.a + pow(imPresent(aurora, f), 1.0 / 2.2) * (1.0 - c.a);
	if (c.a <= 0.0 && max(max(aurora.r, aurora.g), aurora.b) <= 0.0) discard_fragment();
	return float4(col, c.a);
}
