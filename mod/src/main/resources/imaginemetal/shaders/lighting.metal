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
	// the sky (Complementary Reimagined's, lib/colors/skyColors.glsl and lib/atmospherics/sky.glsl; linear)
	float4 dayUp;        // rgb its colour overhead by day, w rain 0..1
	float4 dayMiddle;    // rgb at the horizon by day, w sunFactor (Reimagined's: the day's share)
	float4 dayDown;      // rgb below the horizon by day, w noonFactor
	float4 nightUp;      // rgb overhead by night, w nightFactor
	float4 nightMiddle;  // rgb at the horizon by night, w sunVisibility
	float4 nightDown;    // rgb below the horizon by night, w shadowTime (how far from sunrise/sunset)
	float4 sunsetDown;   // rgb the sunset band (Reimagined's sunsetDownSkyColorP), w invNoonFactor
	float4 sunDir;       // xyz unit direction toward the sun (by night too), w 1 = this frame's fog is ours (overworld, eyes in air)
	float4 hazeTint;     // rgb the biome's tint on the haze, w its density multiplier
	float4 fogParams;    // x render distance (blocks), y the camera's world height, z 1 - how far underground, w 0
	float4x4 histClip;   // the reflections' history (the last frame's picture and depth): camera-relative world (then) -> clip
	float4x4 histClipInv;
	float4 histShift;    // xyz the camera's move since (now - then: a camera-relative position now + this = then), w 1 = valid
};

static inline float imSqrt2(float x) { x = 1.0 - x; x *= x; x *= x; return 1.0 - x; }
static inline float imSqrt3(float x) { x = 1.0 - x; x *= x; x *= x; x *= x; return 1.0 - x; }
static inline float imSmooth1(float x) { return x * x * (3.0 - 2.0 * x); }

// The sky's colour in direction d (world space, unit; linear, before the tone curve): Complementary Reimagined's GetSky. A
// gradient from overhead to a bright horizon (wider toward the sun), the sunset band, a scattered glow below the horizon, and
// the sun's (or moon's) glare. ground: darken below the horizon (the sky pass; the haze's colour leaves it).
static float3 imSkyColor(float3 d, constant ImFrame &f, bool ground) {
	float VdotU = d.y, VdotS = dot(d, f.sunDir.xyz);
	float rain = f.dayUp.w, rain2 = rain * rain, invRain = 1.0 - rain;
	float sunFactor = f.dayMiddle.w, noon = f.dayDown.w, night = f.nightUp.w, sunVis = f.nightMiddle.w, shadowTime = f.nightDown.w;
	float invNoon = f.sunsetDown.w;
	float nfs2 = imSqrt2(night);
	float nfM = imSqrt2(nfs2) * 0.4;
	float s1 = max(VdotS, 0.0);
	float VdotSM1 = s1 * s1, VdotSM2 = VdotSM1 * VdotSM1;
	float s3 = max(-VdotS, 0.0);
	s3 *= s3;
	float VdotSM3 = s3 * s3;
	float VdotSML = sunVis > 0.5 ? VdotS : -VdotS;
	float VdotUmax0 = max(VdotU, 0.0);

	float3 upColor = mix(f.nightUp.rgb * (1.5 - 0.5 * nfs2 + nfM * VdotSM3 * 1.5), f.dayUp.rgb, sunFactor);
	float3 middleColor = mix(f.nightMiddle.rgb * (3.0 - 2.0 * nfs2), f.dayMiddle.rgb * (1.0 + VdotSM2 * 0.3), sunFactor);
	float3 downColor = mix(f.nightDown.rgb, f.dayDown.rgb, (sunFactor + sunVis) * 0.5);

	float u1 = 1.0 - VdotUmax0;
	float VdotUM1 = u1 * u1;
	VdotUM1 = pow(VdotUM1, (1.0 - VdotSM2 * 0.4) * (1.15 - 0.15 * rain2));
	VdotUM1 = mix(VdotUM1, 1.0, rain2 * 0.15);
	float3 c = mix(upColor, middleColor, VdotUM1);

	float u2 = 1.0 - abs(VdotU);
	float VdotUM2 = u2 * u2;
	VdotUM2 = VdotUM2 * VdotUM2 * (3.0 - 2.0 * VdotUM2);
	VdotUM2 *= (0.7 - nfM + VdotSM1 * (0.3 + nfM)) * invNoon * sunFactor;
	c = mix(c, f.sunsetDown.rgb * (1.0 + VdotSM1 * 0.3), VdotUM2 * invRain);

	float VdotUM3 = imSmooth1(min(max(-VdotU + 0.08, 0.0) / 0.35, 1.0));
	float3 mixer = float3(VdotUM3 * VdotUM3, VdotUM3 * (2.0 - VdotUM3), imSqrt3(VdotUM3));
	mixer = mix(float3(VdotUM3), mixer, 0.75 - 0.5 * rain);
	c = mix(c, downColor, mixer);

	if (ground) {
		float g = 1.0 + min(VdotU, 0.0);
		c *= imSmooth1(g * g);
	}

	if (VdotSML > 0.0) {
		float scatter = 3.0 * (2.0 - saturate(VdotS * 1000.0));
		scatter *= 1.0 - 0.75 * rain2;
		float VdotSM4 = pow(abs(VdotS), scatter);
		const float vis = 0.075;
		float glare = (vis / (1.0 - (1.0 - vis) * VdotSM4) - vis) * 0.7;
		float3 glareColor = mix(float3(0.38, 0.4, 0.5) * 0.3, float3(1.5, 0.7, 0.3) + float3(0.0, 0.5, 0.5) * noon, sunVis);
		glare *= 1.0 - 0.8 * rain;
		glareColor = mix(glareColor, float3(dot(glareColor, float3(0.2126, 0.7152, 0.0722))), 0.5 * rain);
		c += glareColor * glare * shadowTime;
	}
	return max(c, 0.0);
}

