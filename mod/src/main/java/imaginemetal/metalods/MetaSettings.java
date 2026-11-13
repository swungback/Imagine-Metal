package imaginemetal.metalods;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * MetaLODS' settings, kept apart from Imagine Metal's in config/metalods.properties (written on first launch, saved when its
 * screen closes). Everything here applies at once in game.
 */
public final class MetaSettings {
	/** Far terrain drawn at all. */
	public static volatile boolean enabled = true;
	/** How far the LODs reach, in chunks (32 to 512). */
	public static volatile int distance = 128;
	/**
	 * Detail: a column of a level is split into the next finer level while the camera is closer than this many of its own
	 * widths. More: finer LODs further out (sharper, more to draw and generate). 4 to 16.
	 */
	public static volatile int detail = 6;
	/** Terrain never visited is filled in from the world seed (singleplayer only: a server's seed isn't known). */
	public static volatile boolean generate = true;
	/** The game's own trees on the nearest generated LODs (slower to generate than the biome's estimate). */
	public static volatile boolean exactTrees = true;
	/** Generated LODs in 3D: overhangs, arches and floating ground from the terrain's density (off: a heightfield, faster). */
	public static volatile boolean overhangs = true;
	/** Worker threads for generating and meshing (0: automatic). */
	public static volatile int threads = 0;
	/** Hide the LODs inside the real terrain (where real chunks are drawn). Debug: off shows both. */
	public static volatile boolean hideUnderReal = true;
	/** Debug, not saved: each level drawn in its own colour. */
	public static volatile boolean showLevels = false;
	/** Log a stats line every 10 seconds. */
	public static volatile boolean stats = true;

	private static boolean loaded;

	private MetaSettings() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("metalods.properties");
	}

	public static synchronized void load() {
		if (loaded) return;
		loaded = true;
		Path f = file();
		if (!Files.exists(f)) {
			save();
			return;
		}
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			p.load(r);
		} catch (IOException e) {
			System.out.println("[MetaLODS] couldn't read " + f + ": " + e);
			return;
		}
		enabled = bool(p, "enabled", enabled);
		distance = Math.clamp(integer(p, "distance", distance), 32, 512);
		detail = Math.clamp(integer(p, "detail", detail), 4, 16);
		generate = bool(p, "generate", generate);
		exactTrees = bool(p, "exactTrees", exactTrees);
		overhangs = bool(p, "overhangs", overhangs);
		threads = Math.clamp(integer(p, "threads", threads), 0, 16);
		stats = bool(p, "stats", stats);
	}

	public static synchronized void save() {
		Path f = file();
		String text = "# MetaLODS: Imagine Metal's far terrain (Voxy-style voxel LODs, natively on Metal).\n"
			+ "# Changed from the MetaLODS screen (Video Settings or the pause menu, next to Imagine Metal); applies at once.\n"
			+ "enabled=" + enabled + "\n"
			+ "# How far the LODs reach, in chunks (32 to 512).\n"
			+ "distance=" + distance + "\n"
			+ "# Detail (4 to 16): finer LODs further out with more. 6 is the default.\n"
			+ "detail=" + detail + "\n"
			+ "# Fill in terrain you never visited from the world seed (singleplayer).\n"
			+ "generate=" + generate + "\n"
			+ "# The game's own trees on the nearest generated LODs.\n"
			+ "exactTrees=" + exactTrees + "\n"
			+ "# Generated LODs in 3D (overhangs, arches, floating ground). Applies when a world is next loaded.\n"
			+ "overhangs=" + overhangs + "\n"
			+ "# Worker threads (0: automatic).\n"
			+ "threads=" + threads + "\n"
			+ "# A stats line in the log every 10 seconds.\n"
			+ "stats=" + stats + "\n";
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
				w.write(text);
			}
		} catch (IOException e) {
			System.out.println("[MetaLODS] couldn't write " + f + ": " + e);
		}
	}

	private static boolean bool(Properties p, String k, boolean def) {
		String v = System.getProperty("metalods." + k, p.getProperty(k));
		return v == null ? def : Boolean.parseBoolean(v.trim());
	}

	private static int integer(Properties p, String k, int def) {
		String v = System.getProperty("metalods." + k, p.getProperty(k));
		if (v == null) return def;
		try {
			return Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/** Worker threads to use. */
	static int workerThreads() {
		if (threads > 0) return threads;
		// (Apple silicon: the workers run at the utility QoS class, so they fill the efficiency cores and whatever the performance
		// cores have left over; the game's own threads, at a higher class, always go first. All cores but two.)
		int cores = Runtime.getRuntime().availableProcessors();
		return Math.clamp(cores - 2, 2, 8);
	}
}
