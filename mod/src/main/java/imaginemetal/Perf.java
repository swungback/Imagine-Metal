package imaginemetal;

/**
 * A small timer for our own per-frame work on the CPU (the render thread's time inside each step, not the GPU's), logged every 10
 * seconds with the frame rate, so a slow stretch in the game can be traced to a step or ruled out. One line in latest.log:
 * "[Imagine Metal] perf: 143 fps, frame 0.09 ms, shadows 0.35 ms, light 0.05 ms, bloom 0.04 ms", then the GPU's time per pass
 * (PassTimes): "[Imagine Metal] perf: gpu 24.1 ms (...): terrain 12.30, shadow maps 4.10, ...".
 */
final class Perf {
	static final int FRAME = 0, SHADOWS = 1, LIGHT = 2, BLOOM = 3;
	private static final String[] NAMES = {"frame", "shadows", "light", "bloom"};
	private static final long[] NANOS = new long[NAMES.length];
	private static final long[] MAX = new long[NAMES.length];
	private static long frames, since;
	// (fix19) frame intervals (µs) this window, the render thread's CPU time, GC, waits: where a slow frame's time went
	private static final int[] INTERVALS = new int[8192];
	private static int nInt;
	private static long lastFrameNs, lastCpu = -1, lastGcMs = -1, lastGcN = -1, cpuWin;
	private static final java.lang.management.ThreadMXBean TMX = java.lang.management.ManagementFactory.getThreadMXBean();
	private static int spikesLogged;

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

	/**
	 * (fix21) Pauses only: ZGC's "Cycles" beans and G1's "Concurrent GC" count work done beside the game, not time it stood
	 * still (fix19's line summed them: "GC 44 in 7751 ms" per 10 s under ZGC was its concurrent cycles).
	 */
	private static boolean pause(java.lang.management.GarbageCollectorMXBean g) {
		String n = g.getName();
		return !n.contains("Cycles") && !n.contains("Concurrent");
	}

	private static long gcMs() {
		long t = 0;
		for (var g : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) if (pause(g)) t += Math.max(0, g.getCollectionTime());
		return t;
	}

	private static long gcN() {
		long t = 0;
		for (var g : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) if (pause(g)) t += Math.max(0, g.getCollectionCount());
		return t;
	}

	private static final String[] GROUPS = {"render", "server", "MetaLODS", "game workers", "C2ME", "other"};
	private static final java.util.HashMap<Long, Long> LAST_ALLOC = new java.util.HashMap<>();
	private static long lastAllocNs;

	private static int group(String name) {
		if (name.equals("Render thread")) return 0;
		if (name.equals("Server thread")) return 1;
		if (name.startsWith("MetaLODS")) return 2;
		if (name.startsWith("Worker-Main") || name.startsWith("Worker-")) return 3;
		if (name.toLowerCase(java.util.Locale.ROOT).contains("c2me")) return 4;
		return 5;
	}

