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

// Per-frame values, written by imaginemetal.ImagineFrame (same layout, 416 bytes) and bound at fragment buffer 30 (and at buffer 0
// of the sky pass, sky.metal).
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
	float4 env;          // x how far underground the player is (0..1, eased), y how much it's night (0..1)
	float4x4 shadowNear; // camera-relative world -> the near cascade's light clip (orthographic)
	float4x4 shadowFar;  // ... the far cascade's
	float4 shadow;       // x near cascade normal offset (blocks), y 1 = the maps are valid this frame, z far cascade normal offset,
	                     // w 1 = PBR (reflections and highlights) on
	float4 lightVol;     // the coloured light grid (light.metal): xyz its low corner, camera-relative (blocks), w 1 = valid this frame
	float4 lightSize;    // xyz its size in voxels (one a block), w the generated normals' strength (0 off)
	float4 skyZenith;    // the sky straight up (linear), w rain 0..1
	float4 skyHorizon;   // the sky at the horizon (linear), w how much it's sunset or sunrise 0..1
	float4 skySunset;    // the sunset band's colour (linear), w the distance haze's density (per block)
	float4 sunDir;       // xyz unit direction toward the sun (by night too), w how much its glow shows 0..1
	float4 sunGlow;      // the glow around the sun (linear), w 1 = this frame's fog is ours (overworld, eyes in air)
};

// The sky's colour in direction d (world space, unit; linear light, HDR around the sun): a gradient from the zenith to a bright
// horizon, a glow around the sun, a warm band along the horizon at sunrise and sunset, strongest toward the sun. The sky pass
// (sky.metal) draws it behind everything; the terrain's distance haze fades into it, so far land melts into the horizon.
static float3 imSkyColor(float3 d, constant ImFrame &f) {
	float up = d.y;
	float h = 1.0 - saturate(up);
	float3 c = mix(f.skyZenith.rgb, f.skyHorizon.rgb, h * h * h);
	if (up < 0.0) c = f.skyHorizon.rgb * mix(1.0, 0.55, saturate(-up * 2.5));  // below the horizon: a darker haze
	float mu = dot(d, f.sunDir.xyz);
	float toward = saturate(mu * 0.5 + 0.5);
	float band = pow(1.0 - abs(up), 5.0) * (0.25 + 0.75 * toward * toward);
	c = mix(c, f.skySunset.rgb, band * f.skyHorizon.w);
	float m = saturate(mu);
	float m2 = m * m, m4 = m2 * m2, m8 = m4 * m4, m16 = m8 * m8;
	c += f.sunGlow.rgb * (0.30 * m8 * m4 + 1.2 * m16 * m16 * m16) * f.sunDir.w;
	return c;
}

static inline float imLuma(float3 c) {
	return dot(c, float3(0.2126, 0.7152, 0.0722));
}

// Block light level 0..1 -> intensity: a hot core next to the source and a long reach (3.0 at level 15, 2.2 at 14, 1.1 at 12,
// 0.57 at 10, 0.15 at 7, 0.05 at 5), so weak lights still light their surroundings: a redstone torch (level 7) or a soul torch
// (10) colours the wall it's on. The dark between lights comes from the grade (Bloom's resolve), not from cutting the reach.
static inline float imBlockCurve(float b) {
	float b2 = b * b, b4 = b2 * b2, b8 = b4 * b4;
	return 2.2 * pow(b, 3.5) + 0.8 * b8;
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
	// the normal offset grows as the light grazes the face (a low sun across the ground): there one shadow texel spans a long strip
	// of the surface and a fixed offset leaves the surface shadowing itself in crawling stripes
	float nl = dot(n, f.lightDir.xyz);
	float grazing = 0.6 + 1.6 * sqrt(saturate(1.0 - nl * nl));
	// the near cascade
	float4 c0 = f.shadowNear * float4(p + n * (f.shadow.x * grazing), 1.0);
	float e0 = max(abs(c0.x), abs(c0.y));
	float nearW = (c0.z > 0.0 && c0.z < 1.0) ? 1.0 - smoothstep(0.8, 0.92, e0) : 0.0;
	float litNear = 1.0;
	if (nearW > 0.0) {
		float2 uv = float2(0.5 + 0.5 * c0.x, 0.5 - 0.5 * c0.y);
		litNear = imShadowPcf(smap, cmp, uv, c0.z - 0.00006, 0);
		if (nearW >= 1.0) return float2(litNear, 1.0);
	}
	// the far cascade
	float4 c1 = f.shadowFar * float4(p + n * (f.shadow.z * grazing), 1.0);
	float e1 = max(abs(c1.x), abs(c1.y));
	if (e1 > 0.98 || c1.z > 1.0 || c1.z < 0.0) return nearW > 0.0 ? float2(litNear, nearW) : float2(1.0, 0.0);
	float2 uv1 = float2(0.5 + 0.5 * c1.x, 0.5 - 0.5 * c1.y);
	float litFar = imShadowPcf(smap, cmp, uv1, c1.z - 0.00012, 1);
	float cover = 1.0 - smoothstep(0.9, 0.98, e1);
	return float2(mix(litFar, litNear, nearW), max(cover, nearW));
}

