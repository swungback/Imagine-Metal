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
		v.put("imaginemetal.generatedNormals", String.format(java.util.Locale.ROOT, "%.2f", normals));
		v.put("imaginemetal.bloom", String.valueOf(bloom));
		v.put("imaginemetal.bloomStrength", String.format(java.util.Locale.ROOT, "%.2f", bloomStrength));
		v.put("imaginemetal.debug", String.valueOf(debug));
		Config.save(v);
	}
}