	/** (fix21) Allocation rate by thread group over the window, MB/s (what the GC has to keep up with, and who makes it). */
	private static String allocLine(long now) {
		if (!(TMX instanceof com.sun.management.ThreadMXBean sun)) return "";
		try {
			long[] ids = sun.getAllThreadIds();
			long[] bytes = sun.getThreadAllocatedBytes(ids);
			java.lang.management.ThreadInfo[] info = sun.getThreadInfo(ids, 0);
			double[] mb = new double[GROUPS.length];
			java.util.HashMap<Long, Long> next = new java.util.HashMap<>();
			for (int i = 0; i < ids.length; i++) {
				if (bytes[i] < 0 || info[i] == null) continue;
				next.put(ids[i], bytes[i]);
				Long prev = LAST_ALLOC.get(ids[i]);
				if (lastAllocNs != 0) mb[group(info[i].getThreadName())] += (bytes[i] - (prev != null ? prev : 0)) / 1e6;
			}
			LAST_ALLOC.clear();
			LAST_ALLOC.putAll(next);
			double secs = lastAllocNs != 0 ? (now - lastAllocNs) / 1e9 : 0;
			lastAllocNs = now;
			if (secs <= 0) return "";
			StringBuilder b = new StringBuilder("; allocation MB/s:");
			double total = 0;
			for (int g = 0; g < GROUPS.length; g++) {
				total += mb[g];
				b.append(String.format(java.util.Locale.ROOT, " %s %.0f", GROUPS[g], mb[g] / secs));
			}
			b.append(String.format(java.util.Locale.ROOT, ", total %.0f; heap used %.0f of %.0f MB", total / secs,
				(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1e6, Runtime.getRuntime().maxMemory() / 1e6));
			return b.toString();
		} catch (RuntimeException e) {
			return "";
		}
	}

	/** (fix19) "[Imagine Metal] frames: ..." every 10 s: the spread of frame times and where the render thread's time went. */
	private static void framesLine(long now) {
		int n = nInt;
		if (n < 2) return;
		int[] v = java.util.Arrays.copyOf(INTERVALS, n);
		java.util.Arrays.sort(v);
		long sum = 0;
		int over20 = 0, over34 = 0, over50 = 0;
		for (int x : v) {
			sum += x;
			if (x > 20_000) over20++;
			if (x > 34_000) over34++;
			if (x > 50_000) over50++;
		}
		double wallMs = sum / 1000.0 / n;
		long[] w = mcopt.metal.WaitStats.take();
		long gc = gcMs(), gcn = gcN();
		long dGc = lastGcMs < 0 ? 0 : gc - lastGcMs, dGcN = lastGcN < 0 ? 0 : gcn - lastGcN;
		lastGcMs = gc;
		lastGcN = gcn;
		double lods = imaginemetal.metalods.MetaLods.frameNanos / 1e6 / n, lodsMax = imaginemetal.metalods.MetaLods.frameMaxNanos / 1e6;
		imaginemetal.metalods.MetaLods.frameNanos = 0;
		imaginemetal.metalods.MetaLods.frameMaxNanos = 0;
		double cpu = cpuWin / 1e6 / n;
		double waits = 0;
		for (int s = 0; s < 5; s++) waits += w[s] / 1e6 / n;
		System.out.println(String.format(java.util.Locale.ROOT,
			"[Imagine Metal] frames: %d, ms p50 %.1f p90 %.1f p99 %.1f max %.1f; over 20 ms %.0f%%, over 34 ms %.0f%%, over 50 ms %d; "
				+ "per frame (avg %.1f ms): render thread on the CPU %.1f, waiting %.1f (GPU in flight %.1f, fence %.1f, vsync/drawable %.1f, pacer %.1f, drain %.1f), "
				+ "off the CPU otherwise %.1f; longest waits: GPU %.1f, vsync %.1f; GC pauses %d in %d ms; MetaLODS on the render thread %.2f (max %.1f)%s",
			n, v[n / 2] / 1000.0, v[(int) (n * 0.9)] / 1000.0, v[Math.min(n - 1, (int) (n * 0.99))] / 1000.0, v[n - 1] / 1000.0,
			100.0 * over20 / n, 100.0 * over34 / n, over50, wallMs, cpu, waits, w[0] / 1e6 / n, w[1] / 1e6 / n, w[2] / 1e6 / n, w[3] / 1e6 / n,
			w[4] / 1e6 / n, Math.max(0, wallMs - cpu - waits), w[5] / 1e6, w[7] / 1e6, dGcN, dGc, lods, lodsMax, allocLine(now)));
		nInt = 0;
		cpuWin = 0;
		spikesLogged = 0;
	}

	/** Once a frame (ImagineFrame). */
	static void frame() {
		long now = System.nanoTime();
		if (since == 0) since = now;
		frames++;
		long cpuNow = TMX.isCurrentThreadCpuTimeSupported() ? TMX.getCurrentThreadCpuTime() : -1;
		if (lastFrameNs != 0) {
			long dt = now - lastFrameNs;
			if (nInt < INTERVALS.length) INTERVALS[nInt++] = (int) Math.min(Integer.MAX_VALUE, dt / 1000);
			long cpuDt = lastCpu >= 0 && cpuNow >= 0 ? cpuNow - lastCpu : 0;
			cpuWin += cpuDt;
			// one line for each very slow frame (at most 8 a window): its render-thread CPU against its wall time
			if (dt > 50_000_000L && spikesLogged < 8) {
				spikesLogged++;
				long gc = gcMs();
				System.out.println(String.format(java.util.Locale.ROOT,
					"[Imagine Metal] slow frame: %.1f ms, render thread on the CPU %.1f ms of it, GC pauses so far %d ms, MetaLODS this window max %.1f ms",
					dt / 1e6, cpuDt / 1e6, gc, imaginemetal.metalods.MetaLods.frameMaxNanos / 1e6));
			}
		}
		lastFrameNs = now;
		lastCpu = cpuNow;
		PassTimes.poll();
		if (now - since < 10_000_000_000L) return;
		StringBuilder b = new StringBuilder("[Imagine Metal] perf: ").append(Math.round(frames / ((now - since) / 1e9))).append(" fps");
		ImNative.gpuMs();  // (the old whole-command-buffer number, drained: it spans frames in flight and read 60-87 ms at 28 fps)
		for (int i = 0; i < NAMES.length; i++) {
			b.append(String.format(java.util.Locale.ROOT, ", %s %.2f ms (max %.1f)", NAMES[i], NANOS[i] / 1e6 / frames, MAX[i] / 1e6));
			NANOS[i] = 0;
			MAX[i] = 0;
		}
		System.out.println(b);
		framesLine(now);
		// the GPU's time per pass (PassTimes): where the frame goes
		if (!PassTimes.perf().empty()) System.out.println("[Imagine Metal] perf: " + PassTimes.perf().describe());
		PassTimes.perf().clear();
		frames = 0;
		since = now;
	}
}