// Material classes (the low 4 bits of a face's material byte, imaginemetal.Materials).
#define IM_MAT_EMITTER 1   // a block that gives off light (glowstone, sea lantern, shroomlight, modded lights...)
#define IM_MAT_LAVA 2
#define IM_MAT_FLAME 3     // a small light with a flame or a lit part (torch, lantern, candle, fire, campfire)
#define IM_MAT_ORE 4       // an ore: its coloured specks glow faintly
#define IM_MAT_DIM 5       // a faint light (magma, sculk sensors, glow lichen...): its bright coloured parts glow
#define IM_MAT_NETHER_ORE 6 // an ore in netherrack (nether quartz, nether gold, ancient debris)
#define IM_MAT_SCULK 7     // sculk: its specks glow like stars
#define IM_MAT_METAL 8     // metal blocks (iron, gold, copper, netherite; chains, anvils, bells...): coloured reflections
#define IM_MAT_POLISHED 9  // polished and glazed stone, quartz, prismarine, concrete...: a smooth stone sheen
#define IM_MAT_GLOSSY 10   // ice, obsidian, amethyst, gem blocks: a near-mirror finish

// How much light a fragment of class cls gives off, as a multiple of its texture's colour (tex: the texel as sampled, before the
// vertex colour's tint and Minecraft's corner shading, so every face of a block glows alike). The result goes on top of the
// tone-mapped picture, so values past 1 are real HDR: the bloom picks them up.
static float imEmission(uint cls, float3 tex) {
	float l = imLuma(tex);
	float hi = max(max(tex.r, tex.g), tex.b), lo = min(min(tex.r, tex.g), tex.b);
	float sat = hi - lo;
	switch (cls) {
		case IM_MAT_EMITTER: return 2.6 * l * l + 0.25;
		case IM_MAT_LAVA: return 2.4 + 1.8 * l;
		case IM_MAT_FLAME: return 4.0 * smoothstep(0.62, 0.92, hi) * smoothstep(0.15, 0.4, sat);
		// ores: every pixel with clear colour against the grey stone around it (the whole speck, its coloured edge too; iron's pale
		// specks count; a speck's near-grey highlights and coal's black specks don't), at a faint glow that keeps the texture's own
		// light and dark
		case IM_MAT_ORE: return 0.6 * smoothstep(0.08, 0.2, sat);
		// nether ores sit in red netherrack: their specks are told apart by brightness instead
		case IM_MAT_NETHER_ORE: return 0.6 * smoothstep(0.5, 0.75, l);
		// faint lights (sculk sensors and catalysts, magma, glow lichen): only their bright, coloured parts glow, as a flame's
		case IM_MAT_DIM: return 1.6 * smoothstep(0.5, 0.85, hi) * smoothstep(0.12, 0.35, sat);
		// sculk's specks: only their brightest pixels (small, calm dots), but far past white, so the bloom gives each a soft halo
		// while the screen's roll-off keeps the dot itself from getting brighter
		case IM_MAT_SCULK: return 3.5 * smoothstep(0.62, 0.9, hi) * smoothstep(0.2, 0.45, sat);
		default: return 0.0;
	}
}

