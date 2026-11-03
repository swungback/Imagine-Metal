// Imagine Metal: the lighting model.
//
// Prepended to mcopt's terrain library (mcopt/own/terrain.metal) with IM_SHADE defined; own_fs calls imShade for every terrain
// fragment. Written for Imagine Metal; the ideas follow Complementary Reimagined's look (studied, not copied):
//  - the sun wraps 0.4 past the terminator, so a block's side turned a little away still catches some light;
//  - daylight (sun + sky) and block light combine in quadrature, sqrt(day^2 + block^2 + floor^2), so a torch in sunlight barely
//    shows, a torch in a dark cave shows in full, and nothing blows out where they meet;
//  - sky-coloured (blue) shadows, warm torches that give way to daylight, a faint cool floor in caves;
//  - no auto-exposure: the colours themselves keep the brightness steady; a Lottes curve rolls the highlights off.
//
// Spaces: positions camera-relative world (blocks, +y up). Colours: linear light; the result is display-encoded (gamma 2.2)
// because the terrain pass writes straight into the game's 8-bit target.
#include <metal_stdlib>
using namespace metal;

// Per-frame values, written by imaginemetal.ImagineFrame (same layout, 304 bytes) and bound at fragment buffer 30.
struct ImFrame {
	float4 lightDir;     // xyz unit direction toward the light that casts this frame's light (sun by day, moon by night), w 1 = sun
	float4 lightColor;   // rgb direct light (linear), w rain 0..1
	float4 ambientUp;    // rgb sky light from above (linear)
	float4 ambientDown;  // rgb light bounced up from the ground (linear)
	float4 blockColor;   // rgb torch light at level 15 (linear), w how much daylight there is 0..1 (torches give way to it)
	float4 minLight;     // rgb the least light anything gets (caves), w exposure
	float4 tonemap;      // Lottes 2016: y = x^a / (x^(a d) b + c); x a, y d, z b, w c
	float4 grade;        // x saturation, y vibrance, zw 0
	int4 flags;          // x dimension (0 overworld, 1 Nether, 2 End), y debug view, z 1 = the engine filled this frame, w frame index
	float4 reserved;
	float4x4 shadowNear; // camera-relative world -> the near cascade's light clip (orthographic)
	float4x4 shadowFar;  // ... the far cascade's
	float4 shadow;       // x near cascade normal offset (blocks), y 1 = the maps are valid this frame, z far cascade normal offset, w 0
};

static inline float imLuma(float3 c) {
	return dot(c, float3(0.2126, 0.7152, 0.0722));
}

// Block light level 0..1 -> intensity: a hot core right next to the source on top of a long, soft reach (2.8 at level 15,
// 1.7 at 14, 0.23 at 10, 0.05 at 7), so a room lit by one torch reads as lit, and the torch itself glows.
static inline float imBlockCurve(float b) {
	float v = b / (4.0 - 3.0 * b);
	float b2 = b * b, b4 = b2 * b2, b8 = b4 * b4;
	return 1.6 * v * v + 1.2 * b8;
}

// Sky light level 0..1 -> how much of the sky's light reaches the surface.
static inline float imSkyCurve(float s) {
	return s * s * (3.0 - 2.0 * s);
}

static inline float3 imTonemap(float3 x, float4 t) {
	float3 xa = pow(max(x, 1e-6), float3(t.x));
	float3 xad = pow(max(x, 1e-6), float3(t.x * t.y));
	return xa / (xad * t.z + t.w);
}

// The face normal from the position's screen derivatives (a 2x2 quad always shades one triangle), turned toward the camera and
// snapped to its axis on the axis-aligned faces that make up nearly all of Minecraft.
static inline float3 imFaceNormal(float3 p, thread bool &slanted) {
	float3 n = cross(dfdx(p), dfdy(p));
	n *= rsqrt(max(dot(n, n), 1e-30));
	if (dot(n, p) > 0.0) n = -n;
	float3 a = abs(n);
	float m = max(max(a.x, a.y), a.z);
	slanted = m < 0.9; // crossed plants and other slanted cutouts
	if (slanted) return n;
	return a.x == m ? float3(sign(n.x), 0, 0) : a.y == m ? float3(0, sign(n.y), 0) : float3(0, 0, sign(n.z));
}

// One cascade's 3x3 tent filter at uv (each tap 2x2 filtered by the compare sampler). A fixed pattern: soft edges that stay still
// when the view turns (a per-pixel rotated pattern crawled).
static inline float imShadowPcf(depth2d_array<float> smap, sampler cmp, float2 uv, float ref, uint slice) {
	float o = 1.5 / float(smap.get_width());
	float lit = 0.0, wsum = 0.0;
	for (int j = -1; j <= 1; j++) {
		for (int i = -1; i <= 1; i++) {
			float w = (2.0 - abs(float(i))) * (2.0 - abs(float(j)));
			lit += w * smap.sample_compare(cmp, uv + float2(i, j) * o, slice, ref);
			wsum += w;
		}
	}
	return lit / wsum;
}

