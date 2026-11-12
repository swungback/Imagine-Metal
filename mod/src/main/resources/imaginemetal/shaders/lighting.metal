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

// Per-frame values, written by imaginemetal.ImagineFrame (same layout, 848 bytes) and bound at fragment buffer 30 (and at buffer 0
// of the sky pass, sky.metal).
struct ImFrame {
	float4 lightDir;     // xyz unit direction toward the light that casts this frame's light (sun by day, moon by night), w 1 = sun
	float4 lightColor;   // rgb direct light (linear), w rain 0..1
	float4 ambientUp;    // rgb sky light from above (linear), w 1 = daylight under something high overhead by the shadow maps (Build C1)
	float4 ambientDown;  // rgb light bounced up from the ground (linear), w the far shadows' softness: a shadow's blur in blocks per
	                     // block between caster and receiver (0: off; Build C1)
	float4 blockColor;   // rgb torch light at level 15 (linear), w how much daylight there is 0..1 (torches give way to it)
	float4 minLight;     // rgb the least light anything gets (caves), w exposure
	float4 tonemap;      // Lottes 2016: y = x^a / (x^(a d) b + c); x a, y d, z b, w c
	float4 grade;        // x saturation, y vibrance, z 1 = the generated normals' bevels are baked (texture 27), w the desert look
	                     // (Build C1: how much the camera is in a dry biome, 0..1: less vibrance on beige, warmer sand)
	int4 flags;          // x dimension (0 overworld, 1 Nether, 2 End), y debug view, z 1 = the engine filled this frame, w frame index
	float4 env;          // x how far underground the player is (0..1, eased), y how much it's night (0..1), z the light shafts'
	                     // ceiling (bloom.metal; 0: none), w bits (Build C2): 1 the shafts' strong mode only through shadow, 2 the
	                     // plants' shadow slice (2) is valid, 4 the far map's receivers without the oversized offsets, 8 water and
	                     // glass reflect the sky alone (benchmark)
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
	float4 hazeTint;     // the air fog (Photon's): rgb Rayleigh scattering per block (by biome), w Mie per block (time of day, weather)
	float4 fogParams;    // x render distance (blocks), y the camera's world height, z 1 - how far underground, w the air's thickness (setting)
	float4x4 histClip;   // the reflections' history (the last frame's picture and depth): camera-relative world (then) -> clip
	float4x4 histClipInv;
	float4 histShift;    // xyz the camera's move since (now - then: a camera-relative position now + this = then), w 1 = valid
	float4 cloudPos;     // our clouds: xy the camera's place on the cloud map (texels, wind included), z their height, w 1 = on
	float4 aurora;       // x the aurora's visibility (night, cold biome), y its drift, zw the camera's place on its plane
	float4 world;        // xyz the camera's world position (wrapped every 4096 blocks), w time in seconds (wrapped hourly)
	float4 auroraLow;    // the aurora's colour where its curtains end (low), w its style (0 smooth, 1 pixel)
	float4 auroraHigh;   // its colour at their height, w its brightness
	float4 held;         // the light in the player's hands: rgb its colour (brightest channel 1), w its level 0..1
	float4 auroraLight;  // rgb the aurora's light on the world (linear, from above), w stars (bit 1 ours, twinkling; bit 2 shooting stars)
	// water and glass (the translucent pass, which has no material bytes: water told by its sprites)
	float4 waterStill;   // the still water sprite's rectangle in the block atlas (u0, v0, u1, v1)
	float4 waterFlow;    // the flowing water's
	float4 portalRect;   // the nether portal's (not glass: it doesn't reflect)
	float4 water;        // x water style (0 vanilla, 1 Reimagined, 2 Reimagined with waves), y 1 = glass reflections, z 1 = eyes in water,
	                     // w how wet the world is (rain, eased; 0 in dry biomes or with the setting off)
	float4 taa;          // TAA: xy this frame's sub-pixel jitter (Minecraft's clip units, y up), z 1 = on, w the render scale (the
	                     // world's size as a share of the screen's, 1 = all of it)
	float4 nether;       // the Nether's haze (Reimagined's netherColor as the terrain shows it, linear), w its full distance (0: not ours)
	float4 extra;        // x 1 = portal effects, y 1 = sunlight coloured by stained glass, z 1 = an End portal near (endPortal), w 1 = night nebula
	float4 endPortal;    // the End portal's rays (bloom.metal): xyz its middle, camera-relative; w the time (s)
	float4 toggles;      // x 1 = coated textures, y 1 = coloured light fog (bloom.metal), z 1 = mob shadows, w 1 = water refraction and foam
	float4 endFlash;     // the End's flashes: xyz toward the flash, w its strength now (0 none)
	float4 pbrPack;      // x 1 = a PBR resource pack's maps (PbrAtlas, textures 27 and 28), y 1 = parallax from their height
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
	// (by day a fifth less saturated: measured against Reimagined's frames, our day sky came out a deeper, purer blue)
	float cl = dot(c, float3(0.2126, 0.7152, 0.0722));
	// (only toward noon: at sunrise and sunset it dulled the gold and orange Reimagined's are full of)
	c = mix(c, float3(cl), 0.2 * sunVis * noon);
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
	// Reimagined's own curve (mainLighting.glsl: a steep part near the light plus a calm one that carries it across a room,
	// to the power 2.25), scaled to our light's brightness: it reaches further and stays even through a room, where ours fell
	// off fast and left everything a few blocks from a light dark
	float b2 = b * b, b4 = b2 * b2, b8 = b4 * b4;
	return 0.07 * pow(2.5 * b8 + 3.1 * b, 2.25);
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
	// (and a little more colour where it's muted, Reimagined's vibrance: the picture read bland, washed out)
	// (but not near-grey colours: the boost for muted ones turned netherite's faint purple tint, a sword in the hand, plainly purple)
	float muted = smoothstep(0.03, 0.12, sat);
	float boost = (f.grade.y * (1.0 - sat) + 0.12 * (1.0 - saturate(sat * 2.5))) * muted;
	// (Build C1, the desert look: the boost for muted colours pushed beige, sand above all, toward yellow: "pale lemon". Warm
	// hues between orange and yellow (red over green over blue, green well clear of blue) keep only a third of it)
	if (f.grade.w > 0.0 && c.r >= c.g && c.g >= c.b) boost *= 1.0 - 0.67 * f.grade.w * saturate((c.g - c.b) / max(sat, 1e-4) * 2.0 - 0.6);
	return mix(float3(l), c, f.grade.x + boost);
}

// light (linear, before the tone curve) as the terrain's own light ends up: exposure, the tone curve, the grade (linear after)
static inline float3 imPresent(float3 c, constant ImFrame &f) {
	return max(imGrade(imTonemap(c * f.minLight.w, f.tonemap), f), 0.0);
}

// Minecraft's linear fog (fog.glsl linear_fog_value)
static inline float imLinFog(float d, float s, float e) {
	return d <= s ? 0.0 : d >= e ? 1.0 : (d - s) / (e - s);
}

// The fog with the eyes under water (Reimagined's): the colour (display-encoded) and, in w, how much of it at this distance.
// mcFog: Minecraft's own water fog colour, only its brightness used. For the terrain and for mobs alike, so a mob fades into
// the water exactly as the sea floor beside it does.
static inline float4 imWaterFog(float3 mcFog, float dist, constant ImFrame &f) {
	// (thicker the deeper the eyes are under the sea's level: "a little more foggy further down")
	float deep = saturate((60.0 - f.fogParams.y) / 40.0);
	float d = dist / (46.0 - 16.0 * deep);
	// (bluer and a little brighter than the plain colour: Reimagined's water reads blue, ours came out teal-grey)
	float3 wfc = pow(max(mcFog, 1e-3), float3(0.33, 0.21, 0.26)) * 0.24 * float3(0.8, 1.0, 1.3);
	// (the hue held at Reimagined's blue-green, only its brightness from Minecraft's colour: at dusk Minecraft mixes the sky's
	// purple into it and under the sea turned a dull grey-violet)
	float wl = dot(wfc, float3(0.2126, 0.7152, 0.0722));
	// (a vibrant tropical turquoise, brighter, not a cold deep blue: "not like a cold ocean"; a little darker the deeper)
	wfc = mix(wfc, float3(0.12, 0.75, 0.7) * (wl / 0.5348) * 1.3 * (1.0 - 0.45 * deep), 0.8);
	return float4(pow(imPresent(wfc, f), 1.0 / 2.2), 1.0 - exp(-d * d));
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
	// (four filtered compares (each one 2x2 texels) three quarters of a texel out: about the softness of the nine taps it
	// replaced, at under half the reads, on every pixel the sun reaches)
	float o = 0.75 / float(smap.get_width());
	return 0.25 * (smap.sample_compare(cmp, uv + float2(-o, -o), slice, ref) + smap.sample_compare(cmp, uv + float2(o, -o), slice, ref)
		+ smap.sample_compare(cmp, uv + float2(-o, o), slice, ref) + smap.sample_compare(cmp, uv + float2(o, o), slice, ref));
}

// The depth one shadow texel spans along a surface at this slope (tan of the light's angle to the normal), in the cascade's depth
// units, times k texels (the filter's reach): an orthographic cascade, so its x and z rows give a texel's size and depth's scale.
static inline float imSlopeBias(float4x4 m, float slope, float k, uint res) {
	float xs = length(float3(m[0][0], m[1][0], m[2][0])), zs = length(float3(m[0][2], m[1][2], m[2][2]));
	float texel = 2.0 / max(xs * float(res), 1e-6);
	return zs * texel * slope * k * 0.5;
}

// (Build C2) The plants' shadow (slice 2: grass, flowers, tall grass of the near map, nothing else), 1 lit .. 0.3: soft, and lighter
// than a wall's, the blades let some light through (Jack's reference: Reimagined's grass shadows on a path). The ground and the
// walls aren't in this slice, so they need no offset against themselves: the shadow starts at the plant's foot (the "ghost
// copies" of before were the main map's offsets for big faces pushing a plant's small shadow away from it). A plant receiving
// (a slanted face) is moved a quarter block toward the light, past its own caster. Four taps 2.5 texels out (a twelfth of a
// block), turned per pixel and frame (the TAA blends them).
static float imPlantShadow(float3 p, float3 n, float2 pixel, constant ImFrame &f, depth2d_array<float> smap, sampler cmp) {
	float3 a = abs(n);
	bool plantRecv = max(max(a.x, a.y), a.z) < 0.9;
	float3 q = plantRecv ? p + f.lightDir.xyz * 0.25 : p + n * 0.01;
	float4 c = f.shadowNear * float4(q, 1.0);
	if (c.z <= 0.0 || c.z >= 1.0) return 1.0;
	float2 uv = float2(0.5 + 0.5 * c.x, 0.5 - 0.5 * c.y);
	float r = 2.5 / float(smap.get_width());
	float dth = fract(dot(pixel, float2(0.06711056, 0.00583715)) * 52.9829189);
	if (f.taa.z > 0.5) dth = fract(dth + 0.61803399 * float(f.flags.w & 63));
	float ca = cos(dth * 6.2831853) * r, sa = sin(dth * 6.2831853) * r;
	float ref = c.z - 0.00003;
	float lit = 0.25 * (smap.sample_compare(cmp, uv + float2(ca, sa), 2, ref) + smap.sample_compare(cmp, uv - float2(ca, sa), 2, ref)
		+ smap.sample_compare(cmp, uv + float2(-sa, ca) * 0.5, 2, ref) + smap.sample_compare(cmp, uv + float2(sa, -ca) * 0.5, 2, ref));
	return 1.0 - 0.7 * (1.0 - lit);
}

