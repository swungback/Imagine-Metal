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
	float4 mix;    // x strength (negative: debug, the glow alone), y number of levels (the chain's sum is divided by it), z haze,
	               // w 1 = the shafts' history is usable (their pass only)
	float4 grade;  // x how much the dark parts are deepened (the resolve's toe: 0 none), y bloom fog strength (0 none), z 1 = under water,
	               // w the rainbow's strength (0 none)
	float4 shaft;  // light shafts: z strength (0 none)
	float4 shaftColor; // rgb the sun's (or moon's) light, linear; w ambient occlusion strength (0 none)
	float4x4 clip;     // this frame's camera-relative world -> clip (y up: uv.y = ndc.y * 0.5 + 0.5), depth 0..1
	float4x4 clipInv;
	float4x4 prevClip; // the last frame's clip, from this frame's camera-relative world (the shafts' history)
	float4 taa;        // TAA: xy this frame's jitter in uv, z 1 = the history is usable
	float4 taaPrev;    // TAA: xy the last frame's jitter in uv
};

// The history sampled with a Catmull-Rom filter (9 taps folded into 5 bilinear ones): plain bilinear blurs it a little every
// frame, and over many frames that adds up to the soft, smeared look.
static float3 imbCatmull(texture2d<float> t, sampler s, float2 uv) {
	float2 size = float2(t.get_width(), t.get_height());
	float2 sp = uv * size;
	float2 tp = floor(sp - 0.5) + 0.5;
	float2 f = sp - tp;
	float2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
	float2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
	float2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
	float2 w3 = f * f * (-0.5 + 0.5 * f);
	float2 w12 = w1 + w2;
	float2 tc12 = (tp + w2 / w12) / size;
	float2 tc0 = (tp - 1.0) / size, tc3 = (tp + 2.0) / size;
	float3 r = t.sample(s, float2(tc12.x, tc0.y)).rgb * (w12.x * w0.y)
		+ t.sample(s, float2(tc0.x, tc12.y)).rgb * (w0.x * w12.y)
		+ t.sample(s, tc12).rgb * (w12.x * w12.y)
		+ t.sample(s, float2(tc3.x, tc12.y)).rgb * (w3.x * w12.y)
		+ t.sample(s, float2(tc12.x, tc3.y)).rgb * (w12.x * w3.y);
	float wsum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
	return max(r / wsum, 0.0);
}


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


// lighting.metal's ImFrame up to world (the frame's values, bound as they are)
struct ImVlFrame {
	float4 lightDir;     // xyz toward the light (sun by day, moon by night)
	float4 lightColor;
	float4 pad0[6];
	int4 flags;
	float4 env;
	float4x4 shadowNear;
	float4x4 shadowFar;
	float4 shadow;       // y 1 = the maps are valid this frame
	float4 lightVol, lightSize;
	float4 dayUp;        // w rain
	float4 dayMiddle, dayDown;  // dayDown.w noon factor
	float4 nightUp, nightMiddle; // nightMiddle.w sun visibility
	float4 nightDown, sunsetDown; // sunsetDown.w 1 - noon factor
	float4 sunDir;
	float4 hazeTint;
	float4 fogParams;    // x render distance
	float4x4 histClip, histClipInv;
	float4 histShift, cloudPos, aurora;
	float4 world;        // xyz the camera's world position (wrapped every 4096 blocks), w seconds
};

// an integer hash (sin-based ones lose their precision far from the origin and come out in stripes)
static inline float imbHash(int2 q) {
	uint h = uint(q.x) * 1597334677u ^ uint(q.y) * 3812015801u;
	h = (h ^ (h >> 16)) * 2246822519u;
	h ^= h >> 13;
	return float(h & 0xffffffu) / 16777216.0;
}

static inline float imbNoise(float2 q) {
	float2 fi = floor(q), u = q - fi;
	int2 i = int2(fi);
	u = u * u * (3.0 - 2.0 * u);
	return mix(mix(imbHash(i), imbHash(i + int2(1, 0)), u.x), mix(imbHash(i + int2(0, 1)), imbHash(i + int2(1, 1)), u.x), u.y);
}

static inline float imbDither8(float2 dp) {
	float d1 = fract(52.9829189 * fract(dot(dp, float2(0.06711056, 0.00583715))));
	float d2 = fract(52.9829189 * fract(dot(dp + 17.0, float2(0.06711056, 0.00583715))));
	return (d1 + d2 - 1.0) / 255.0;
}

static inline float imbSmooth(float x) {
	return x * x * (3.0 - 2.0 * x);
}

