// Imagine Metal: bloom.
//
// Run once per frame right after the level is drawn (before the hand and the HUD), on the game's main image (imbloom.m):
//  1. prefilter: the main image at half size, linear light, the bright parts kept (a soft threshold) plus a faint share of
//     everything (the overall soft glow Complementary Reimagined has). A Karis average (each tap weighted 1 / (1 + luma)) keeps
//     single bright pixels (a far torch, a sun glint) from flickering the glow on and off as they move.
//  2. down: a chain of half-size copies, each a 13-tap filter of the one before (wide, soft, no square artefacts).
//  3. up: back up the chain, each level adding a 3x3 tent of the smaller one to itself, so the top level holds every size of
//     glow, from a tight halo to a wide haze.
//  4. resolve (HDR, the usual path): the world was drawn into a 16-bit float image (imaginemetal.Hdr); the glow is added to it in
//     linear light and the result mapped into the game's 8-bit main image: anything brighter than white (lava, flames, glowing
//     blocks: the terrain's emission) is brought back to the screen's range keeping its hue. composite (no HDR image): the glow added to the 8-bit main image in place, read with Apple GPUs'
//     programmable blending ([[color(0)]]: the pixel's current value straight from tile memory).
//
// Both images are display-encoded (gamma 2.2: the terrain's shading writes them that way, vanilla's passes too); the HDR one
// goes past 1 where something gives off light.
#include <metal_stdlib>
using namespace metal;

struct ImBloomParams {
	float4 texel;  // xy 1 / the source's size; z threshold (linear luma), w soft knee
	float4 mix;    // x strength (negative: debug, the glow alone), y number of levels (the chain's sum is divided by it), z haze, w 0
	float4 grade;  // x how much the dark parts are deepened (the resolve's toe: 0 none)
	float4 shaft;  // light shafts: z strength (0 none)
	float4 shaftColor; // rgb the sun's (or moon's) light, linear; w ambient occlusion strength (0 none)
	float4x4 clip;     // this frame's camera-relative world -> clip (y up: uv.y = ndc.y * 0.5 + 0.5), depth 0..1
	float4x4 clipInv;
};

struct ImBloomOut {
	float4 pos [[position]];
	float2 uv;
};

// one triangle covering the target
vertex ImBloomOut im_bloom_vs(uint vid [[vertex_id]]) {
	float2 p = float2((vid << 1) & 2, vid & 2);
	ImBloomOut o;
	o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0);
	o.uv = float2(p.x, 1.0 - p.y);
	return o;
}

static inline float3 imbLinear(float3 c) {
	return pow(max(c, 0.0), 2.2);
}

static inline float imbLuma(float3 c) {
	return dot(c, float3(0.2126, 0.7152, 0.0722));
}

fragment float4 im_bloom_prefilter(ImBloomOut in [[stage_in]], texture2d<float> src [[texture(0)]], sampler s [[sampler(0)]],
	constant ImBloomParams &p [[buffer(0)]]) {
	float2 t = p.texel.xy;
	// four bilinear taps one texel out: the 4x4 source pixels behind each output pixel
	float3 a = imbLinear(src.sample(s, in.uv + t * float2(-1.0, -1.0)).rgb);
	float3 b = imbLinear(src.sample(s, in.uv + t * float2(1.0, -1.0)).rgb);
	float3 c = imbLinear(src.sample(s, in.uv + t * float2(-1.0, 1.0)).rgb);
	float3 d = imbLinear(src.sample(s, in.uv + t * float2(1.0, 1.0)).rgb);
	float wa = 1.0 / (1.0 + imbLuma(a)), wb = 1.0 / (1.0 + imbLuma(b)), wc = 1.0 / (1.0 + imbLuma(c)), wd = 1.0 / (1.0 + imbLuma(d));
	float3 col = (a * wa + b * wb + c * wc + d * wd) / (wa + wb + wc + wd);
	// soft threshold: nothing below threshold - knee, a smooth curve through the knee, everything above threshold in full.
	// Measured on the strongest channel, not luma: a red or blue light (lava, redstone, soul fire) glows as much as a white one
	float l = max(max(col.r, col.g), col.b);
	float th = p.texel.z, knee = p.texel.w;
	float soft = clamp(l - th + knee, 0.0, 2.0 * knee);
	soft = soft * soft / (4.0 * knee + 1e-5);
	float keep = max(soft, l - th) / max(l, 1e-5);
	return float4(col * (keep + p.mix.z), 1.0);
}

