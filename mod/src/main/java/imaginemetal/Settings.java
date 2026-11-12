package imaginemetal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The settings the in-game screen (SettingsScreen) changes. The live ones are read every frame, so a change shows at once; the
 * rest are read once at startup and apply after a restart. All are saved to config/imaginemetal.properties when the screen
 * closes.
 */
public final class Settings {
	static {
		Config.load();
	}

	// live
	public static volatile boolean shadows = Engine.flag("imaginemetal.shadows", true);
	/** (Build C1: 256 by default, the whole view at render distance 16; a saved 160, the old default, is moved up once: Config.migrate) */
	public static volatile float shadowRange = Config.number("imaginemetal.shadowRange", 256, 64, 256);
	public static volatile boolean coloredLight = Engine.flag("imaginemetal.coloredLight", true);
	public static volatile boolean sky = Engine.flag("imaginemetal.sky", true);
	public static volatile float haze = Config.number("imaginemetal.airFog", 1f, 0f, 3f);
	public static volatile boolean pbr = Engine.flag("imaginemetal.pbr", true);
	public static volatile boolean clouds = Engine.flag("imaginemetal.clouds", true);
	public static volatile boolean aurora = Engine.flag("imaginemetal.aurora", true);
	/** 0 every night, 1 cold biomes, 2 full moon, 3 cold biomes or full moon */
	public static volatile int auroraWhen = (int) Config.number("imaginemetal.auroraWhen", 3, 0, 3);
	public static volatile int auroraStyle = (int) Config.number("imaginemetal.auroraStyle", 0, 0, 1);
	public static volatile int auroraColors = (int) Config.number("imaginemetal.auroraColors", 0, 0, Aurora.NAMES.length - 1);
	public static volatile float auroraBrightness = Config.number("imaginemetal.auroraBrightness", 1f, 0.25f, 3f);
	public static volatile float auroraInfluence = Config.number("imaginemetal.auroraInfluence", 1f, 0f, 3f);
	public static volatile boolean heldLight = Engine.flag("imaginemetal.heldLight", true);
	public static volatile boolean waving = Engine.flag("imaginemetal.waving", true);
	public static volatile int waterStyle = (int) Config.number("imaginemetal.waterStyle", 1, 0, 2);
	public static volatile int stars = (int) Config.number("imaginemetal.stars", 1, 0, 1);
	/** the End's sky: 0 Reimagined's violet with its beams, 1 a black hole (after Iteration's) */
	public static volatile int endStyle = (int) Config.number("imaginemetal.endStyle", 0, 0, 1);
	public static volatile boolean shootingStars = Engine.flag("imaginemetal.shootingStars", true);
	public static volatile boolean wet = Engine.flag("imaginemetal.wet", true);
	public static volatile boolean intenseDeepDark = Engine.flag("imaginemetal.intenseDeepDark", true);
	public static volatile boolean taa = Engine.flag("imaginemetal.taa", true);
	/**
	 * The world's render scale in percent (Hdr.scale; live): the share of the screen's size, on each axis, the world is drawn
	 * with (67: under half the pixels); the TAA brings it up to the screen's size. The hand, the HUD and menus are always full size.
	 */
	public static volatile int renderScale = Math.round(Config.number("imaginemetal.renderScale", 0.67f, 0.5f, 1f) * 100f);
	/** The TAA's sub-pixel offset of the terrain (ImagineFrame.jitterX): not saved, a debug switch. -Dimaginemetal.taaJitter=false. */
	public static volatile boolean taaJitter = Engine.flag("imaginemetal.taaJitter", true);
	/** The cutout (leaves) depth pre-pass (mcopt OwnTerrain.leafPrepass): not saved; -Dmcopt.own.prepass=false starts with it off. */
	public static volatile boolean leafPrepass = !"false".equalsIgnoreCase(System.getProperty("mcopt.own.prepass", "true"));
	public static volatile boolean rainbows = Engine.flag("imaginemetal.rainbows", true);
	public static volatile boolean netherFog = Engine.flag("imaginemetal.netherFog", true);
	/** mobs, block entities, dropped items and particles lit as the terrain is (EntityShade; read when their shaders are made) */
	public static volatile boolean entityLight = Engine.flag("imaginemetal.entityLight", true);
	public static volatile boolean portals = Engine.flag("imaginemetal.portals", true);
	public static volatile boolean glassSun = Engine.flag("imaginemetal.glassSun", true);
	public static volatile boolean nebula = Engine.flag("imaginemetal.nebula", false);
	public static volatile boolean coated = Engine.flag("imaginemetal.coated", true);
	public static volatile boolean lightFog = Engine.flag("imaginemetal.lightFog", true);
	public static volatile boolean mobShadows = Engine.flag("imaginemetal.mobShadows", true);
	public static volatile boolean waterFx = Engine.flag("imaginemetal.waterFx", true);
	public static volatile boolean pbrPack = Engine.flag("imaginemetal.pbrPack", true);
	public static volatile boolean parallax = Engine.flag("imaginemetal.parallax", true);
	public static volatile int outline = (int) Config.number("imaginemetal.outline", 0, 0, 2);
	public static volatile boolean netherStorm = Engine.flag("imaginemetal.netherStorm", true);
	public static volatile boolean bloomFog = Engine.flag("imaginemetal.bloomFog", true);
	public static volatile boolean shadowFarAlternate = Engine.flag("imaginemetal.shadowFarAlternate", true);
	/**
	 * The far shadow cascade kept until something changes it (Shadows): not saved. ON by default again since Build B1 (the pop-in
	 * seen with it on was the foliage softening band, changed in Build A; Settings > Advanced > Debug has a toggle).
	 * -Dimaginemetal.shadowFarCache=false.
	 */
	public static volatile boolean shadowFarCache = Engine.flag("imaginemetal.shadowFarCache", true);
	/** The haze and the border fade in one pass over the opaque world (Reflections.promiseAir): not saved. -Dimaginemetal.airPass=false. */
	public static volatile boolean airPass = Engine.flag("imaginemetal.airPass", true);
	/** Build B2: the opaque terrain lit once per pixel in tile memory (Deferred): not saved. -Dimaginemetal.deferred=false. */
	public static volatile boolean deferred = Engine.flag("imaginemetal.deferred", true);
	/** The near shadow cascade drawn every other frame (Shadows.skipNear): not saved. -Dimaginemetal.shadowNearAlternate=false. */
	public static volatile boolean shadowNearAlternate = Engine.flag("imaginemetal.shadowNearAlternate", true);
	/**
	 * The near shadow cascade's casters culled to what the view can see (Shadows.viewCull): not saved. OFF by default since
	 * 2026-11-11: with it on, shadows went missing over part of the screen when looking down from the air (suspected, not proven,
	 * to be this and not the far cache). -Dimaginemetal.shadowNearViewCull=true turns it on.
	 */
	public static volatile boolean shadowNearViewCull = Engine.flag("imaginemetal.shadowNearViewCull", false);
	// ---- Build C1: each change behind its own switch (Settings > Advanced > Debug), none saved; a -D starts with it off ----
	/**
	 * The light shafts' "something overhead" mode kept to where something is: its strength from a ramp on the sky light at the eyes
	 * (ImagineFrame.shaftCover), only for rays that pass through shadow, and the veil's total capped (bloom.metal). Off: as Build B2
	 * (any sky light below 15 at the eyes = full strength: the whiteout). -Dimaginemetal.shaftVeilFix=false.
	 */
	public static volatile boolean shaftVeilFix = Engine.flag("imaginemetal.shaftVeilFix", true);
	/**
	 * Flat things drawn as translucent mobs (Simple Blood's splats on the ground) pulled toward the camera by the depth their own
	 * slope spans in a pixel and a half: the terrain is drawn with the TAA's sub-pixel offset and they are not, so the depth test
	 * between the two flipped from frame to frame and whole splats blinked. -Dimaginemetal.decalBias=false.
	 */
	public static volatile boolean decalBias = Engine.flag("imaginemetal.decalBias", true);
	/** The TAA steadier far away while the camera is still (bloom.metal im_bloom_taa). -Dimaginemetal.taaFarSteady=false. */
	public static volatile boolean taaFarSteady = Engine.flag("imaginemetal.taaFarSteady", true);
	/** The far shadow cascade softer the farther the caster, and a wider fade at its edge (lighting.metal imShadow). -Dimaginemetal.shadowSoftFar=false. */
	public static volatile boolean shadowSoftFar = Engine.flag("imaginemetal.shadowSoftFar", true);
	/**
	 * Daylight under something high overhead (a sky island, a tall overhang): Minecraft's sky light is 0 there, as in a cave, which
	 * cut the sun and the sky's light to a black, block-edged patch. The sun's shadow maps decide instead where the caster is far
	 * away, and a cover high above the player isn't "underground" (ImagineFrame). -Dimaginemetal.shadowOpenAir=false.
	 */
	public static volatile boolean shadowOpenAir = Engine.flag("imaginemetal.shadowOpenAir", true);
	/** Dry biomes toward Reimagined's desert: colder, darker shade, warmer sand, less vibrance on beige. -Dimaginemetal.desertLook=false. */
	public static volatile boolean desertLook = Engine.flag("imaginemetal.desertLook", true);
	/** Our clouds drawn after the opaque world, behind what's in front of them (Sky.drawCloudsLate). -Dimaginemetal.cloudsLate=false. */
	public static volatile boolean cloudsLate = Engine.flag("imaginemetal.cloudsLate", true);
	// ---- Build C2: each change behind its own switch (Settings > Advanced > Debug), none saved; a -D starts with it off ----
	/**
	 * The light shafts as strong as Build B2 under a canopy and at sunrise: C1's ceiling on a ray's light gone (ImFrame.env.z 0)
	 * and the "something overhead" strength back to B2's (any sky light below 15 at the eyes). The whiteout stays fixed by C1's
	 * other half (shaftVeilFix): the strong mode only for rays that pass through shadow. -Dimaginemetal.shaftsFull=false.
	 */
	public static volatile boolean shaftsFull = Engine.flag("imaginemetal.shaftsFull", true);
	/**
	 * Grass, flowers and tall grass cast shadows again, from a map of their own (slice 2 of the shadow map, near cascade only):
	 * soft, lighter (they let some light through) and swaying with the plant. The old "ghost copies" were the receivers' normal
	 * offset and slope bias, made for big flat faces, pushing a plant's small shadow off its foot; the ground isn't in the plants'
	 * map, so it needs no bias there. Off: as C1 (small plants cast none, tall grass's top half hard in the main map).
	 * -Dimaginemetal.plantShadows=false.
	 */
	public static volatile boolean plantShadows = Engine.flag("imaginemetal.plantShadows", true);
	/**
	 * The far shadow map (past ~28 blocks) without the receivers' oversized offsets: its normal offset was 1.5 of its texels (a
	 * quarter block each) times up to 2.2, and its slope bias on top, which lifted the ground about a block over itself, so
	 * anything a block tall or less (steps, fences, bushes) cast nothing past the near map's edge: the line where shadows ended.
	 * Its taps now lie in the receiving face's own plane (each with its exact depth), the casters' own raster bias does the rest.
	 * -Dimaginemetal.shadowFarFix=false.
	 */
	public static volatile boolean shadowFarFix = Engine.flag("imaginemetal.shadowFarFix", true);
	/** Pink petals and wildflowers don't sway (their heads are flat and stayed still while the stems moved). Rebuilds the chunks. */
	public static volatile boolean petalsStill = Engine.flag("imaginemetal.petalsStill", true);
	/**
	 * A section compiled for a level that's already gone (the End, after respawning) isn't drawn: its compile finished after the
	 * level change, landed in a slot of the new level's table and stayed there (the ghost slabs). -Dimaginemetal.levelGuard=false.
	 */
	public static volatile boolean levelGuard = Engine.flag("imaginemetal.levelGuard", true);
	/**
	 * Mobs, items, block entities and particles drawn with the TAA's sub-pixel offset as the terrain is. The TAA takes the offset
	 * back out of the whole picture, so things drawn without it were shifted by it every frame: a pig's eyes swam.
	 * -Dimaginemetal.entityJitter=false.
	 */
	public static volatile boolean entityJitter = Engine.flag("imaginemetal.entityJitter", true);
	/**
	 * (Build C2b) The near shadow map centred on the ground under the camera instead of the camera: flying or standing high and
	 * looking toward a low sun, the ground ahead left the near map ~17 blocks out and its shadows ended there (the pop-in).
	 * -Dimaginemetal.shadowNearGround=false.
	 */
	public static volatile boolean shadowNearGround = Engine.flag("imaginemetal.shadowNearGround", true);
	/**
	 * (Build C2b) Under water, the surface seen from below past the see-through window (where it mirrors the water) in the
	 * water's own fog colour, not that colour at a fifth: it was a dark murky ring around the window. -Dimaginemetal.waterSurfaceFog=false.
	 */
	public static volatile boolean waterSurfaceFog = Engine.flag("imaginemetal.waterSurfaceFog", true);
	/**
	 * (Build C3) Under water, the window up through the surface wide: the surface only turns into the mirrored deep water at a
	 * grazing angle (past ~70 degrees from straight up), not past ~45 (a real water surface's window, which is what Jack found
	 * too small: "can't see out except a circle right above me"). -Dimaginemetal.waterWideWindow=false.
	 */
	public static volatile boolean waterWideWindow = Engine.flag("imaginemetal.waterWideWindow", true);
	/** (benchmark only) Water and glass reflections traced through the screen; false: the sky alone. */
	public static volatile boolean waterTrace = true;
	public static volatile boolean ssao = Engine.flag("imaginemetal.ssao", true);
	public static volatile float ssaoStrength = Config.number("imaginemetal.ssaoStrength", 0.7f, 0f, 1f);
	public static volatile boolean shafts = Engine.flag("imaginemetal.shafts", true);
	public static volatile float shaftStrength = Config.number("imaginemetal.shaftStrength", 1f, 0f, 3f);
	public static volatile boolean glassReflections = Engine.flag("imaginemetal.glassReflections", true);
	public static volatile float nightBrightness = Config.number("imaginemetal.nightBrightness", 1f, 0.25f, 2f);
	public static volatile float normals = Config.number("imaginemetal.generatedNormals", 0f, 0f, 4f);
	public static volatile boolean bloom = Engine.flag("imaginemetal.bloom", true);
	public static volatile float bloomStrength = Config.number("imaginemetal.bloomStrength", 0.5f, 0f, 2f);
	/** 0 the picture, 1 light levels, 2 normals, 3 the bloom's glow alone, 4 the coloured light grid alone. */
	public static volatile int debug = (int) Config.number("imaginemetal.debug", 0, 0, 5);