// Reimagined's Nether storm (lib/atmospherics/netherStorm.glsl, on by default): slow clouds of the biome's haze drifting through
// the caverns. Along the view ray, out to the haze's full distance, a lumpy 3D noise (a plain value noise, linearly blended as
// Reimagined's noise texture is) on a slanted lattice (taller than wide), raised to the 8th so only its peaks show, thickest at
// height 28 and thinning out over 200 blocks above (ten times faster below). Reimagined adds four sizes of it; the two finest
// (a block and half a block) are far finer than the steps along the ray, so here their average stands in for them. Its lattice
// and its wind are whole repeats of the camera's 4096-block wrap and the clock's hourly one: no jump at either.
// Returns how much of the haze covers the pixel (0..1).
static inline float imbHash3(int3 q, int3 per) {
	q = ((q % per) + per) % per;
	uint h = uint(q.x) * 1597334677u ^ uint(q.y) * 3812015801u ^ uint(q.z) * 2654435761u;
	h = (h ^ (h >> 16)) * 2246822519u;
	h ^= h >> 13;
	return float(h & 0xffffffu) / 16777216.0;
}

static inline float imbNoise3(float3 q, int3 per) {
	float3 fi = floor(q), u = q - fi;
	int3 i = int3(fi);
	float a = mix(mix(imbHash3(i, per), imbHash3(i + int3(1, 0, 0), per), u.x),
		mix(imbHash3(i + int3(0, 1, 0), per), imbHash3(i + int3(1, 1, 0), per), u.x), u.y);
	float b = mix(mix(imbHash3(i + int3(0, 0, 1), per), imbHash3(i + int3(1, 0, 1), per), u.x),
		mix(imbHash3(i + int3(0, 1, 1), per), imbHash3(i + int3(1, 1, 1), per), u.x), u.y);
	return mix(a, b, u.z);
}

static float imbStorm(float3 dir, float len, bool open, float j, texture3d<float> nt, sampler ns, constant ImBloomParams &p,
	constant ImVlFrame &f) {
	float maxDist = max(p.shaft.w, 32.0);
	float rayEnd = open ? maxDist : min(len, maxDist);
	const int N = 12;
	float stepLen = maxDist / float(N);
	float t0 = f.world.w;
	// (the wind: Reimagined's 0.002 a second on its noise texture, here on the lattice, a whole number of cells an hour)
	float windW = t0 * (512.0 / 3600.0), wind1 = t0 * (1024.0 / 3600.0);
	float a = 0.0;
	for (int i = 0; i < N; i++) {
		float t = (float(i) + 1.0 + j) * stepLen;
		if (t > rayEnd) break;
		float3 wp = f.world.xyz + dir * t;
		// (its slant: height and x together, as Reimagined's lattice, so the clouds lean)
		float3 q = float3(wp.x, wp.y + wp.x, wp.z);
		// a slow warp first (cells of 8 blocks), then the two coarse sizes (4 and 2 blocks across, 16 and 8 tall)
		// (the noise from a 64^3 texture of random values, filtered by the sampler: one sample for eight hashed corners, Apple's
		// texture units doing the blend; its 64-cell repeat divides every lattice's wrap)
		float3 warp = float3(nt.sample(ns, (q * 0.125 - windW) * (1.0 / 64.0)).r);
		float3 l1 = q * float3(0.25, 0.0625, 0.25) + warp * float3(2.56, 0.64, 2.56);
		float alt = abs(wp.y - 28.0);
		if (wp.y < 28.0) alt *= 10.0;
		alt = 1.0 - min(alt / 200.0, 1.0);
		float alt4 = alt * alt;
		alt4 *= alt4;
		float fade = saturate(1.0 - t / maxDist);
		fade = fade * (2.0 - fade);
		float n1 = nt.sample(ns, (l1 + wind1) * (1.0 / 64.0)).r;
		float n2 = nt.sample(ns, (l1 * 2.0 - wind1 * 2.0) * (1.0 / 64.0)).r;
		n1 *= n1; n1 *= n1; n1 *= n1;
		n2 *= n2; n2 *= n2; n2 *= n2;
		// (the two finest sizes: their mean, E[n^8] of this noise, 0.0229 each)
		a += (n1 + n2 + 2.0 * 0.0229) * alt4 * fade;
	}
	// (Reimagined steps 8 blocks: each of these stands for stepLen / 8 of its samples; its strength 0.40)
	return saturate(a * (stepLen / 8.0) * 0.40);
}

