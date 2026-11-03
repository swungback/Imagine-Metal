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

	static float number(String key, float def, float min, float max) {
		try {
			return Math.clamp(Float.parseFloat(System.getProperty(key, String.valueOf(def)).trim()), min, max);
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