	// after a restart (what the game started with is in Resolution.RETINA, Engine.TERRAIN, Shadows.RES)
	public static volatile boolean retina = Engine.flag("imaginemetal.retina", false);
	/** Resolution.OUTPUT after a restart: the framebuffer's height in lines (0 = the window's size in points). */
	public static volatile int output = (int) Config.number("imaginemetal.output", 1080, 0, 4320);
	public static volatile boolean terrain = Engine.flag("imaginemetal.terrain", true);
	public static volatile int shadowRes = (int) Config.number("imaginemetal.shadowRes", 2048, 512, 8192);

	private Settings() {
	}

	public static void save() {
		// (the shaders switched off live: put back first, so the file keeps the real settings)
		if (Live.isOff()) Live.setOff(false);
		Map<String, String> v = new LinkedHashMap<>();
		v.put("imaginemetal.retina", String.valueOf(retina));
		v.put("imaginemetal.output", String.valueOf(output));
		v.put("imaginemetal.renderScale", String.format(java.util.Locale.ROOT, "%.2f", renderScale / 100f));
		v.put("imaginemetal.terrain", String.valueOf(terrain));
		v.put("imaginemetal.shadows", String.valueOf(shadows));
		v.put("imaginemetal.shadowRes", String.valueOf(shadowRes));
		v.put("imaginemetal.shadowRange", String.valueOf(Math.round(shadowRange)));
		v.put("imaginemetal.coloredLight", String.valueOf(coloredLight));
		v.put("imaginemetal.sky", String.valueOf(sky));
		v.put("imaginemetal.airFog", String.format(java.util.Locale.ROOT, "%.2f", haze));
		v.put("imaginemetal.pbr", String.valueOf(pbr));
		v.put("imaginemetal.clouds", String.valueOf(clouds));
		v.put("imaginemetal.aurora", String.valueOf(aurora));
		v.put("imaginemetal.auroraWhen", String.valueOf(auroraWhen));
		v.put("imaginemetal.auroraStyle", String.valueOf(auroraStyle));
		v.put("imaginemetal.auroraColors", String.valueOf(auroraColors));
		v.put("imaginemetal.auroraBrightness", String.format(java.util.Locale.ROOT, "%.2f", auroraBrightness));
		v.put("imaginemetal.auroraInfluence", String.format(java.util.Locale.ROOT, "%.2f", auroraInfluence));
		v.put("imaginemetal.heldLight", String.valueOf(heldLight));
		v.put("imaginemetal.waving", String.valueOf(waving));
		v.put("imaginemetal.waterStyle", String.valueOf(waterStyle));
		v.put("imaginemetal.glassReflections", String.valueOf(glassReflections));
		v.put("imaginemetal.shafts", String.valueOf(shafts));
		v.put("imaginemetal.ssao", String.valueOf(ssao));
		v.put("imaginemetal.bloomFog", String.valueOf(bloomFog));
		v.put("imaginemetal.rainbows", String.valueOf(rainbows));
		v.put("imaginemetal.netherFog", String.valueOf(netherFog));
		v.put("imaginemetal.entityLight", String.valueOf(entityLight));
		v.put("imaginemetal.portals", String.valueOf(portals));
		v.put("imaginemetal.glassSun", String.valueOf(glassSun));
		v.put("imaginemetal.nebula", String.valueOf(nebula));
		v.put("imaginemetal.coated", String.valueOf(coated));
		v.put("imaginemetal.lightFog", String.valueOf(lightFog));
		v.put("imaginemetal.mobShadows", String.valueOf(mobShadows));
		v.put("imaginemetal.waterFx", String.valueOf(waterFx));
		v.put("imaginemetal.outline", String.valueOf(outline));
		v.put("imaginemetal.pbrPack", String.valueOf(pbrPack));
		v.put("imaginemetal.parallax", String.valueOf(parallax));
		v.put("imaginemetal.netherStorm", String.valueOf(netherStorm));
		v.put("imaginemetal.taa", String.valueOf(taa));
		v.put("imaginemetal.intenseDeepDark", String.valueOf(intenseDeepDark));
		v.put("imaginemetal.wet", String.valueOf(wet));
		v.put("imaginemetal.ssaoStrength", String.format(java.util.Locale.ROOT, "%.2f", ssaoStrength));
		v.put("imaginemetal.stars", String.valueOf(stars));
		v.put("imaginemetal.endStyle", String.valueOf(endStyle));
		v.put("imaginemetal.shootingStars", String.valueOf(shootingStars));
		v.put("imaginemetal.shaftStrength", String.format(java.util.Locale.ROOT, "%.2f", shaftStrength));
		v.put("imaginemetal.nightBrightness", String.format(java.util.Locale.ROOT, "%.2f", nightBrightness));
		v.put("imaginemetal.generatedNormals", String.format(java.util.Locale.ROOT, "%.2f", normals));
		v.put("imaginemetal.bloom", String.valueOf(bloom));
		v.put("imaginemetal.bloomStrength", String.format(java.util.Locale.ROOT, "%.2f", bloomStrength));
		v.put("imaginemetal.debug", String.valueOf(debug));
		v.put("imaginemetal.configVersion", String.valueOf(Config.VERSION));
		Config.save(v);
	}
}