// How much of the sun reaches p (camera-relative, n its face normal), from two cascades: the near one (sharp, around the player)
// where it covers p, the far one beyond, blended across a band so the switch doesn't show. Both are snapped to world-fixed texel
// grids, so a shadow stays put as the player walks. x: 1 lit, 0 shadowed; y: how much the maps cover p (1 inside, fading to 0 at
// the far cascade's edge, 0 outside or with no maps this frame), so the caller blends to its sky-light fallback without a seam.
static float2 imShadowX(float3 p, float3 n, float2 pixel, constant ImFrame &f, depth2d_array<float> smap, sampler cmp, bool wantOpen,
	thread float &openAir) {
	openAir = 0.0;
	if (f.shadow.y < 0.5) return float2(1.0, 0.0);
	// the normal offset grows as the light grazes the face (a low sun across the ground): there one shadow texel spans a long strip
	// of the surface and a fixed offset leaves the surface shadowing itself in crawling stripes
	float nl = dot(n, f.lightDir.xyz);
	float grazing = 0.6 + 1.6 * sqrt(saturate(1.0 - nl * nl));
	// (and a depth bias growing with the slope: on ground the light grazes, one shadow texel spans more depth than a fixed bias
	// covers, and past the near cascade, with its coarser texels, the ground shadowed itself in stripes, line, gap, line)
	float slope = min(sqrt(saturate(1.0 - nl * nl)) / max(abs(nl), 0.05), 4.0);
	// (Build C1) the far cascade's softness (0: off, everything below as it was before)
	float soft = f.ambientDown.w;
	bool search = soft > 0.0 || wantOpen;
	// the near cascade
	float4 c0 = f.shadowNear * float4(p + n * (f.shadow.x * grazing), 1.0);
	float e0 = max(abs(c0.x), abs(c0.y));
	float nearW = (c0.z > 0.0 && c0.z < 1.0) ? 1.0 - smoothstep(0.8, 0.92, e0) : 0.0;
	float litNear = 1.0;
	if (nearW > 0.0) {
		float2 uv = float2(0.5 + 0.5 * c0.x, 0.5 - 0.5 * c0.y);
		litNear = imShadowPcf(smap, cmp, uv, c0.z - 0.00006 - imSlopeBias(f.shadowNear, slope, 2.0, smap.get_width()), 0);
	}
	// (Build C2) the plants' own slice, near only, eased out from 18 to 28 blocks (past that their shadows would be the far map's
	// quarter-block texels: blobs)
	uint bits = uint(f.env.w + 0.5);
	float plantK = 1.0;
	if (nearW > 0.0 && (bits & 2u) != 0u) {
		float pw = nearW * (1.0 - smoothstep(18.0, 28.0, length(p)));
		if (pw > 0.0) plantK = mix(1.0, imPlantShadow(p, n, pixel, f, smap, cmp), pw);
	}
	if (nearW >= 1.0 && !search) return float2(litNear * plantK, 1.0);
	// the far cascade
	// (Build C2, env.w bit 4, the line where shadows ended: this map's normal offset was 1.5 of its texels (a quarter block each)
	// times up to 2.2, with a slope bias on top: the ground was looked up about a block above itself, so steps, fences and bushes
	// cast nothing here. Now a quarter of that offset, and the four taps lie in the receiving face's own plane, each with its own
	// exact depth there: a flat face can't shadow itself however it slopes, and the casters' raster bias (two texels of slope,
	// imshadow.m) covers what the 2x2 filter reaches. A small depth bias against the depth's own precision.)
	bool farFix = (bits & 4u) != 0u;
	float4 c1 = f.shadowFar * float4(p + n * (f.shadow.z * grazing * (farFix ? 0.25 : 1.0)), 1.0);
	float e1 = max(abs(c1.x), abs(c1.y));
	if (e1 > 0.98 || c1.z > 1.0 || c1.z < 0.0) return nearW > 0.0 ? float2(litNear * plantK, nearW) : float2(1.0, 0.0);
	float res = float(smap.get_width());
	float2 uv1 = float2(0.5 + 0.5 * c1.x, 0.5 - 0.5 * c1.y);
	float biasFar = farFix ? 0.00004 : 0.00012 + imSlopeBias(f.shadowFar, slope, 1.5, smap.get_width());
	float z1 = c1.z - biasFar;
	float litFar = 1.0;
	if (nearW < 1.0) {
		float o1 = 0.75 / res;
		if (farFix) {
			float4x4 m = f.shadowFar;
			float xs = length(float3(m[0][0], m[1][0], m[2][0]));
			float ob = 0.75 * 2.0 / max(xs * res, 1e-6);  // (three quarters of a far texel, in blocks)
			float3 up = abs(n.y) > 0.9 ? float3(1.0, 0.0, 0.0) : float3(0.0, 1.0, 0.0);
			float3 T = normalize(cross(n, up)), B = cross(n, T);
			float3 dT = (m * float4(T, 0.0)).xyz * ob, dB = (m * float4(B, 0.0)).xyz * ob;
			float3 q0 = c1.xyz - dT - dB, q1 = c1.xyz + dT - dB, q2 = c1.xyz - dT + dB, q3 = c1.xyz + dT + dB;
			litFar = 0.25 * (smap.sample_compare(cmp, float2(0.5 + 0.5 * q0.x, 0.5 - 0.5 * q0.y), 1, q0.z - biasFar)
				+ smap.sample_compare(cmp, float2(0.5 + 0.5 * q1.x, 0.5 - 0.5 * q1.y), 1, q1.z - biasFar)
				+ smap.sample_compare(cmp, float2(0.5 + 0.5 * q2.x, 0.5 - 0.5 * q2.y), 1, q2.z - biasFar)
				+ smap.sample_compare(cmp, float2(0.5 + 0.5 * q3.x, 0.5 - 0.5 * q3.y), 1, q3.z - biasFar));
		} else {
			litFar = 0.25 * (smap.sample_compare(cmp, uv1 + float2(-o1, -o1), 1, z1) + smap.sample_compare(cmp, uv1 + float2(o1, -o1), 1, z1)
				+ smap.sample_compare(cmp, uv1 + float2(-o1, o1), 1, z1) + smap.sample_compare(cmp, uv1 + float2(o1, o1), 1, z1));
		}
	}
	// (Build C1: with the soft far shadows the fade out at the far cascade's edge starts sooner, over the last fifth of its reach)
	float cover = 1.0 - smoothstep(soft > 0.0 ? 0.8 : 0.9, 0.98, e1);
	if (search) {
		// (Build C1) Softer the farther the caster (after percentage-closer soft shadows): five looks at the far map around the
		// point, up to two blocks away along its own face, find what stands between it and the light and how far off that is;
		// the shadow is then averaged over a disc that grows with that distance. A fence post's shadow stays as sharp as it was;
		// a sky island's, a hundred blocks up, gets an edge blocks wide instead of its blocks' outlines.
		// The looks and the taps lie in the face's own plane, so each has its own exact depth there and a face the light grazes
		// doesn't shadow itself however wide the disc; turned by a different angle per pixel and frame, which the TAA evens out.
		float4x4 m = f.shadowFar;
		float xs = length(float3(m[0][0], m[1][0], m[2][0])), zs = length(float3(m[0][2], m[1][2], m[2][2]));
		float texel = 2.0 / max(xs * res, 1e-6);  // (a far texel, in blocks)
		float3 up = abs(n.y) > 0.9 ? float3(1.0, 0.0, 0.0) : float3(0.0, 1.0, 0.0);
		float3 T = normalize(cross(n, up)), B = cross(n, T);
		float3 dT = (m * float4(T, 0.0)).xyz, dB = (m * float4(B, 0.0)).xyz;
		float dth = fract(dot(pixel, float2(0.06711056, 0.00583715)) * 52.9829189);
		if (f.taa.z > 0.5) dth = fract(dth + 0.61803399 * float(f.flags.w & 63));
		float ca = cos(dth * 6.2831853), sa = sin(dth * 6.2831853);
		const float reach = 2.0;  // (blocks: the widest a shadow's edge gets is twice this)
		const float2 k5[5] = {float2(0.3162, 0.0), float2(-0.4039, 0.3700), float2(0.0618, -0.7044), float2(0.5091, 0.6640), float2(-0.9342, -0.1652)};
		float nb = 0.0, bsum = 0.0;
		for (int i = 0; i < 5; i++) {
			float2 o = float2(k5[i].x * ca - k5[i].y * sa, k5[i].x * sa + k5[i].y * ca) * reach;
			float3 q = c1.xyz + dT * o.x + dB * o.y;
			float2 quv = clamp(float2(0.5 + 0.5 * q.x, 0.5 - 0.5 * q.y), 0.0, 0.9999);
			float d = smap.read(uint2(quv * res), 1);
			float gap = (q.z - biasFar) - d;
			if (gap > 0.0) {
				nb += 1.0;
				bsum += gap;
			}
		}
		float away = nb > 0.0 ? bsum / (nb * max(zs, 1e-9)) : 0.0;  // (blocks from here to what shadows it, along the light)
		// (how open the air toward the light is: lit, or shadowed only by something far off, a sky island and not a cave's roof)
		if (wantOpen) openAir = ((5.0 - nb) + nb * smoothstep(24.0, 64.0, away)) * 0.2 * cover;
		if (soft > 0.0 && nb > 0.0) {
			float rB = min(away * soft, reach);  // (the disc's radius, blocks)
			if (rB > texel) {
				const float2 k8[8] = {float2(0.25, 0.0), float2(-0.3193, 0.2925), float2(0.0489, -0.5569), float2(0.4024, 0.5249),
					float2(-0.7385, -0.1306), float2(0.6996, -0.4450), float2(-0.2340, 0.8705), float2(-0.4463, -0.8593)};
				float pcf = 0.0;
				for (int i = 0; i < 8; i++) {
					float2 o = float2(k8[i].x * ca - k8[i].y * sa, k8[i].x * sa + k8[i].y * ca) * rB;
					float3 q = c1.xyz + dT * o.x + dB * o.y;
					pcf += smap.sample_compare(cmp, float2(0.5 + 0.5 * q.x, 0.5 - 0.5 * q.y), 1, q.z - biasFar);
				}
				pcf *= 0.125;
				// (from one far texel's width the disc takes over from the four fixed taps; and inside the near cascade too once
				// the edge is a block wide or more (a caster 20 to 50 blocks off along the light): a sky island's shadow is as soft
				// at the player's feet as farther off, a tree's keeps the near map's leaves)
				litFar = mix(litFar, pcf, nearW < 1.0 ? smoothstep(1.0, 2.5, rB / texel) : 1.0);
				if (nearW > 0.0) litNear = mix(litNear, pcf, smoothstep(0.6, 1.5, rB));
			}
		}
	}
	return float2(mix(litFar, litNear, nearW) * plantK, max(cover, nearW));
}