fragment float4 im_bloom_down(ImBloomOut in [[stage_in]], texture2d<float> src [[texture(0)]], sampler s [[sampler(0)]],
	constant ImBloomParams &p [[buffer(0)]]) {
	float2 t = p.texel.xy, uv = in.uv;
	float3 a = src.sample(s, uv + t * float2(-2.0, -2.0)).rgb;
	float3 b = src.sample(s, uv + t * float2(0.0, -2.0)).rgb;
	float3 c = src.sample(s, uv + t * float2(2.0, -2.0)).rgb;
	float3 d = src.sample(s, uv + t * float2(-1.0, -1.0)).rgb;
	float3 e = src.sample(s, uv + t * float2(1.0, -1.0)).rgb;
	float3 f = src.sample(s, uv + t * float2(-2.0, 0.0)).rgb;
	float3 g = src.sample(s, uv).rgb;
	float3 h = src.sample(s, uv + t * float2(2.0, 0.0)).rgb;
	float3 i = src.sample(s, uv + t * float2(-1.0, 1.0)).rgb;
	float3 j = src.sample(s, uv + t * float2(1.0, 1.0)).rgb;
	float3 k = src.sample(s, uv + t * float2(-2.0, 2.0)).rgb;
	float3 l = src.sample(s, uv + t * float2(0.0, 2.0)).rgb;
	float3 m = src.sample(s, uv + t * float2(2.0, 2.0)).rgb;
	float3 r = g * 0.125 + (a + c + k + m) * 0.03125 + (b + f + h + l) * 0.0625 + (d + e + i + j) * 0.125;
	return float4(r, 1.0);
}

static inline float3 imbTent(texture2d<float> src, sampler s, float2 uv, float2 t) {
	float3 r = src.sample(s, uv).rgb * 4.0;
	r += (src.sample(s, uv + float2(-t.x, 0.0)).rgb + src.sample(s, uv + float2(t.x, 0.0)).rgb + src.sample(s, uv + float2(0.0, -t.y)).rgb
		+ src.sample(s, uv + float2(0.0, t.y)).rgb) * 2.0;
	r += src.sample(s, uv + float2(-t.x, -t.y)).rgb + src.sample(s, uv + float2(t.x, -t.y)).rgb + src.sample(s, uv + float2(-t.x, t.y)).rgb
		+ src.sample(s, uv + t).rgb;
	return r * (1.0 / 16.0);
}

// drawn with additive blending onto the level's own downsampled copy
fragment float4 im_bloom_up(ImBloomOut in [[stage_in]], texture2d<float> src [[texture(0)]], sampler s [[sampler(0)]],
	constant ImBloomParams &p [[buffer(0)]]) {
	return float4(imbTent(src, s, in.uv, p.texel.xy), 1.0);
}

fragment float4 im_bloom_composite(ImBloomOut in [[stage_in]], float4 dst [[color(0)]], texture2d<float> src [[texture(0)]],
	sampler s [[sampler(0)]], constant ImBloomParams &p [[buffer(0)]]) {
	float3 glow = imbTent(src, s, in.uv, p.texel.xy) / p.mix.y;
	if (p.mix.x < 0.0) return float4(pow(saturate(glow * -p.mix.x * 4.0), 1.0 / 2.2), dst.a);
	float3 c = imbLinear(dst.rgb) + glow * p.mix.x;
	return float4(pow(saturate(c), 1.0 / 2.2), dst.a);
}

// The start of lighting.metal's ImFrame (the frame's values, bound as they are): the light, and the sun's shadow maps.
struct ImVlFrame {
	float4 lightDir;     // xyz toward the light (sun by day, moon by night)
	float4 lightColor;
	float4 pad0[6];
	int4 flags;
	float4 env;
	float4x4 shadowNear;
	float4x4 shadowFar;
	float4 shadow;       // y 1 = the maps are valid this frame
};

