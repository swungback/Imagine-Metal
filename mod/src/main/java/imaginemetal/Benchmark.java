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

	private static final double SETTLE = 1.5;
	/** seconds measured per window (4; ColdTp's bench sets its own, benchMeasure) */
	static double MEASURE = 4.0;  // (3 s windows swung by a few fps either way)
	/** (s1119) MetaLODS' far shadows before a step changed them (null: untouched) */
	private static Boolean savedFarShadows;
	/** (s1119) MetaLODS' detail and far detail before a step changed them (null: untouched) */
	private static int[] savedDetail;
	/** (s1119, ColdTp benchShots=true) called on the render thread at the end of each measured window, with its step's name */
	static java.util.function.Consumer<String> shot;
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
	private static final String BUSHY = "bushy leaf planes off (chunks rebuilt)", LEAFCULL = "leaf culling off (chunks rebuilt)";
	/** (s1120) bushy leaves and leaf culling before a step changed them (null: untouched); put back with a rebuild */
	private static boolean[] savedLeaves;
	/** (s1120) MetaLODS' shadow casters before a step changed them (null: untouched) */
	private static Boolean savedLodShadows;
	private static final double[] refPass = new double[PassTimes.TAGS];
	private static double refPassFrame, refPassIdle;
	private static int refPassN;

	private Benchmark() {
	}

	/** steps after which the land takes a while to come back as it was (the reference after them waits longer, and isn't used) */
	private static boolean slow(String name) {
		return name.equals(DISTANCE) || name.equals(BUSHY) || name.equals(LEAFCULL) || name.startsWith("MetaLODS far detail") || name.startsWith("MetaLODS detail 3");
	}

	public static boolean running() {
		return steps != null;
	}

	public static void start() {
		start(false);
	}

	/**
	 * (s1119, ColdTp's bench=) only the named steps, each between two "everything on" windows, the list run `repeat` times.
	 * Keys: lods_off, lods_vertex, lods_plain, farshadows_off, shadow128, shadow96, shadows_off, scale50, scale100, split, taa_off,
	 * ssao_off, shafts_off, deferred_off, airpass_off, bloom_off, clouds_off, pbr_off, normals_off, parallax_off, watertrace_off,
	 * leafprepass_off, plantshadows_off, sky_off, colouredlight_off, shaders_off, distance8. Unknown keys are logged and skipped.
	 */
	public static void startKeys(List<String> keys, int repeat) {
		List<Step> chosen = new ArrayList<>();
		for (int r = 0; r < Math.max(1, repeat); r++) for (String k : keys) {
			Step st = keyed(k.trim());
			if (st == null) System.out.println("[Imagine Metal] benchmark: unknown step key '" + k + "'");
			else chosen.add(st);
		}
		start(false, chosen);
	}

	private static Step keyed(String k) {
		return switch (k) {
			case "lods_off" -> new Step("MetaLODS not drawn", () -> imaginemetal.metalods.MetaLods.probe = 3);
			case "lods_vertex" -> new Step("MetaLODS vertex work only (no pixels)", () -> imaginemetal.metalods.MetaLods.probe = 2);
			case "lods_plain" -> new Step("MetaLODS shading skipped (plain colour)", () -> imaginemetal.metalods.MetaLods.probe = 1);
			case "lods_lightcheap" -> new Step("MetaLODS pixels lit cheaply (lighting skipped)", () -> imaginemetal.metalods.MetaLods.probe = 4);
			case "lods_notex" -> new Step("MetaLODS textures skipped (average colour)", () -> imaginemetal.metalods.MetaLods.probe = 5);
			case "fardetail4" -> new Step("MetaLODS far detail 4 (coarser past the full-detail ring)", () -> {
				savedDetail = new int[] {imaginemetal.metalods.MetaSettings.detail, imaginemetal.metalods.MetaSettings.farDetail};
				imaginemetal.metalods.MetaSettings.farDetail = 4;
			}, 25.0);
			case "detail3" -> new Step("MetaLODS detail 3 (full-detail ring to 192 blocks, below the menu's 4)", () -> {
				savedDetail = new int[] {imaginemetal.metalods.MetaSettings.detail, imaginemetal.metalods.MetaSettings.farDetail};
				imaginemetal.metalods.MetaSettings.detail = 3;
			}, 25.0);
			case "farshadows_off" -> new Step("MetaLODS far shadows off", () -> {
				savedFarShadows = imaginemetal.metalods.MetaSettings.farShadows;
				imaginemetal.metalods.MetaSettings.farShadows = false;
			});
			case "shadow128" -> new Step("shadow range 128", () -> Settings.shadowRange = Math.min(Settings.shadowRange, 128f));
			case "shadow96" -> new Step("shadow range 96", () -> Settings.shadowRange = Math.min(Settings.shadowRange, 96f));
			case "shadows_off" -> new Step("shadows off", () -> Settings.shadows = false);
			case "scale50" -> new Step("render scale 50%", () -> Settings.renderScale = 50, 2.5);
			case "scale100" -> new Step("render scale 100% (every pixel drawn)", () -> Settings.renderScale = 100, 2.5);
			case "split" -> new Step(SPLIT, () -> PassTimes.splitTerrain = true);
			case "taa_off" -> new Step("TAA off", () -> Settings.taa = false);
			case "ssao_off" -> new Step("ambient occlusion off", () -> Settings.ssao = false);
			case "shafts_off" -> new Step("light shafts off", () -> Settings.shafts = false);
			case "deferred_off" -> new Step("deferred lighting off (every terrain fragment lit, as Build B1)", () -> Settings.deferred = false);
			case "airpass_off" -> new Step("air pass off (the haze in every terrain fragment)", () -> Settings.airPass = false);
			case "bloom_off" -> new Step("bloom off", () -> Settings.bloom = false);
			case "clouds_off" -> new Step("clouds off", () -> Settings.clouds = false);
			case "pbr_off" -> new Step("PBR (reflections, highlights) off", () -> Settings.pbr = false);
			case "normals_off" -> new Step("generated normals off", () -> Settings.normals = 0f);
			case "parallax_off" -> new Step("parallax off", () -> Settings.parallax = false);
			case "watertrace_off" -> new Step("water and glass reflect only the sky (no screen trace)", () -> Settings.waterTrace = false);
			case "leafprepass_off" -> new Step("leaf depth pre-pass off", () -> Settings.leafPrepass = false);
			case "lodshadows_off" -> new Step("MetaLODS shadow casters off (s1120)", () -> {
				savedLodShadows = imaginemetal.metalods.MetaSettings.lodShadows;
				imaginemetal.metalods.MetaSettings.lodShadows = false;
			}, 3.0);
			// (s1120: what the real leaves' geometry costs: the chunks are rebuilt, so these wait longer and the reference after them too)
			case "bushy_off" -> new Step(BUSHY, () -> {
				savedLeaves = new boolean[] {Settings.bushyLeaves, Settings.leafCulling};
				Settings.bushyLeaves = false;
				SettingsScreen.rebuildChunks();
			}, 15.0);
			case "leafcull_off" -> new Step(LEAFCULL, () -> {
				savedLeaves = new boolean[] {Settings.bushyLeaves, Settings.leafCulling};
				Settings.leafCulling = false;
				SettingsScreen.rebuildChunks();
			}, 15.0);
			case "plantshadows_off" -> new Step("plant shadows off (C2: their own map slice, a second near pass)", () -> Settings.plantShadows = false);
			case "sky_off" -> new Step("our sky and air off", () -> Settings.sky = false);
			case "colouredlight_off" -> new Step("coloured light off", () -> Settings.coloredLight = false);
			case "shaders_off" -> new Step("shaders off (live, near vanilla)", () -> Live.setOff(true));
			case "distance8" -> new Step(DISTANCE, Benchmark::distance8, 4.0);
			default -> null;
		};
	}

	/** farOnly (fix19, the MetaLODS screen's button): only the far terrain's steps, about 40 seconds. */
	public static void start(boolean farOnly) {
		start(farOnly, null);
	}

	private static void start(boolean farOnly, List<Step> chosen) {
		wasOff = Live.isOff();
		if (wasOff) Live.setOff(false);
		saved = Live.snapshot();
		steps = new ArrayList<>();
		steps.add(new Step("everything on", () -> {
		}));
		if (chosen != null) {
			steps.addAll(chosen);
		} else if (farOnly) {
			steps.add(new Step("MetaLODS not drawn", () -> imaginemetal.metalods.MetaLods.probe = 3));
			steps.add(new Step("MetaLODS vertex work only (no pixels)", () -> imaginemetal.metalods.MetaLods.probe = 2));
			steps.add(new Step("MetaLODS shading skipped (plain colour)", () -> imaginemetal.metalods.MetaLods.probe = 1));
		} else {
		steps.add(new Step(SPLIT, () -> PassTimes.splitTerrain = true));
		steps.add(new Step("leaf depth pre-pass off", () -> Settings.leafPrepass = false));
		steps.add(new Step("shadows off", () -> Settings.shadows = false));
		steps.add(new Step("shadow range 96", () -> Settings.shadowRange = Math.min(Settings.shadowRange, 96f)));
		steps.add(new Step("far shadow map drawn every other frame (not kept)", () -> Settings.shadowFarCache = false));
		steps.add(new Step("near shadow map drawn every frame", () -> Settings.shadowNearAlternate = false));
		// (Build C1: what each of its changes costs; "off" = as Build B2)
		steps.add(new Step("shadow range 160 (as before C1)", () -> Settings.shadowRange = Math.min(Settings.shadowRange, 160f)));
		steps.add(new Step("soft far shadows off (C1)", () -> Settings.shadowSoftFar = false));
		steps.add(new Step("daylight under sky islands off (C1)", () -> Settings.shadowOpenAir = false));
		steps.add(new Step("clouds drawn before the land (C1 off)", () -> Settings.cloudsLate = false));
		steps.add(new Step("shaft veil fix off (C1)", () -> Settings.shaftVeilFix = false));
		steps.add(new Step("TAA steadier far away off (C1)", () -> Settings.taaFarSteady = false));
		// (Build C2: what each of its changes costs; "off" = as Build C1)
		steps.add(new Step("plant shadows off (C2: their own map slice, a second near pass)", () -> Settings.plantShadows = false));
		steps.add(new Step("far shadow fix off (C2)", () -> Settings.shadowFarFix = false));
		steps.add(new Step("shafts as C1 (C2's full shafts off)", () -> Settings.shaftsFull = false));
		// (what the water costs, part by part: the screen-space trace of its reflections, its waves (normals), its refraction)
		steps.add(new Step("water and glass reflect only the sky (no screen trace)", () -> Settings.waterTrace = false));
		steps.add(new Step("water waves off (Reimagined water, flat)", () -> Settings.waterStyle = Math.min(Settings.waterStyle, 1)));
		steps.add(new Step("air pass off (the haze in every terrain fragment)", () -> Settings.airPass = false));
		steps.add(new Step("deferred lighting off (every terrain fragment lit, as Build B1)", () -> Settings.deferred = false));
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
		// (fix19: the far terrain, part by part: MetaLods.probe; the vertex step shows what the quads cost before any pixel, the
		// shading step what ml_fs's body costs, not drawn what MetaLODS costs the frame in all)
		steps.add(new Step("MetaLODS not drawn", () -> imaginemetal.metalods.MetaLods.probe = 3));
		steps.add(new Step("MetaLODS vertex work only (no pixels)", () -> imaginemetal.metalods.MetaLods.probe = 2));
		steps.add(new Step("MetaLODS shading skipped (plain colour)", () -> imaginemetal.metalods.MetaLods.probe = 1));
		steps.add(new Step("shaders off (live, near vanilla)", () -> Live.setOff(true)));
		// (last: the chunks beyond 8 are dropped and take a while to come back, so the reference window after it waits longer)
		steps.add(new Step(DISTANCE, Benchmark::distance8, 4.0));
		}
		List<Step> ab = new ArrayList<>();
		boolean slow = false;
		for (Step st : steps) {
			if (!ab.isEmpty() || !st.name().equals(REF)) ab.add(new Step(REF, () -> {
			}, slow ? 25.0 : 0.0));
			if (!st.name().equals(REF)) ab.add(st);
			slow = slow(st.name());
		}
		ab.add(new Step(REF, () -> {
		}, slow ? 25.0 : 0.0));
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
			if (shot != null) shot.accept(r.name);
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
			if (slow(r.name)) after = before;
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
		imaginemetal.metalods.MetaLods.probe = 0;
		if (savedDetail != null) {
			imaginemetal.metalods.MetaSettings.detail = savedDetail[0];
			imaginemetal.metalods.MetaSettings.farDetail = savedDetail[1];
			savedDetail = null;
		}
		if (savedLodShadows != null) {
			imaginemetal.metalods.MetaSettings.lodShadows = savedLodShadows;
			savedLodShadows = null;
		}
		if (savedFarShadows != null) {
			imaginemetal.metalods.MetaSettings.farShadows = savedFarShadows;
			savedFarShadows = null;
		}
		if (Live.isOff()) Live.setOff(false);
		Live.restore(saved);
		if (savedLeaves != null) {
			Settings.bushyLeaves = savedLeaves[0];
			Settings.leafCulling = savedLeaves[1];
			savedLeaves = null;
			SettingsScreen.rebuildChunks();
		}
	}

	private static void say(String s, boolean chat) {
		System.out.println("[Imagine Metal] " + s);
		if (chat) Live.chat(s);
	}
}