// Reimagined's ender beams (lib/atmospherics/volumetricLight/enderBeams.glsl, in its End volumetric light): tall columns of violet
// light rising out of the void around height 68, from a slow noise over the world's x and z (so they stand upright), each as
// tall as the noise there says, reaching higher the farther out they are; past a few hundred blocks a hot orange creeps into
// them. Traced as Reimagined's End light: out to 1536 blocks (96 chunks), the near part through the shadow maps (the islands
// cast shadows into the beams), the air past the land's fog included. Returns r violet, g the distance, b orange.
static inline float imbHashP(int2 q, int2 per) {
	q = ((q % per) + per) % per;
	uint h = uint(q.x) * 1597334677u ^ uint(q.y) * 3812015801u;
	h = (h ^ (h >> 16)) * 2246822519u;
	h ^= h >> 13;
	return float(h & 0xffffffu) / 16777216.0;
}

// (linearly blended, as Reimagined's noise texture; per: its repeat in cells, a whole number over the 4096-block wrap)
static inline float imbNoiseL(float2 q, int2 per) {
	float2 fi = floor(q), u = q - fi;
	int2 i = int2(fi);
	return mix(mix(imbHashP(i, per), imbHashP(i + int2(1, 0), per), u.x), mix(imbHashP(i + int2(0, 1), per), imbHashP(i + int2(1, 1), per), u.x), u.y);
}

static float4 imbEnd(float3 dir, float len, bool open, float j, depth2d_array<float> smap, sampler cmp, constant ImBloomParams &p,
	constant ImVlFrame &f) {
	const float maxD = 1536.0;
	float rd = max(p.shaft.w, 32.0);   // the render distance (blocks)
	float shadowReach = p.shaft.y;     // the shadow maps' reach (0: none this frame)
	// (Reimagined: past the land's fog the rays go on to the end, so beams stand in front of the far islands too)
	float bf = len / rd;
	bf *= bf;
	bf *= bf;
	bf = 1.0 - exp(-3.0 * bf);
	float rayEnd = open ? maxD : mix(min(len, maxD), maxD, bf);
	float active = min(min(rd, shadowReach > 0.0 ? shadowReach : rd) * 0.98, maxD);
	const int NN = 16, NF = 4;
	int total = rayEnd <= active ? NN : NN + NF;
	float w = f.world.w * (256.0 / 3600.0);  // (the wind, a whole number of repeats an hour)
	float vio = 0.0, org = 0.0, last = 0.0;
	for (int i = 0; i <= total; i++) {
		float next, dens = 1.0;
		float3 q;
		if (i <= NN) {
			float t = (float(i) + j) / float(NN);
			next = pow(t, 1.3) * active;
			if (next >= rayEnd) break;
			q = dir * next;
			if (shadowReach > 0.0) {
				float4 c0 = f.shadowNear * float4(q, 1.0);
				if (max(abs(c0.x), abs(c0.y)) < 0.95 && c0.z > 0.0 && c0.z < 1.0) {
					dens = smap.sample_compare(cmp, float2(0.5 + 0.5 * c0.x, 0.5 - 0.5 * c0.y), 0, c0.z - 0.0001);
				} else {
					float4 c1 = f.shadowFar * float4(q, 1.0);
					if (max(abs(c1.x), abs(c1.y)) < 0.98 && c1.z > 0.0 && c1.z < 1.0)
						dens = smap.sample_compare(cmp, float2(0.5 + 0.5 * c1.x, 0.5 - 0.5 * c1.y), 1, c1.z - 0.0002);
				}
			}
		} else {
			float t = (float(i - NN) + j) / float(NF);
			next = mix(active, maxD, pow(t, 2.5));
			if (next >= rayEnd) break;
			q = dir * next;
		}
		float cw = imbSmooth(pow(saturate(last / maxD), 0.55));
		float nw = imbSmooth(pow(saturate(next / maxD), 0.55));
		float slice = nw - cw;
		last = next;
		// the beam here
		float2 xz = f.world.xz + q.xz;
		float lxz = length(q.xz);
		float noise = (imbNoiseL(xz / 64.0 - w, int2(64)) * 0.5 + imbNoiseL(xz / 256.0 + w * 0.5, int2(16)) * 2.0
			+ imbNoiseL(xz / 16.0 - w, int2(256)) * 0.5) / 3.0;
		float fire = imbNoiseL(xz / 128.0 - w, int2(32));
		float reach = (2000.0 * noise * noise + 40.0 * fire) * 0.001 * (250.0 + 0.5 * lxz);
		float altDis = abs(f.world.y + q.y - 68.0);
		float af = 0.5 * (1.0 - smoothstep(0.0, reach, altDis)) + 0.5 * (1.0 - smoothstep(0.0, 4.0 * reach, altDis));
		float af2 = af * af;
		float n = noise * noise * 0.7 + 0.3 * fire;
		n = pow(max(n * af2 * af2, 0.0), 1.75);
		float k = n * (1.0 + smoothstep(0.0, 128.0, lxz)) * dens * slice;
		float fm = fire - 0.5;
		fm *= fm;
		vio += k;
		org += k * 300.0 * (0.0015 + 0.2 * smoothstep(0.0, 256.0, lxz) * fm * fm);
	}
	return float4(vio, len, org, 1.0);
}