// Light shafts (volumetric, after Complementary Reimagined's; at a quarter of the size): along each pixel's view ray, out to the
// surface or 96 blocks, twelve points checked against the sun's shadow maps; the share of the way the sun reaches is the light
// the air scatters toward the eye, strongest looking toward the sun. Rays lit the whole way (open land in daylight) count less:
// that's the even haze the air fog already draws; the beams are where light and shadow alternate (through leaves, a cave mouth,
// an arch). A fixed per-pixel jitter, smoothed by the upscale.
fragment float4 im_bloom_shafts(ImBloomOut in [[stage_in]], depth2d<float> depth [[texture(1)]], depth2d_array<float> smap [[texture(4)]],
	sampler cmp [[sampler(1)]], constant ImBloomParams &p [[buffer(0)]], constant ImVlFrame &f [[buffer(1)]]) {
	float2 size = float2(depth.get_width(), depth.get_height());
	float d = depth.read(uint2(clamp(in.uv, 0.0, 0.9999) * size));
	float4 w = p.clipInv * float4(in.uv * 2.0 - 1.0, d >= 0.99999 ? 0.9999 : d, 1.0);
	float3 P = w.xyz / w.w;
	float len = length(P);
	float3 dir = P / max(len, 1e-4);
	float L = d >= 0.99999 ? 96.0 : min(len, 96.0);
	const int n = 12;
	float j = fract(52.9829189 * fract(dot(in.pos.xy, float2(0.06711056, 0.00583715))));
	float lit = 0.0;
	for (int i = 0; i < n; i++) {
		float t = (float(i) + j) / float(n);
		float3 q = dir * (L * t * t);  // (denser near the eye, where the beams are big on screen)
		float4 c0 = f.shadowNear * float4(q, 1.0);
		float s = 1.0;
		if (max(abs(c0.x), abs(c0.y)) < 0.95 && c0.z > 0.0 && c0.z < 1.0) {
			s = smap.sample_compare(cmp, float2(0.5 + 0.5 * c0.x, 0.5 - 0.5 * c0.y), 0, c0.z - 0.0001);
		} else {
			float4 c1 = f.shadowFar * float4(q, 1.0);
			if (max(abs(c1.x), abs(c1.y)) < 0.98 && c1.z > 0.0 && c1.z < 1.0)
				s = smap.sample_compare(cmp, float2(0.5 + 0.5 * c1.x, 0.5 - 0.5 * c1.y), 1, c1.z - 0.0002);
		}
		lit += s;
	}
	lit /= float(n);
	float amount = lit * (1.0 - exp(-L * 0.02));
	amount *= 1.0 - 0.8 * smoothstep(0.75, 1.0, lit);
	float vl = dot(dir, f.lightDir.xyz);
	float ph = 0.25 + 2.2 * pow(saturate(vl * 0.5 + 0.5), 6.0);
	return float4(amount * ph, 0.0, 0.0, 1.0);
}

// Ambient occlusion (at half size): corners, the ground under ledges, between close blocks a little darker. Eight points in the
// half sphere above the surface (its normal from the neighbouring depths), half a block out, each counting as hidden when the depth
// there lies in front of it (and not far in front: that's something else, nearer the eye). A fixed per-pixel turn of the pattern.
static inline float3 imbPos(depth2d<float> depth, float2 uv, float2 size, constant ImBloomParams &p, thread float &d) {
	d = depth.read(uint2(clamp(uv, 0.0, 0.9999) * size));
	float4 w = p.clipInv * float4(uv * 2.0 - 1.0, d, 1.0);
	return w.xyz / w.w;
}

fragment float4 im_bloom_ao(ImBloomOut in [[stage_in]], depth2d<float> depth [[texture(1)]], constant ImBloomParams &p [[buffer(0)]]) {
	float2 size = float2(depth.get_width(), depth.get_height());
	float2 uv = in.uv, t = 2.0 / size;
	float d;
	float3 P = imbPos(depth, uv, size, p, d);
	if (d >= 0.99999) return float4(1.0);
	float dd;
	float3 px = imbPos(depth, uv + float2(t.x, 0.0), size, p, dd) - P, nx = P - imbPos(depth, uv - float2(t.x, 0.0), size, p, dd);
	float3 py = imbPos(depth, uv + float2(0.0, t.y), size, p, dd) - P, ny = P - imbPos(depth, uv - float2(0.0, t.y), size, p, dd);
	float3 ex = dot(px, px) < dot(nx, nx) ? px : nx, ey = dot(py, py) < dot(ny, ny) ? py : ny;  // (the nearer side: no smear across edges)
	float3 N = normalize(cross(ex, ey));
	if (dot(N, P) > 0.0) N = -N;
	float3 T = normalize(abs(N.y) < 0.9 ? cross(N, float3(0.0, 1.0, 0.0)) : cross(N, float3(1.0, 0.0, 0.0)));
	float3 Bt = cross(N, T);
	float j = fract(52.9829189 * fract(dot(in.pos.xy, float2(0.06711056, 0.00583715))));
	float dist = length(P);
	float radius = 0.8;
	float occ = 0.0;
	const int n = 8;
	for (int i = 0; i < n; i++) {
		float a = (float(i) + j) * 2.39996;
		float r = sqrt((float(i) + 0.5) / float(n));
		float h = 0.15 + 0.85 * fract(j + float(i) * 0.618);
		float3 o = (T * cos(a) * r + Bt * sin(a) * r) * sqrt(1.0 - h * h * 0.5) + N * h * 0.7;
		float3 S = P + o * radius;
		float4 c = p.clip * float4(S, 1.0);
		if (c.w <= 0.05) continue;
		float2 suv = c.xy / c.w * 0.5 + 0.5;
		if (any(suv < 0.0) || any(suv > 1.0)) continue;
		float sd;
		float3 Q = imbPos(depth, suv, size, p, sd);
		float front = length(S) - length(Q);  // (> 0: the depth there is in front of the point)
		occ += smoothstep(0.02, 0.1, front) * (1.0 - smoothstep(radius, radius * 2.5, front));
	}
	float ao = 1.0 - occ / float(n);
	ao *= ao;  // (deeper in the corners)
	ao = mix(1.0, ao, 1.0 - smoothstep(64.0, 128.0, dist));
	return float4(ao, 0.0, 0.0, 1.0);
}

