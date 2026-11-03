package imaginemetal;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * config/imaginemetal.properties: written with the defaults on first launch, read once at startup into system properties (a
 * -D on the command line still wins). Every switch in Imagine Metal reads its key from there.
 */
public final class Config {
	private static boolean loaded;

	private static final String DEFAULTS = """
		# Imagine Metal settings. Edit, then restart the game.
		#
		# Resolution. false: the game draws at the window's size (a 1440x900 window draws 1440x900 pixels; the Mac scales it up
		# to the Retina screen, as Sodium Extra's "Reduce resolution on macOS" does): 4x fewer pixels, much faster.
		# true: every Retina pixel (2880x1800 for that window): sharper, much slower.
		imaginemetal.retina=false
		#
		# Shadows from the sun and moon.
		imaginemetal.shadows=true
		# Shadow map size (1024, 2048, 4096): sharper leaf shadows with more. Most of the shadows' cost is the blocks drawn into
		# the map, not its size, so 1024 saves little and blurs the leaves.
		imaginemetal.shadowRes=2048
		# How far around you shadows reach, in blocks (64 to 256). Less is faster: fewer blocks to draw into the map.
		imaginemetal.shadowRange=160
		#
		# Our sky and distance haze (false: Minecraft's sky and fog).
		imaginemetal.sky=true
		# How thick the distance haze is (1 = Complementary Reimagined's; 0 none, up to 2).
		imaginemetal.haze=0.75
		#
		# Surfaces: reflections and highlights (every block family by its texture, as Complementary Reimagined and Photon do), and
		# generated normals' strength (0 off; 1 is Reimagined's default strength, up to 4): edges between texels bevel.
		imaginemetal.pbr=true
		imaginemetal.generatedNormals=0
		#
		# Coloured light: torches, soul fire, redstone, lava and other light sources light the world in their own colours.
		imaginemetal.coloredLight=true
		#
		# Bloom: the soft glow around bright things (the sun, lava, torches, glowing blocks) and a faint haze over everything.
		imaginemetal.bloom=true
		# How strong the glow is (0 off, 0.5 default, up to 2).
		imaginemetal.bloomStrength=0.5
		#
		# Imagine Metal's lighting on the terrain (false: vanilla's lighting, for comparing). A restart applies it.
		imaginemetal.terrain=true
		#
		# Debug view: 0 the picture, 1 light levels, 2 normals, 3 the bloom's glow alone, 4 the coloured light grid alone.
		imaginemetal.debug=0
		""";

	private Config() {
	}

	public static synchronized void load() {
		if (loaded) return;
		loaded = true;
		try {
			Path file = FabricLoader.getInstance().getConfigDir().resolve("imaginemetal.properties");
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Files.writeString(file, DEFAULTS, StandardCharsets.UTF_8);
				System.out.println("[Imagine Metal] wrote " + file);
			}
			Properties p = new Properties();
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				p.load(r);
			}
			for (String k : p.stringPropertyNames()) {
				if (k.startsWith("imaginemetal.") && System.getProperty(k) == null) System.setProperty(k, p.getProperty(k).trim());
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[Imagine Metal] config: " + e + " (using the defaults)");
		}
	}

	/**
	 * Writes values (key -> value) to the file: the defaults' layout and comments with these values in place, then any other
	 * imaginemetal keys the file had, as they were.
	 */
	public static synchronized void save(java.util.Map<String, String> values) {
		try {
			Path file = FabricLoader.getInstance().getConfigDir().resolve("imaginemetal.properties");
			Properties old = new Properties();
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					old.load(r);
				}
			}
			String text = DEFAULTS;
			StringBuilder extra = new StringBuilder();
			for (var e : values.entrySet()) {
				String line = e.getKey() + "=" + e.getValue();
				var m = java.util.regex.Pattern.compile("(?m)^" + java.util.regex.Pattern.quote(e.getKey()) + "=.*$").matcher(text);
				if (m.find()) text = m.replaceFirst(java.util.regex.Matcher.quoteReplacement(line));
				else extra.append(line).append('\n');
				System.setProperty(e.getKey(), e.getValue());
			}
			for (String k : old.stringPropertyNames()) {
				if (k.startsWith("imaginemetal.") && !values.containsKey(k) && !text.contains("\n" + k + "=")) extra.append(k).append('=').append(old.getProperty(k)).append('\n');
			}
			Files.createDirectories(file.getParent());
			Files.writeString(file, text + extra, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			System.out.println("[Imagine Metal] couldn't save the settings: " + e);
		}
	}

	static float number(String key, float def, float min, float max) {
		try {
			return Math.clamp(Float.parseFloat(System.getProperty(key, String.valueOf(def)).trim()), min, max);
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
