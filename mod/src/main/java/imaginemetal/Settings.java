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
	public static volatile float shadowRange = Config.number("imaginemetal.shadowRange", 160, 64, 256);
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
	public static volatile boolean netherStorm = Engine.flag("imaginemetal.netherStorm", true);
	public static volatile boolean bloomFog = Engine.flag("imaginemetal.bloomFog", true);
	public static volatile boolean shadowFarAlternate = Engine.flag("imaginemetal.shadowFarAlternate", true);
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
	public static volatile int debug = (int) Config.number("imaginemetal.debug", 0, 0, 4);

	// after a restart (what the game started with is in Resolution.RETINA, Engine.TERRAIN, Shadows.RES)
	public static volatile boolean retina = Engine.flag("imaginemetal.retina", false);
	public static volatile boolean terrain = Engine.flag("imaginemetal.terrain", true);
	public static volatile int shadowRes = (int) Config.number("imaginemetal.shadowRes", 2048, 512, 8192);

	private Settings() {
	}

	public static void save() {
		Map<String, String> v = new LinkedHashMap<>();
		v.put("imaginemetal.retina", String.valueOf(retina));
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
		Config.save(v);
	}
}
