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
 * Every feature step is measured between two "everything on" windows and compared with their average: the frame time drifts
 * by several ms over a few minutes (chunks finishing, the Mac warming up), and one reference at the start made features that do
 * nothing here (aurora, stars by day) look like 3.5 ms each.
 */
public final class Benchmark {
	/** settle: seconds to wait before measuring (0: SETTLE). */
	private record Step(String name, Runnable off, double settle) {
		Step(String name, Runnable off) {
			this(name, off, 0.0);
		}
	}

	private record Result(String name, double fps, double ms) {
	}

	private static final double SETTLE = 1.5, MEASURE = 4.0;  // (3 s windows swung by a few fps either way)
	private static final String REF = "everything on";
	/** A timing step, not a feature: the terrain's encoder cut into its parts (leaf depth, solid, leaf colour) to time them apart. */
	private static final String SPLIT = "(timing) terrain cut into its parts";
	private static List<Step> steps;
	private static int index;
	private static long stepStart, frames, measureStart;
	private static final List<Result> results = new ArrayList<>();
	private static Map<Field, Object> saved;
	private static boolean wasOff;
	/** The render distance step: Minecraft's own setting before it (-1: untouched). */
	private static int savedDistance = -1;
	private static final String DISTANCE = "render distance 8";
	private static final double[] refPass = new double[PassTimes.TAGS];
	private static double refPassFrame, refPassIdle;
	private static int refPassN;

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
		steps.add(new Step(SPLIT, () -> PassTimes.splitTerrain = true));
		steps.add(new Step("leaf depth pre-pass off", () -> Settings.leafPrepass = false));
		steps.add(new Step("shadows off", () -> Settings.shadows = false));
		steps.add(new Step("shadow range 96", () -> Settings.shadowRange = Math.min(Settings.shadowRange, 96f)));
		steps.add(new Step("far shadow map drawn every other frame (not kept)", () -> Settings.shadowFarCache = false));
		steps.add(new Step("near shadow map drawn every frame", () -> Settings.shadowNearAlternate = false));
		steps.add(new Step("air pass off (the haze in every terrain fragment)", () -> Settings.airPass = false));
		steps.add(new Step("near shadow casters culled to the view (experimental, off)", () -> Settings.shadowNearViewCull = true));
		steps.add(new Step("ambient occlusion off", () -> Settings.ssao = false));
		steps.add(new Step("light shafts off", () -> Settings.shafts = false));
		steps.add(new Step("PBR (reflections, highlights) off", () -> Settings.pbr = false));
		steps.add(new Step("generated normals off", () -> Settings.normals = 0f));
		steps.add(new Step("parallax off", () -> Settings.parallax = false));
		steps.add(new Step("TAA off", () -> Settings.taa = false));
		steps.add(new Step("render scale 100% (every pixel drawn)", () -> Settings.renderScale = 100));
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
		// (last: the chunks beyond 8 are dropped and take a while to come back, so the reference window after it waits longer)
		steps.add(new Step(DISTANCE, Benchmark::distance8, 4.0));
		List<Step> ab = new ArrayList<>();
		boolean slow = false;
		for (Step st : steps) {
			if (!ab.isEmpty() || !st.name().equals(REF)) ab.add(new Step(REF, () -> {
			}, slow ? 12.0 : 0.0));
			if (!st.name().equals(REF)) ab.add(st);
			slow = st.name().equals(DISTANCE);
		}
		ab.add(new Step(REF, () -> {
		}, slow ? 12.0 : 0.0));
		steps = ab;
		index = -1;
		results.clear();
		java.util.Arrays.fill(refPass, 0.0);
		refPassFrame = refPassIdle = 0.0;
		refPassN = 0;
		double total = 0.0;
		for (Step st : steps) total += (st.settle() > 0.0 ? st.settle() : SETTLE) + MEASURE;
		say("Benchmark: stand still and don't touch anything, about " + Math.round(total) + " seconds...", true);
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
		double settle = steps.get(index).settle();
		if (t < (settle > 0.0 ? settle : SETTLE)) return;
		if (frames < 0) {
			frames = 0;
			measureStart = now;
			PassTimes.bench().clear();
			return;
		}
		frames++;
		double m = (now - measureStart) / 1e9;
		if (m >= MEASURE) {
			double fps = frames / m;
			Result r = new Result(steps.get(index).name(), fps, 1000.0 * m / Math.max(frames, 1));
			results.add(r);
			System.out.println("[Imagine Metal] benchmark: " + String.format(Locale.ROOT, "%-36s %6.1f fps %6.2f ms", r.name, r.fps, r.ms));
			// (and where the GPU's time went in that window, per pass)
			PassTimes.Window w = PassTimes.bench();
			if (!w.empty()) {
				System.out.println("[Imagine Metal] benchmark passes: " + String.format(Locale.ROOT, "%-36s ", r.name) + w.describe());
				if (r.name.equals(REF)) {
					refPassN++;
					for (int tg = 0; tg < PassTimes.TAGS; tg++) refPass[tg] += w.ms(tg);
					refPassFrame += w.frameMs();
					refPassIdle += w.idleMs();
				}
			}
			next();
		}
	}

	private static void finish() {
		steps = null;
		restore();
		if (results.isEmpty()) return;
		// each feature against the average of the "everything on" windows either side of it
		List<Result> rest = new ArrayList<>();
		double refSum = 0.0;
		int refN = 0;
		for (int i = 0; i < results.size(); i++) {
			Result r = results.get(i);
			if (r.name.equals(REF)) {
				refSum += r.ms;
				refN++;
				continue;
			}
			if (r.name.equals(SPLIT)) continue;  // (its cost is the cuts themselves; its passes line is the point)
			double before = i > 0 && results.get(i - 1).name.equals(REF) ? results.get(i - 1).ms : Double.NaN;
			double after = i + 1 < results.size() && results.get(i + 1).name.equals(REF) ? results.get(i + 1).ms : before;
			if (Double.isNaN(before)) before = after;
			if (Double.isNaN(before)) continue;
			// (the render distance step: only the window before it; the one after may still be loading its chunks back)
			if (r.name.equals(DISTANCE)) after = before;
			rest.add(new Result(r.name, r.fps, (before + after) * 0.5 - r.ms));  // (ms here: the saving)
		}
		rest.sort((a, b) -> Double.compare(b.ms, a.ms));  // (the biggest saving first)
		double base = refN > 0 ? refSum / refN : results.get(0).ms;
		say(String.format(Locale.ROOT, "benchmark: everything on %.0f fps (%.1f ms a frame, averaged). Cost of each feature:", 1000.0 / base, base), true);
		int shown = 0;
		for (Result r : rest) {
			String line = String.format(Locale.ROOT, "  %-36s %+5.1f ms  (%.0f fps)", r.name, r.ms, r.fps);
			System.out.println("[Imagine Metal] benchmark: " + line);
			if (shown++ < 10) Live.chat(line.trim());
		}
		if (refPassN > 0) {
			// the GPU's time per pass with everything on (the reference windows averaged), the most expensive first
			List<Integer> order = new ArrayList<>();
			for (int t = 0; t < PassTimes.TAGS; t++) if (refPass[t] / refPassN >= 0.05) order.add(t);
			order.sort((a, b) -> Double.compare(refPass[b], refPass[a]));
			StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "benchmark: GPU %.1f ms a frame with everything on (idle %.1f):",
				refPassFrame / refPassN, refPassIdle / refPassN));
			for (int i = 0; i < order.size(); i++) {
				int t = order.get(i);
				b.append(i == 0 ? " " : ", ").append(PassTimes.NAMES[t]).append(String.format(Locale.ROOT, " %.1f", refPass[t] / refPassN));
			}
			say(b.toString(), true);
		}
		say("benchmark done (all results in latest.log)", true);
		if (wasOff) Live.setOff(true);
	}

	/** The render distance step: Minecraft's render distance to 8 chunks (put back by restore). */
	private static void distance8() {
		try {
			var o = net.minecraft.client.Minecraft.getInstance().options.renderDistance();
			int now = o.get();
			if (now <= 8) return;
			savedDistance = now;
			o.set(8);
		} catch (Throwable t) {
			System.out.println("[Imagine Metal] benchmark: render distance step skipped: " + t);
		}
	}

	private static void restore() {
		if (savedDistance > 0) {
			try {
				net.minecraft.client.Minecraft.getInstance().options.renderDistance().set(savedDistance);
			} catch (Throwable ignored) {
			}
			savedDistance = -1;
		}
		PassTimes.splitTerrain = false;
		if (Live.isOff()) Live.setOff(false);
		Live.restore(saved);
	}

	private static void say(String s, boolean chat) {
		System.out.println("[Imagine Metal] " + s);
		if (chat) Live.chat(s);
	}
}