static float2 imShadow(float3 p, float3 n, float2 pixel, constant ImFrame &f, depth2d_array<float> smap, sampler cmp) {
	float open;
	return imShadowX(p, n, pixel, f, smap, cmp, false, open);
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
#define IM_MAT_GLOW_GEM 15 // crying obsidian: glows like a light and reflects like a gem

// How much light a fragment of class cls gives off, as a multiple of its texture's colour (tex: the texel as sampled, before the
// vertex colour's tint and Minecraft's corner shading, so every face of a block glows alike). The result goes on top of the
// tone-mapped picture, so values past 1 are real HDR: the bloom picks them up.
static float imEmission(uint cls, float3 tex) {
	float l = imLuma(tex);
	float hi = max(max(tex.r, tex.g), tex.b), lo = min(min(tex.r, tex.g), tex.b);
	float sat = hi - lo;
	switch (cls) {
		// (hot cores: the brightest texels far past white, so the bloom haloes them, the darker ones lower so the texture reads)
		// (only the bright parts far past white: the whole block glowing turned a jack o'lantern's pumpkin skin white too)
		// (and not so far past white that its colour goes: a jack o'lantern's carved face read as a flat white glare, no detail)
		// (only the parts that are the light: warm ones from mid brightness, cool or green ones only near white (a sea lantern,
		// an end rod); a copper bulb's green frame glowed as much as its lit middle)
		case IM_MAT_EMITTER: {
			// (by the brightest channel: a jack o'lantern's orange skin (about 0.8) stays a surface, its yellow face (1.0) glows)
			float warm = smoothstep(0.08, 0.25, tex.r - tex.b) * step(tex.g, tex.r + 0.02);
			float part = max(warm * smoothstep(0.86, 0.98, hi), smoothstep(0.82, 0.95, l));
			return (1.9 * l * smoothstep(0.45, 0.98, l) + 0.25 * l) * part;
		}
		case IM_MAT_GLOW_GEM: return 0.0;  // (crying obsidian: its veins only, imShade)
		// (Reimagined's: brighter where the texture is; its hot spots orange-yellow, not white: white specks over a lava lake read
		// like a rash)
		// (lower overall so its texture shows instead of one flat glowing sheet; the hot spots still far past white)
		case IM_MAT_LAVA: return 0.55 + 2.6 * l * l * l;
		case IM_MAT_FLAME: return 4.0 * smoothstep(0.62, 0.92, hi) * smoothstep(0.15, 0.4, sat);
		// ores: every pixel with clear colour against the grey stone around it (the whole speck, its coloured edge too; iron's pale
		// specks count; a speck's near-grey highlights and coal's black specks don't), at a faint glow that keeps the texture's own
		// light and dark
		case IM_MAT_ORE: return 1.1 * smoothstep(0.08, 0.2, sat) * (0.5 + 0.5 * hi);
		// nether ores sit in red netherrack: their specks are told apart by brightness instead
		case IM_MAT_NETHER_ORE: return 1.2 * smoothstep(0.5, 0.75, l);
		// faint lights (sculk sensors and catalysts, magma, glow lichen): only their bright, coloured parts glow, as a flame's
		case IM_MAT_DIM: return 1.6 * smoothstep(0.5, 0.85, hi) * smoothstep(0.12, 0.35, sat);
		// sculk's specks: only their brightest pixels (small, calm dots), but far past white, so the bloom gives each a soft halo
		// while the screen's roll-off keeps the dot itself from getting brighter
		// (6, was 3.5: in the deep dark the specks had almost no halo)
		case IM_MAT_SCULK: return 6.0 * smoothstep(0.62, 0.9, hi) * smoothstep(0.2, 0.45, sat);
		default: return 0.0;
	}
}

// Block light at p (camera-relative, n its face normal) from the coloured light grid in front of the face: rgb its colour at the
// torch light's strength (the warm default where the grid has nothing: outside it, or no light source it knows of), a the grid's
// light level there (0..1; it spreads each source 4 levels stronger than Minecraft does, so coloured lights read clearly).
// The colour's saturation is pushed past the source's own, since lighting grey stone and the tone curve wash a light's colour
// out; on screen it then reads as the colour it is.
// (the filter's result, c the open voxels' weighted mean, as the grid's colour at the torch light's strength)
static float4 imBlockLightOf(float4 c, float inside, constant ImFrame &f) {
	float3 warm = f.blockColor.rgb;
	float m = max(max(c.r, c.g), c.b);
	if (m < 1e-4) return float4(warm, 0.0);
	float3 hue = c.rgb / m;
	float hl = imLuma(hue);
	hue = max(hl + (hue - hl) * 1.35, 0.0);
	// (as bright as the torch colour, times how bright the sources here are: 1 for most, about half near lava)
	hue *= max(max(warm.r, warm.g), warm.b) / max(max(max(hue.r, hue.g), hue.b), 1e-4) * min(m, 1.0);
	float w = inside * smoothstep(0.0, 0.04, c.a);
	return float4(mix(warm, hue, w), c.a * inside);
}

static float4 imBlockLight(float3 p, float3 n, constant ImFrame &f, texture3d<float> vol, sampler vs) {
	float3 warm = f.blockColor.rgb;
	if (f.lightVol.w < 0.5) return float4(warm, 0.0);
	float3 uvw = (p + n * 0.5 - f.lightVol.xyz) / f.lightSize.xyz;
	float3 e = min(uvw, 1.0 - uvw) * f.lightSize.xyz;  // blocks to the grid's nearest side
	float inside = smoothstep(1.0, 14.0, min(min(e.x, e.y), e.z));  // (a long fade: walking toward a light, its colour comes in gradually)
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
	return imBlockLightOf(c, inside, f);
}

// The same filter from the grid's filter-weighted copies (light.metal filt and open; the terrain binds them at 29 and 30): the
// hardware's trilinear sample of filt is the sum of w v over the open voxels, of open the sum of w, so two filtered samples give
// what the eight reads above add up by hand (the same sampler: linear, clamped to the edge, as the reads' clamp)
static float4 imBlockLightF(float3 p, float3 n, constant ImFrame &f, texture3d<float> filt, texture3d<float> open, sampler vs) {
	float3 warm = f.blockColor.rgb;
	if (f.lightVol.w < 0.5) return float4(warm, 0.0);
	float3 uvw = (p + n * 0.5 - f.lightVol.xyz) / f.lightSize.xyz;
	float3 e = min(uvw, 1.0 - uvw) * f.lightSize.xyz;
	float inside = smoothstep(1.0, 14.0, min(min(e.x, e.y), e.z));
	if (inside <= 0.0) return float4(warm, 0.0);
	float4 c = filt.sample(vs, uvw, level(0.0));
	float wsum = open.sample(vs, uvw, level(0.0)).r;
	c = wsum > 1e-4 ? c / wsum : float4(0.0);
	return imBlockLightOf(c, inside, f);
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

static float3 imGenNormal(texture2d<float> atlas, float2 uv, float4 rect, float strength, float2 texelsPerPixel, float3 col /* the texel, untinted */,
	texture2d<float> bake, bool baked) {
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
	// (the four neighbours' reads, each skipped past the sprite's edge as before; a read whose point stays in the centre texel reads
	// the centre texel, so that one is read once and shared: near, an eighth of a texel out, most points don't cross into a
	// neighbour, so mostly one read, never more than three, instead of four. The same values summed in the same order.)
	float2 tc = floor(t);
	bool upIn = t.y + off.y < hi.y, rightIn = t.x + off.x < hi.x, downIn = t.y - off.y > lo.y, leftIn = t.x - off.x > lo.x;
	bool upX = floor(t.y + off.y) != tc.y, rightX = floor(t.x + off.x) != tc.x, downX = floor(t.y - off.y) != tc.y, leftX = floor(t.x - off.x) != tc.x;
	// (Build B1: the bevels baked per texel at load, PbrAtlas.bake: the texel's difference to each neighbour, one read of the bake in
	// place of up to three of the atlas and their lengths; the neighbour is the next texel even where a far texel's step is longer.
	// A texel that isn't baked (0: an animated sprite) is worked out as before, below.)
	if (baked) {
		float4 bk = bake.read(min(uint2(tc), uint2(bake.get_width() - 1, bake.get_height() - 1)), 0);
		if (bk.r > 0.0) {
			float4 dd = ((bk * 255.0 - 1.0) / 254.0 - 0.5) * 0.4;
			if (upIn && upX) nm.y += dd.r;
			if (rightIn && rightX) nm.x += dd.g;
			if (downIn && downX) nm.y -= dd.b;
			if (leftIn && leftX) nm.x -= dd.a;
			nm = clamp(nm * (2.5 * strength) * fade, -1.0, 1.0);
			return normalize(float3(nm, 1.0));
		}
	}
	float dc = (upIn && !upX) || (rightIn && !rightX) || (downIn && !downX) || (leftIn && !leftX) ? imGnDif(atlas, t, lc) : 0.0;
	if (upIn) nm.y += upX ? imGnDif(atlas, t + float2(0, off.y), lc) : dc;
	if (rightIn) nm.x += rightX ? imGnDif(atlas, t + float2(off.x, 0), lc) : dc;
	if (downIn) nm.y -= downX ? imGnDif(atlas, t - float2(0, off.y), lc) : dc;
	if (leftIn) nm.x -= leftX ? imGnDif(atlas, t - float2(off.x, 0), lc) : dc;
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
			if (th > 0.12) {  // the ore's own pixels: gem-like (a softer highlight: on top of the glow they read as white blobs)
				s.g = 0.7;
				s.d = 0.65;
				s.f0 = 0.15;
				s.hl = 1.4;
			} else {
				s.g = min(g4 * 2.0, 1.0);
				s.d = s.g;
			}
			break;
		}
		case IM_MAT_SAND: {  // Reimagined's sand: its brightest grains glossy, they glint and catch reflections (a lantern in them)
			float g16 = col.g * col.g;
			g16 *= g16;
			g16 *= g16;
			g16 *= g16;
			s.g = min(g16 * 2.0, 1.0);
			s.d = s.g;
			s.hl = 2.0;
			break;
		}
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
		case IM_MAT_POLISHED:
			s.g = 0.2 + 0.5 * imLinStep(0.1, 0.85, L);
			s.d = s.g;
			s.hl = 1.5;
			break;
		case IM_MAT_GEM: case IM_MAT_GLOW_GEM:  // Photon's gems and obsidian: smooth, a strong reflectance
			s.g = 0.68 + 0.3 * sqrt(imLinStep(0.05, 0.85, L));
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
	sampler hs, bool thinTest) {
	float dist = length(p);
	float3 q = p + f.histShift.xyz + n * (0.03 + dist * 0.004);
	float2 size = float2(hd.get_width(), hd.get_height());
	float stepLen = 0.2 + dist * 0.03;
	// (the start jittered over half a step, not most of one: a full step's jitter put neighbouring pixels on either side of
	// an edge, hit and missed, the static on water and polished floors that TAA couldn't settle)
	float t = stepLen * (0.5 + 0.5 * dither), tPrev = 0.0;
	// (18 steps growing by 1.4: as far as the 26 by 1.3 reached, with fewer reads of the depth; water and glass, which cover
	// half the screen over a sea, 11 growing by 1.75 (as far, coarser: the refinement below finds the hit): the sea cost a third
	// of the frame)
	int steps = thinTest ? 18 : 11;
	float grow = thinTest ? 1.4 : 1.75;
	for (int i = 0; i < steps; i++) {
		float3 r = q + R * t;
		float4 c = f.histClip * float4(r, 1.0);
		if (c.w <= 0.05) break;
		float2 ndc = c.xy / c.w;
		if (any(abs(ndc) > 1.0)) break;
		float d = hd.read(uint2(clamp(ndc * 0.5 + 0.5, 0.0, 0.9999) * size));
		float4 sp = f.histClipInv * float4(ndc, d, 1.0);
		float diff = length(r) - length(sp.xyz / sp.w);
		// (a little past the surface before it counts: far away the depth's precision alone puts the ray "behind" whatever
		// surface it started from, in bands)
		if (diff > 0.02 + length(r) * 0.004) {
			// (only just behind a surface counts as reaching it: far behind is passing behind something thin in front, a torch, a
			// fence, whose back the screen doesn't show)
			// (water and glass skip this: their rays run long and flat toward hills, and passing well behind one in a single step
			// is normal there; the check after the refinement still catches a miss)
			if (thinTest && diff > 0.15 + stepLen * 0.6) return float4(0.0, 0.0, 0.0, -1.0);
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
			// (fading out over the outer part of the screen: rays from the upper part of the picture leave it soon, and a hard cut
			// there between reflection and sky flickered as the jitter moved it)
			float2 e = abs(nb);
			float edge = (1.0 - smoothstep(0.8, 1.0, e.x)) * (1.0 - smoothstep(0.6, 1.0, e.y));
			float3 col = hc.sample(hs, uv, level(lod)).rgb;
			return float4(pow(max(col, 0.0), 2.2), edge);
		}
		tPrev = t;
		t += stepLen;
		stepLen *= grow;
	}
	return float4(0.0);
}

// ---- the air (Photon's analytic air fog, include/fog/overworld/analytic.glsl) ----
// Rayleigh (blue, thin, thinning with height: distant land turns blue) and Mie (white, glowing toward the sun, lying low: mist in
// the valleys at dawn, dusk and night, in rain and humid biomes), integrated exactly along the line from the eye to the surface
// (no marching), lit by the sun or moon (with multiple scattering) and the sky. Returns the light scattered in (linear, like the
// terrain's light) and sets trans, how much of the surface still shows through.
static inline float imHg(float nu, float g) {
	float gg = g * g;
	float d = 1.0 + gg - 2.0 * g * nu;
	return (0.0795775 - 0.0795775 * gg) / (d * sqrt(d));
}

static float3 imAirFog(float3 p, float skyLight, constant ImFrame &f, thread float3 &trans) {
	trans = float3(1.0);
	if (f.sunDir.w < 0.5 || f.fogParams.w <= 0.0) return float3(0.0);
	float len = length(p);
	if (len < 6.0) return float3(0.0);  // (too thin to see this close: most of the screen skips the rest)
	float3 dir = p / len;
	// heights clamped at Photon's volume floor (sea level - 24): the air doesn't keep thickening down into caves
	float y0 = max(f.fogParams.y, 39.0), y1 = max(f.fogParams.y + p.y, 39.0);
	float dy = (y1 - y0) / len;
	const float2 mul = -1.0 / float2(30.0, 7.0);
	const float2 add = -mul * float2(93.0, 70.0);  // (falloff starts: sea level + 30 for Rayleigh, + 7 for Mie)
	float2 a = y0 * mul + add;
	float2 am;
	if (abs(dy) < 1e-4) am = exp2(a) * len;
	else am = (exp2(len * dy * mul + a) - exp2(a)) / (0.693147 * mul * dy);
	am = clamp(am, 0.0, len) * (0.5 * f.fogParams.w * f.fogParams.z);
	float3 R = f.hazeTint.rgb;
	float mie = f.hazeTint.w, rain = f.dayUp.w;
	float3 od = R * am.x + mie * am.y;
	trans = exp(-od);
	float3 si = (1.0 - trans) / max(od, 1e-6);
	float3 rs = si * am.x * R;
	float3 ms = si * am.y * (mie * mix(0.9, 0.5, rain));
	float LoV = dot(dir, f.lightDir.xyz);
	const float iso = 0.0795775;
	float3 sc = 2.0 * (rs + ms) * iso * f.ambientUp.rgb;
	// (Photon's four scattering orders: the first with its full phase, the other three, nearly even by then, folded into one
	// even term; one phase evaluation instead of four)
	float ph = 0.7 * imHg(LoV, 0.4) + 0.3 * imHg(LoV, -0.2);
	float phMulti = 0.5 * (0.7 * imHg(LoV, 0.35) + 0.3 * imHg(LoV, -0.14)) + 0.375 * iso;
	sc += (rs * (1.875 * iso) + ms * (ph + phMulti)) * f.lightColor.rgb;
	sc *= max(skyLight, f.fogParams.z);
	float sy = f.sunDir.y + 0.02;
	sc *= 1.0 + 0.35 * saturate((exp(-300.0 * sy * sy) - 0.05) / 0.95);  // (Photon: brighter in the early morning and evening)
	// (a soft ceiling: the glow toward a low sun never washes the land out to a flat sheet)
	// (0.35, was 0.5: looking into a low sun the land turned a pink-white sheet, "whiteout")
	float sm = max(max(sc.r, sc.g), sc.b), cap = 0.35 * max(max(f.lightColor.r, f.lightColor.g), f.lightColor.b) + 0.04;
	if (sm > 1e-4) sc *= cap * (1.0 - exp(-sm / cap)) / sm;
	return max(sc, 0.0);
}

// Waves on water's top faces (the "Reimagined with waves" style): a few travelling waves summed by their slopes, in world space
// so they stay put as the camera moves; flattening with distance (they'd only shimmer there).
static float3 imWaterNormal(float3 p, float3 n, constant ImFrame &f) {
	float2 q = p.xz + f.world.xz;
	float t = f.world.w;
	float2 g = float2(0.0);
	const float4 w[5] = {float4(0.8, 0.6, 0.55, 1.1), float4(-0.45, 0.89, 0.9, 1.5), float4(0.31, -0.95, 1.6, 2.1),
		float4(-0.9, -0.43, 2.7, 2.8), float4(0.6, -0.8, 4.3, 3.6)};
	for (int i = 0; i < 5; i++) {
		float2 k = w[i].xy * w[i].z;
		float a = 0.05 / w[i].z;  // (shorter waves lower)
		g += k * (a * cos(dot(k, q) + t * w[i].w));
	}
	g *= 1.0 / (1.0 + length(p) * 0.04);
	return normalize(float3(-g.x, 1.0, -g.y));
}

// Smooth value noise (one a unit), for shapes in the world.
static inline float imHash2(int2 q) {
	uint h = uint(q.x) * 1597334677u ^ uint(q.y) * 3812015801u;
	h = (h ^ (h >> 16)) * 2246822519u;
	h ^= h >> 13;
	return float(h & 0xffffffu) / 16777216.0;
}

// (an integer hash: sin-based ones lose their precision a few thousand blocks out and come out in straight stripes)
static inline float imValueNoise(float2 q) {
	float2 fi = floor(q), u = q - fi;
	int2 i = int2(fi);
	u = u * u * (3.0 - 2.0 * u);
	return mix(mix(imHash2(i), imHash2(i + int2(1, 0)), u.x), mix(imHash2(i + int2(0, 1)), imHash2(i + int2(1, 1)), u.x), u.y);
}

// Contact shadows toward the sun from the last frame's depth: what the screen showed between this point and the sun, up to 3 blocks
// away, shades it. Mobs, players and items aren't drawn into the shadow map, so this is their shadow on the ground beside them.
// Is the screen's surface at sp (camera-relative) something the sun's shadow maps already hold (terrain), or not (a mob, an item,
// the selection outline)? Terrain's own shadows come from the maps; a second, screen-space copy of them showed as ghostly grey
// "3D" doubles of plants and ledges a little off their real shadows.
static bool imInShadowMap(float3 sp, constant ImFrame &f, depth2d_array<float> smap) {
	float4 c = f.shadowNear * float4(sp, 1.0);
	uint slice = 0;
	float4x4 m = f.shadowNear;
	if (max(abs(c.x), abs(c.y)) > 0.95 || c.z <= 0.0 || c.z >= 1.0) {
		c = f.shadowFar * float4(sp, 1.0);
		slice = 1;
		m = f.shadowFar;
		if (max(abs(c.x), abs(c.y)) > 0.97 || c.z <= 0.0 || c.z >= 1.0) return true;  // (no map here: leave it to the maps' fallback)
	}
	float2 uv = float2(0.5 + 0.5 * c.x, 0.5 - 0.5 * c.y);
	float2 sz = float2(smap.get_width(), smap.get_height());
	uint2 t = uint2(clamp(uv, 0.0, 0.9999) * sz);
	// (the nearest caster around it, the smallest depth (the maps keep the nearest to the sun): a texel's neighbours too, so a
	// block's edge isn't taken for something else)
	// (and a wider ring a quarter of a block out: a plant waving in the wind stands somewhere else in the maps than in the last
	// frame's depth, and the difference cast a ghost copy of every dead bush and fern on the sand next to it)
	float xs = length(float3(m[0][0], m[1][0], m[2][0])) * 0.5 * sz.x;  // (map texels a block)
	int r4 = clamp(int(xs * 0.25 + 0.5), 2, 12);
	int2 ti = int2(t), smax = int2(sz) - 1;
	float md = smap.read(t, slice);
	md = min(md, smap.read(uint2(min(t + uint2(1, 0), uint2(sz) - 1)), slice));
	md = min(md, smap.read(uint2(min(t + uint2(0, 1), uint2(sz) - 1)), slice));
	md = min(md, smap.read(uint2(t.x > 0 ? t.x - 1 : 0, t.y), slice));
	md = min(md, smap.read(uint2(t.x, t.y > 0 ? t.y - 1 : 0), slice));
	md = min(md, smap.read(uint2(clamp(ti + int2(r4, 0), int2(0), smax)), slice));
	md = min(md, smap.read(uint2(clamp(ti - int2(r4, 0), int2(0), smax)), slice));
	md = min(md, smap.read(uint2(clamp(ti + int2(0, r4), int2(0), smax)), slice));
	md = min(md, smap.read(uint2(clamp(ti - int2(0, r4), int2(0), smax)), slice));
	float zs = length(float3(m[0][2], m[1][2], m[2][2]));  // (light-depth units a block)
	// (closer to the sun than every caster there by more than half a block: not in the maps)
	return c.z > md - zs * 0.5;
}

static float imContactShadow(float3 p, float3 n, constant ImFrame &f, depth2d<float> hd, float dither, depth2d_array<float> smap) {
	float dist = length(p);
	if (dist > 40.0) return 1.0;
	float3 q = p + f.histShift.xyz + n * (0.04 + dist * 0.003);
	float2 size = float2(hd.get_width(), hd.get_height());
	float3 L = f.lightDir.xyz;
	float occ = 0.0;
	// (8 steps over 1.6 blocks: a mob's shadow at its feet. Over 3 blocks it trailed, from the last frame's depth, as a long
	// blotchy smear behind a hopping rabbit at a low sun; and it costs less)
	for (int i = 0; i < 8; i++) {
		float t = (float(i) + dither) * 0.2;
		float3 r = q + L * t;
		float4 c = f.histClip * float4(r, 1.0);
		if (c.w <= 0.05) break;
		float2 ndc = c.xy / c.w;
		if (any(abs(ndc) > 1.0)) break;
		float2 suv = clamp(ndc * 0.5 + 0.5, 0.0, 0.9999);
		float d = hd.read(uint2(suv * size));
		float4 sp = f.histClipInv * float4(ndc, d, 1.0);
		float3 spp = sp.xyz / sp.w;
		float diff = length(r) - length(spp);
		float lim = 0.12 + length(r) * 0.005;
		// (just behind a surface the screen shows: something is between here and the sun; far behind is only passing behind it)
		if (diff > lim && diff < 0.9) {
			// (something thin, a pixel or two across, the selection outline's lines, a fishing line, isn't a shadow caster: the
			// screen two pixels to either side must hold it too)
			float2 o = 2.0 / size;
			float da = hd.read(uint2(clamp(suv + o, 0.0, 0.9999) * size)), db = hd.read(uint2(clamp(suv - o, 0.0, 0.9999) * size));
			float df = min(min(d, da), db);  // (reversed Z: the farthest of the three)
			float4 sf = f.histClipInv * float4(ndc, df, 1.0);
			float difff = length(r) - length(sf.xyz / sf.w);
			if (difff <= lim) continue;
			// (terrain is in the sun's shadow maps, which shadow it already: only what they don't hold casts here)
			if (imInShadowMap(spp - f.histShift.xyz, f, smap)) continue;
			occ = 1.0 - float(i) / 9.0;
			break;
		}
	}
	return 1.0 - 0.85 * occ * (1.0 - smoothstep(24.0, 40.0, dist));
}

// Sunlight through stained glass (Reimagined's coloured shadows): from the surface toward the sun, block by block through the
// coloured light grid's seeds (light.metal: bit 6 of a marks stained glass, rgb its colour), the light takes the colour of
// each pane it passes, as through a coloured filter. Up to 20 blocks; solid blocks end it (the shadow map has those).
static float3 imGlassTint(float3 p, float3 n, constant ImFrame &f, texture3d<float> seeds) {
	if (f.lightVol.w < 0.5 || length(p) > 72.0) return float3(1.0);
	float3 d = f.lightDir.xyz;
	float3 q = p + n * 0.02 - f.lightVol.xyz;  // (voxel space: one voxel a block)
	int3 size = int3(f.lightSize.xyz);
	int3 v = int3(floor(q));
	int3 st = int3(sign(d));
	float3 inv = 1.0 / max(abs(d), 1e-5);
	float3 tMax = (select(float3(v) + 1.0 - q, q - float3(v), d < 0.0)) * inv;
	float3 tDelta = inv;
	float3 tint = float3(1.0);
	uint lastColour = 0u;
	for (int i = 0; i < 20; i++) {
		if (tMax.x < tMax.y && tMax.x < tMax.z) { v.x += st.x; tMax.x += tDelta.x; }
		else if (tMax.y < tMax.z) { v.y += st.y; tMax.y += tDelta.y; }
		else { v.z += st.z; tMax.z += tDelta.z; }
		if (any(v < 0) || any(v >= size)) break;
		float4 s = seeds.read(uint3(v));
		uint bits = uint(s.a * 255.0 + 0.5);
		if ((bits & 128u) != 0u) break;
		if ((bits & 64u) != 0u) {
			// (a run of the same glass tints once: a thick window isn't darker than a pane)
			uint key = uint(s.r * 255.0) | uint(s.g * 255.0) << 8 | uint(s.b * 255.0) << 16;
			if (key != lastColour) tint *= mix(float3(1.0), max(s.rgb, 0.04), 0.82);
			lastColour = key;
		}
	}
	// (kept bright: the colour, not a darkening; Reimagined's beams through glass stay luminous)
	float m = max(max(tint.r, tint.g), tint.b);
	return m < 0.999 ? tint / max(m, 0.15) * mix(m, 1.0, 0.6) : tint;
}

// Reimagined's glowing ores (lib/materials/materialHandling/terrainIPBR.glsl, GLOWING_ORE_*), ore by ore: which texels are the ore's
// own (its rule on the texel's colour), how strongly they glow (e: Reimagined adds e^2 to the light, so the light given off is
// (colour x e^2)^2.2 once linear: a diamond's core far past white, iron a soft peach) and how the speck's colour is deepened
// (cm: colour x colour^k; redstone's green and blue cut to a tenth). t: the ore (Materials.oreType); c: the texel (gamma).
static float imOre(uint t, float3 c, thread float3 &cm, bool nether) {
	cm = float3(1.0);
	// (the Nether's class reuses the overworld ores' numbers for its plants: 1 crimson and warped fungus, weeping vines)
	if (nether && t == 1u) return c.r > 0.91 ? 3.0 * c.g : 0.0;
	switch (t) {
		case 1u:  // iron
			// (softer than Reimagined's: in a dark cave its pale peach specks blew out to white blobs, the texture gone)
			if (c.r != c.g && c.r - c.b > 0.15) { cm = pow(max(c, 0.0), 0.75); return pow(c.r, 1.5) * 1.2; }
			break;
		case 2u:  // copper
			if (max(c.r * 0.5, c.g) - c.b > 0.05 && (c.r + c.g > 1.9 || c.r + c.g + c.b < 1.9)) { cm = c; return c.r * 2.0 + 0.7; }
			break;
		case 3u:  // gold
			if ((c.g - c.b > 0.15 || c.r > 0.99) && (c.r + c.g > 1.9 || c.r + c.g + c.b < 1.9)) { cm = c; return c.r + 1.0; }
			break;
		case 4u:  // diamond
			if (c.b / max(c.r, 1e-3) > 1.5 || c.b > 0.8) { cm = c; return c.g + 1.5; }
			break;
		case 5u: {  // emerald
			float dif = max(max(abs(c.r - c.g), abs(c.r - c.b)), abs(c.g - c.b));
			if (dif > 0.4 || c.b > 0.85) { cm = c; return 2.0; }
			break;
		}
		case 6u:  // lapis
			if (c.r != c.g && c.b - c.r > 0.2) { cm = c; return 2.0; }
			break;
		case 7u:  // redstone
		case 8u:  // (lit)
			if (c.r - c.g > 0.2) { cm = float3(1.0, 0.1, 0.1); return c.r * pow(c.r, 1.5) * (t == 8u ? 4.5 : 4.0); }
			break;
		case 10u:  // nether quartz
			if (c.g != c.b) { float b = c.b * 1.6; return b * b; }
			break;
		case 11u:  // nether gold
			if (c.g != c.b) return c.g * 1.5;
			break;
		case 12u: {  // ancient debris (all of it, its bright lines most)
			float g6 = c.g * 6.0;
			cm = pow(max(c, 0.0), 0.75);
			return 1.5 + 0.6 * min(g6 * g6, 8.0);
		}
		case 13u:  // gilded blackstone
			if (c.r > c.b * 3.0) return c.g * 1.5;
			break;
		case 14u:  // crimson stem and hyphae: the orange veins (Reimagined: red over blue past 2.5)
			// (half as strong: the veins glowed so hard the stems looked pasted in)
			if (c.r > 2.5 * c.b && c.r > 0.45) { cm = float3(1.0, 0.5, 0.5); return c.r * c.r * 3.2; }
			break;
		case 15u:  // warped stem and hyphae: the teal veins
			// (only the bright veins, and softer: the whole dark bark glowed too, flat and pasted-looking)
			if ((c.r < 0.37 * c.b || c.r + c.g * 3.0 < 3.4 * c.b) && c.g > 0.5) { float g = c.g + 0.2 * c.b; return g * g * 2.2; }
			break;
		default:
			break;
	}
	return 0.0;
}

// The terrain's features as function constants (40-53, set by mcown.m from imaginemetal.ShadeFeatures): a feature that is off isn't
// compiled into the terrain's fragment stages at all, so it costs nothing (registers, texture reads), not just a skipped branch.
// Each still keeps its runtime switch in the frame (so on = exactly the old shader); a stage made without them has them all on.
constant bool imFcShadows_ [[function_constant(40)]];
constant bool imFcPbr_ [[function_constant(41)]];
constant bool imFcNormals_ [[function_constant(42)]];
constant bool imFcPbrPack_ [[function_constant(43)]];
constant bool imFcParallax_ [[function_constant(44)]];
constant bool imFcCoated_ [[function_constant(45)]];
constant bool imFcMobShadows_ [[function_constant(46)]];
constant bool imFcGlassSun_ [[function_constant(47)]];
constant bool imFcColorLight_ [[function_constant(48)]];
constant bool imFcHeld_ [[function_constant(49)]];
constant bool imFcSky_ [[function_constant(50)]];
constant bool imFcWet_ [[function_constant(51)]];
constant bool imFcGlassRefl_ [[function_constant(52)]];
constant bool imFcPortals_ [[function_constant(53)]];
constant bool IM_FC_SHADOWS = is_function_constant_defined(imFcShadows_) ? imFcShadows_ : true;
constant bool IM_FC_PBR = is_function_constant_defined(imFcPbr_) ? imFcPbr_ : true;
constant bool IM_FC_NORMALS = is_function_constant_defined(imFcNormals_) ? imFcNormals_ : true;
constant bool IM_FC_PBRPACK = is_function_constant_defined(imFcPbrPack_) ? imFcPbrPack_ : true;
constant bool IM_FC_PARALLAX = is_function_constant_defined(imFcParallax_) ? imFcParallax_ : true;
constant bool IM_FC_COATED = is_function_constant_defined(imFcCoated_) ? imFcCoated_ : true;
constant bool IM_FC_MOBSHADOWS = is_function_constant_defined(imFcMobShadows_) ? imFcMobShadows_ : true;
constant bool IM_FC_GLASSSUN = is_function_constant_defined(imFcGlassSun_) ? imFcGlassSun_ : true;
constant bool IM_FC_COLORLIGHT = is_function_constant_defined(imFcColorLight_) ? imFcColorLight_ : true;
constant bool IM_FC_HELD = is_function_constant_defined(imFcHeld_) ? imFcHeld_ : true;
constant bool IM_FC_SKY = is_function_constant_defined(imFcSky_) ? imFcSky_ : true;
constant bool IM_FC_WET = is_function_constant_defined(imFcWet_) ? imFcWet_ : true;
constant bool IM_FC_GLASSREFL = is_function_constant_defined(imFcGlassRefl_) ? imFcGlassRefl_ : true;
constant bool IM_FC_PORTALS = is_function_constant_defined(imFcPortals_) ? imFcPortals_ : true;

static inline float imDither(float2 pixel, constant ImFrame &f) {
	float d = fract(dot(pixel, float2(0.06711056, 0.00583715)) * 52.9829189);
	return f.taa.z > 0.5 ? fract(d + 0.61803399 * float(f.flags.w & 63)) : d;
}

// (Build B2) What a fragment can only know where its triangle is drawn, from its own screen derivatives: the face normal, the
// normal with the generated bevels, where the torch light comes from, its atlas texel. imShade works them out itself (pre.on
// false: forward shading, as always) or is handed them (deferred lighting: own_fs_g stores them, im_defer_fs lights from them).
struct ImPre {
	bool on;
	bool slanted;
	float3 n;      // the face's normal
	float3 N;      // with the generated normal
	float3 Lt;     // toward the torch light (the face's normal leaned up the block light's slope)
	float2 texel;  // the atlas texel (the coated textures' and the sculk's per-texel numbers)
};

static inline ImPre imNoPre() {
	ImPre m;
	m.on = false;
	m.slanted = false;
	m.n = float3(0.0, 1.0, 0.0);
	m.N = float3(0.0, 1.0, 0.0);
	m.Lt = float3(0.0, 1.0, 0.0);
	m.texel = float2(0.0);
	return m;
}

static ImPre imMaterial(float3 p, float2 light, uint mat, float3 tex, float2 uv, float4 rect, texture2d<float> atlas, texture2d<float> pbrN,
	constant ImFrame &f) {
	ImPre m;
	m.on = true;
	bool slanted;
	float3 n = imFaceNormal(p, slanted);
	// the texture's tangent frame from the screen derivatives (Schüler 2013), taken before any branch
	float3 dp1 = dfdx(p), dp2 = dfdy(p);
	float2 duv1 = dfdx(uv), duv2 = dfdy(uv);
	// (and how the block light level changes across the face: where the torch light comes from, below)
	float dl1 = dfdx(light.x), dl2 = dfdy(light.x);
	float2 asz = float2(atlas.get_width(), atlas.get_height());
	float2 tpp = (abs(duv1) + abs(duv2)) * asz;  // texels a pixel covers
	uint cls = mat & 15u;
	uint kind = (mat >> 8) & 255u;
	float3 N = n;
	if (IM_FC_NORMALS && f.lightSize.w > 0.0 && f.flags.z != 0 && cls != IM_MAT_LAVA && !slanted && kind == 0u) {
		float3 tn = imGenNormal(atlas, uv, rect, f.lightSize.w, tpp, tex, pbrN, f.grade.z > 0.5);
		if (tn.z < 0.9999) {
			float3 dp2perp = cross(dp2, n), dp1perp = cross(n, dp1);
			float3 T = dp2perp * duv1.x + dp1perp * duv2.x;
			float3 B = dp2perp * duv1.y + dp1perp * duv2.y;
			float im = rsqrt(max(max(dot(T, T), dot(B, B)), 1e-30));
			N = normalize(T * (tn.x * im) + B * (tn.y * im) + n * tn.z);
		}
	}
	// torch light with a direction: the block light level's slope across the face points toward the light (it rises toward it),
	// so the light comes from the face's front, leaning that way
	float3 Lt = n;
	{
		float3 dq2 = cross(dp2, n), dq1 = cross(n, dp1);
		float det = dot(dp1, dq2);
		float3 G = (dq2 * dl1 + dq1 * dl2) / (abs(det) > 1e-10 ? det : 1e-10);
		G -= n * dot(G, n);
		float gl = length(G);
		float tconf = smoothstep(0.012, 0.05, gl);
		if (gl > 1e-5) Lt = normalize(n * 0.75 + (G / gl) * (0.7 * tconf));
	}
	m.slanted = slanted;
	m.n = n;
	m.N = N;
	m.Lt = Lt;
	m.texel = floor(uv * asz);
	return m;
}

static float3 imShade(float3 albedo, float3 tex, float3 p, float2 light, uint mat, float2 pixel, constant ImFrame &f, depth2d_array<float> smap, sampler cmp,
	texture3d<float> lvol, sampler lsamp, texture3d<float> lfilt, texture3d<float> lopen, texture3d<float> seeds, texture2d<float> pbrN, texture2d<float> pbrS, float2 uv, float4 rect, texture2d<float> atlas, texture2d<float> hc, depth2d<float> hd, sampler hs,
	depth2d<float> ud, ImPre pre) {
	// (the engine hasn't filled a frame yet, or the shaders are switched off live: lit plainly, close to vanilla's lightmap)
	if (f.flags.z == 0) {
		float lb = light.x, ls = light.y * mix(0.2, 1.0, f.blockColor.w);
		float3 lm = max(float3(lb) * float3(1.0, 0.9, 0.75), float3(ls));
		if (f.flags.x != 0) lm = max(lm, float3(0.3));  // (the Nether and the End: no sky light, vanilla's ambient)
		return albedo * max(lm * lm * (3.0 - 2.0 * lm), float3(0.04));
	}
	// (the End has no sky light in Minecraft: everything there counts as open to its sky, so its light (Reimagined's lavender,
	// or the black hole's) reaches the islands, the shadow maps deciding where)
	if (f.flags.x == 2) light.y = 1.0;
	// (Build B2: what needs the fragment's own derivatives comes from imMaterial, here or, with deferred lighting, where the
	// triangle was drawn: pre)
	ImPre m = pre;
	if (!pre.on) m = imMaterial(p, light, mat, tex, uv, rect, atlas, pbrN, f);
	bool slanted = m.slanted;
	float3 n = m.n;
	uint cls = mat & 15u;
	uint kind = (mat >> 8) & 255u;  // (the translucent pass: 1 water, 2 glass and other see-through blocks)
	// (mat bit 16: an opaque fragment. With the air pass on (f.pbrPack.w) its haze is laid on later, once per pixel: air.metal)
	bool airLater = (mat & 0x10000u) != 0u && f.pbrPack.w > 0.5;
	float3 N = m.N;
	// a PBR resource pack (LabPBR): its normal map, ambient occlusion and height (parallax), and below its smoothness, metalness and
	// glow; in place of ours where the pack has a map
	float4 pS = float4(0.0);
	bool packed = false;
	if (IM_FC_PBRPACK && f.pbrPack.x > 0.5 && kind == 0u && !slanted && !pre.on) {
		constexpr sampler ps(filter::nearest, address::clamp_to_edge);
		float3 dp1 = dfdx(p), dp2 = dfdy(p);
		float2 duv1 = dfdx(uv), duv2 = dfdy(uv);
		float3 dp2perp = cross(dp2, n), dp1perp = cross(n, dp1);
		float3 T = dp2perp * duv1.x + dp1perp * duv2.x;
		float3 B = dp2perp * duv1.y + dp1perp * duv2.y;
		float im = rsqrt(max(max(dot(T, T), dot(B, B)), 1e-30));
		float3 Tn = T * im, Bn = B * im;
		float2 puv = uv;
		float4 pn = pbrN.sample(ps, uv, level(0.0));
		if (IM_FC_PARALLAX && f.pbrPack.y > 0.5 && pn.a < 0.999 && length(p) < 24.0) {
			// (parallax: the surface's height pushes the texture along the view, four steps into it)
			float3 Vv = -normalize(p);
			float3 vt = float3(dot(Vv, normalize(Tn)), dot(Vv, normalize(Bn)), max(dot(Vv, n), 0.25));
			float2 rs = rect.zw - rect.xy;
			float2 stepUv = -vt.xy / vt.z * 0.25 * rs * 0.25;
			float hgt = 1.0;
			for (int k = 0; k < 4; k++) {
				float hh = pbrN.sample(ps, puv, level(0.0)).a;
				if (hh >= hgt) break;
				puv = clamp(puv + stepUv, rect.xy, rect.zw);
				hgt -= 0.25;
			}
			if (any(puv != uv)) {
				float3 t2 = atlas.sample(ps, puv, level(0.0)).rgb;
				albedo *= t2 / max(tex, float3(0.004));
				tex = t2;
				pn = pbrN.sample(ps, puv, level(0.0));
			}
		}
		pS = pbrS.sample(ps, puv, level(0.0));
		packed = any(pS != 0.0);
		float2 nxy = pn.rg * 2.0 - 1.0;
		if (any(abs(nxy) > 0.004)) {
			float nz = sqrt(saturate(1.0 - dot(nxy, nxy)));
			N = normalize(Tn * nxy.x - Bn * nxy.y + n * nz);
		}
		albedo *= mix(1.0, pn.b, 0.8);
	}
	// (Reimagined lights the texture's colours as they are and encodes only at the end; that lifts the mid tones and keeps the
	// shadows open: its bright, lively look. Linearizing them first, as physically based packs do, gives a darker, moodier picture.)
	// ores we know glow as Reimagined's (imOre); one we don't (modded) by its own colours (imEmission)
	uint oreT = (cls == IM_MAT_ORE || cls == IM_MAT_NETHER_ORE) ? (mat >> 4) & 15u : 0u;
	float3 oreCm = float3(1.0);
	float oreE = oreT != 0u ? imOre(oreT, tex, oreCm, cls == IM_MAT_NETHER_ORE) : 0.0;
	if (cls == IM_MAT_GLOW_GEM) {
		// Reimagined's crying obsidian: only its purple veins (blue far over green) glow, the brightest far past white; its red
		// pushed up a little so they read violet
		float f0 = max(tex.b - tex.g * 6.0, 0.0);
		f0 = imSqrt2(f0);
		float f1 = tex.b * tex.b;
		float f14 = f1 * f1;
		oreE = (1.35 + f14 * f14 * 7.5) * f0;
		oreCm = float3(1.15, 1.0, 1.0);
	}
	if (cls == IM_MAT_NETHER_ORE && oreT == 2u) {
		// Reimagined's End portal frame: the eye of ender glowing from its middle out, the frame's pale corner bits a soft green
		oreE = 0.0;
		oreCm = float3(1.0);
		if (abs(tex.r - tex.g - 0.05) >= 0.10) {
			float2 ac = abs(fract(p.xz + f.world.xz) - 0.5);
			float mx = max(ac.x, ac.y);
			if (mx < 0.2505) {
				float g = min(tex.g, 0.25);
				oreE = sqrt(g * g * 170.0 * max(0.28 - mx, 0.0));
				oreCm = float3(0.8, 1.15, 1.0);
			} else if (min(ac.x, ac.y) > 0.25 && all(abs(tex - float3(0.6, 0.776, 0.576)) < 0.03)) {
				oreE = 1.2;
				oreCm = float3(0.75, 1.3, 1.0);
			}
		}
	}
	if (oreE > 0.0) albedo *= oreCm;
	// (sand and sandstone a little richer and less bright: in full sun they washed out to a flat white)
	if (cls == IM_MAT_SAND && kind == 0u) {
		float al = imLuma(albedo);
		// (Build C1, the desert look: less of the extra colour and a touch toward peach: in the sun it came out a pale lemon
		// where Reimagined's sand is a warm beige)
		float3 was = max(al + (albedo - al) * 1.3, 0.0) * 0.93;
		albedo = f.grade.w > 0.0 ? mix(was, max(al + (albedo - al) * 1.18, 0.0) * float3(0.955, 0.905, 0.925), f.grade.w) : was;
	}
	float3 base = pow(max(albedo, 0.0), 2.2);
	bool pbr = IM_FC_PBR && f.shadow.w > 0.5 && f.flags.y == 0;
	// Reimagined's coated textures: each texel a touch lighter or darker than its neighbours, so flat colours read as a material
	if (IM_FC_COATED && f.toggles.x > 0.5 && kind == 0u && cls != IM_MAT_EMITTER && cls != IM_MAT_LAVA && cls != IM_MAT_FLAME) {
		float2 tx = m.texel;
		float hn = fract(sin(dot(tx, float2(12.9898, 78.233))) * 43758.5453);
		albedo *= 1.0 + (hn - 0.5) * 0.32;
	}
	ImSurf surf = pbr ? imSurface(cls, albedo, tex) : ImSurf{0.0, 0.0, 0.0, 0.04, 0.0};
	if (pbr && packed) {
		// (LabPBR: r perceptual smoothness, g f0 (230 and up: a metal), a emission (255: none))
		float metalP = pS.g * 255.0 >= 229.5 ? 1.0 : 0.0;
		surf.d = pS.r;
		surf.g = pS.r;
		surf.metal = metalP;
		surf.f0 = metalP > 0.5 ? 0.9 : max(pS.g, 0.02);
	}
	// (obsidian and crying obsidian: dark gems, kept to a sheen; diamond, emerald, amethyst: bright ones, kept shiny)
	float gemDark = 1.0 - smoothstep(0.15, 0.4, imLuma(tex));
	float3 V = -normalize(p);
	if (kind == 1u && f.water.x > 1.5 && n.y > 0.5) N = imWaterNormal(p, n, f);
	// rain puddles (Reimagined's, RAIN_PUDDLES 1): on tops of blocks under the open sky, away from torches; their shape from three
	// sizes of noise over the world (in the texture's pixels), so they lie in wide organic pools that grow as the rain goes on;
	// a puddle is a flat mirror. The rest of the world only gets a little glossier.
	if (pbr && IM_FC_WET && f.water.w > 0.0 && kind == 0u && cls != IM_MAT_LAVA && cls != IM_MAT_EMITTER && cls != IM_MAT_FOLIAGE && cls != IM_MAT_SAND) {
		float pmix = saturate(light.y * 32.0 - 31.0) * saturate((1.0 - 1.15 * light.x) * 10.0);
		float up = max(n.y - 0.5, 0.0) * 2.0;
		pmix *= up * up;
		float wetF = f.water.w;
		float w4 = wetF * wetF;
		if (w4 * w4 * pmix > 1e-5) {
			float2 wq = floor((p.xz + f.world.xz) * 16.0 + 0.001) * (0.1 / 16.0) * 0.05 * 128.0;  // (Reimagined's scale on a 128-texel noise)
			float form = imValueNoise(wq) * 3.0 + imValueNoise(wq * 0.5 + 17.3) * 5.0 + imValueNoise(wq * 0.25 + 41.7) * 8.0;
			form *= sqrt(1.0 - (1.0 - wetF) * (1.0 - wetF)) * 0.5625 + 0.4375;
			pmix *= saturate(form - 8.5);  // (Reimagined: - 7 on its noise texture; this smoother noise needs a higher cut for the same share)
			surf.g = mix(surf.g, 0.7 - wetF * 0.3, pmix);
			surf.hl = mix(surf.hl, 1.5, pmix);
			surf.d = mix(surf.d, 1.0, 1.0 - (1.0 - pmix) * (1.0 - pmix));
			N = normalize(mix(N, n, 1.0 - (1.0 - pmix) * (1.0 - pmix)));
		}
		surf.g = max(surf.g, 0.4 * wetF * saturate(n.y));  // (the rest: a wet sheen in the sun, no reflections traced)
	}
	float block = light.x, sky = light.y;
	float skyF = imSkyCurve(sky);
	// (the Nether has no sky light, so its ambient (Reimagined's netherColor, ambientUp) reached nothing: it lights everything)
	if (f.flags.x == 1) skyF = 1.0;
	// under water (the eyes in it): the sky light drops a level a block down, which left the sea floor nearly black; Reimagined's
	// stays a lit blue-green, so the sky's light keeps at least about half
	if (f.water.z > 0.5 && f.flags.x == 0) skyF = max(skyF, 0.22);  // (no sudden change where the sky light runs out deeper down)

	// direct light: wrapped N.L; until there are shadow maps, the sky light stands in for them (no sun under a roof)
	float nl = dot(N, f.lightDir.xyz);
	float wrap = slanted ? 0.35 + 0.5 * abs(nl) : max(nl + 0.4, 0.0) * (1.0 / 1.4);
	// leaves and plants let light through (Reimagined's subsurface modes): plants lit as if facing up, leaves a third of the way
	// there whichever way they face (green parts only: dirt and the grass block's sides share the class)
	bool leafy = cls == IM_MAT_FOLIAGE && tex.g > tex.r * 1.05 && tex.g > tex.b;
	if (leafy) wrap = slanted ? max(wrap, saturate(f.lightDir.y) * 0.9 + 0.1) : mix(wrap, 1.0, 0.35);
	// sunlight: the shadow map where it reaches; the sky light stands in beyond it. Underground (no sky light at all) the sun is
	// off regardless, so a cave never catches sunlight through an imprecision of the map.
	// (Build C1: under something high overhead, a sky island, a tall overhang, Minecraft's sky light is 0 as in a cave, which cut
	// the sun and the sky's light there to a black patch with block-stepped edges on open ground. Where the sky light is low
	// the far shadow map is asked how open the air toward the sun is (imShadowX: lit, or shadowed only by something 24 to 64
	// blocks off or more), and by that much the sky's light and the sun are let back in. Not while the player is underground:
	// caves stay as dark as they were)
	float oa = 0.0;
	bool wantOpen = IM_FC_SHADOWS && f.ambientUp.w > 0.5 && f.flags.x == 0 && sky < 0.45 && f.env.x < 0.99 && f.water.z < 0.5;
	float2 sm = IM_FC_SHADOWS ? imShadowX(p, n, pixel, f, smap, cmp, wantOpen, oa) : float2(1.0, 0.0);
	oa *= 1.0 - f.env.x;
	if (oa > 0.0) skyF = max(skyF, 0.8 * oa);
	float open = max(smoothstep(0.45, 0.9, sky), oa);
	float sun = mix(open, sm.x, sm.y);
	// (under water the light is scattered: shadows there are soft and shallow, not the hard dark shapes cast on dry land)
	if (f.water.z > 0.5 && f.flags.x == 0) sun = (0.45 + 0.55 * sun) * (0.3 + 0.7 * smoothstep(0.0, 0.6, sky));
	else sun *= max(smoothstep(0.0, 0.07, sky), oa);
	// mobs' and items' shadows (they aren't in the shadow map): a short trace toward the sun through the last frame's depth
	// (a fixed step pattern, no per-pixel jitter: the jitter, against the last frame's depth of grass and petals, was a crawling
	// grain over the ground)
	// (the trace reads the depth from before the water was drawn when there is one: the water's surface over shallows isn't
	// something between the seabed and the sun)
	if (IM_FC_MOBSHADOWS && IM_FC_SHADOWS && f.toggles.z > 0.5 && sun > 0.02 && f.histShift.w > 0.5 && kind == 0u) sun *= f.pbrPack.z > 0.5 ? imContactShadow(p, n, f, ud, 0.5, smap) : imContactShadow(p, n, f, hd, 0.5, smap);
	// caustics (eyes under water: what you see is under it too): the waves above focus the sunlight into a moving net of bright
	// lines on everything it reaches, from two drifting noises' crossings, along the light's direction
	if (f.water.z > 0.5 && f.flags.x == 0 && sun > 0.0) {
		float3 wpc = p + f.world.xyz;
		float2 cq = (wpc.xz - f.lightDir.xz * (wpc.y / max(f.lightDir.y, 0.2))) * 0.6;
		float tc = f.world.w * 0.5;
		float n1 = imValueNoise(cq + float2(tc, tc * 0.6)), n2 = imValueNoise(cq * 1.3 - float2(tc * 0.7, tc));
		float cl = 1.0 - abs(n1 - n2) * 3.0;
		cl = saturate(cl);
		cl *= cl;
		cl *= cl;
		sun *= 0.45 + 1.8 * cl;
	}
	// (a low sun or moon (Reimagined's shadowTime): its light weaker and its shadows paler, so at sunrise and sunset the land
	// isn't cut by long, hard, near-black shadows across whole valleys; the sky's light carries more of the picture)
	float lowSun = f.flags.x == 0 ? 1.0 - smoothstep(0.03, 0.32, f.lightDir.y) : 0.0;
	sun = mix(sun, max(sun, open * 0.55), lowSun);
	// (leaves past the near shadow map: the far map's coarse texels speckled the canopy with blotchy dark patches, "shadows far
	// look off"; there their shadows are softened toward the open sky)
	// (eased in over 30 to 96 blocks: over 26 to 40 the shadows on grass and leaves lost half their strength within 14 blocks,
	// and walking or flying toward them read as shadows popping in)
	if (cls == IM_MAT_FOLIAGE) sun = mix(sun, max(sun, open), 0.45 * smoothstep(30.0, 96.0, length(p)));
	float3 direct = f.lightColor.rgb * (wrap * sun * (1.0 - 0.35 * lowSun));
	if (IM_FC_GLASSSUN && IM_FC_COLORLIGHT && sun > 0.002 && f.extra.y > 0.5) direct *= imGlassTint(p, n, f, seeds);
	// (and glow when the light is behind them: Reimagined's subsurface highlight, looking toward the sun through grass and leaves,
	// their backlit edges lit up gold; added on top of the surface, as its highlight is)
	float3 sssGlow = float3(0.0);
	if (leafy && f.flags.x == 0) {
		float vl = saturate(dot(-V, f.lightDir.xyz));
		float lf = pow(vl, 10.0) * (slanted ? 0.8 : 0.6) * sun;
		float3 hc = normalize(pow(max(f.lightColor.rgb, 1e-4), 0.37)) * (0.3 + 1.5 * f.nightMiddle.w * f.nightMiddle.w) * (1.0 - 0.85 * f.dayUp.w);
		sssGlow = hc * lf * (0.6 + 0.4 * base / max(max(max(base.r, base.g), base.b), 0.02));
	}

	// sky light: blue from above, warmer and dimmer from below
	float3 ambient = mix(f.ambientDown.rgb, f.ambientUp.rgb, N.y * 0.5 + 0.5) * skyF;
	// the aurora's light: from the sky above, so tops take it fully, sides a little, undersides none
	ambient += f.auroraLight.rgb * (skyF * (0.12 + 0.88 * saturate(N.y)));
	// under water: light scattered through the water reaches everything in a blue-green glow (Reimagined's sea floor is never
	// black, even in the shade of kelp); dim at night
	if (f.water.z > 0.5 && f.flags.x == 0) ambient += float3(0.035, 0.12, 0.11) * mix(0.12, 1.0, f.nightMiddle.w);  // (turquoise, not cold blue)

	// torches: warm, weaker in full daylight
	// (no block light and full daylight: the grid's eight reads skipped, most of an open landscape by day)
	float4 bl = !IM_FC_COLORLIGHT || (block < 0.01 && sky > 0.97 && f.blockColor.w > 0.5) ? float4(f.blockColor.rgb, 0.0) : imBlockLightF(p, n, f, lfilt, lopen, lsamp);
	float3 torch = bl.rgb * imBlockCurve(max(block, bl.a)) * (1.0 - 0.25 * f.blockColor.w * smoothstep(0.5, 1.0, sky));
	// (a light's own faces sit in its brightest light: the whole block lit up, a copper bulb's frame and a jack o'lantern's skin
	// as bright as their glowing parts. Only the parts that glow should: its own light on it much less)
	if (cls == IM_MAT_EMITTER || cls == IM_MAT_FLAME || cls == IM_MAT_DIM) torch *= 0.3;
	// the light in the player's hands: a level falling one a block from the eye, as a placed light would, softer at the edge
	if (IM_FC_HELD && f.held.w > 0.0) {
		// (as placed coloured lights are: 4 levels stronger, the colour's saturation pushed 1.6x, as bright as the torch colour)
		float hl = saturate(min(f.held.w + 4.0 / 15.0, 1.0) - length(p) / 15.0);
		if (hl > 0.0) {
			float3 hh = f.held.rgb;
			float hlum = imLuma(hh);
			hh = max(hlum + (hh - hlum) * 1.35, 0.0);
			float3 hc = hh * (max(max(f.blockColor.r, f.blockColor.g), f.blockColor.b) / max(max(max(hh.r, hh.g), hh.b), 1e-3));
			// (as bright as a placed torch at the same distance: a held one lit a cave about half as much as the same torch dropped)
			float facing = 0.8 + 0.2 * saturate(dot(N, -normalize(p)));
			torch = max(torch, hc * imBlockCurve(hl) * (facing * 1.5) * (1.0 - 0.25 * f.blockColor.w * smoothstep(0.5, 1.0, sky)));
		}
	}

	// directional face shading (vanilla's is taken out of the vertex colours, FlatFaceShadeMixin): tops full, bottoms 60 %,
	// north/south a little brighter than east/west, so every block reads as a solid shape even in flat light
	float face = slanted ? 0.9 : (n.y >= 0.0 ? 1.0 : 1.0 + 0.4 * n.y) * (1.0 + 0.05 * abs(n.z) - 0.1 * abs(n.x));

	// torch light with a direction: the block light level's slope across the face points toward the light (it rises toward it),
	// so the light comes from the face's front, leaning that way. Bumps (the generated normals) take it by how they face it, and
	// glossy surfaces catch its highlight (below): stone's specks, sand's grains, a polished floor glint near a lantern. Flat faces
	// keep exactly their light.
	float3 Lt = m.Lt;
	torch *= clamp(saturate(dot(N, Lt)) / max(dot(n, Lt), 0.3), 0.55, 1.35);
	float3 day = direct + ambient;
	float3 lit = sqrt(day * day + torch * torch + f.minLight.rgb * f.minLight.rgb);
	// Complementary Reimagined adds its lights up in gamma space (on the texture as it is, linearized only afterwards,
	// composite1 "pow(color, 2.2)"): where two lights meet, sun and sky, the sum comes out far brighter than added in linear light,
	// while each alone stays as it is. That is its bright, contrasty sunlight over soft blue shade (measured on matching
	// screenshots: our sunlit ground at half its brightness, the shade about equal). Done here in the same way, by day; nights
	// and caves keep the plain sum.
	if (f.nightMiddle.w > 0.0 && f.flags.x == 0) {
		const float g = 1.0 / 2.2;
		float3 dayG = pow(max(direct, 0.0), g) + pow(max(ambient, 0.0), g);
		float3 tG = pow(max(torch, 0.0), g), mG = pow(max(f.minLight.rgb, 0.0), g);
		float3 litG = pow(sqrt(dayG * dayG + tG * tG + mG * mG), 2.2);
		lit = mix(lit, litG, f.nightMiddle.w);
	}
	float3 radiance = base * lit * face * f.minLight.w + sssGlow * f.minLight.w;

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
		// (the torch's: weaker than the sun's, by how bright the torch light is here)
		float tl = imLuma(torch);
		if (tl > 0.002) {
			float nlt = saturate(dot(N, Lt));
			float hl2 = imHighlight(N, V, Lt, nlt, surf.g) * surf.hl * mix(0.3, 1.0, max(surf.metal, smoothstep(0.04, 0.2, surf.f0)));
			if (cls == IM_MAT_FOLIAGE) hl2 *= 0.3;
			if (cls == IM_MAT_GEM) hl2 *= mix(1.0, 0.7, gemDark);  // (obsidian beside a torch: a glint, not a glaze)
			radiance += torch * (hl2 * 0.35) * tint * f.minLight.w;
		}
		// (the light in the hand: its highlight on whatever glossy surface faces the eye, as a torch held up to obsidian or
		// polished stone glints; it lit them before but never shone on them)
		if (IM_FC_HELD && f.held.w > 0.0) {
			float hlv = saturate(min(f.held.w + 4.0 / 15.0, 1.0) - length(p) / 15.0);
			if (hlv > 0.0) {
				float3 hcol = f.held.rgb / max(max(max(f.held.r, f.held.g), f.held.b), 1e-3) * max(max(f.blockColor.r, f.blockColor.g), f.blockColor.b);
				float nv = saturate(dot(N, V));
				float hs = imHighlight(N, V, V, nv, surf.g) * surf.hl * mix(0.4, 1.0, max(surf.metal, smoothstep(0.04, 0.2, surf.f0)));
				if (cls == IM_MAT_FOLIAGE) hs *= 0.3;
				radiance += hcol * imBlockCurve(hlv) * (hs * 0.45) * tint * f.minLight.w;
			}
		}
	}

	// water's and glass's sun glint (Reimagined's: water a soft one by its texture's brightness, stronger at an angle; glass sharper)
	if (kind != 0u && f.flags.y == 0) {
		float nlh = saturate(dot(N, f.lightDir.xyz)) * step(0.0, dot(n, f.lightDir.xyz));
		float fr = 1.0 - saturate(dot(N, V));
		float hm;
		if (kind == 1u) {
			float tt = dot(tex, tex) * 0.4;
			tt *= tt;
			hm = min(tt * tt, 0.5) * (16.0 - 15.0 * fr * fr) * (f.nightMiddle.w > 0.5 ? 0.85 : 0.425);
			if (f.water.x > 1.5) hm = max(hm, 0.6);
		} else {
			hm = 1.5;
		}
		radiance += f.lightColor.rgb * (imHighlight(N, V, f.lightDir.xyz, nlh, kind == 1u && f.water.x > 1.5 ? 0.9 : 0.5) * hm * sun) * f.minLight.w;
	}

	// the air between the eye and the surface (Photon's air fog)
	float3 airT = float3(1.0);
	float3 airS = float3(0.0);
	if (IM_FC_SKY && f.flags.y == 0 && !airLater) airS = imAirFog(p, sky, f, airT);
	if (f.flags.y == 0) radiance = radiance * airT + airS * f.minLight.w;

	if (f.flags.y == 1) return float3(light, 0.0);          // debug: light levels
	if (f.flags.y == 2) return N * 0.5 + 0.5;               // debug: normals (with the generated ones)
	if (f.flags.y == 5) {                                   // debug: the shadow maps (which cascade serves the pixel, and what it says)
		// red: the near cascade, blue: the far one, purple: the blend band between them, yellow: no map covers it (the sky light
		// stands in); bright: the map says lit, dark: shadowed. Without the softening by a low sun or distance.
		float2 sd = IM_FC_SHADOWS ? imShadow(p, n, pixel, f, smap, cmp) : float2(1.0, 0.0);
		float4 d0 = f.shadowNear * float4(p, 1.0);
		float nw = (d0.z > 0.0 && d0.z < 1.0) ? 1.0 - smoothstep(0.8, 0.92, max(abs(d0.x), abs(d0.y))) : 0.0;
		float3 tint = sd.y <= 0.0 ? float3(1.0, 1.0, 0.1) : mix(float3(0.25, 0.4, 1.0), float3(1.0, 0.3, 0.25), nw);
		return tint * (0.12 + 0.88 * sd.x) * (0.6 + 0.4 * saturate(imLuma(base) * 3.0));
	}
	if (f.flags.y == 4) {                                   // debug: the coloured light grid alone (its colour where light reaches)
		if (f.lightVol.w < 0.5) return float3(0.25, 0.0, 0.25);
		float3 uvw = (p + n * 0.5 - f.lightVol.xyz) / f.lightSize.xyz;
		if (any(uvw < 0.0) || any(uvw > 1.0)) return float3(0.25, 0.0, 0.25);
		float4 gl = imBlockLightF(p, n, f, lfilt, lopen, lsamp);
		float gm = max(max(gl.r, gl.g), gl.b);
		return pow(gm > 1e-4 ? gl.rgb / gm * saturate(gl.a * 1.5) : float3(0.0), 1.0 / 2.2) * 0.9 + base * 0.1;
	}
	float emit = oreT != 0u ? 0.0 : imEmission(cls, tex);
	if (packed && pS.a * 255.0 < 254.5) emit = max(emit, pS.a * 3.0);
	// (Reimagined's distant light bokeh: far torches, lanterns and glowstone glow brighter, so they read as soft points of light)
	if (cls == IM_MAT_FLAME || cls == IM_MAT_EMITTER) emit *= 1.0 + 1.8 * smoothstep(20.0, 140.0, length(p));
	if (cls == IM_MAT_LAVA) {
		// lava's slow currents (after Reimagined's lava noise): two layers of noise drifting against each other across the surface,
		// hotter where they meet, so a lava lake churns instead of only playing its texture's loop
		float3 lw = p + f.world.xyz;
		float2 lq = (abs(n.y) > 0.5 ? lw.xz : abs(n.x) > 0.5 ? lw.zy : lw.xy) * 0.35;
		float lt = f.world.w * 0.06;
		float l1 = imValueNoise(lq + float2(lt, lt * 0.7)), l2 = imValueNoise(lq * 1.7 - float2(lt * 0.8, lt * 1.3));
		float churn = 1.0 - abs(l1 - l2) * 2.0;
		emit *= 0.72 + 0.55 * churn * churn;
	}
	if (cls == IM_MAT_SCULK && emit > 0.0) {
		// sculk's specks pulse (after Euphoria Patches): each speck its own slow breathing, from its block and its place in the
		// texture, so a field of sculk twinkles instead of glowing flat
		// alien: slow rings of light roll outward through the sculk from hidden hearts, a few blocks apart, like a heartbeat
		// spreading through it; on top each speck flickers on its own
		float3 wp3 = p + f.world.xyz - n * 0.01;
		float3 bp = floor(wp3);
		float2 tx = m.texel;
		float h = imHash2(int2(bp.xz) * 31 + int2(bp.y) * 17 + int2(tx) * int2(7, 13));
		float h2 = fract(h * 7.31 + 0.17);
		float2 cell = floor(wp3.xz / 11.0);
		float ring = 0.0;
		for (int cy = -1; cy <= 1; cy++)
			for (int cx = -1; cx <= 1; cx++) {
				float2 cc = cell + float2(cx, cy);
				float2 heart = (cc + float2(imHash2(int2(cc) + int2(5, 9)), imHash2(int2(cc) + int2(13, 2)))) * 11.0;
				float dd = length(wp3.xz - heart);
				float period = 3.5 + 2.5 * imHash2(int2(cc) * 3 + int2(1, 7));
				float ph = fract((f.world.w - dd * 0.35) / period + imHash2(int2(cc) + int2(21, 4)));
				float r = exp(-ph * 9.0) * (1.0 - smoothstep(6.0, 11.0, dd));
				ring = max(ring, r);
			}
		float flick = 0.5 + 0.5 * sin(f.world.w * (1.5 + 3.0 * h2) + h * 6.2832);
		emit *= 0.08 + 0.35 * smoothstep(0.3, 1.0, flick) + 1.6 * ring;
	}

	float3 c = imGrade(imTonemap(radiance, f.tonemap), f);

	// reflections (Reimagined's blend): how much by the angle, with a steep falloff for rough surfaces (they only reflect at
	// grazing angles), metals and gems at every angle; traced through the last frame's picture where it shows what's reflected,
	// the sky where nothing is in the way (as much as the sky can be seen from here: in a cave only what the screen shows)
	if (pbr && surf.d >= 0.3) {
		float fresnel = 1.0 - saturate(dot(N, V));
		float ff = (1.0 - surf.d) * 0.7;
		float fm = max(fresnel - ff, 0.0) / (1.0 - ff);
		float intense = surf.metal;  // (Reimagined: only metals reflect strongly head-on; gems and obsidian mostly at an angle)
		fm = mix(fm * fm, fm * 0.75 + 0.25, intense);
		fm *= sqrt(surf.d);
		if (fm > 0.01) {
			float3 R = reflect(-V, N);
			if (dot(R, n) < 0.02) R = normalize(R - n * (dot(R, n) - 0.02));  // (a bumped normal can't reflect into the face)
			float lod = (1.0 - surf.d) * 5.0;
			// (a fixed dither without TAA (a per-frame one would only shimmer); with it a new one each frame, which TAA averages away
			// instead of leaving a fixed grain on every glossy surface)
			float dither = imDither(pixel, f);
			// (traced within 96 blocks, fading out from 64: farther, a reflection is a few pixels and the sky stands in)
			float rd = length(p);
			// (only smooth surfaces trace: on rough ones the blurred, jittered trace never settled and laid a fine diagonal static over
			// every polished wall and floor; they take the sky's light and their own colour instead, as Reimagined's rough ones do)
			float4 hit = f.histShift.w > 0.5 && rd < 96.0 && surf.d >= 0.6 ? imReflect(p, n, R, lod, dither, f, hc, hd, hs, true) : float4(0.0);
			if (hit.a > 0.0) hit.a *= 1.0 - smoothstep(64.0, 96.0, rd);
			// (from 0.3 to 0.85: sky light is interpolated per triangle, and over 0.6..1 the two triangles of one face read
			// differently: obsidian and polished blocks showed a diagonal seam, one half reflecting more sky than the other)
			float open = smoothstep(0.75, 0.97, sky);  // (nearly open sky only: partial sky light differs per triangle, a diagonal seam indoors)
			float3 skyR = float3(0.0);
			if (open * smoothstep(-0.1, 0.2, R.y) > 0.0)
				skyR = (f.flags.x == 0 ? imPresent(imSkyColor(R, f, false), f) : imPresent(f.ambientUp.rgb * 2.0, f)) * (open * open)
					* smoothstep(-0.1, 0.2, R.y);
			// (a ray that passed behind something: the surface itself, a little darker, not no reflection at all: pixel by pixel
			// that switched the reflection on and off, a grain)
			float3 refl = hit.a > 0.0 ? mix(skyR, hit.rgb, hit.a) : hit.a < 0.0 ? c * 0.9 : skyR;
			// (texture kept: Reimagined keeps 70 % of the surface under its reflection)
			refl = mix(refl, max(c, refl), 0.7);
			if (surf.metal < 0.5) fm *= cls == IM_MAT_GEM ? mix(1.0, 0.85, gemDark) : 0.75;  // (obsidian: "should be shinier")  // (a dark gem or obsidian stays dark: its reflection a sheen over it)
			// (a gem's reflection takes its colour too, as a metal's: in a cave the trace finds the last frame's grey haze, and an
			// amethyst cluster showed flat grey-white squares on its brightest texels)
			float tintK = max(surf.metal, cls == IM_MAT_GEM ? 0.7 : 0.0);
			refl *= mix(float3(1.0), base / max(max(max(base.r, base.g), base.b), 0.02), tintK);
			c = mix(c, refl, saturate(fm));
		}
	}
	// water and glass (Reimagined's: a share by the angle, 15 % head-on; water less on its sides and from below; glass none right
	// up close): what the last frame's picture shows along the reflected ray, the sky where nothing is in the way
	if (kind != 0u && f.flags.y == 0 && (kind == 2u ? IM_FC_GLASSREFL && f.water.y > 0.5 : f.water.x > 0.5 && f.water.z < 0.5)) {
		float fresnel = 1.0 - saturate(dot(N, V));
		float rm = kind == 1u ? (0.5 + 0.5 * saturate(n.y)) * (f.water.z > 0.5 ? 0.5 : 1.0) : 1.0 - smoothstep(1.8, 0.3, length(p));
		float fm = (fresnel * fresnel * fresnel * 0.85 + 0.15) * rm;
		if (kind == 2u) fm *= 0.6;  // (glass: a sheen of the sky, not a mirror; a glass tunnel washed out white, reflections in reflections)
		if (fm > 0.01) {
			float3 R = reflect(-V, N);
			if (dot(R, n) < 0.02) R = normalize(R - n * (dot(R, n) - 0.02));
			float dither = imDither(pixel, f);
			// (traced only within 64 blocks: past that the reflection is the sky, cheaper and without the depth's banding)
			float dist = length(p);
			// (env.w bit 8: the benchmark's step, the sky alone)
			float4 hit = f.histShift.w > 0.5 && dist < 64.0 && (uint(f.env.w + 0.5) & 8u) == 0u ? imReflect(p, n, R, kind == 1u ? 0.0 : 1.5, dither, f, hc, hd, hs, false) : float4(0.0);
			hit.a = hit.a > 0.0 ? hit.a * (1.0 - smoothstep(40.0, 64.0, dist)) : hit.a;
			// (from 0.3 to 0.85: sky light is interpolated per triangle, and over 0.6..1 the two triangles of one face read
			// differently: obsidian and polished blocks showed a diagonal seam, one half reflecting more sky than the other)
			float open = smoothstep(0.75, 0.97, sky);  // (nearly open sky only: partial sky light differs per triangle, a diagonal seam indoors)
			float3 skyR = float3(0.0);
			if (open * smoothstep(-0.1, 0.2, R.y) > 0.0)
				skyR = (f.flags.x == 0 ? imPresent(imSkyColor(R, f, false), f) : imPresent(f.ambientUp.rgb * 2.0, f)) * (open * open)
					* smoothstep(-0.1, 0.2, R.y);
			// (blocked, or no sky to see: the sky where it's open, else the surface's own colour a little darker)
			float3 refl = hit.a > 0.0 ? mix(skyR, hit.rgb, hit.a) : open > 0.0 ? skyR : c * 0.85;
			c = mix(c, refl, saturate(fm));
		}
	}
	// light given off: on top of the tone-mapped light, unclamped (the picture is HDR: Bloom's resolve maps it to the screen)
	float3 ecol = pow(max(tex, 0.0), 2.2);
	// (lava: its darker crust a deep red-orange, only its hot spots running to yellow-white, as Reimagined's)
	// (a ramp by brightness instead of the texture's own colour: its red crust under the bloom's white haze read pink)
	if (cls == IM_MAT_LAVA) {
		float ll = imLuma(tex);
		// (more orange-red, the hottest a deep yellow-orange that never reaches white even past the screen's top)
		// (a little redder again: "too orange")
		float3 crust = float3(0.78, 0.075, 0.01), mid = float3(1.0, 0.22, 0.02), hot = float3(1.0, 0.42, 0.06);
		// (the texture's light and dark kept strongly: dark crust much darker than the hot veins)
		ecol = mix(mix(crust, mid, smoothstep(0.2, 0.5, ll)), hot, smoothstep(0.55, 0.95, ll)) * (0.15 + 0.85 * ll * ll);
	}
	if (cls == IM_MAT_EMITTER) {
		// (a light's colour kept: deepened a little, so its brightest parts glow in their own colour instead of washing to white)
		float em = max(max(ecol.r, ecol.g), ecol.b);
		ecol = ecol * pow(ecol / max(em, 1e-3), 0.4);
	}
	if (cls == IM_MAT_ORE || cls == IM_MAT_NETHER_ORE) {
		// (Reimagined's glowing ores: each speck's own colour made deeper (colour x colour^k: redstone's red pure, diamond's
		// cyan, emerald's green), then glowing well past white so the bloom gives every speck a halo; the stone stays dark)
		float em = max(max(ecol.r, ecol.g), ecol.b);
		ecol = ecol * pow(ecol / max(em, 1e-3), 0.35);
	}
	c = max(c, 0.0) + ecol * emit * airT;  // (the texture's colour: light given off isn't darkened by corner shading)
	if (oreE > 0.0) {
		// (Reimagined's light given off, linear, a quarter less in full daylight; through the tone curve (its own goes through
		// Reimagined's), then doubled so the brightest specks pass white and the bloom haloes them)
		float3 oe = pow(max(tex * oreCm * (oreE * oreE), 0.0), 2.2) * (1.0 - 0.25 * f.blockColor.w * sky);
		// (1.5, not 2: specks glowed into soft white blobs with the bloom over them, each speck's own pixels lost)
		c += imTonemap(oe * 0.5, f.tonemap) * (1.5 * airT);
	}
	return pow(c, 1.0 / 2.2);
}

