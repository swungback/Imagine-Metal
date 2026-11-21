package imaginemetal;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Cold-teleport benchmark (test harness, inert unless config/imaginemetal-coldtp.properties says enabled=true AND names a world
 * folder starting with "IM-coldtp", so it can only ever open a disposable copy). Opens that world, waits, teleports the player to
 * a fixed unexplored spot with a fixed camera, flying off (the fall guard holds it), and times for each chunk of the ring around
 * the destination: when the integrated server has it FULL, when the client has it, when the real terrain draws it whole (the
 * MetaLODS hand-over mask). Also frame times, server ticks, CPU by thread group, thread states and hot frames, the player's
 * height (fall guard), and a JFR recording if the runtime has JFR. Report: logs/coldtp-LABEL.txt (+ .jfr). Then quits.
 */
public final class ColdTp {
	private ColdTp() {
	}

	private static Path cfgFile;
	private static Properties cfg;
	private static volatile boolean on;
	private static String label, world;
	private static int dx, dz, ring;
	private static double ty;
	private static float yaw, pitch;
	private static double settle, timeout;
	// (fix24) fly: hover at the spot (no fall); shots: seconds after the teleport to grab the picture at (screenshots/coldtp-LABEL-Ns.png,
	// the HUD hidden); the run lasts until the last one
	private static boolean fly;
	/** (fix24) the day time set at the teleport (-1: left as the save has it), so runs on different copies see the same sun */
	private static long dayTime = -1;
	private static double[] shots = new double[0];
	/** (s1121) steps: "seconds:field=value" (comma separated), a MetaSettings (or "im." + Settings) field set on the render thread at
	 * that time after the teleport, so one run gives matched shots of the same view with a setting on and off */
	private static double[] stepAt = new double[0];
	private static String[] stepSet = new String[0];

	// timeline
	private static volatile long tpNanos;           // 0 until teleported
	private static volatile long joinNanos;
	private static long[] tServer, tClient, tDrawn;
	private static int n;
	private static volatile boolean finished;

	// frames (render thread)
	private static long lastFrame;
	private static final long[] frameMs = new long[200_000];   // microseconds
	private static final long[] frameAt = new long[200_000];   // ms since teleport
	private static int frames;
	private static final List<String> yLog = new ArrayList<>();
	private static long lastYLog;