fragment float4 im_bloom_resolve(ImBloomOut in [[stage_in]], texture2d<float> src [[texture(0)]], sampler s [[sampler(0)]],
	texture2d<float> hdr [[texture(1)]], texture2d<float> shafts [[texture(2)]], texture2d<float> aoT [[texture(3)]],
	constant ImBloomParams &p [[buffer(0)]]) {
	float4 h = hdr.read(uint2(in.pos.xy));
	float3 c = imbLinear(h.rgb);
	if (p.shaftColor.w > 0.0) {
		// (four taps a half-size texel apart: the pattern's grain smoothed)
		float2 at = 1.0 / float2(aoT.get_width(), aoT.get_height());
		float ao = 0.25 * (aoT.sample(s, in.uv + at * float2(-0.75, -0.75)).r + aoT.sample(s, in.uv + at * float2(0.75, -0.75)).r
			+ aoT.sample(s, in.uv + at * float2(-0.75, 0.75)).r + aoT.sample(s, in.uv + at * float2(0.75, 0.75)).r);
		// (light given off isn't shaded: lava, glowing blocks)
		float l = max(max(c.r, c.g), c.b);
		c *= mix(1.0, ao, saturate(p.shaftColor.w) * (1.0 - smoothstep(1.0, 2.0, l)));
	}
	if (p.shaft.z > 0.0) {
		// the beams (four taps a quarter-size texel apart: the jitter's grain smoothed)
		float2 st = 1.0 / float2(shafts.get_width(), shafts.get_height());
		float sh = 0.25 * (shafts.sample(s, in.uv + st * float2(-0.6, -0.6)).r + shafts.sample(s, in.uv + st * float2(0.6, -0.6)).r
			+ shafts.sample(s, in.uv + st * float2(-0.6, 0.6)).r + shafts.sample(s, in.uv + st * float2(0.6, 0.6)).r);
		c += p.shaftColor.rgb * (sh * p.shaft.z * 0.35);
	}
	if (p.mix.x != 0.0) {
		float3 glow = imbTent(src, s, in.uv, p.texel.xy) / p.mix.y;
		if (p.mix.x < 0.0) return float4(pow(saturate(glow * -p.mix.x * 4.0), 1.0 / 2.2), 1.0);
		c += glow * p.mix.x;
	}
	// the top of the range rolled off keeping the hue (mixing toward white turned lava pink once gamma-encoded): up to 0.85 as
	// it is, above that a soft shoulder that reaches white only far past it, so a glowing surface keeps its texture's light and
	// dark instead of every bright pixel landing on the same white
	// the grade: below mid grey (0.18 linear) the picture is darkened by (lum / 0.18)^k, more the darker it is; mid grey and
	// everything brighter stay as they are. Strong in caves and at night (lights stand out of the dark), off in daylight.
	float lum = dot(c, float3(0.2126, 0.7152, 0.0722));
	if (p.grade.x > 0.0 && lum < 0.18) c *= pow(max(lum, 1e-6) / 0.18, p.grade.x);
	float m = max(max(c.r, c.g), c.b);
	const float t = 0.85;
	if (m > t) c *= (t + (1.0 - t) * (m - t) / ((m - t) + (1.0 - t))) / m;
	return float4(pow(saturate(c), 1.0 / 2.2), h.a);
}
