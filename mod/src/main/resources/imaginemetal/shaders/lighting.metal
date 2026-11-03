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

// Per-frame values, written by imaginemetal.ImagineFrame (same layout, 240 bytes) and bound at fragment buffer 30.
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
	float4x4 shadowMat;  // camera-relative world -> the shadow map's light clip (orthographic, before the warp)
	float4 shadow;       // x warp strength, y 1 = the map is valid this frame, z normal offset at the camera (blocks), w its growth per block
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

// The shadow map's radial warp (shadow.metal's casters use the same): most texels near the player.
static inline float2 imWarp(float2 xy, float b) {
	return xy / (length(xy) * b + (1.0 - b));
}

// How much of the sun reaches p (camera-relative, n its face normal): 4 hardware-filtered taps of the shadow map around the
// point, rotated per pixel. x: 1 lit, 0 shadowed; y: how much the map covers p (1 inside, fading to 0 at its edge, 0 outside or
// with no map this frame), so the caller blends to its sky-light fallback without a visible seam.
static float2 imShadow(float3 p, float3 n, float2 pixel, constant ImFrame &f, depth2d<float> smap, sampler cmp) {
	if (f.shadow.y < 0.5) return float2(1.0, 0.0);
	float dist = length(p);
	float3 q = p + n * (f.shadow.z + f.shadow.w * dist);
	float4 c = f.shadowMat * float4(q, 1.0);
	float2 xy = imWarp(c.xy, f.shadow.x);
	float edge = max(abs(xy.x), abs(xy.y));
	if (edge > 0.98 || c.z > 1.0 || c.z < 0.0) return float2(1.0, 0.0);
	float2 uv = float2(0.5 + 0.5 * xy.x, 0.5 - 0.5 * xy.y);
	float texel = 1.0 / float(smap.get_width());
	float ref = c.z - 0.00012;
	// a fixed 5-tap pattern (centre + 4 diagonals), each tap 2x2 filtered by the compare sampler: soft edges that stay still when
	// the view turns (a per-pixel rotated pattern crawled without temporal filtering)
	float o = 1.2 * texel;
	float lit = 2.0 * smap.sample_compare(cmp, uv, ref)
		+ smap.sample_compare(cmp, uv + float2(o, o), ref) + smap.sample_compare(cmp, uv + float2(-o, o), ref)
		+ smap.sample_compare(cmp, uv + float2(o, -o), ref) + smap.sample_compare(cmp, uv + float2(-o, -o), ref);
	return float2(lit / 6.0, 1.0 - smoothstep(0.9, 0.98, edge));
}

// One terrain fragment: albedo as sampled and tinted (display-encoded, vanilla's AO and face shade already in it through the
// vertex colour), camera-relative position, light levels (x block, y sky; 0..1). Returns the display-encoded colour.
static float3 imShade(float3 albedo, float3 p, float2 light, float2 pixel, constant ImFrame &f, depth2d<float> smap, sampler cmp) {
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