	public static void init() {
		try {
			cfgFile = FabricLoader.getInstance().getConfigDir().resolve("imaginemetal-coldtp.properties");
			if (!Files.exists(cfgFile)) return;
			cfg = new Properties();
			try (Reader r = Files.newBufferedReader(cfgFile, StandardCharsets.UTF_8)) {
				cfg.load(r);
			}
			if (!"true".equals(cfg.getProperty("enabled", "false").trim())) return;
			world = cfg.getProperty("world", "").trim();
			if (!world.startsWith("IM-coldtp")) {
				System.out.println("[ColdTp] refusing: world folder must start with IM-coldtp (got '" + world + "')");
				return;
			}
			label = cfg.getProperty("label", "run").trim();
			dx = Integer.parseInt(cfg.getProperty("x", "4000").trim());
			dz = Integer.parseInt(cfg.getProperty("z", "-2500").trim());
			ty = Double.parseDouble(cfg.getProperty("y", "300").trim());
			yaw = Float.parseFloat(cfg.getProperty("yaw", "0").trim());
			pitch = Float.parseFloat(cfg.getProperty("pitch", "25").trim());
			settle = Double.parseDouble(cfg.getProperty("settle", "30").trim());
			timeout = Double.parseDouble(cfg.getProperty("timeout", "150").trim());
			ring = Integer.parseInt(cfg.getProperty("ring", "4").trim());
			fly = "true".equals(cfg.getProperty("fly", "false").trim());
			dayTime = Long.parseLong(cfg.getProperty("time", "-1").trim());
			String sh = cfg.getProperty("shots", "").trim();
			if (!sh.isEmpty()) shots = Arrays.stream(sh.split(",")).map(String::trim).filter(v -> !v.isEmpty()).mapToDouble(Double::parseDouble).sorted().toArray();
			String st = cfg.getProperty("steps", "").trim();
			if (!st.isEmpty()) {
				String[] parts = Arrays.stream(st.split(",")).map(String::trim).filter(v -> v.contains(":")).toArray(String[]::new);
				Arrays.sort(parts, (a, b) -> Double.compare(Double.parseDouble(a.substring(0, a.indexOf(':'))), Double.parseDouble(b.substring(0, b.indexOf(':')))));
				stepAt = new double[parts.length];
				stepSet = new String[parts.length];
				for (int k = 0; k < parts.length; k++) {
					stepAt[k] = Double.parseDouble(parts[k].substring(0, parts[k].indexOf(':')));
					stepSet[k] = parts[k].substring(parts[k].indexOf(':') + 1);
				}
			}
			int side = 2 * ring + 1;
			n = side * side;
			tServer = new long[n];
			tClient = new long[n];
			tDrawn = new long[n];
			Arrays.fill(tServer, -1);
			Arrays.fill(tClient, -1);
			Arrays.fill(tDrawn, -1);
			// one shot: switch the file off now, so a crash or a normal launch never repeats it
			cfg.setProperty("enabled", "false");
			try (Writer w = Files.newBufferedWriter(cfgFile, StandardCharsets.UTF_8)) {
				cfg.store(w, "ColdTp: switched off after starting run " + label);
			}
			on = true;
			Thread t = new Thread(ColdTp::drive, "ColdTp driver");
			t.setDaemon(true);
			t.start();
			System.out.println("[ColdTp] armed: " + label + " world " + world + " -> " + dx + "," + dz);
		} catch (IOException | RuntimeException e) {
			System.out.println("[ColdTp] config: " + e);
		}
	}

	/** (s1121) one step: a static boolean/int/float field of MetaSettings ("im.name": imaginemetal.Settings) set from text */
	private static void applyStep(String set) {
		try {
			String k = set.substring(0, set.indexOf('=')).trim(), v = set.substring(set.indexOf('=') + 1).trim();
			Class<?> owner = imaginemetal.metalods.MetaSettings.class;
			if (k.startsWith("im.")) {
				owner = Settings.class;
				k = k.substring(3);
			}
			var f = owner.getDeclaredField(k);
			f.setAccessible(true);
			Class<?> t = f.getType();
			if (t == boolean.class) f.setBoolean(null, Boolean.parseBoolean(v));
			else if (t == int.class) f.setInt(null, Integer.parseInt(v));
			else if (t == float.class) f.setFloat(null, Float.parseFloat(v));
			else if (t == double.class) f.setDouble(null, Double.parseDouble(v));
			System.out.println("[ColdTp] step " + set + " at " + ms(System.nanoTime()) + " ms");
		} catch (ReflectiveOperationException | RuntimeException e) {
			System.out.println("[ColdTp] step " + set + " failed: " + e);
		}
	}

	public static boolean on() {
		return on;
	}

	private static long ms(long nanos) {
		return (nanos - tpNanos) / 1_000_000L;
	}

	private static int cx0() {
		return dx >> 4;
	}

	private static int cz0() {
		return dz >> 4;
	}

