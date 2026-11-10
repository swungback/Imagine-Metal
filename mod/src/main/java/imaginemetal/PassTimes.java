package imaginemetal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import mcopt.metal.MetalBridge;

/**
 * The GPU's time per pass (native imtime.m): every few frames each encoder of the frame is timestamped on the GPU and booked under
 * the pass it draws. Java says which pass the game is drawing now (tag), at the level renderer's steps (LevelRendererMixin);
 * our own passes book themselves natively. Read once a frame into windows: the perf line's (Perf, every 10 s) and the
 * benchmark's (one per step).
 */
public final class PassTimes {
	// (the order of imtime's IMT_* tags, mcmetal.h)
	public static final int MISC = 0, SHADOW = 1, LIGHT = 2, SKY = 3, TERRAIN = 4, ENTITIES = 5, TRANSLUCENT = 6, OVERLAY = 7, WEATHER = 8,
		CAPTURE = 9, SHAFTS = 10, AO = 11, BLOOM = 12, RESOLVE = 13, TAA = 14, HUD = 15, PRESENT = 16, CULL = 17, CLOUDS = 18;
	static final int TAGS = 24;
	static final String[] NAMES = {"uploads/lightmap", "shadow maps", "light grid", "sky", "terrain", "entities", "translucent", "outline/overlay",
		"weather/particles", "reflection copies", "shafts", "ao", "bloom", "resolve", "taa", "hand+gui", "present", "culling", "clouds",
		"?", "?", "?", "?", "?"};
	/** -Dimaginemetal.passTimes=false turns the sampling off (it splits the main pass on a sampled frame, a few tenths of a ms). */
	static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("imaginemetal.passTimes", "true"));

	/** A window of sums: µs per tag, the frames' spans, frames, encoders. */
	public static final class Window {
		final double[] sum = new double[TAGS];
		double span, frames, encoders;

		void add(double[] v) {
			for (int t = 0; t < TAGS; t++) this.sum[t] += v[t];
			this.span += v[TAGS];
			this.frames += v[TAGS + 1];
			this.encoders += v[TAGS + 2];
		}

		public void clear() {
			java.util.Arrays.fill(this.sum, 0.0);
			this.span = this.frames = this.encoders = 0.0;
		}

		public boolean empty() {
			return this.frames < 1;
		}

		/** ms a frame on the GPU: the whole frame (first sample to last). */
		public double frameMs() {
			return this.frames > 0 ? this.span / this.frames / 1000.0 : 0.0;
		}

		public double ms(int tag) {
			return this.frames > 0 ? this.sum[tag] / this.frames / 1000.0 : 0.0;
		}

		/** "gpu 24.1 ms: terrain 12.3, shadow maps 4.1, ..." the passes from the most expensive, those under 0.05 ms left out. */
		public String describe() {
			if (this.empty()) return "gpu (no samples)";
			List<Integer> order = new ArrayList<>();
			for (int t = 0; t < TAGS; t++) if (this.ms(t) >= 0.05) order.add(t);
			order.sort((a, b) -> Double.compare(this.ms(b), this.ms(a)));
			StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "gpu %.1f ms (%d frames sampled", this.frameMs(), Math.round(this.frames)));
			b.append(String.format(Locale.ROOT, ", %.0f encoders each):", this.encoders / Math.max(this.frames, 1)));
			for (int i = 0; i < order.size(); i++) {
				int t = order.get(i);
				b.append(i == 0 ? " " : ", ").append(NAMES[t]).append(String.format(Locale.ROOT, " %.2f", this.ms(t)));
			}
			return b.toString();
		}
	}

	private static final Window PERF = new Window(), BENCH = new Window();
	private static final MemorySegment OUT = Arena.global().allocate(ValueLayout.JAVA_DOUBLE, TAGS + 3);
	private static final double[] V = new double[TAGS + 3];
	private static boolean enabled, failed;
	private static Object encoder;
	private static long enc;

	private PassTimes() {
	}

	private static boolean ready() {
		if (!ON || failed) return false;
		if (enc != 0) return true;
		try {
			encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) {
				failed = true;
				return false;
			}
			enc = MetalBridge.enc(encoder);
			ImNative.timeEnable(true);
			enabled = true;
			return true;
		} catch (Throwable t) {
			failed = true;
			System.out.println("[Imagine Metal] pass timing off: " + t);
			return false;
		}
	}

	/** The game's passes from here on are booked under tag. */
	public static void tag(int tag) {
		if (!ready()) return;
		try {
			// (2: a sampled frame's open render encoder was cut for the new tag: the pass's state bound again)
			if (ImNative.timeTag(enc, tag, MetalBridge.inRenderPass(encoder)) == 2) MetalBridge.restorePass(encoder);
		} catch (Throwable t) {
			failed = true;
			System.out.println("[Imagine Metal] pass timing off: " + t);
		}
	}

	/** Once a frame (Perf.frame): this frame's completed samples into the windows. */
	static void poll() {
		if (!enabled || failed) return;
		ImNative.timeRead(OUT.address());
		for (int i = 0; i < V.length; i++) V[i] = OUT.getAtIndex(ValueLayout.JAVA_DOUBLE, i);
		if (V[TAGS + 1] <= 0 && V[TAGS + 2] <= 0) return;
		PERF.add(V);
		BENCH.add(V);
	}

	static Window perf() {
		return PERF;
	}

	/** The benchmark's window: cleared at the start of a step's measurement, read at its end. */
	static Window bench() {
		return BENCH;
	}
}
