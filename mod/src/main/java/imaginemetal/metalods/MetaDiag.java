package imaginemetal.metalods;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * (fix15, diagnostics only: changes nothing the game does) Where the CPU goes while MetaLODS works, every 10 s in the log:
 * the machine's and this game's CPU, the game's threads' CPU by group, garbage collection, and what the MetaLODS workers are
 * doing (sampled 20 times a second: running, blocked on a lock, waiting, and where in the code).
 */
final class MetaDiag {
	private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
	private final Map<Long, Long> lastCpu = new HashMap<>();
	private long lastNs, lastProc;
	private final Map<String, Long> lastGc = new HashMap<>();
	/** Sampler: per state and per top frame of the MetaLODS workers. */
	private final Map<String, Integer> states = new TreeMap<>(), frames = new HashMap<>(), lockOwners = new HashMap<>();
	private int samples, rescan;
	private long[] workerIds;
	private volatile boolean closed;

	MetaDiag() {
		Thread t = new Thread(this::sample, "MetaLODS diag sampler");
		t.setDaemon(true);
		t.start();
	}

	void close() {
		this.closed = true;
	}

	private void sample() {
		while (!this.closed) {
			try {
				Thread.sleep(50);
				if (this.workerIds == null || this.workerIds.length == 0 || (++this.rescan & 255) == 0) {
					java.util.List<Long> ids = new java.util.ArrayList<>();
					for (ThreadInfo ti : THREADS.getThreadInfo(THREADS.getAllThreadIds())) if (ti != null && ti.getThreadName().startsWith("MetaLODS worker")) ids.add(ti.getThreadId());
					this.workerIds = ids.stream().mapToLong(Long::longValue).toArray();
				}
				// (the workers only: a per-thread handshake each, not the whole game's threads)
				ThreadInfo[] infos = THREADS.getThreadInfo(this.workerIds, 24);
				synchronized (this) {
					for (ThreadInfo ti : infos) {
						if (ti == null || !ti.getThreadName().startsWith("MetaLODS worker")) continue;
						this.samples++;
						String st = ti.getThreadState().toString();
						StackTraceElement[] s = ti.getStackTrace();
						if (st.equals("TIMED_WAITING") && s.length > 0 && s[0].getMethodName().equals("sleep")) st = "IDLE (no job)";
						this.states.merge(st, 1, Integer::sum);
						if (ti.getLockOwnerName() != null) this.lockOwners.merge(String.valueOf(ti.getLockName()) + " held by " + ti.getLockOwnerName(), 1, Integer::sum);
						if (st.startsWith("IDLE")) continue;
						// the top frame, and the first frame in MetaLODS' own code (what it was doing)
						String top = s.length > 0 ? s[0].getClassName().replaceAll("^.*\\.", "") + "." + s[0].getMethodName() : "?";
						String own = "";
						for (StackTraceElement e : s) {
							String c = e.getClassName();
							if (c.startsWith("mcopt.") || c.startsWith("imaginemetal.")) {
								own = c.replaceAll("^.*\\.", "") + "." + e.getMethodName();
								break;
							}
						}
						this.frames.merge(st.charAt(0) + " " + own + " <- " + top, 1, Integer::sum);
					}
				}
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				// (diagnostics never break the game)
			}
		}
	}

	private static String group(String name) {
		if (name.equals("Render thread")) return "render";
		if (name.equals("Server thread")) return "server";
		if (name.startsWith("MetaLODS worker")) return "MetaLODS";
		String n = name.toLowerCase(java.util.Locale.ROOT);
		if (n.contains("c2me")) return "C2ME";
		if (n.startsWith("worker-main")) return "Worker-Main";
		if (n.contains("mesh") || n.contains("chunk")) return "chunk/mesh";
		if (n.contains("io-worker") || n.contains("io ")) return "IO";
		return "other";
	}