	/** Render thread, each frame MetaLODS prepares (after the hand-over mask is made). */
	public static void frame() {
		if (!on || finished) return;
		long now = System.nanoTime();
		if (lastFrame != 0 && frames < frameMs.length) {
			frameMs[frames] = (now - lastFrame) / 1000L;
			frameAt[frames] = tpNanos == 0 ? -1 : ms(now);
			frames++;
		}
		lastFrame = now;
		if (tpNanos == 0) return;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;
		int side = 2 * ring + 1, i = 0;
		for (int z = -ring; z <= ring; z++) for (int x = -ring; x <= ring; x++, i++) {
			if (tClient[i] < 0 && level.getChunkSource().getChunk(cx0() + x, cz0() + z, false) != null) tClient[i] = ms(now);
			if (tDrawn[i] < 0 && imaginemetal.metalods.MetaLods.realDrawn(cx0() + x, cz0() + z)) tDrawn[i] = ms(now);
		}
		if (mc.player != null && now - lastYLog > 250_000_000L) {
			lastYLog = now;
			boolean under = level.getChunkSource().getChunk(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4, false) != null;
			yLog.add(String.format(Locale.ROOT, "%7d ms y %.1f vy %.2f chunkUnderLoaded %s flying %s", ms(now), mc.player.getY(),
				mc.player.getDeltaMovement().y, under, mc.player.getAbilities().flying));
		}
	}

	// ======================================================= driver
	private static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();
	private static final Map<String, long[]> cpuByGroupWindow = new TreeMap<>();   // group -> cpu ns per 5 s window
	private static final Map<Long, Long> lastCpu = new HashMap<>();
	private static final Map<String, int[]> states = new TreeMap<>();               // group -> RUNNABLE, BLOCKED, WAITING, TIMED_WAITING
	private static final Map<String, Map<String, Integer>> hot = new HashMap<>();   // group -> frame signature -> samples
	private static int stateSamples;
	private static final List<String> tickLog = new ArrayList<>();
	private static final int WINDOWS = 64;

	static String group(String name) {
		String g = name.replaceAll("[0-9]+", "#");
		if (g.startsWith("Worker-Main")) return "Worker-Main";
		if (g.contains("MetaLODS worker")) return "MetaLODS worker";
		return g;
	}

