package imaginemetal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings, Advanced, "Benchmark": with the player standing still, each feature in turn is switched off for a few seconds and
 * the frame rate measured, then everything is put back as it was. The results go to the log (and the chat): which feature costs
 * how many frames per second here, on this Mac, so the optimizing goes where the time actually goes.
 */
public final class Benchmark {
	private record Step(String name, Runnable off) {
	}

	private static final double SETTLE = 1.5, MEASURE = 3.5;
	private static List<Step> steps;
	private static int index;
	private static long stepStart, frames, measureStart;
	private static final List<String> results = new ArrayList<>();
	private static double baseline;
	private static boolean[] saved;
	private static int savedWater;

	private Benchmark() {
	}

	public static boolean running() {
		return steps != null;
	}

	public static void start() {
		saved = new boolean[] {Settings.shadows, Settings.ssao, Settings.shafts, Settings.pbr, Settings.taa, Settings.coloredLight,
			Settings.entityLight, Settings.bloom, Settings.bloomFog, Settings.clouds, Settings.glassReflections, Settings.glassSun, Settings.lightFog};
		savedWater = Settings.waterStyle;
		steps = new ArrayList<>();
		steps.add(new Step("everything on", () -> {
		}));
		steps.add(new Step("shadows off", () -> Settings.shadows = false));
		steps.add(new Step("ambient occlusion off", () -> Settings.ssao = false));
		steps.add(new Step("light shafts off", () -> Settings.shafts = false));
		steps.add(new Step("PBR (reflections) off", () -> Settings.pbr = false));
		steps.add(new Step("TAA off", () -> Settings.taa = false));
		steps.add(new Step("coloured light off", () -> Settings.coloredLight = false));
		steps.add(new Step("sunlight through glass off", () -> Settings.glassSun = false));
		steps.add(new Step("light fog off", () -> Settings.lightFog = false));
		steps.add(new Step("mobs and items lit off", () -> Settings.entityLight = false));
		steps.add(new Step("bloom off", () -> Settings.bloom = false));
		steps.add(new Step("bloom fog off", () -> Settings.bloomFog = false));
		steps.add(new Step("clouds off", () -> Settings.clouds = false));
		steps.add(new Step("water and glass reflections off", () -> {
			Settings.waterStyle = Math.min(Settings.waterStyle, 1);
			Settings.glassReflections = false;
		}));
		steps.add(new Step("everything above off", () -> {
			Settings.shadows = Settings.ssao = Settings.shafts = Settings.pbr = Settings.taa = Settings.coloredLight = false;
			Settings.entityLight = Settings.bloom = Settings.bloomFog = Settings.clouds = Settings.glassReflections = false;
		}));
		index = -1;
		results.clear();
		say("Benchmark: stand still, about " + Math.round(steps.size() * (SETTLE + MEASURE)) + " seconds...");
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
			if (index == 0) baseline = fps;
			String line = String.format(Locale.ROOT, "%-34s %6.1f fps (%+.1f, %.2f ms per frame %s)", steps.get(index).name(), fps,
				fps - baseline, Math.abs(1000.0 / Math.max(baseline, 1) - 1000.0 / Math.max(fps, 1)), fps >= baseline ? "saved" : "more");
			results.add(line);
			System.out.println("[Imagine Metal] benchmark: " + line);
			next();
		}
	}

	private static void finish() {
		steps = null;
		restore();
		say("benchmark done");
	}

	private static void restore() {
		Settings.shadows = saved[0];
		Settings.ssao = saved[1];
		Settings.shafts = saved[2];
		Settings.pbr = saved[3];
		Settings.taa = saved[4];
		Settings.coloredLight = saved[5];
		Settings.entityLight = saved[6];
		Settings.bloom = saved[7];
		Settings.bloomFog = saved[8];
		Settings.clouds = saved[9];
		Settings.glassReflections = saved[10];
		Settings.glassSun = saved[11];
		Settings.lightFog = saved[12];
		Settings.waterStyle = savedWater;
	}

	private static void say(String s) {
		System.out.println("[Imagine Metal] " + s);
	}
}
