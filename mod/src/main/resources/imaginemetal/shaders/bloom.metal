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

fragment float4 im_bloom_resolve(ImBloomOut in [[stage_in]], texture2d<float> src [[texture(0)]], sampler s [[sampler(0)]],
	texture2d<float> hdr [[texture(1)]], constant ImBloomParams &p [[buffer(0)]]) {
	float4 h = hdr.read(uint2(in.pos.xy));
	float3 c = imbLinear(h.rgb);
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