// Block light at p (camera-relative, n its face normal) from the coloured light grid in front of the face: rgb its colour at the
// torch light's strength (the warm default where the grid has nothing: outside it, or no light source it knows of), a the grid's
// light level there (0..1; it spreads each source 4 levels stronger than Minecraft does, so coloured lights read clearly).
// The colour's saturation is pushed past the source's own, since lighting grey stone and the tone curve wash a light's colour
// out; on screen it then reads as the colour it is.
static float4 imBlockLight(float3 p, float3 n, constant ImFrame &f, texture3d<float> vol, sampler vs) {
	float3 warm = f.blockColor.rgb;
	if (f.lightVol.w < 0.5) return float4(warm, 0.0);
	float3 uvw = (p + n * 0.5 - f.lightVol.xyz) / f.lightSize.xyz;
	float3 e = min(uvw, 1.0 - uvw) * f.lightSize.xyz;  // blocks to the grid's nearest side
	float inside = smoothstep(1.0, 6.0, min(min(e.x, e.y), e.z));
	if (inside <= 0.0) return float4(warm, 0.0);
	// a trilinear filter by hand that leaves solid voxels out (they hold a < 0): blending their nothing into the light next to
	// a wall made dark smears along the block grid, like shadows; this is the leak-proof filter Complementary uses too
	float3 g = uvw * f.lightSize.xyz - 0.5;
	float3 g0 = floor(g), t = g - g0;
	int3 i0 = int3(g0), top = int3(f.lightSize.xyz) - 1;
	float4 c = float4(0.0);
	float wsum = 0.0;
	for (int k = 0; k < 8; k++) {
		int3 o = int3(k & 1, (k >> 1) & 1, (k >> 2) & 1);
		float4 v = vol.read(uint3(clamp(i0 + o, int3(0), top)));
		if (v.a < 0.0) continue;
		float3 tw = select(1.0 - t, t, bool3(o != 0));
		float w = tw.x * tw.y * tw.z;
		c += v * w;
		wsum += w;
	}
	c = wsum > 1e-4 ? c / wsum : float4(0.0);
	float m = max(max(c.r, c.g), c.b);
	if (m < 1e-4) return float4(warm, 0.0);
	float3 hue = c.rgb / m;
	float hl = imLuma(hue);
	hue = max(hl + (hue - hl) * 1.6, 0.0);
	hue *= max(max(warm.r, warm.g), warm.b) / max(max(max(hue.r, hue.g), hue.b), 1e-4);
	float w = inside * smoothstep(0.0, 0.1, c.a);
	return float4(mix(warm, hue, w), c.a * inside);
}

// One terrain fragment: albedo as sampled and tinted (display-encoded, vanilla's AO and face shade already in it through the
// vertex colour), camera-relative position, light levels (x block, y sky; 0..1). Returns the display-encoded colour.
// ---- PBR ----

// The surface's height at texel t of its texture (wrapped inside the sprite's rectangle lo..hi, in texels): its brightness, so
// the mortar between bricks and the cracks in stone sit lower than the faces around them. Clear texels count as low.
static inline float imHeight(texture2d<float> atlas, float2 t, float2 lo, float2 size) {
	float2 w = lo + fmod(fmod(t - lo, size) + size, size);
	float4 c = atlas.read(uint2(w), 0);
	return imLuma(c.rgb) * c.a;
}

// Generated normals (no resource pack needed): the texture's brightness read as a height map, its slope tilting the face's normal,
// so every brick, plank and pebble catches the light on its own. Tangent space: x along the texture's u, y along its v, z out of
// the face. Fades out with distance (where a texel is smaller than a pixel it would only shimmer).
static float3 imGenNormal(texture2d<float> atlas, float2 uv, float4 rect, float strength, float2 texelsPerPixel) {
	float2 asz = float2(atlas.get_width(), atlas.get_height());
	float2 lo = floor(rect.xy * asz + 0.5), hi = floor(rect.zw * asz + 0.5);
	float2 size = hi - lo;
	if (strength <= 0.0 || any(size < 2.0) || any(size > 512.0)) return float3(0, 0, 1);
	float fade = 1.0 - smoothstep(0.6, 1.5, max(texelsPerPixel.x, texelsPerPixel.y));
	if (fade <= 0.0) return float3(0, 0, 1);
	float2 t = floor(uv * asz) + 0.5;
	float hx = imHeight(atlas, t + float2(1, 0), lo, size) - imHeight(atlas, t - float2(1, 0), lo, size);
	float hy = imHeight(atlas, t + float2(0, 1), lo, size) - imHeight(atlas, t - float2(0, 1), lo, size);
	float k = strength * fade;
	return normalize(float3(-hx * k, -hy * k, 1.0));
}