// Light shafts: Complementary Reimagined's volumetric light (lib/atmospherics/volumetricLight/volumetricLight.glsl), at half size.
// Along each pixel's view ray out to the render distance, points checked against the sun's shadow maps (more of them near the eye),
// each standing for its slice of a fog that thickens with distance; the lit share of that fog is the light scattered toward the eye.
// Strongest at sunrise and sunset, looking toward the light and toward the horizon; at noon only an eighth of it, except where
// something is overhead (a forest, a cave, an arch: "scene-aware", p.shaft.y), where the beams are the point.
// p.shaft: x 1 = eyes under water, y how much there's something overhead (0..1), z strength (0 off), w the far shadow map's reach.
static float4 imbShaftsNow(ImBloomOut in, depth2d<float> depth, depth2d_array<float> smap, sampler cmp, texture3d<float> nt, sampler ns,
	constant ImBloomParams &p, constant ImVlFrame &f) {
	float2 size = float2(depth.get_width(), depth.get_height());
	float d = depth.read(uint2(clamp(in.uv, 0.0, 0.9999) * size));
	bool sky = d >= 0.99999;
	float4 w = p.clipInv * float4(in.uv * 2.0 - 1.0, sky ? 0.9999 : d, 1.0);
	float3 P = w.xyz / w.w;
	float len = length(P);
	float3 dir = P / max(len, 1e-4);
	if (p.shaft.x < -1.5) {
		// (the Nether: its storm instead; the End: its beams. A new step offset each frame)
		float js = fract(52.9829189 * fract(dot(in.pos.xy, float2(0.06711056, 0.00583715))) + 0.61803399 * float(f.flags.w & 1023));
		if (p.shaft.x < -2.5) return imbEnd(dir, len, sky, js, smap, cmp, p, f);
		return float4(imbStorm(dir, len, sky, js, nt, ns, p, f), len, 0.0, 1.0);
	}
	bool water = p.shaft.x < -0.5;
	float intense = water ? 1.0 : p.shaft.y;
	float rain = f.dayUp.w, sunVis = f.nightMiddle.w, noon = f.dayDown.w, invNoon = f.sunsetDown.w;

	// how much: by the view (toward the light, toward the horizon) and the time of day
	float vdotl = dot(dir, f.lightDir.xyz), vdotu = dir.y;
	float vlMult = 1.0;
	if (sunVis < 0.5) intense = 0.0;
	float vdotlm = max((vdotl + 1.0) * 0.5, 0.0);
	float up = max(vdotu, 0.0);
	float vdotum = imbSmooth(mix((1.0 - up) * (1.0 - up), 1.0, 0.5 * intense));
	float maxD = max(f.fogParams.x * 0.98, 128.0);
	if (water) maxD = 128.0;
	vdotum = pow(vdotum, min(len / maxD, 1.0) * (3.0 - 2.0 * intense));
	float sdotu = f.sunDir.y;
	float vlTime = saturate((min(abs(sdotu) - 0.05, 0.15)) / 0.15);
	float rainyNight = (1.0 - sunVis) * rain;
	vlMult *= mix(vdotum * vdotlm, 1.0, 0.4 * rainyNight) * vlTime;
	float invNoon2 = invNoon * invNoon;
	vlMult *= mix(invNoon2 * 0.875 + 0.125, 1.0, max(intense, rain * rain));
	vlMult *= 1.4 - 0.4 * rain;
	if (sunVis < 0.5) vlMult *= 0.3 + 0.4 * rain * rain + 0.5 * max(f.fogParams.x - len, 0.0) / max(f.fogParams.x, 1.0);
	if (vlMult < 0.0001) return float4(0.0, len, 0.0, 1.0);

	// the march
	int nearN = water || intense > 0.5 ? 15 : 10;
	float fogCurve = mix(0.9, 0.5, max(intense, rain));
	float distrib = mix(2.0, 1.5, intense);
	float reach = min(p.shaft.w * 0.98, maxD);
	float active = mix(reach, min(100.0, reach), intense);
	float rayEnd = sky ? maxD : min(len, maxD);
	float j = fract(52.9829189 * fract(dot(in.pos.xy, float2(0.06711056, 0.00583715))) + 0.61803399 * float(f.flags.w & 1023));
	float eyeBright = saturate(p.shaft.x);  // (beyond the shadow maps: as lit as where the player stands, by the sky light there)
	float vl = 0.0, last = 0.0;
	for (int i = 0; i <= nearN + 1; i++) {
		float next, dens;
		if (i <= nearN) {
			float t = (float(i) + j) / float(nearN);
			next = pow(t, distrib) * active;
			if (next >= rayEnd) break;
			float3 q = dir * next;
			dens = eyeBright;
			float4 c0 = f.shadowNear * float4(q, 1.0);
			if (max(abs(c0.x), abs(c0.y)) < 0.95 && c0.z > 0.0 && c0.z < 1.0) {
				dens = smap.sample_compare(cmp, float2(0.5 + 0.5 * c0.x, 0.5 - 0.5 * c0.y), 0, c0.z - 0.0001);
			} else {
				float4 c1 = f.shadowFar * float4(q, 1.0);
				if (max(abs(c1.x), abs(c1.y)) < 0.98 && c1.z > 0.0 && c1.z < 1.0)
					dens = smap.sample_compare(cmp, float2(0.5 + 0.5 * c1.x, 0.5 - 0.5 * c1.y), 1, c1.z - 0.0002);
			}
			if (water && dens > 0.0) {
				// (the waves above focus the light into moving beams: a pattern on the surface, carried down along the light)
				float3 wq = q + f.world.xyz;
				float2 s2 = (wq.xz - f.lightDir.xz * (wq.y / max(f.lightDir.y, 0.2))) * 0.35;
				float tt = f.world.w * 0.4;
				float nz = imbNoise(s2 + float2(tt, tt * 0.7)) * 0.6 + imbNoise(s2 * 2.1 - float2(tt * 0.8, tt)) * 0.4;
				dens *= 0.15 + 0.85 * smoothstep(0.45, 0.75, nz);
			}
		} else {
			// (one far sample, past the maps, out to the render distance)
			next = rayEnd;
			if (next <= last) break;
			dens = eyeBright;
		}
		float cw = imbSmooth(pow(saturate(last / maxD), fogCurve));
		float nw = imbSmooth(pow(saturate(next / maxD), fogCurve));
		vl += dens * (nw - cw);
		last = next;
	}
	return float4(vl * vlMult, len, 0.0, 1.0);
}