// Entities, block entities, dropped items and particles (Minecraft's own shaders, patched by imaginemetal.EntityShade): the same
// light as the terrain's. col: their colour as Minecraft drew it but unlit (texture x tint x overlay, fogged), display-encoded;
// p camera-relative position, nrm the vertex normal (0: none, a particle: lit as if facing the light half-way), light (block,
// sky) 0..1. Sun with the shadow maps, the sky's light from above, block light in the grid's colour, the floor, the held light,
// combined as the terrain combines them, through the same tone curve. Returns display-encoded.
static float3 imShadeEntity(float3 col, float3 p, float3 nrm, float2 light, constant ImFrame &f, depth2d_array<float> smap, sampler cmp,
	texture3d<float> lvol, sampler lsamp, float kind, float4 vfog) {
	// (kind: 0 a mob, 1 an item, 2 armour, 3 the first-person hand and what it holds)
	// (vfog: Minecraft's own fog as it was mixed into col: its colour, and in w how much)
	bool hand = kind > 2.5;
	bool item = (kind > 0.5 && kind < 1.5) || hand;
	if (f.flags.z == 0 || f.flags.y != 0) return col;
	// (eyes under water: Minecraft's water fog is a dark navy that starts 8 blocks behind the eye and, for the first seconds
	// under, ends 24 blocks out, so a turtle three blocks away was already a third navy before any light fell on it: "it
	// becomes blue". Taken back out; ours, the terrain's, goes over it at the end)
	bool uw = f.water.z > 0.5 && f.flags.x == 0 && !hand;
	if (uw && vfog.w > 0.0) col = saturate((col - vfog.rgb * min(vfog.w, 0.96)) / (1.0 - min(vfog.w, 0.96)));
	if (f.flags.x == 2) light.y = 1.0;
	float3 base = pow(max(col, 0.0), 2.2);
	float nlen = length(nrm);
	bool particle = nlen < 0.1;
	float3 n = particle ? -normalize(p) : nrm / nlen;
	if (!particle && !hand && kind < 2.5) {
		// (mobs' and armour's own bumps, generated as the terrain's are: the texture's light and dark across the surface (screen
		// derivatives, Schüler's frame) tilt the normal a little, so a mob's skin and armour plates read in relief, not flat print)
		float lb = imLuma(base);
		float3 dp1 = dfdx(p), dp2 = dfdy(p);
		float dl1 = dfdx(lb), dl2 = dfdy(lb);
		float3 r1 = cross(dp2, n), r2 = cross(n, dp1);
		float det = dot(dp1, r1);
		float3 g = (r1 * dl1 + r2 * dl2) / (abs(det) > 1e-10 ? det : 1e-10);
		g -= n * dot(g, n);
		float gl = length(g);
		if (gl > 1e-5) n = normalize(n - g * (min(gl, 4.0) / gl) * 0.03);  // (0.06 read as noise on a husk and a camel)
	}
	float block = saturate(light.x), sky = saturate(light.y);
	float skyF = imSkyCurve(sky);
	// (the Nether has no sky light, so its ambient (Reimagined's netherColor, ambientUp) reached nothing: it lights everything)
	if (f.flags.x == 1) skyF = 1.0;
	if (f.water.z > 0.5 && f.flags.x == 0) skyF = max(skyF, 0.22);
	float nl = dot(n, f.lightDir.xyz);
	float wrap = particle ? 0.6 : max(nl + 0.4, 0.0) * (1.0 / 1.4);
	float2 sm = imShadow(p, n, float2(0.0), f, smap, cmp);
	float open = smoothstep(0.45, 0.9, sky);
	float sun = mix(open, sm.x, sm.y);
	// (under water the light is scattered: shadows there are soft and shallow, not the hard dark shapes cast on dry land)
	if (f.water.z > 0.5 && f.flags.x == 0) sun = (0.45 + 0.55 * sun) * (0.3 + 0.7 * smoothstep(0.0, 0.6, sky));
	else sun *= smoothstep(0.0, 0.07, sky);
	float3 direct = f.lightColor.rgb * (wrap * sun);
	float ny = particle ? 0.6 : n.y;
	float3 ambient = mix(f.ambientDown.rgb, f.ambientUp.rgb, ny * 0.5 + 0.5) * skyF;
	ambient += f.auroraLight.rgb * (skyF * (0.12 + 0.88 * saturate(ny)));
	// (what's in the hand has no normal and took the sky's full blue from straight above: a netherite sword's warm grey read
	// purple. Its share of the sky light mostly neutral)
	if (hand) ambient = mix(ambient, float3(imLuma(ambient)), 0.65);
	if (f.water.z > 0.5 && f.flags.x == 0) ambient += float3(0.035, 0.12, 0.11) * mix(0.12, 1.0, f.nightMiddle.w);  // (turquoise, not cold blue)
	float4 bl = imBlockLight(p, particle ? float3(0.0, 1.0, 0.0) : n, f, lvol, lsamp);
	float3 torch = bl.rgb * imBlockCurve(max(block, bl.a)) * (1.0 - 0.25 * f.blockColor.w * smoothstep(0.5, 1.0, sky));
	if (!particle && f.lightVol.w > 0.5) {
		// (block light with a direction: the grid's light in front of the face against behind it, so the side of a mob toward
		// a torch is lit and the far side falls off, as the terrain's bumps are shaded by it; lit evenly they read flat)
		float lf = imBlockLight(p + n * 0.45, n, f, lvol, lsamp).a, lb = imBlockLight(p - n * 0.45, -n, f, lvol, lsamp).a;
		float dirL = saturate(0.5 + (lf - lb) * 5.0);
		torch *= mix(0.45, 1.25, dirL);
		// (and the grid's own light where Minecraft's level at the mob is lower: a mob a few blocks from a lantern isn't dark)
		torch = max(torch, bl.rgb * imBlockCurve(saturate(max(lf, bl.a) - 1.0 / 15.0)) * mix(0.45, 1.25, dirL));
	}
	if (f.held.w > 0.0) {
		float hl = saturate(min(f.held.w + 4.0 / 15.0, 1.0) - length(p) / 15.0);
		if (hl > 0.0) {
			float3 hh = f.held.rgb;
			float hlum = imLuma(hh);
			hh = max(hlum + (hh - hlum) * 1.35, 0.0);
			float3 hc = hh * (max(max(f.blockColor.r, f.blockColor.g), f.blockColor.b) / max(max(max(hh.r, hh.g), hh.b), 1e-3));
			torch = max(torch, hc * imBlockCurve(hl) * (1.0 - 0.25 * f.blockColor.w * smoothstep(0.5, 1.0, sky)));
		}
	}
	if (hand) torch = mix(torch, float3(imLuma(torch)), 0.5);
	float3 day = direct + ambient;
	float3 lit = sqrt(day * day + torch * torch + f.minLight.rgb * f.minLight.rgb);
	if (f.nightMiddle.w > 0.0 && f.flags.x == 0) {
		const float g = 1.0 / 2.2;
		float3 dayG = pow(max(direct, 0.0), g) + pow(max(ambient, 0.0), g);
		float3 tG = pow(max(torch, 0.0), g), mG = pow(max(f.minLight.rgb, 0.0), g);
		float3 litG = pow(sqrt(dayG * dayG + tG * tG + mG * mG), 2.2);
		lit = mix(lit, litG, f.nightMiddle.w);
	}
	// (shaded by which way the face points, as the terrain's blocks are: tops full, sides a little less, undersides darker; mobs
	// lit evenly from every side read flat, cut-outs)
	float face = particle ? 1.0 : (n.y >= 0.0 ? 1.0 : 1.0 + 0.5 * n.y) * (1.0 + 0.05 * abs(n.z) - 0.16 * abs(n.x));
	// (in the dark a mob keeps a faint outline: a little of the cave's floor light from the side facing the viewer)
	if (!particle && !hand) lit += f.minLight.rgb * (0.6 * saturate(dot(n, -normalize(p))));
	float3 radiance = base * lit * face * f.minLight.w;
	// (the hand holding a light: lit by it on top of the day, so a torch in the hand shows on the arm even in sunlight; in the
	// light's colour)
	if (hand && f.held.w > 0.0) {
		float3 hh = f.held.rgb / max(max(max(f.held.r, f.held.g), f.held.b), 1e-3);
		radiance += base * hh * (0.55 * f.held.w * f.minLight.w);
	}
	if (kind < 0.5 && !particle) {
		// mobs (no PBR of their own): a soft sheen from the sun and nearby lights on their lighter texels, so a mob's shape reads
		// in the light instead of a flat cut-out; the darker texels stay matte
		float3 V = -normalize(p);
		float3 H = normalize(V + f.lightDir.xyz);
		float shineM = smoothstep(0.25, 0.8, imLuma(base)) * 0.15;
		float spM = pow(saturate(dot(n, H)), 12.0) * sun * saturate(nl * 3.0);
		float rimM = pow(1.0 - saturate(dot(n, V)), 3.0) * 0.5;
		radiance += base * (f.lightColor.rgb * spM + (ambient + torch) * rimM) * (shineM * f.minLight.w);
	}
	if (kind > 1.5 && kind < 2.5 && !particle) {
		// armour (Reimagined gives it a metal's smoothness by the texture's brightness): a sharp sun highlight and the sky's light
		// mirrored, stronger at a grazing angle, both tinted by the metal's own colour; dull cloth and leather barely
		float3 V = -normalize(p);
		float3 H = normalize(V + f.lightDir.xyz);
		float shine = smoothstep(0.02, 0.2, imLuma(base));
		float sp = pow(saturate(dot(n, H)), 32.0) * 5.0 * sun * saturate(nl * 4.0);
		float3 R = reflect(-V, n);
		float fres = pow(1.0 - saturate(dot(n, V)), 3.0);
		float3 env = mix(f.ambientDown.rgb, f.ambientUp.rgb, R.y * 0.5 + 0.5) * skyF * 1.4 + torch * 0.9;
		// (the shine follows the texture's own light and dark: a flat tint over the whole piece hid every detail of gold armour
		// by a torch, one smooth yellow shape)
		// (a metal: less of its light diffuse, more mirrored; both in the texture's own light and dark, squared, so the plates'
		// edges and rivets stay readable: "cooked", flat and not shiny, by a torch)
		radiance *= mix(1.0, 0.6, shine);
		float3 tintM = base / max(max(max(base.r, base.g), base.b), 0.05);
		float tl = smoothstep(0.05, 0.75, imLuma(base));
		tintM *= 0.15 + 0.85 * tl * tl;
		// (a torch or lantern near it makes its own highlight, as the sun does: armour shines indoors too)
		float tsp = pow(saturate(dot(n, V)), 3.0) * 0.5;
		radiance += tintM * (f.lightColor.rgb * sp + env * (0.15 + 0.7 * fres) + torch * tsp) * (shine * f.minLight.w);
	}
	// (the hand: no vibrance, its light's colour half kept: a netherite sword's warm grey went maroon by a torch and purple by the sky)
	float3 c = hand ? imTonemap(radiance, f.tonemap) : imGrade(imTonemap(radiance, f.tonemap), f);
	// items in the world that give off light (a lava bucket's lava, a torch's flame, glowstone dust, blaze rods): their hot,
	// saturated warm pixels glow past white, so the bloom haloes them and they aren't dark in a cave
	if (item) {
		float hi = max(max(base.r, base.g), base.b), lo = min(min(base.r, base.g), base.b);
		// (warm, or the soul flame's cyan: green and blue well over red, and bright)
		// (a flame's yellow-orange only, and bright: a red mushroom's cap and other warm items lit up as if they glowed)
		float warm = step(base.b, base.g) * step(base.g, base.r) * smoothstep(0.3, 0.5, base.g / max(base.r, 1e-3));
		float soul = step(base.r * 1.6, base.g) * step(base.r * 1.6, base.b) * smoothstep(0.7, 0.9, hi);
		float e = max(warm, soul) * smoothstep(0.75, 0.92, hi) * smoothstep(0.45, 0.75, (hi - lo) / max(hi, 1e-3));
		c += base * (1.6 * e);
	}
	c = pow(max(c, 0.0), 1.0 / 2.2);
	if (uw) {
		// (the water's fog as the terrain has it; and where Minecraft's own had left nothing of the mob to take back, wholly)
		float4 wf = imWaterFog(vfog.rgb, length(p), f);
		c = mix(c, wf.rgb, max(wf.w, smoothstep(0.85, 1.0, vfog.w)));
	}
	return c;
}