// Reimagined's atmospheric fog colour (GetAtmFogColor): the night's sky colour by night, the day's colour below the horizon by day.
static float3 imAtmFogColor(float altitudeFactorRaw, float VdotS, constant ImFrame &f) {
	float rain = f.dayUp.w, night = f.nightUp.w, sunVis = f.nightMiddle.w;
	float a2 = altitudeFactorRaw * altitudeFactorRaw;
	float nightFogMult = 2.5 - 0.625 * max(a2 * a2, rain);
	float blend = pow(1.0 - night, 4.0 - VdotS - 2.5 * sunVis * sunVis);
	return mix(f.nightUp.rgb * (nightFogMult - blend * nightFogMult), f.dayDown.rgb * (0.9 + 0.3 * f.dayDown.w), blend);
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

// saturation and vibrance (vibrance lifts the colours that aren't saturated yet)
static inline float3 imGrade(float3 c, constant ImFrame &f) {
	float l = imLuma(c);
	float sat = max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b);
	return mix(float3(l), c, f.grade.x + f.grade.y * (1.0 - sat));
}

// light (linear, before the tone curve) as the terrain's own light ends up: exposure, the tone curve, the grade (linear after)
static inline float3 imPresent(float3 c, constant ImFrame &f) {
	return max(imGrade(imTonemap(c * f.minLight.w, f.tonemap), f), 0.0);
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
#define IM_MAT_GEM 10      // obsidian, amethyst, gem blocks, packed ice: smooth, strongly reflective
#define IM_MAT_STONE 11    // stone, cobblestone, deepslate, andesite, granite, diorite, tuff, bricks...: its specks glint
#define IM_MAT_SAND 12     // sand, red sand, sandstone, gravel: the brightest grains glint
#define IM_MAT_WOOD 13     // planks, logs, wood
#define IM_MAT_FOLIAGE 14  // leaves, grass (the block and the plants), vines, ferns
#define IM_MAT_DIRT 15     // dirt, coarse dirt, podzol, mud, farmland, paths

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
// Built from what Complementary Reimagined and Photon do (their integrated PBR, no resource pack): every block family's
// smoothness comes from its own texture (bright parts shinier: stone's specks, sand's brightest grains, polished wood), the sun
// leaves a GGX highlight on them, and reflections are traced through the screen with a steep fresnel falloff, so rough blocks
// reflect only at grazing angles while metal, gems and polished stone reflect clearly.

// Generated normals, as Reimagined does them: each texel compared with its neighbours an eighth of a texel away, so only the
// edges between texels that differ clearly in brightness bevel (a pixel-art relief), and never across the sprite's edge. Small
// differences are ignored, big ones capped. As the texels shrink on screen the bevels widen to a pixel and fade.
static inline float imGnDif(texture2d<float> atlas, float2 t, float lc) {
	float4 c = atlas.read(uint2(t), 0);
	if (c.a < 0.1) return 0.0;
	float d = lc - length(c.rgb);
	d = d > 0.0 ? max(d - 0.05, 0.0) : min(d + 0.05, 0.0);
	return clamp(d, -0.2, 0.2);
}

static float3 imGenNormal(texture2d<float> atlas, float2 uv, float4 rect, float strength, float2 texelsPerPixel, float3 col /* the texel, untinted */) {
	float2 asz = float2(atlas.get_width(), atlas.get_height());
	float2 lo = rect.xy * asz, hi = rect.zw * asz;
	if (strength <= 0.0 || any(hi - lo < 2.0)) return float3(0, 0, 1);
	float2 t = uv * asz;
	float2 off0 = float2(0.125);
	float2 off = max(off0, texelsPerPixel);
	float2 fade = off0 / off;
	if (max(fade.x, fade.y) < 0.02) return float3(0, 0, 1);
	float lc = length(col);
	float2 nm = float2(0.0);
	if (t.y + off.y < hi.y) nm.y += imGnDif(atlas, t + float2(0, off.y), lc);
	if (t.x + off.x < hi.x) nm.x += imGnDif(atlas, t + float2(off.x, 0), lc);
	if (t.y - off.y > lo.y) nm.y -= imGnDif(atlas, t - float2(0, off.y), lc);
	if (t.x - off.x > lo.x) nm.x -= imGnDif(atlas, t - float2(off.x, 0), lc);
	nm = clamp(nm * (2.5 * strength) * fade, -1.0, 1.0);
	return normalize(float3(nm, 1.0));
}

// A surface's specular properties. g: smoothness for the sun's highlight; d: smoothness for reflections; metal 0/1; f0 the
// reflectance head-on (dielectrics); hl how strong the highlight is.
struct ImSurf {
	float g, d, metal, f0, hl;
};

static inline float imLinStep(float a, float b, float x) {
	return saturate((x - a) / (b - a));
}

// col: the surface's colour as drawn (texture times tint, gamma space); tex: the texture alone.
static ImSurf imSurface(uint cls, float3 col, float3 tex) {
	ImSurf s = {0.0, 0.0, 0.0, 0.04, 1.0};
	float hi = max(max(col.r, col.g), col.b), lo = min(min(col.r, col.g), col.b);
	float L = 0.5 * (hi + lo);  // (HSL lightness, as Photon)
	float g2 = col.g * col.g, g4 = g2 * g2;
	switch (cls) {
		case IM_MAT_STONE:  // Reimagined: the light specks shine, the rest stays matte
			s.g = min(g4 * 2.0, 1.0);
			s.d = s.g;
			break;
		case IM_MAT_ORE: {
			float th = max(max(tex.r, tex.g), tex.b) - min(min(tex.r, tex.g), tex.b);
			if (th > 0.12) {  // the ore's own pixels: gem-like
				s.g = 0.8;
				s.d = 0.7;
				s.f0 = 0.2;
				s.hl = 2.5;
			} else {
				s.g = min(g4 * 2.0, 1.0);
				s.d = s.g;
			}
			break;
		}
		case IM_MAT_SAND:  // Photon: only the brightest grains glint
			s.g = 0.8 * imLinStep(0.72, 0.95, L);
			s.d = s.g * 0.5;
			s.hl = 2.0;
			break;
		case IM_MAT_WOOD:  // Photon's planks
			s.g = 0.5 * imLinStep(0.3, 0.8, L);
			s.d = s.g * 0.5;
			break;
		case IM_MAT_FOLIAGE:
			if (col.g > max(col.r, col.b) * 1.05) {  // Reimagined's leaves and grass: the green parts, with a strong sheen
				float k = min(pow(max(col.g - 0.15 * (col.r + col.b), 0.0), 2.0) * 2.5, 1.0);
				s.g = k * 0.4;
				s.hl = k * 4.0 + 2.0;
			} else {
				s.g = col.r * 0.1 + 0.1;  // (the dirt under a grass block's top)
			}
			break;
		case IM_MAT_DIRT:
			s.g = col.r * 0.1 + 0.1;
			break;
		case IM_MAT_POLISHED:
			s.g = 0.2 + 0.5 * imLinStep(0.1, 0.85, L);
			s.d = s.g;
			s.hl = 1.5;
			break;
		case IM_MAT_GEM:  // Photon's gems and obsidian: smooth, a strong reflectance
			s.g = 0.55 + 0.4 * sqrt(imLinStep(0.05, 0.85, L));
			s.d = s.g;
			s.f0 = 0.2;
			s.hl = 2.0;
			break;
		case IM_MAT_METAL:  // Photon's metals: smooth where bright; the colour is in the reflection
			s.g = max(sqrt(imLinStep(0.1, 0.9, L)), 0.3);
			s.d = s.g;
			s.metal = 1.0;
			s.hl = 2.5;
			break;
		case IM_MAT_EMITTER: case IM_MAT_LAVA: case IM_MAT_FLAME: case IM_MAT_DIM: case IM_MAT_SCULK:
			break;
		default:  // everything else (Photon's default, softer): a faint highlight on the light parts, no reflection
			s.g = 0.2 * smoothstep(0.2, 0.6, L);
			s.hl = 0.6;
			break;
	}
	return s;
}

// The sun's highlight (Reimagined's GGX): N the shading normal, V toward the eye, L toward the light.
static float imHighlight(float3 N, float3 V, float3 L, float nl, float smooth) {
	float sm = sqrt(saturate(smooth * 0.9 + 0.1));
	float rp = 1.35 - sm;
	float r = rp * rp;
	r *= r;
	float3 H = normalize(L + V);
	float lh = saturate(dot(H, L));
	float nh = saturate(dot(N, H));
	float nh2 = nh * nh;
	float den = nh2 * r - nh2 + 1.0;
	float D = r / (3.14159265 * den * den);
	const float f0 = 0.05;
	float F = exp2((-5.55473 * lh - 6.98316) * lh) * (1.0 - f0) + f0;
	return min(nl * D * F / max(lh * lh, 1e-3), 32.0);
}

// Screen-space reflection through the last frame's picture and depth (Reflections): march from p (camera-relative, now) along
// R, in the last frame's screen, until the ray passes just behind what was drawn there; then a few halving steps to the surface.
// rgba: the colour there (linear; blurred by lod) and how much to trust it; a = 0: the ray left the screen or ran out without
// passing behind anything (it escaped: the sky is a fair guess); a = -1: it went behind something whose far side the screen
// doesn't show (blocked: nothing is known, the surface keeps its own colour).
static float4 imReflect(float3 p, float3 n, float3 R, float lod, float dither, constant ImFrame &f, texture2d<float> hc, depth2d<float> hd,
	sampler hs) {
	float dist = length(p);
	float3 q = p + f.histShift.xyz + n * (0.03 + dist * 0.004);
	float2 size = float2(hd.get_width(), hd.get_height());
	float stepLen = 0.2 + dist * 0.03;
	float t = stepLen * (0.25 + 0.75 * dither), tPrev = 0.0;
	for (int i = 0; i < 26; i++) {
		float3 r = q + R * t;
		float4 c = f.histClip * float4(r, 1.0);
		if (c.w <= 0.05) break;
		float2 ndc = c.xy / c.w;
		if (any(abs(ndc) > 1.0)) break;
		float d = hd.read(uint2(clamp(ndc * 0.5 + 0.5, 0.0, 0.9999) * size));
		float4 sp = f.histClipInv * float4(ndc, d, 1.0);
		float diff = length(r) - length(sp.xyz / sp.w);
		if (diff > 0.0) {
			// (only just behind a surface counts as reaching it: far behind is passing behind something thin in front, a torch, a
			// fence, whose back the screen doesn't show)
			if (diff > 0.15 + stepLen * 0.6) return float4(0.0, 0.0, 0.0, -1.0);
			float a = tPrev, b = t;
			float2 uv = float2(0.0);
			for (int j = 0; j < 5; j++) {
				float m = 0.5 * (a + b);
				float3 rm = q + R * m;
				float4 cm = f.histClip * float4(rm, 1.0);
				float2 nm = cm.xy / cm.w;
				float dm = hd.read(uint2(clamp(nm * 0.5 + 0.5, 0.0, 0.9999) * size));
				float4 spm = f.histClipInv * float4(nm, dm, 1.0);
				if (length(rm) > length(spm.xyz / spm.w)) b = m; else a = m;
			}
			float3 rb = q + R * b;
			float4 cb = f.histClip * float4(rb, 1.0);
			float2 nb = cb.xy / cb.w;
			uv = nb * 0.5 + 0.5;
			float db = hd.read(uint2(clamp(uv, 0.0, 0.9999) * size));
			float4 spb = f.histClipInv * float4(nb, db, 1.0);
			if (abs(length(rb) - length(spb.xyz / spb.w)) > 0.1 + 0.01 * length(rb)) return float4(0.0, 0.0, 0.0, -1.0);
			float2 e = abs(nb);
			float edge = saturate(1.0 - pow(max(e.x, e.y), 24.0));
			float3 col = hc.sample(hs, uv, level(lod)).rgb;
			return float4(pow(max(col, 0.0), 2.2), edge);
		}
		tPrev = t;
		t += stepLen;
		stepLen *= 1.3;
	}
	return float4(0.0);
}

static float3 imShade(float3 albedo, float3 tex, float3 p, float2 light, uint mat, float2 pixel, constant ImFrame &f, depth2d_array<float> smap, sampler cmp,
	texture3d<float> lvol, sampler lsamp, float2 uv, float4 rect, texture2d<float> atlas, texture2d<float> hc, depth2d<float> hd, sampler hs) {
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
	if (f.lightSize.w > 0.0 && cls != IM_MAT_LAVA && !slanted) {
		float3 tn = imGenNormal(atlas, uv, rect, f.lightSize.w, tpp, tex);
		if (tn.z < 0.9999) {
			float3 dp2perp = cross(dp2, n), dp1perp = cross(n, dp1);
			float3 T = dp2perp * duv1.x + dp1perp * duv2.x;
			float3 B = dp2perp * duv1.y + dp1perp * duv2.y;
			float im = rsqrt(max(max(dot(T, T), dot(B, B)), 1e-30));
			N = normalize(T * (tn.x * im) + B * (tn.y * im) + n * tn.z);
		}
	}
	float3 base = pow(max(albedo, 0.0), 2.2);
	bool pbr = f.shadow.w > 0.5 && f.flags.y == 0;
	ImSurf surf = pbr ? imSurface(cls, albedo, tex) : ImSurf{0.0, 0.0, 0.0, 0.04, 0.0};
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

	// the sun's highlight (on top of the light, before the tone curve: a bright one blooms); a metal's in its own colour
	if (pbr && surf.g > 0.0) {
		float nlh = saturate(dot(N, f.lightDir.xyz)) * step(0.0, dot(n, f.lightDir.xyz));
		float hlt = imHighlight(N, V, f.lightDir.xyz, nlh, surf.g) * surf.hl;
		if (cls == IM_MAT_FOLIAGE) {
			float fr = 1.0 - saturate(dot(N, V));
			fr *= fr;
			hlt *= (1.0 - fr * fr) * 0.5;  // (fields of grass at a low angle: a sheen, not a glare)
		}
		float3 tint = mix(float3(1.0), base / max(max(max(base.r, base.g), base.b), 0.02), surf.metal);
		hlt *= mix(0.3, 1.0, max(surf.metal, smoothstep(0.04, 0.2, surf.f0)));  // (a dielectric's: a glint, not a white wash)
		radiance += f.lightColor.rgb * (hlt * sun) * tint * f.minLight.w;
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

	float3 c = imGrade(imTonemap(radiance, f.tonemap), f);

	// reflections (Reimagined's blend): how much by the angle, with a steep falloff for rough surfaces (they only reflect at
	// grazing angles), metals and gems at every angle; traced through the last frame's picture where it shows what's reflected,
	// the sky where nothing is in the way (as much as the sky can be seen from here: in a cave only what the screen shows)
	if (pbr && surf.d > 0.0) {
		float fresnel = 1.0 - saturate(dot(N, V));
		float ff = (1.0 - surf.d) * 0.7;
		float fm = max(fresnel - ff, 0.0) / (1.0 - ff);
		float intense = max(surf.metal, smoothstep(0.04, 0.2, surf.f0) * 0.6);
		fm = mix(fm * fm, fm * 0.75 + 0.25, intense);
		fm *= sqrt(surf.d);
		if (fm > 0.01) {
			float3 R = reflect(-V, N);
			if (dot(R, n) < 0.02) R = normalize(R - n * (dot(R, n) - 0.02));  // (a bumped normal can't reflect into the face)
			float lod = (1.0 - surf.d) * 5.0;
			// (a fixed dither: without TAA a per-frame one would only shimmer)
			float dither = fract(dot(pixel, float2(0.06711056, 0.00583715)) * 52.9829189);
			float4 hit = f.histShift.w > 0.5 ? imReflect(p, n, R, lod, dither, f, hc, hd, hs) : float4(0.0);
			float3 skyR = (f.flags.x == 0 ? imPresent(imSkyColor(R, f, false), f) : imPresent(f.ambientUp.rgb * 2.0, f)) * (skyF * skyF)
				* smoothstep(-0.1, 0.2, R.y);
			float3 refl = hit.a > 0.0 ? mix(skyR, hit.rgb, hit.a) : skyR;
			if (hit.a < 0.0) fm = 0.0;
			// (texture kept: Reimagined keeps 70 % of the surface under its reflection)
			refl = mix(refl, max(c, refl), 0.7);
			refl *= mix(float3(1.0), base / max(max(max(base.r, base.g), base.b), 0.02), surf.metal);
			c = mix(c, refl, saturate(fm));
		}
	}
	// light given off: on top of the tone-mapped light, unclamped (the picture is HDR: Bloom's resolve maps it to the screen)
	c = max(c, 0.0) + pow(max(tex, 0.0), 2.2) * emit;  // (the texture's colour: light given off isn't darkened by corner shading)
	return pow(c, 1.0 / 2.2);
}