fragment float4 im_bloom_shafts(ImBloomOut in [[stage_in]], depth2d<float> depth [[texture(1)]], texture2d<float> hist [[texture(2)]],
	depth2d_array<float> smap [[texture(4)]], texture3d<float> nt [[texture(5)]], sampler ls [[sampler(0)]], sampler cmp [[sampler(1)]],
	sampler ns [[sampler(2)]], constant ImBloomParams &p [[buffer(0)]], constant ImVlFrame &f [[buffer(1)]]) {
	float4 res = imbShaftsNow(in, depth, smap, cmp, nt, ns, p, f);
	// (temporal: a jitter that changes every frame, averaged with the last frames' result where that is the same place in the
	// world: smooth beams for twelve samples a pixel, as Reimagined gets with its TAA)
	if (p.mix.w > 0.5) {
		float len = res.g;
		float2 size = float2(depth.get_width(), depth.get_height());
		float d = depth.read(uint2(clamp(in.uv, 0.0, 0.9999) * size));
		float4 w = p.clipInv * float4(in.uv * 2.0 - 1.0, d >= 0.99999 ? 0.9999 : d, 1.0);
		float3 P = w.xyz / w.w;
		float4 c = p.prevClip * float4(P, 1.0);
		if (c.w > 0.05) {
			float2 puv = c.xy / c.w * 0.5 + 0.5;
			if (all(puv > 0.0) && all(puv < 1.0)) {
				float4 h = hist.sample(ls, puv);
				// (the history only where it was the same distance: an edge that moved shows the new value)
				float same = 1.0 - smoothstep(0.05, 0.2, abs(h.g - len) / max(len, 1.0));
				res.r = mix(res.r, h.r, 0.85 * same);
				res.b = mix(res.b, h.b, 0.85 * same);
			}
		}
	}
	return res;
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
	if (d >= 0.99999 || length(P) > 128.0) return float4(1.0);  // (the sky; and past 128 blocks the occlusion has faded out)
	float dd;
	float3 px = imbPos(depth, uv + float2(t.x, 0.0), size, p, dd) - P, nx = P - imbPos(depth, uv - float2(t.x, 0.0), size, p, dd);
	float3 py = imbPos(depth, uv + float2(0.0, t.y), size, p, dd) - P, ny = P - imbPos(depth, uv - float2(0.0, t.y), size, p, dd);
	float3 ex = dot(px, px) < dot(nx, nx) ? px : nx, ey = dot(py, py) < dot(ny, ny) ? py : ny;  // (the nearer side: no smear across edges)
	float3 N = normalize(cross(ex, ey));
	if (dot(N, P) > 0.0) N = -N;
	float3 T = normalize(abs(N.y) < 0.9 ? cross(N, float3(0.0, 1.0, 0.0)) : cross(N, float3(1.0, 0.0, 0.0)));
	float3 Bt = cross(N, T);
	float j = fract(52.9829189 * fract(dot(in.pos.xy, float2(0.06711056, 0.00583715))));
	// (with TAA a new turn each frame (from its jitter, eight a cycle): averaged into smooth shading instead of a fixed grain)
	if (p.taa.z > 0.5) j = fract(j + fract(p.taa.x * 7919.0 + p.taa.y * 4513.0));
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
		// (a margin growing with distance: far away the depth's precision alone puts flat ground "in front" of its own sample
		// points, which darkened distant sand and grass in faint crawling patches)
		occ += smoothstep(0.03 + dist * 0.006, 0.12 + dist * 0.012, front) * (1.0 - smoothstep(radius, radius * 2.5, front));
	}
	float ao = 1.0 - occ / float(n);
	ao *= ao;  // (deeper in the corners)
	// (ground seen at a very low angle: the normal from the depth is least reliable there, so less occlusion)
	ao = mix(1.0, ao, smoothstep(0.05, 0.25, abs(dot(N, P / max(dist, 1e-3)))));
	ao = mix(1.0, ao, 1.0 - smoothstep(64.0, 128.0, dist));
	return float4(ao, 0.0, 0.0, 1.0);
}