// The nether portal as Reimagined's special portal: only its bright swirls glow (crisp, past white so they bloom), the rest a deep
// see-through violet, and the swirls repeated in layers behind the surface, offset by the view (parallax), fainter and bluer the
// deeper, so the portal reads as a window into depth rather than a flat sheet.
static float4 imPortalLook(float2 u, float3 p, float4 texel, texture2d<float> atlas, constant ImFrame &f) {
	float2 r0 = f.portalRect.xy, rs = f.portalRect.zw - f.portalRect.xy;
	bool sl;
	float3 n = imFaceNormal(p, sl);
	float3 dp1 = dfdx(p), dp2 = dfdy(p);
	float2 duv1 = dfdx(u), duv2 = dfdy(u);
	float3 dp2perp = cross(dp2, n), dp1perp = cross(n, dp1);
	float3 T = dp2perp * duv1.x + dp1perp * duv2.x, B = dp2perp * duv1.y + dp1perp * duv2.y;
	// (the gradients of u and v along the face: T, B over the face's area in screen terms)
	float det = dot(n, cross(dp1, dp2));
	det = abs(det) < 1e-14 ? 1e-14 : det;
	T /= det;
	B /= det;
	float3 V = normalize(p);
	float into = max(abs(dot(V, n)), 0.25);
	constexpr sampler ns(filter::nearest, address::clamp_to_edge);
	// (by the brightest channel: the portal is violet, its luma low even where it's brightest, so by luma nothing glowed and the
	// portal showed as clear glass)
	float l0 = max(max(texel.r, texel.g), texel.b);
	float g0 = smoothstep(0.6, 0.95, l0);
	float3 col = texel.rgb * texel.rgb * 0.3 + float3(0.06, 0.0, 0.12) + texel.rgb * g0 * 2.4;
	float a = mix(0.88, 1.0, g0);
	for (int k = 1; k <= 3; k++) {
		float depth = 0.22 * float(k);
		float2 du = float2(dot(V, T), dot(V, B)) * (depth / into);
		float2 lu = fract((u + du - r0) / rs);
		float3 s = atlas.sample(ns, r0 + lu * rs, level(0.0)).rgb;
		float g = smoothstep(0.6, 0.95, max(max(s.r, s.g), s.b));
		float fade = 0.55 / float(k);
		col += s * g * fade * float3(0.75, 0.7, 1.15);
	}
	return float4(col, a);
}
