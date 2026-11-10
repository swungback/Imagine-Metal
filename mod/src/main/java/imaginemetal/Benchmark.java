package imaginemetal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings, Advanced, "Benchmark": with the player standing still, each feature in turn is switched off for a few seconds and
 * the frame time measured, then everything is put back exactly as it was (every live setting snapshotted, Live.snapshot). The
 * results go to the log and the chat, the most expensive first: which feature costs how many milliseconds a frame here, on this
 * Mac, so the optimizing goes where the time actually goes.
 */
public final class Benchmark {
	private record Step(String name, Runnable off) {
	}

	private record Result(String name, double fps, double ms) {
	}

	private static final double SETTLE = 2.0, MEASURE = 5.0;  // (longer: 3 s windows swung by a few fps either way)
	private static List<Step> steps;
	private static int index;
	private static long stepStart, frames, measureStart;
	private static final List<Result> results = new ArrayList<>();
	private static Map<Field, Object> saved;
	private static boolean wasOff;

	private Benchmark() {
	}

	public static boolean running() {
		return steps != null;
	}

	public static void start() {
		wasOff = Live.isOff();
		if (wasOff) Live.setOff(false);
		saved = Live.snapshot();
		steps = new ArrayList<>();
		steps.add(new Step("everything on", () -> {
		}));
		steps.add(new Step("shadows off", () -> Settings.shadows = false));
		steps.add(new Step("shadow range 96", () -> Settings.shadowRange = Math.min(Settings.shadowRange, 96f)));
		steps.add(new Step("ambient occlusion off", () -> Settings.ssao = false));
		steps.add(new Step("light shafts off", () -> Settings.shafts = false));
		steps.add(new Step("PBR (reflections, highlights) off", () -> Settings.pbr = false));
		steps.add(new Step("generated normals off", () -> Settings.normals = 0f));
		steps.add(new Step("parallax off", () -> Settings.parallax = false));
		steps.add(new Step("TAA off", () -> Settings.taa = false));
		steps.add(new Step("coloured light off", () -> Settings.coloredLight = false));
		steps.add(new Step("held light off", () -> Settings.heldLight = false));
		steps.add(new Step("sunlight through glass off", () -> Settings.glassSun = false));
		steps.add(new Step("light fog off", () -> Settings.lightFog = false));
		steps.add(new Step("mob shadows off", () -> Settings.mobShadows = false));
		steps.add(new Step("water refraction off", () -> Settings.waterFx = false));
		steps.add(new Step("water vanilla", () -> Settings.waterStyle = 0));
		steps.add(new Step("glass reflections off", () -> Settings.glassReflections = false));
		steps.add(new Step("mobs and items lit off", () -> Settings.entityLight = false));
		steps.add(new Step("bloom off", () -> Settings.bloom = false));
		steps.add(new Step("bloom fog off", () -> Settings.bloomFog = false));
		steps.add(new Step("clouds off", () -> Settings.clouds = false));
		steps.add(new Step("our sky and air off", () -> Settings.sky = false));
		steps.add(new Step("air haze off", () -> Settings.haze = 0f));
		steps.add(new Step("wet in rain off", () -> Settings.wet = false));
		steps.add(new Step("aurora off", () -> Settings.aurora = false));
		steps.add(new Step("nether storm off", () -> Settings.netherStorm = false));
		steps.add(new Step("stars, nebula, shooting stars off", () -> {
			Settings.stars = 0;
			Settings.nebula = Settings.shootingStars = false;
		}));
		steps.add(new Step("shaders off (live, near vanilla)", () -> Live.setOff(true)));
		index = -1;
		results.clear();
		say("Benchmark: stand still and don't touch anything, about " + Math.round(steps.size() * (SETTLE + MEASURE)) + " seconds...", true);
		next();
	}

	private static void next() {
		restore();
		index++;
		if (index >= steps.size()) {
			finish();
			return;
		}
		steps.get(index).off().run();
		stepStart = System.nanoTime();
		frames = -1;
	}

	/** Every frame (LevelRendererMixin). */
	public static void frame() {
		if (steps == null) return;
		long now = System.nanoTime();
		double t = (now - stepStart) / 1e9;
		if (t < SETTLE) return;
		if (frames < 0) {
			frames = 0;
			measureStart = now;
			return;
		}
		frames++;
		double m = (now - measureStart) / 1e9;
		if (m >= MEASURE) {
			double fps = frames / m;
			Result r = new Result(steps.get(index).name(), fps, 1000.0 * m / Math.max(frames, 1));
			results.add(r);
			System.out.println("[Imagine Metal] benchmark: " + String.format(Locale.ROOT, "%-36s %6.1f fps %6.2f ms", r.name, r.fps, r.ms));
			next();
		}
	}

	private static void finish() {
		steps = null;
		restore();
		if (results.isEmpty()) return;
		Result base = results.get(0);
		List<Result> rest = new ArrayList<>(results.subList(1, results.size()));
		rest.sort((a, b) -> Double.compare(a.ms, b.ms));  // (the biggest saving first)
		say(String.format(Locale.ROOT, "benchmark: everything on %.0f fps (%.1f ms a frame). Cost of each feature:", base.fps, base.ms), true);
		int shown = 0;
		for (Result r : rest) {
			String line = String.format(Locale.ROOT, "  %-36s %+5.1f ms  (%.0f fps)", r.name, base.ms - r.ms, r.fps);
			System.out.println("[Imagine Metal] benchmark: " + line);
			if (shown++ < 10) Live.chat(line.trim());
		}
		say("benchmark done (all results in latest.log)", true);
		if (wasOff) Live.setOff(true);
	}

	private static void restore() {
		if (Live.isOff()) Live.setOff(false);
		Live.restore(saved);
	}

	private static void say(String s, boolean chat) {
		System.out.println("[Imagine Metal] " + s);
		if (chat) Live.chat(s);
	}
}