fragment float4 im_bloom_resolve(ImBloomOut in [[stage_in]], texture2d<float> src [[texture(0)]], sampler s [[sampler(0)]],
	texture2d<float> hdr [[texture(1)]], texture2d<float> shafts [[texture(2)]], texture2d<float> aoT [[texture(3)]],
	texture2d<float> pic [[texture(4)]], depth2d<float> depth [[texture(5)]], sampler ms [[sampler(1)]],
	constant ImBloomParams &p [[buffer(0)]], constant ImVlFrame &f [[buffer(1)]]) {
	float4 h = hdr.read(uint2(in.pos.xy));
	float3 c = imbLinear(h.rgb);
	if (p.grade.w > 0.0) {
		// Reimagined's rainbow (lib/atmospherics/rainbow.glsl, after rain): a ring opposite the sun, over the sky and far land
		float d = depth.read(uint2(clamp(in.uv, 0.0, 0.9999) * float2(depth.get_width(), depth.get_height())));
		float4 w = p.clipInv * float4(in.uv * 2.0 - 1.0, d >= 0.99999 ? 0.9999 : d, 1.0);
		float3 P = w.xyz / w.w;
		float len = length(P);
		float3 dir = P / max(len, 1e-4);
		float rl = 0.9 * 256.0;
		float far = d >= 0.99999 ? 1.0 : saturate(len / rl);
		float vl = dot(dir, f.lightDir.xyz);
		float rc = saturate(1.0 - (vl + 0.75) / 0.0625);
		float rf = rc * (1.0 - rc) * 3.7;
		rf *= rf;
		rf *= rf;
		rf *= far * far * p.grade.w;
		if (rf > 0.0) {
			float j = fract(52.9829189 * fract(dot(in.pos.xy, float2(0.06711056, 0.00583715))));
			float rcm = pow(rc, 1.4 + max(rc - 0.5, 0.0) * 1.6);
			rcm = smoothstep(0.0, 1.0, rcm) * 0.85 + (j - 0.5) * 0.1;
			float3 rb = float3(0.0);
			for (int k = -1; k <= 1; k++) {
				float q = rcm + 0.1 * float(k);
				rb += saturate(abs(fmod(q * 6.0 + float3(-0.55, 4.3, 2.2) + 60.0, 6.0) - 3.0) - 1.0);
			}
			rb /= 3.0;
			rb.r += pow(max(rc - 0.5, 0.0), 2.0) * max(1.0 - rc, 0.0) * 26.0;
			rb = pow(rb, 2.2) * float3(0.25, 0.075, 0.25) * 3.0;
			c += rb * rf;
		}
	}
	if (p.grade.y > 0.0) {
		// Reimagined's bloom fog (lib/atmospherics/fog/bloomFog.glsl): in rain, at night, in caves and under water, the farther
		// something is the more it dissolves into a soft glow of what's around it, the soft, hazy depth of its rainy and underwater
		// shots. Its strength comes from Bloom (rain, night, cave, water); the glow is the picture's own blurred copy.
		float d = depth.read(uint2(clamp(in.uv, 0.0, 0.9999) * float2(depth.get_width(), depth.get_height())));
		if (d < 0.99999) {
			float4 w = p.clipInv * float4(in.uv * 2.0 - 1.0, d, 1.0);
			float dist = length(w.xyz / w.w);
			float bf;
			if (p.grade.z > 1.5) {
				// (the Nether: Reimagined's, by the cube of the distance over the view range: the far side of the cavern a glowing
				// haze)
				float nf = dist / clamp(p.grade.z - 2.0, 96.0, 512.0);
				bf = 1.0 - exp(-8.0 * nf * nf * nf);
			} else {
				bf = 1.0 - exp(-dist * (p.grade.z > 0.5 ? 0.06 : 0.02));
				bf *= bf;
				bf *= bf;
			}
			float k = bf * p.grade.y;
			float3 blur = imbLinear(pic.sample(ms, in.uv, level(4.0)).rgb) * 0.5 + imbLinear(pic.sample(ms, in.uv, level(5.5)).rgb) * 0.5;
			c = mix(c, blur, k / (1.0 + k));
		}
	}
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
		// the light (a wide tent over the half-size texture: the jitter's grain smoothed; Reimagined smooths it with TAA)
		float2 st = 1.0 / float2(shafts.get_width(), shafts.get_height());
		// (edge-aware: each half-size texel counts by how close its traced distance is to this pixel's, so beams don't bleed
		// over the edges of leaves and blocks)
		float myLen = -1.0;
		if (p.texel.z > 0.5) {  // (the resolve's texel.z: 1 = the depth is bound)
			float dd = depth.read(uint2(clamp(in.uv, 0.0, 0.9999) * float2(depth.get_width(), depth.get_height())));
			float4 ww = p.clipInv * float4(in.uv * 2.0 - 1.0, dd >= 0.99999 ? 0.9999 : dd, 1.0);
			myLen = length(ww.xyz / ww.w);
		}
		float sh = 0.0, sb = 0.0, sw = 0.0;
		for (int y = -1; y <= 1; y++)
			for (int x = -1; x <= 1; x++) {
				float3 sv = shafts.sample(s, in.uv + st * float2(x, y)).rgb;
				float wgt = (2.0 - abs(float(x))) * (2.0 - abs(float(y)));
				if (myLen >= 0.0) wgt *= 1.0 / (1.0 + 20.0 * abs(sv.g - myLen) / max(myLen, 1.0));
				sh += sv.r * wgt;
				sb += sv.b * wgt;
				sw += wgt;
			}
		sh /= max(sw, 1e-4);
		sb /= max(sw, 1e-4);
		// (the Nether's storm: the haze over the picture; the End's beams: violet light, with a hot orange part farther out (b);
		// the light shafts: light added)
		if (p.shaft.x < -2.5) c += (p.shaftColor.rgb * sh + float3(1.0, 0.3, 0.0) * sb) * p.shaft.z;
		else if (p.shaft.x < -1.5) c = mix(c, p.shaftColor.rgb, saturate(sh * p.shaft.z));
		else c += p.shaftColor.rgb * (sh * p.shaft.z);
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
	// the light part lifted (Photon's dark darks, Reimagined's bright brights): above mid grey up to a quarter brighter, the lit
	// ground, the sky and the lights; the shade and the dark untouched, so the picture spans more range instead of a brighter grey
	float lumH = dot(c, float3(0.2126, 0.7152, 0.0722));
	// (fading out toward the top: lifting the bright sky and sunsets pushed them into the roll-off, washing their colour out)
	float mxH = max(max(c.r, c.g), c.b);
	c *= 1.0 + 0.25 * smoothstep(0.18, 0.5, lumH) * (1.0 - smoothstep(0.5, 0.85, mxH));
	// (Reimagined's tone curve's "path to white": the very brightest light, lava's hot spots, a flame's core, the sun's glare,
	// turns toward white, so lava reads yellow-hot instead of a flat orange)
	float lum2 = dot(c, float3(0.2126, 0.7152, 0.0722));
	float m = max(max(c.r, c.g), c.b);
	c = mix(c, float3(m), smoothstep(0.8, 3.0, lum2) * 0.55);
	m = max(max(c.r, c.g), c.b);
	const float t = 0.88;
	if (m > t) c *= (t + (1.0 - t) * (m - t) / ((m - t) + (1.0 - t))) / m;
	float3 o = pow(saturate(c), 1.0 / 2.2);
	// Reimagined's vignette (final.glsl VIGNETTE_R, on by default): the corners a little darker, less where it's bright
	float2 vc = in.uv - 0.5;
	o *= 1.0 - 0.4 * dot(vc, vc) * (1.0 - dot(o, float3(0.2126, 0.7152, 0.0722)));
	// (a triangular dither of one 8-bit step: smooth gradients on walls and in the sky without bands; with TAA it's done on its
	// output, the screen)
	if (p.taa.w < 0.5) o += imbDither8(in.pos.xy);
	return float4(o, h.a);
}