// How much of the sun reaches p (camera-relative, n its face normal), from two cascades: the near one (sharp, around the player)
// where it covers p, the far one beyond, blended across a band so the switch doesn't show. Both are snapped to world-fixed texel
// grids, so a shadow stays put as the player walks. x: 1 lit, 0 shadowed; y: how much the maps cover p (1 inside, fading to 0 at
// the far cascade's edge, 0 outside or with no maps this frame), so the caller blends to its sky-light fallback without a seam.
static float2 imShadow(float3 p, float3 n, float2 pixel, constant ImFrame &f, depth2d_array<float> smap, sampler cmp) {
	if (f.shadow.y < 0.5) return float2(1.0, 0.0);
	// the near cascade
	float4 c0 = f.shadowNear * float4(p + n * f.shadow.x, 1.0);
	float e0 = max(abs(c0.x), abs(c0.y));
	float nearW = (c0.z > 0.0 && c0.z < 1.0) ? 1.0 - smoothstep(0.8, 0.92, e0) : 0.0;
	float litNear = 1.0;
	if (nearW > 0.0) {
		float2 uv = float2(0.5 + 0.5 * c0.x, 0.5 - 0.5 * c0.y);
		litNear = imShadowPcf(smap, cmp, uv, c0.z - 0.00006, 0);
		if (nearW >= 1.0) return float2(litNear, 1.0);
	}
	// the far cascade
	float4 c1 = f.shadowFar * float4(p + n * f.shadow.z, 1.0);
	float e1 = max(abs(c1.x), abs(c1.y));
	if (e1 > 0.98 || c1.z > 1.0 || c1.z < 0.0) return nearW > 0.0 ? float2(litNear, nearW) : float2(1.0, 0.0);
	float2 uv1 = float2(0.5 + 0.5 * c1.x, 0.5 - 0.5 * c1.y);
	float litFar = imShadowPcf(smap, cmp, uv1, c1.z - 0.00012, 1);
	float cover = 1.0 - smoothstep(0.9, 0.98, e1);
	return float2(mix(litFar, litNear, nearW), max(cover, nearW));
}

// One terrain fragment: albedo as sampled and tinted (display-encoded, vanilla's AO and face shade already in it through the
// vertex colour), camera-relative position, light levels (x block, y sky; 0..1). Returns the display-encoded colour.
static float3 imShade(float3 albedo, float3 p, float2 light, float2 pixel, constant ImFrame &f, depth2d_array<float> smap, sampler cmp) {
	if (f.flags.z == 0) return albedo * (0.2 + 0.8 * max(light.x, light.y)); // the engine hasn't filled a frame yet
	bool slanted;
	float3 n = imFaceNormal(p, slanted);
	float3 base = pow(max(albedo, 0.0), 2.2);
	float block = light.x, sky = light.y;
	float skyF = imSkyCurve(sky);

	// direct light: wrapped N.L; until there are shadow maps, the sky light stands in for them (no sun under a roof)
	float nl = dot(n, f.lightDir.xyz);
	float wrap = slanted ? 0.35 + 0.5 * abs(nl) : max(nl + 0.4, 0.0) * (1.0 / 1.4);
	// sunlight: the shadow map where it reaches; the sky light stands in beyond it. Underground (no sky light at all) the sun is
	// off regardless, so a cave never catches sunlight through an imprecision of the map.
	float2 sm = imShadow(p, n, pixel, f, smap, cmp);
	float open = smoothstep(0.45, 0.9, sky);
	float sun = mix(open, sm.x, sm.y) * smoothstep(0.02, 0.3, sky);
	float3 direct = f.lightColor.rgb * (wrap * sun);

	// sky light: blue from above, warmer and dimmer from below
	float3 ambient = mix(f.ambientDown.rgb, f.ambientUp.rgb, n.y * 0.5 + 0.5) * skyF;

	// torches: warm, weaker in full daylight
	float3 torch = f.blockColor.rgb * imBlockCurve(block) * (1.0 - 0.45 * f.blockColor.w * smoothstep(0.5, 1.0, sky));

	// directional face shading (vanilla's is taken out of the vertex colours, FlatFaceShadeMixin): tops full, bottoms 60 %,
	// north/south a little brighter than east/west, so every block reads as a solid shape even in flat light
	float face = slanted ? 0.9 : (n.y >= 0.0 ? 1.0 : 1.0 + 0.4 * n.y) * (1.0 + 0.05 * abs(n.z) - 0.1 * abs(n.x));

	float3 day = direct + ambient;
	float3 lit = sqrt(day * day + torch * torch + f.minLight.rgb * f.minLight.rgb);
	float3 radiance = base * lit * face * f.minLight.w;

	if (f.flags.y == 1) return float3(light, 0.0);          // debug: light levels
	if (f.flags.y == 2) return n * 0.5 + 0.5;               // debug: normals

	float3 c = imTonemap(radiance, f.tonemap);
	// saturation and vibrance (vibrance lifts the colours that aren't saturated yet)
	float l = imLuma(c);
	float sat = max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b);
	c = mix(float3(l), c, f.grade.x + f.grade.y * (1.0 - sat));
	return pow(saturate(c), 1.0 / 2.2);
}
