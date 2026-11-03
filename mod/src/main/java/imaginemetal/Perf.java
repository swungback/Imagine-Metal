package imaginemetal;

/**
 * A small timer for our own per-frame work on the CPU (the render thread's time inside each step, not the GPU's), logged every 10
 * seconds with the frame rate, so a slow stretch in the game can be traced to a step or ruled out. One line in latest.log:
 * "[Imagine Metal] perf: 143 fps, frame 0.09 ms, shadows 0.35 ms, light 0.05 ms, bloom 0.04 ms".
 */
final class Perf {
	static final int FRAME = 0, SHADOWS = 1, LIGHT = 2, BLOOM = 3;
	private static final String[] NAMES = {"frame", "shadows", "light", "bloom"};
	private static final long[] NANOS = new long[NAMES.length];
	private static final long[] MAX = new long[NAMES.length];
	private static long frames, since;

	private Perf() {
	}

	static long start() {
		return System.nanoTime();
	}

	static void end(int what, long t0) {
		long d = System.nanoTime() - t0;
		NANOS[what] += d;
		if (d > MAX[what]) MAX[what] = d;
	}

	/** Once a frame (ImagineFrame). */
	static void frame() {
		long now = System.nanoTime();
		if (since == 0) since = now;
		frames++;
		if (now - since < 10_000_000_000L) return;
		StringBuilder b = new StringBuilder("[Imagine Metal] perf: ").append(Math.round(frames / ((now - since) / 1e9))).append(" fps");
		for (int i = 0; i < NAMES.length; i++) {
			b.append(String.format(java.util.Locale.ROOT, ", %s %.2f ms (max %.1f)", NAMES[i], NANOS[i] / 1e6 / frames, MAX[i] / 1e6));
			NANOS[i] = 0;
			MAX[i] = 0;
		}
		System.out.println(b);
		frames = 0;
		since = now;
	}
}