// How each class reflects: x smoothness (0 rough .. 1 mirror), y metalness (0 or 1); tex the texture's own colour. Smoothness
// follows the texture a little (bright parts of a block shinier than its dark seams), so it isn't one flat sheen.
static float2 imSurface(uint cls, float3 tex) {
	float l = imLuma(tex);
	float hi = max(max(tex.r, tex.g), tex.b), lo = min(min(tex.r, tex.g), tex.b);
	switch (cls) {
		case IM_MAT_METAL: return float2(0.50 + 0.35 * l, 1.0);
		case IM_MAT_POLISHED: return float2(0.55 + 0.2 * l, 0.0);
		case IM_MAT_GLOSSY: return float2(0.82 + 0.1 * l, 0.0);
		case IM_MAT_ORE: return float2(mix(0.2, 0.85, smoothstep(0.08, 0.2, hi - lo)), 0.0);  // the gems in it glint
		case IM_MAT_LAVA: case IM_MAT_EMITTER: case IM_MAT_FLAME: return float2(0.0, 0.0);
		default: return float2(0.12 + 0.18 * l, 0.0);  // everything else: a faint sheen at grazing angles
	}
}

static inline float3 imFresnel(float3 f0, float c, float rough) {
	float x = 1.0 - saturate(c);
	float x2 = x * x;
	return f0 + (max(float3(1.0 - rough), f0) - f0) * (x2 * x2 * x);
}