	/** One line of where the time went since the last call (render thread, every 10 s). */
	String line(int workers) {
		try {
			long now = System.nanoTime();
			var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
			long proc = os.getProcessCpuTime();
			double machine = os.getCpuLoad();
			int cores = os.getAvailableProcessors();
			Map<String, Long> by = new TreeMap<>(), others = new HashMap<>();
			Map<Long, Long> seen = new HashMap<>();
			long javaSum = 0;
			for (ThreadInfo ti : THREADS.getThreadInfo(THREADS.getAllThreadIds())) {
				if (ti == null) continue;
				long c = THREADS.getThreadCpuTime(ti.getThreadId());
				if (c < 0) continue;
				seen.put(ti.getThreadId(), c);
				long d = c - this.lastCpu.getOrDefault(ti.getThreadId(), this.lastNs == 0 ? c : 0L);
				if (d <= 0) continue;
				String g = group(ti.getThreadName());
				by.merge(g, d, Long::sum);
				// (fix16: name the "other" threads, digits folded: which mods' threads they are)
				if (g.equals("other")) others.merge(ti.getThreadName().replaceAll("[0-9]+", "#"), d, Long::sum);
				javaSum += d;
			}
			this.lastCpu.clear();
			this.lastCpu.putAll(seen);
			StringBuilder gc = new StringBuilder();
			for (var b : ManagementFactory.getGarbageCollectorMXBeans()) {
				long t = b.getCollectionTime(), n = b.getCollectionCount();
				long pt = this.lastGc.getOrDefault(b.getName() + "t", t), pn = this.lastGc.getOrDefault(b.getName() + "n", n);
				this.lastGc.put(b.getName() + "t", t);
				this.lastGc.put(b.getName() + "n", n);
				gc.append(String.format(java.util.Locale.ROOT, "%s %d in %d ms; ", b.getName(), n - pn, t - pt));
			}
			var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
			double wall = this.lastNs == 0 ? 0 : (now - this.lastNs) / 1e9;
			StringBuilder s = new StringBuilder("[MetaLODS] cpu: ");
			if (wall > 0) {
				s.append(String.format(java.util.Locale.ROOT, "machine %.0f%% of %d cores; this game %.2f cores (Java threads %.2f: ", machine * 100, cores,
					(proc - this.lastProc) / 1e9 / wall, javaSum / 1e9 / wall));
				for (var e : by.entrySet()) s.append(String.format(java.util.Locale.ROOT, "%s %.2f, ", e.getKey(), e.getValue() / 1e9 / wall));
				s.append(String.format(java.util.Locale.ROOT, "the rest is GC/JIT/native); GC: %sheap %d of %d MB", gc, heap.getUsed() >> 20, heap.getMax() >> 20));
				var o = new java.util.ArrayList<>(others.entrySet());
				o.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
				s.append("; other threads: ");
				for (int k = 0; k < Math.min(8, o.size()); k++) s.append(String.format(java.util.Locale.ROOT, "%s %.2f, ", o.get(k).getKey(), o.get(k).getValue() / 1e9 / wall));
			} else {
				s.append("(first sample)");
			}
			this.lastNs = now;
			this.lastProc = proc;
			synchronized (this) {
				if (this.samples > 0) {
					s.append("\n[MetaLODS] workers (").append(this.samples).append(" samples of ").append(workers).append("): ");
					for (var e : this.states.entrySet()) s.append(String.format(java.util.Locale.ROOT, "%s %.0f%%, ", e.getKey(), 100.0 * e.getValue() / this.samples));
					var top = new java.util.ArrayList<>(this.frames.entrySet());
					top.sort((a, b) -> b.getValue() - a.getValue());
					s.append("\n[MetaLODS] worker frames: ");
					for (int i = 0; i < Math.min(14, top.size()); i++) s.append(String.format(java.util.Locale.ROOT, "%.1f%% %s | ", 100.0 * top.get(i).getValue() / this.samples, top.get(i).getKey()));
					if (!this.lockOwners.isEmpty()) {
						var locks = new java.util.ArrayList<>(this.lockOwners.entrySet());
						locks.sort((a, b) -> b.getValue() - a.getValue());
						s.append("\n[MetaLODS] worker locks: ");
						for (int i = 0; i < Math.min(6, locks.size()); i++) s.append(String.format(java.util.Locale.ROOT, "%.1f%% %s | ", 100.0 * locks.get(i).getValue() / this.samples, locks.get(i).getKey()));
					}
				}
				this.samples = 0;
				this.states.clear();
				this.frames.clear();
				this.lockOwners.clear();
			}
			return s.toString();
		} catch (Throwable t) {
			return "[MetaLODS] cpu: unavailable (" + t + ")";
		}
	}
}