	private static void drive() {
		Minecraft mc = Minecraft.getInstance();
		try {
			// 1. open the world from the title screen
			// (any menu screen will do: the world is opened from whatever is showing once loading is over and has been for 3 s)
			long stable = 0, said = 0;
			while (true) {
				boolean ready = mc.gui != null && mc.gui.overlay() == null && mc.gui.screen() != null && mc.level == null;
				long now = System.nanoTime();
				if (now - said > 5_000_000_000L) {
					said = now;
					System.out.println("[ColdTp] waiting: screen " + (mc.gui == null ? "-" : mc.gui.screen() == null ? "null" : mc.gui.screen().getClass().getName())
						+ ", overlay " + (mc.gui == null ? "-" : String.valueOf(mc.gui.overlay())));
				}
				if (!ready) stable = 0;
				else if (stable == 0) stable = now;
				else if (now - stable > 3_000_000_000L) break;
				Thread.sleep(250);
			}
			mc.execute(() -> mc.createWorldOpenFlows().openWorld(world, () -> System.out.println("[ColdTp] open cancelled")));
			while (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) Thread.sleep(100);
			joinNanos = System.nanoTime();
			System.out.println("[ColdTp] joined " + world + ", settling " + settle + " s");
			IntegratedServer server = mc.getSingleplayerServer();
			final boolean pauseWas = mc.options.pauseOnLostFocus;
			mc.execute(() -> mc.options.pauseOnLostFocus = false);   // the server must not pause if the window loses focus
			Thread.sleep((long) (settle * 1000) - 3000);
			Object jfr = Jfr.start();
			Thread.sleep(3000);
			// 2. teleport (server thread), flying off, fixed camera
			final long[] done = {0};
			server.execute(() -> {
				ServerPlayer p = server.getPlayerList().getPlayers().get(0);
				ServerLevel sl = server.overworld();
				p.getAbilities().flying = fly;
				if (dayTime >= 0) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set " + dayTime);
				p.onUpdateAbilities();
				tpNanos = System.nanoTime();
				p.teleportTo(sl, dx + 0.5, ty, dz + 0.5, Set.of(), yaw, pitch, true);
				done[0] = 1;
			});
			while (tpNanos == 0) Thread.sleep(5);
			System.out.println("[ColdTp] teleported to " + dx + "," + ty + "," + dz);
			if (shots.length > 0) mc.execute(() -> {
				if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			});
			// 3. sample until the ring is drawn whole or the timeout
			int tick0 = server.getTickCount();
			long nextPoll = 0, nextCpu = 0, nextState = 0, lastTickLog = 0;
			final boolean[] polling = {false};
			long end = tpNanos + (long) (timeout * 1e9);
			long allDrawnAt = 0;
			int shot = 0, step = 0;
			while (System.nanoTime() < end) {
				long now = System.nanoTime();
				if (!polling[0] && now >= nextPoll) {
					polling[0] = true;
					nextPoll = now + 50_000_000L;
					server.execute(() -> {
						long t = System.nanoTime();
						var src = server.overworld().getChunkSource();
						int i = 0;
						for (int z = -ring; z <= ring; z++) for (int x = -ring; x <= ring; x++, i++) {
							if (tServer[i] < 0 && src.getChunkNow(cx0() + x, cz0() + z) != null) tServer[i] = ms(t);
						}
						polling[0] = false;
					});
				}
				if (now >= nextState) {
					nextState = now + 50_000_000L;
					sampleStates();
				}
				if (now >= nextCpu) {
					nextCpu = now + 1_000_000_000L;
					sampleCpu((int) Math.min(WINDOWS - 1, Math.max(0, ms(now) / 5000)));
				}
				if (now - lastTickLog > 1_000_000_000L) {
					lastTickLog = now;
					tickLog.add(String.format(Locale.ROOT, "%7d ms server ticks %d (expected %d) avg tick %.1f ms", ms(now), server.getTickCount() - tick0,
						ms(now) / 50, server.getAverageTickTimeNanos() / 1e6));
				}
				if (allDrawnAt == 0 && Arrays.stream(tDrawn).allMatch(v -> v >= 0)) allDrawnAt = now;
				if (shot < shots.length && now - tpNanos >= (long) (shots[shot] * 1e9)) {
					final String name = String.format(Locale.ROOT, "coldtp-%s-%04.1fs.png", label, shots[shot]);
					shot++;
					mc.execute(() -> {
						net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, message -> { });
						System.out.println("[ColdTp] shot " + name);
					});
				}
				if (step < stepAt.length && now - tpNanos >= (long) (stepAt[step] * 1e9)) {
					final String set = stepSet[step++];
					mc.execute(() -> applyStep(set));
				}
				if (allDrawnAt != 0 && now - allDrawnAt > 5_000_000_000L && shot >= shots.length && step >= stepAt.length) break;
				Thread.sleep(10);
			}
			finished = true;
			// (s1119) bench=key,key,...: the benchmark's steps at this spot, the camera held still, then the report and quit
			String bench = cfg.getProperty("bench", "").trim();
			if (!bench.isEmpty()) {
				double wait = Double.parseDouble(cfg.getProperty("benchWait", "20").trim());
				System.out.println("[ColdTp] bench: waiting " + wait + " s, then " + bench);
				Thread.sleep((long) (wait * 1000));
				Benchmark.MEASURE = Double.parseDouble(cfg.getProperty("benchMeasure", "5").trim());
				int repeat = Integer.parseInt(cfg.getProperty("benchRepeat", "1").trim());
				if ("true".equals(cfg.getProperty("benchShots", "false").trim())) {
					final int[] k = {0};
					Benchmark.shot = name -> {
						String file = String.format(Locale.ROOT, "coldtp-%s-b%02d-%s.png", label, k[0]++, name.replaceAll("[^A-Za-z0-9]+", "_"));
						net.minecraft.client.Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1, message -> { });
					};
				}
				final List<String> keys = "full".equals(bench) ? null : Arrays.asList(bench.split(","));
				mc.execute(() -> {
					if (keys == null) Benchmark.start();
					else Benchmark.startKeys(keys, repeat);
				});
				Thread.sleep(2000);
				long benchEnd = System.nanoTime() + (long) (Double.parseDouble(cfg.getProperty("benchTimeout", "900").trim()) * 1e9);
				while (Benchmark.running() && System.nanoTime() < benchEnd) Thread.sleep(250);
				System.out.println("[ColdTp] bench done" + (Benchmark.running() ? " (timed out)" : ""));
			}
			String base = "coldtp-" + label;
			Path logs = FabricLoader.getInstance().getGameDir().resolve("logs");
			Jfr.stop(jfr, logs.resolve(base + ".jfr"));
			Files.writeString(logs.resolve(base + ".txt"), report(), StandardCharsets.UTF_8);
			System.out.println("[ColdTp] report written: " + logs.resolve(base + ".txt"));
			if (!"false".equals(cfg.getProperty("quit", "true").trim())) {
				Thread.sleep(1000);
				mc.execute(() -> {
					mc.options.pauseOnLostFocus = pauseWas;
					if (shots.length > 0 && mc.gui.hud.isHidden()) mc.gui.hud.toggle();
					mc.stop();
				});
			}
		} catch (Throwable t) {
			System.out.println("[ColdTp] failed: " + t);
			t.printStackTrace();
		}
	}

	private static void sampleCpu(int window) {
		long[] ids = TMX.getAllThreadIds();
		ThreadInfo[] infos = TMX.getThreadInfo(ids, 0);
		for (int i = 0; i < ids.length; i++) {
			if (infos[i] == null) continue;
			long cpu = TMX.getThreadCpuTime(ids[i]);
			if (cpu < 0) continue;
			Long prev = lastCpu.put(ids[i], cpu);
			if (prev == null) continue;
			cpuByGroupWindow.computeIfAbsent(group(infos[i].getThreadName()), k -> new long[WINDOWS])[window] += cpu - prev;
		}
	}

	private static void sampleStates() {
		ThreadInfo[] all = TMX.dumpAllThreads(false, false, 40);
		stateSamples++;
		for (ThreadInfo ti : all) {
			String g = group(ti.getThreadName());
			if (!(g.equals("Server thread") || g.equals("Worker-Main") || g.equals("MetaLODS worker") || g.startsWith("c#me") || g.startsWith("C#ME")
				|| g.toLowerCase(Locale.ROOT).contains("c2me") || g.toLowerCase(Locale.ROOT).contains("c#me") || g.startsWith("IO-Worker") || g.equals("Render thread")
				|| g.toLowerCase(Locale.ROOT).contains("chunk") || g.toLowerCase(Locale.ROOT).contains("polymer"))) continue;
			int[] s = states.computeIfAbsent(g, k -> new int[5]);
			switch (ti.getThreadState()) {
				case RUNNABLE -> s[0]++;
				case BLOCKED -> s[1]++;
				case WAITING -> s[2]++;
				case TIMED_WAITING -> s[3]++;
				default -> s[4]++;
			}
			if (ti.getThreadState() == Thread.State.WAITING || ti.getThreadState() == Thread.State.TIMED_WAITING) {
				// idle pool threads: only record waits that aren't a pool's idle park
				StackTraceElement[] st = ti.getStackTrace();
				if (st.length > 0 && isIdle(st)) continue;
			}
			StackTraceElement[] st = ti.getStackTrace();
			String key = sig(st);
			if (g.equals("Server thread")) {
				// who made the server thread wait for a chunk: the frames below getChunkBlocking / getChunk
				int at = -1;
				for (int i = 0; i < st.length; i++) if (st[i].getMethodName().equals("getChunkBlocking") || st[i].getMethodName().startsWith("getChunk") && st[i].getClassName().endsWith("ServerChunkCache")) at = i;
				if (at >= 0) {
					StringBuilder b = new StringBuilder("WAIT-FOR-CHUNK by ");
					for (int i = at + 1; i < Math.min(st.length, at + 10); i++) {
						String c = st[i].getClassName();
						b.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber()).append(" < ");
					}
					key = b.toString();
				}
			}
			hot.computeIfAbsent(g, k -> new HashMap<>()).merge(ti.getThreadState().name().charAt(0) + " " + key, 1, Integer::sum);
		}
	}

	private static boolean isIdle(StackTraceElement[] st) {
		for (int i = 0; i < Math.min(st.length, 10); i++) {
			String c = st[i].getClassName();
			if (c.startsWith("java.util.concurrent.ForkJoinPool") && st[i].getMethodName().equals("awaitWork")) return true;
			if (c.startsWith("java.util.concurrent.ThreadPoolExecutor") && st[i].getMethodName().equals("getTask")) return true;
			if (c.contains("MetaWorld") && st[i].getMethodName().startsWith("lambda$new")) return false;
		}
		return false;
	}

	/** The first 5 frames below the JDK's own (park, wait, sleep...), joined. */
	private static String sig(StackTraceElement[] st) {
		StringBuilder b = new StringBuilder();
		int k = 0;
		for (StackTraceElement e : st) {
			String c = e.getClassName();
			if (k == 0 && (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun."))) {
				if (b.length() == 0) b.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName()).append(" | ");
				continue;
			}
			b.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
			if (++k >= 6) break;
			b.append(" < ");
		}
		return b.toString();
	}

	private static String report() {
		StringBuilder r = new StringBuilder();
		r.append("ColdTp ").append(label).append("  ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append('\n');
		r.append("world ").append(world).append(" dest ").append(dx).append(',').append(ty).append(',').append(dz).append(" yaw ").append(yaw).append(" pitch ").append(pitch)
			.append(" ring ").append(ring).append(" (").append(n).append(" chunks)\n");
		r.append("MetaLODS ").append(imaginemetal.metalods.MetaSettings.distance).append(" chunks; render distance ").append(Minecraft.getInstance().options.getEffectiveRenderDistance())
			.append("; sim ").append(Minecraft.getInstance().options.simulationDistance().get()).append('\n');
		int c = ring * (2 * ring + 1) + ring;   // centre index
		r.append(String.format(Locale.ROOT, "under player: server %d ms, client %d ms, drawn %d ms%n", tServer[c], tClient[c], tDrawn[c]));
		r.append(line("server FULL", tServer)).append(line("client has", tClient)).append(line("drawn whole", tDrawn));
		// 3x3 (the guard's chunk and its neighbours)
		r.append("\nper chunk (server/client/drawn, s), rows = z:\n");
		int i = 0;
		for (int z = -ring; z <= ring; z++) {
			for (int x = -ring; x <= ring; x++, i++) r.append(String.format(Locale.ROOT, " %4.1f/%4.1f/%4.1f", tServer[i] / 1e3, tClient[i] / 1e3, tDrawn[i] / 1e3));
			r.append('\n');
		}
		// frames
		r.append("\nframes after the teleport, by 5 s window: n, p50, p99, max (ms), >50 ms, >100 ms\n");
		for (int w = 0; w < WINDOWS; w++) {
			List<Long> v = new ArrayList<>();
			for (int f = 0; f < frames; f++) if (frameAt[f] >= w * 5000L && frameAt[f] < (w + 1) * 5000L) v.add(frameMs[f]);
			if (v.isEmpty()) continue;
			r.append(String.format(Locale.ROOT, "%4d-%3ds %s%n", w * 5, w * 5 + 5, stats(v)));
		}
		List<Long> all = new ArrayList<>(), pre = new ArrayList<>();
		for (int f = 0; f < frames; f++) (frameAt[f] >= 0 ? all : pre).add(frameMs[f]);
		r.append("all after: ").append(stats(all)).append('\n').append("before tp: ").append(stats(pre)).append('\n');
		r.append("\nCPU cores used by thread group, 5 s windows:\n");
		for (var e : cpuByGroupWindow.entrySet()) {
			long tot = Arrays.stream(e.getValue()).sum();
			if (tot < 300_000_000L) continue;
			r.append(String.format(Locale.ROOT, "%-34s", e.getKey().length() > 34 ? e.getKey().substring(0, 34) : e.getKey()));
			for (int w = 0; w < 30; w++) r.append(String.format(Locale.ROOT, " %4.1f", e.getValue()[w] / 5e9));
			r.append('\n');
		}
		r.append("\nthread states (samples every 50 ms, ").append(stateSamples).append("): runnable blocked waiting timed other\n");
		for (var e : states.entrySet()) r.append(String.format(Locale.ROOT, "%-30s %s%n", e.getKey(), Arrays.toString(e.getValue())));
		r.append("\nhot frames by group (state, frames; idle pool parks left out):\n");
		for (var e : new TreeMap<>(hot).entrySet()) {
			r.append("== ").append(e.getKey()).append('\n');
			e.getValue().entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(14)
				.forEach(h -> r.append(String.format(Locale.ROOT, "%6d %s%n", h.getValue(), h.getKey())));
		}
		r.append("\nPolymer look-ahead rays answered without waiting for a chunk: ").append(mcopt.metal.compat.ChunkWaitGuard.skipped.get()).append('\n');
		r.append("\nserver ticks:\n");
		for (String s : tickLog) r.append(s).append('\n');
		r.append("\nplayer (fall guard):\n");
		for (String s : yLog) r.append(s).append('\n');
		return r.toString();
	}

	private static String line(String what, long[] t) {
		long[] s = Arrays.stream(t).filter(v -> v >= 0).sorted().toArray();
		if (s.length == 0) return what + ": none\n";
		return String.format(Locale.ROOT, "%-12s first %6d  25%% %6d  50%% %6d  90%% %6d  last %6d ms  (%d of %d)%n", what, s[0], s[s.length / 4], s[s.length / 2],
			s[Math.min(s.length - 1, s.length * 9 / 10)], s[s.length - 1], s.length, t.length);
	}

	private static String stats(List<Long> us) {
		if (us.isEmpty()) return "-";
		long[] s = us.stream().mapToLong(Long::longValue).sorted().toArray();
		long g50 = Arrays.stream(s).filter(v -> v > 50_000).count(), g100 = Arrays.stream(s).filter(v -> v > 100_000).count();
		return String.format(Locale.ROOT, "n %5d p50 %6.1f p99 %6.1f max %7.1f  >50 %3d  >100 %3d", s.length, s[s.length / 2] / 1e3, s[Math.min(s.length - 1, (int) (s.length * 0.99))] / 1e3,
			s[s.length - 1] / 1e3, g50, g100);
	}

	/** JFR through reflection-free code in its own class: a runtime without the jdk.jfr module only loses the recording. */
	static final class Jfr {
		static Object start() {
			try {
				jdk.jfr.Recording r = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
				r.disable("jdk.ObjectAllocationSample");
				r.disable("jdk.ObjectAllocationInNewTLAB");
				r.disable("jdk.ObjectAllocationOutsideTLAB");
				r.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(10));
				r.enable("jdk.NativeMethodSample").withPeriod(java.time.Duration.ofMillis(20));
				r.enable("jdk.JavaMonitorEnter").withThreshold(java.time.Duration.ofMillis(5));
				r.enable("jdk.ThreadPark").withThreshold(java.time.Duration.ofMillis(5));
				r.setToDisk(true);
				r.start();
				return r;
			} catch (Throwable t) {
				System.out.println("[ColdTp] no JFR: " + t);
				return null;
			}
		}

		static void stop(Object o, Path to) {
			if (o == null) return;
			try {
				jdk.jfr.Recording r = (jdk.jfr.Recording) o;
				r.stop();
				r.dump(to);
				r.close();
			} catch (Throwable t) {
				System.out.println("[ColdTp] JFR dump: " + t);
			}
		}
	}
}