static float3 imShade(float3 albedo, float3 tex, float3 p, float2 light, uint mat, float2 pixel, constant ImFrame &f, depth2d_array<float> smap, sampler cmp,
	texture3d<float> lvol, sampler lsamp, float2 uv, float4 rect, texture2d<float> atlas) {
	if (f.flags.z == 0) return albedo * (0.2 + 0.8 * max(light.x, light.y)); // the engine hasn't filled a frame yet
	bool slanted;
	float3 n = imFaceNormal(p, slanted);
	// the texture's tangent frame from the screen derivatives (Schüler 2013), taken before any branch
	float3 dp1 = dfdx(p), dp2 = dfdy(p);
	float2 duv1 = dfdx(uv), duv2 = dfdy(uv);
	float2 asz = float2(atlas.get_width(), atlas.get_height());
	float2 tpp = (abs(duv1) + abs(duv2)) * asz;  // texels a pixel covers
	uint cls = mat & 15u;
	float3 N = n;
	if (f.lightSize.w > 0.0 && cls != IM_MAT_LAVA) {
		float3 tn = imGenNormal(atlas, uv, rect, f.lightSize.w, tpp);
		if (tn.z < 0.9999) {
			float3 dp2perp = cross(dp2, n), dp1perp = cross(n, dp1);
			float3 T = dp2perp * duv1.x + dp1perp * duv2.x;
			float3 B = dp2perp * duv1.y + dp1perp * duv2.y;
			float im = rsqrt(max(max(dot(T, T), dot(B, B)), 1e-30));
			N = normalize(T * (tn.x * im) + B * (tn.y * im) + n * tn.z);
		}
	}
	float3 base = pow(max(albedo, 0.0), 2.2);
	float2 surf = f.shadow.w > 0.5 ? imSurface(cls, tex) : float2(0.0);
	float metal = surf.y;
	float rough = max((1.0 - surf.x) * (1.0 - surf.x), 0.03);
	float3 f0 = mix(float3(0.04), base, metal);
	float3 V = -normalize(p);
	float block = light.x, sky = light.y;
	float skyF = imSkyCurve(sky);

	// direct light: wrapped N.L; until there are shadow maps, the sky light stands in for them (no sun under a roof)
	float nl = dot(N, f.lightDir.xyz);
	float wrap = slanted ? 0.35 + 0.5 * abs(nl) : max(nl + 0.4, 0.0) * (1.0 / 1.4);
	// sunlight: the shadow map where it reaches; the sky light stands in beyond it. Underground (no sky light at all) the sun is
	// off regardless, so a cave never catches sunlight through an imprecision of the map.
	float2 sm = imShadow(p, n, pixel, f, smap, cmp);
	float open = smoothstep(0.45, 0.9, sky);
	float sun = mix(open, sm.x, sm.y) * smoothstep(0.02, 0.3, sky);
	float3 direct = f.lightColor.rgb * (wrap * sun);

	// sky light: blue from above, warmer and dimmer from below
	float3 ambient = mix(f.ambientDown.rgb, f.ambientUp.rgb, N.y * 0.5 + 0.5) * skyF;

	// torches: warm, weaker in full daylight
	float4 bl = imBlockLight(p, n, f, lvol, lsamp);
	float3 torch = bl.rgb * imBlockCurve(max(block, bl.a)) * (1.0 - 0.45 * f.blockColor.w * smoothstep(0.5, 1.0, sky));

	// directional face shading (vanilla's is taken out of the vertex colours, FlatFaceShadeMixin): tops full, bottoms 60 %,
	// north/south a little brighter than east/west, so every block reads as a solid shape even in flat light
	float face = slanted ? 0.9 : (n.y >= 0.0 ? 1.0 : 1.0 + 0.4 * n.y) * (1.0 + 0.05 * abs(n.z) - 0.1 * abs(n.x));

	float3 day = direct + ambient;
	float3 lit = sqrt(day * day + torch * torch + f.minLight.rgb * f.minLight.rgb);
	float3 radiance = base * lit * face * f.minLight.w;

	// reflections and highlights (PBR): the sun's highlight (GGX), the sky reflected (its colour in the mirror direction on smooth
	// surfaces, the sky's light on rough ones; none where the sky can't be seen), a faint sheen of the torches' light
	if (f.shadow.w > 0.5 && f.flags.y == 0) {
		float nv = max(dot(N, V), 1e-3);
		float3 F = imFresnel(f0, nv, rough);
		radiance *= 1.0 - metal * 0.92;   // (a metal's colour is in its reflections; what's left is its tarnish)
		radiance *= 1.0 - (1.0 - metal) * imLuma(F);
		float3 H = normalize(V + f.lightDir.xyz);
		float nlh = saturate(dot(N, f.lightDir.xyz)), nh = saturate(dot(N, H)), vh = saturate(dot(V, H));
		float a2 = rough * rough;
		float d = nh * nh * (a2 - 1.0) + 1.0;
		float D = a2 / (3.14159 * d * d);
		float k = rough * 0.5;
		float vis = 0.25 / ((nlh * (1.0 - k) + k) * (nv * (1.0 - k) + k));
		float3 Fs = f0 + (1.0 - f0) * pow(1.0 - vh, 5.0);
		float3 highlight = min(D * vis * nlh, 24.0) * Fs * f.lightColor.rgb * sun * step(0.0, dot(n, f.lightDir.xyz));
		float3 R = reflect(-V, N);
		float3 blurry = mix(f.ambientDown.rgb, f.ambientUp.rgb, R.y * 0.5 + 0.5) * 2.2;
		float3 sharp = f.flags.x == 0 ? imSkyColor(R, f) * (R.y < 0.0 ? 0.45 : 1.0) : blurry;
		float3 env = mix(blurry, sharp, smoothstep(0.35, 0.85, surf.x)) * skyF;
		env += torch * (0.25 * surf.x);
		radiance += (highlight + env * F) * f.minLight.w;
	}

	if (f.flags.y == 1) return float3(light, 0.0);          // debug: light levels
	if (f.flags.y == 2) return N * 0.5 + 0.5;               // debug: normals (with the generated ones)
	if (f.flags.y == 4) {                                   // debug: the coloured light grid alone (its colour where light reaches)
		if (f.lightVol.w < 0.5) return float3(0.25, 0.0, 0.25);
		float3 uvw = (p + n * 0.5 - f.lightVol.xyz) / f.lightSize.xyz;
		if (any(uvw < 0.0) || any(uvw > 1.0)) return float3(0.25, 0.0, 0.25);
		float4 gl = imBlockLight(p, n, f, lvol, lsamp);
		float gm = max(max(gl.r, gl.g), gl.b);
		return pow(gm > 1e-4 ? gl.rgb / gm * saturate(gl.a * 1.5) : float3(0.0), 1.0 / 2.2) * 0.9 + base * 0.1;
	}
	float emit = imEmission(cls, tex);

	float3 c = imTonemap(radiance, f.tonemap);
	// saturation and vibrance (vibrance lifts the colours that aren't saturated yet)
	float l = imLuma(c);
	float sat = max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b);
	c = mix(float3(l), c, f.grade.x + f.grade.y * (1.0 - sat));
	// light given off: on top of the tone-mapped light, unclamped (the picture is HDR: Bloom's resolve maps it to the screen)
	c = max(c, 0.0) + pow(max(tex, 0.0), 2.2) * emit;  // (the texture's colour: light given off isn't darkened by corner shading)
	return pow(c, 1.0 / 2.2);
}