struct ImTaaOut {
	float4 screen [[color(0)]];
	float4 history [[color(1)]];
};

// TAA (the resolve's result, display-encoded): this frame's pixel blended with the last frames' at the same place in the world
// (found through the depth and the last frame's transform), the history first held within the colours of this pixel's 3x3
// neighbourhood (moving mobs and waving plants don't smear; what newly shows takes over at once). The terrain is drawn half a pixel
// off in a new direction each frame, so over eight frames every edge is sampled across its pixel: smooth edges, and the grain of
// the shafts, the AO and the reflections averaged away. Written to the screen and to the next frame's history.
fragment ImTaaOut im_bloom_taa(ImBloomOut in [[stage_in]], texture2d<float> cur [[texture(0)]], texture2d<float> hist [[texture(1)]],
	depth2d<float> depth [[texture(2)]], sampler s [[sampler(0)]], constant ImBloomParams &p [[buffer(0)]]) {
	uint2 px = uint2(in.pos.xy);
	uint2 size = uint2(cur.get_width(), cur.get_height());
	float3 c = cur.read(px).rgb;
	ImTaaOut o;
	if (p.taa.z < 0.5) {
		o.screen = float4(c, 1.0);
		o.history = o.screen;
		return o;
	}
	// the neighbourhood's colour box, and the nearest depth in it (its edges follow the nearer object)
	float3 mn = c, mx = c, m1 = c, m2 = c * c;
	float dmin = depth.read(min(px, size - 1));
	for (int y = -1; y <= 1; y++)
		for (int x = -1; x <= 1; x++) {
			if (x == 0 && y == 0) continue;
			uint2 q = uint2(clamp(int2(px) + int2(x, y), int2(0), int2(size) - 1));
			float3 v = cur.read(q).rgb;
			mn = min(mn, v);
			mx = max(mx, v);
			m1 += v;
			m2 += v * v;
			dmin = min(dmin, depth.read(q));
		}
	// (variance clipping, tighter than the min/max box: less ghosting)
	m1 /= 9.0;
	m2 /= 9.0;
	float3 sd = sqrt(max(m2 - m1 * m1, 0.0));
	mn = max(mn, m1 - 1.25 * sd);
	mx = min(mx, m1 + 1.25 * sd);
	float2 uv = in.uv - p.taa.xy;  // (this pixel without the jitter)
	float4 w = p.clipInv * float4(uv * 2.0 - 1.0, dmin >= 0.99999 ? 0.9999 : dmin, 1.0);
	float3 P = w.xyz / w.w;
	float4 pc = p.prevClip * float4(P, 1.0);
	float3 res = c, shown = c;
	if (pc.w > 0.05) {
		float2 puv = pc.xy / pc.w * 0.5 + 0.5;
		if (all(puv > 0.0) && all(puv < 1.0)) {
			// (the history was drawn with the last frame's offset: it is read where that put this point)
			float3 h = imbCatmull(hist, s, puv + p.taaPrev.xy);
			h = clamp(h, mn, mx);
			// (less history where the picture moved a lot on screen: sharper in motion)
			float moved = length((puv - in.uv) * float2(size));
			float k = mix(0.88, 0.7, saturate(moved / 12.0));
			res = mix(c, h, k);
			// (a light sharpen against what's left of the softness, on the screen only: fed back into the history it sharpened
			// the already sharpened picture every frame, the finest grain growing until the colour box stopped it, a crawling
			// static over walls and floors)
			shown = max(res + (res - m1) * 0.15, 0.0);
		}
	}
	o.screen = float4(shown + imbDither8(in.pos.xy), 1.0);
	o.history = float4(res, 1.0);
	return o;
}
