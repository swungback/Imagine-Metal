package imaginemetal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import mcopt.metal.MetalBridge;
import mcopt.metal.own.OwnTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The sun/moon shadows: two cascades, each an orthographic view along the light around the camera. The near one covers
 * {@link #NEAR_RANGE} blocks each way at full sharpness, the far one Settings.shadowRange. Each is snapped to its own world-fixed texel
 * grid, so a block casts the same texels wherever the player stands and however they look around: shadows don't shimmer while
 * walking (the earlier single map with a radial warp re-centred on the player re-rasterized every leaf at every step).
 *
 * <p>Per frame: {@link #prepare} at LevelRenderer.render's start (ImagineFrame) fixes the light matrices the receivers use;
 * {@link #render} right before the level's frame graph runs culls the terrain from the light for each cascade (OwnTerrain.shadowCull,
 * mcopt's own cull with the cascade's box, one slot each) and draws the casters' depth into the cascade's slice (imshadow.m).
 * Terrain drawn after it samples both.
 *
 * <p>The far cascade is kept from frame to frame (Settings.shadowFarCache): it is drawn again only when the sun has turned a
 * step of its own, the camera has gone {@link #FAR_MOVE} blocks from where it was drawn, a section inside its box changed
 * ({@link #terrainChanged}), its range changed or it is {@link #FAR_MAX_AGE_NS} old. In between its map is read through the
 * transform it was drawn with, moved by how far the camera has gone since. The near cascade is drawn every frame, with the casters
 * that can't reach anything on screen left out ({@link #viewCull}, Settings.shadowNearViewCull).
 */
public final class Shadows {
	/** Each cascade's size (texels; read at startup). The far cascade's half-width is Settings.shadowRange (live). */
	static final int RES = (int) Config.number("imaginemetal.shadowRes", 2048, 512, 8192);
	/** The near cascade's half-width (blocks): 32 at 2048 texels is 32 texels per block, two per texture pixel. */
	static final float NEAR_RANGE = 32f;
	/** Depth range along the light (blocks before and behind the camera). */
	private static final float DEPTH = 256f;

	/**
	 * (Build C1) The far cascade's depth range: with a reach past ~180 blocks its square's corners (reach x 1.41 from the camera)
	 * lie farther along a low sun's light than 256, and what's out there fell out of the map: lit, no shadow. One and a half
	 * times the reach from 171 blocks up; the near cascade keeps 256 (its depth bias is tuned to it).
	 */
	private static float depth(int c) {
		return c == 0 ? DEPTH : Math.max(DEPTH, 1.5f * Settings.shadowRange);
	}
	/** The light is kept at least this high for the projection (shadows of a grazing sun would stretch to infinity). */
	private static final float MIN_ELEVATION = 0.08f;
	private static final int FRAME_BYTES = 128;
	/**
	 * The light direction the maps use moves in steps of the shadows' length, not of the sun's angle: the quantity stepped is the
	 * horizontal run of a shadow per block of caster height (cot of the elevation), in steps of 0.001, so an 8-block tree's shadow
	 * moves 0.008 blocks (a quarter of a near texel) per step at any time of day. At noon that is a step every ~0.2 s; at dusk,
	 * when shadows are long and really do move fast, it is about every frame. (Fixed angle steps were fine at noon but at a low sun
	 * each one threw long shadows a third of a block or more at once, which read as fast, jerky shadows at sunset.)
	 */
	private static final double RUN_STEP = 0.001;

	/** This frame's light clips (camera-relative world -> cascade clip), column-major: [0] near, [1] far. */
	static final float[][] MATRIX = {new float[16], new float[16]};
	private static final Matrix4f[] CLIP = {new Matrix4f(), new Matrix4f()};
	/** What each cascade's cull keeps: its clip, the near one's narrowed to what the view can see. */
	private static final Matrix4f[] CULL = {new Matrix4f(), new Matrix4f()};
	/** Each cascade's unit direction to the light (the far one's turns in coarser steps, so it differs a little). */
	private static final float[][] LIGHT = new float[2][3];
	/** Each cascade's grid anchor (world position, kept within half a texel of the camera across the light's plane). */
	private static final double[][] ANCHOR = new double[2][];
	private static boolean prepared, everRendered, failed;
	/** The camera has to go this far (blocks, summed over the axes) before the kept far cascade is drawn again around it. */
	private static final double FAR_MOVE = 8.0;
	/** The kept far cascade is drawn again at least this often, whatever else (a safety net for a change the checks missed). */
	private static final long FAR_MAX_AGE_NS = 1_000_000_000L;

	/** The far cascade as it was last drawn. */
	private static final class Far {
		final Matrix4f clip = new Matrix4f();
		/** the camera then, and the box's centre (world) */
		double camX, camY, camZ, ax, ay, az;
		/** the box's two axes across the light */
		final double[] x = new double[3], y = new double[3];
		double run;
		float range;
		/** the OwnTerrain its casters came from, the frame number of the section table they were culled from, when (System.nanoTime) */
		Object own;
		long ownFrame, drawnNs;
	}

	private static volatile Far far;
	/** this frame's far cascade, if it's drawn (becomes {@link #far} once it has been) */
	private static volatile Far farNext;
	/** the first OwnTerrain frame whose section table holds the latest change inside the kept far cascade's box */
	private static volatile long farChangeFrame;
	private static boolean skipFar, farDrawnLast;
	/**
	 * The near cascade every other frame (Settings.shadowNearAlternate): the frame after it was drawn, the same map serves again,
	 * its matrix moved by what the camera has moved since (as the kept far map's is). Its casters (a jungle's leaves, alpha-tested)
	 * are most of what the shadows cost, and nothing in the map moves between two frames but the camera.
	 */
	private static boolean skipNear, nearDrawnLast, nearKept, farPostponed;
	private static final Matrix4f nearClip = new Matrix4f();
	private static double nearCamX, nearCamY, nearCamZ;
	private static OwnTerrain nearOwn;
	private static int frameParity;
	private static long farDraws, farSkips, farLogNs;
	private static long state, atlas;
	private static int pipelines; // 1 ready, -1 failed

	private Shadows() {
	}

	/** The receivers' normal offset for cascade c (blocks): about one and a half of its texels. */
	static float normalOffset(int c) {
		return 1.5f * 2f * (c == 0 ? NEAR_RANGE : Settings.shadowRange) / RES;
	}

	/** The block atlas as the terrain's last opaque draw bound it (the cutout casters' test reads it). */
	public static void atlas(long texture) {
		atlas = texture;
	}

	/** Whether the receivers may sample the map this frame (ImFrame.shadow.y). */
	static boolean valid() {
		return Engine.TERRAIN && Settings.shadows && prepared && everRendered && !failed;
	}

	/**
	 * This frame's light matrix. lightDir: unit direction toward the light; dim: 0 overworld, 1 Nether (no shadows), 2 End.
	 */
	static void prepare(float[] lightDir, int dim) {
		prepared = false;
		if (!Engine.TERRAIN || !Settings.shadows || failed || dim == 1) return;
		Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
		// the sun and moon move in the x-y plane (the End's light is fixed): quantize the shadows' run along x per block of height.
		// The far cascade in steps as much coarser as its texels are (a quarter of ITS texel for an 8-block tree), so its kept map
		// lasts about a second at noon instead of a fifth; with the cache off it turns with the near one, as before.
		double ly0 = Math.max(lightDir[1], MIN_ELEVATION);
		boolean cache = Settings.shadowFarCache;
		double farStep = cache ? RUN_STEP * Math.max(1, Math.floor(Settings.shadowRange / NEAR_RANGE)) : RUN_STEP;
		double farRun = 0;
		Far next = new Far();
		for (int c = 0; c < 2; c++) {
			double st = c == 0 ? RUN_STEP : farStep;
			double run = Math.round(lightDir[0] / ly0 / st) * st;
			if (c == 1) farRun = run;
			double lx = run, ly = 1.0, lz = lightDir[2] / ly0;
			double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
			lx /= ll;
			ly /= ll;
			lz /= ll;
			// the light's basis: z toward the light, x across (the sun path lies in the x-y plane, so world z is never parallel)
			double[] z = {lx, ly, lz}, x = cross(new double[] {0, 0, 1}, z);
			if (len(x) < 1e-4) x = cross(new double[] {1, 0, 0}, z);
			normalize(x);
			double[] y = cross(z, x);
			// the cascade's centre: an anchor kept next to the camera that only ever moves by whole texels of its own grid (and
			// freely along the light), so the grid stays put in the world while walking. The grid hangs on the anchor, not on the
			// world origin: when the light turns a step, a grid through a point thousands of blocks away swung a random part of a
			// texel here and every edge re-rasterized at each step; through a point next to the player only what's above or below
			// it moves, by the true amount the shadows move.
			float range = c == 0 ? NEAR_RANGE : Settings.shadowRange;
			double step = 2.0 * range / RES;
			double[] a = ANCHOR[c];
			if (a == null) {
				a = ANCHOR[c] = new double[] {cam.x, cam.y, cam.z};
			} else {
				double ax = cam.x - a[0], ay = cam.y - a[1], az = cam.z - a[2];
				double kx = Math.round(dot(ax, ay, az, x) / step) * step, ky = Math.round(dot(ax, ay, az, y) / step) * step;
				double kz = dot(ax, ay, az, z);
				for (int i = 0; i < 3; i++) a[i] += kx * x[i] + ky * y[i] + kz * z[i];
			}
			float cx = (float) (a[0] - cam.x), cy = (float) (a[1] - cam.y), cz = (float) (a[2] - cam.z);
			float dep = depth(c);
			CLIP[c].setOrtho(-range, range, -range, range, 0f, 2f * dep, true)
				.lookAt(cx + (float) z[0] * dep, cy + (float) z[1] * dep, cz + (float) z[2] * dep, cx, cy, cz, (float) y[0], (float) y[1], (float) y[2]);
			CLIP[c].get(MATRIX[c]);
			CULL[c].set(CLIP[c]);
			LIGHT[c][0] = (float) lx;
			LIGHT[c][1] = (float) ly;
			LIGHT[c][2] = (float) lz;
			if (c == 0) {
				if (Settings.shadowNearViewCull) viewCull(cx, cy, cz, x, y, z, range);
			} else {
				next.clip.set(CLIP[1]);
				next.camX = cam.x;
				next.camY = cam.y;
				next.camZ = cam.z;
				next.ax = a[0];
				next.ay = a[1];
				next.az = a[2];
				System.arraycopy(x, 0, next.x, 0, 3);
				System.arraycopy(y, 0, next.y, 0, 3);
				next.run = run;
				next.range = range;
			}
		}
		// the far cascade: is the map kept from an earlier frame still good?
		frameParity ^= 1;
		Far k = far;
		OwnTerrain own = OwnTerrain.get();
		boolean usable = k != null && k.own == own && k.range == Settings.shadowRange
			// (Build C1: a sixteenth of the reach where that is more, 16 blocks at 256: the map covers far more than the camera moves)
			&& Math.abs(cam.x - k.camX) + Math.abs(cam.y - k.camY) + Math.abs(cam.z - k.camZ) < Math.max(FAR_MOVE, Settings.shadowRange / 16.0);
		skipNear = Settings.shadowNearAlternate && nearKept && nearDrawnLast && nearOwn == own
			&& Math.abs(cam.x - nearCamX) + Math.abs(cam.y - nearCamY) + Math.abs(cam.z - nearCamZ) < 2.0;
		if (skipNear) new Matrix4f(nearClip).translate((float) (cam.x - nearCamX), (float) (cam.y - nearCamY), (float) (cam.z - nearCamZ)).get(MATRIX[0]);
		boolean due;
		if (!usable) {
			due = true;
		} else if (cache) {
			long ownFrame = own == null ? 0 : own.frameNumber();
			long changed = farChangeFrame;
			due = k.run != farRun || (changed > k.ownFrame && ownFrame >= changed) || System.nanoTime() - k.drawnNs > FAR_MAX_AGE_NS;
			// (never two frames running while the old map still serves: a stream of changes costs what every other frame did)
			if (due && farDrawnLast && Settings.shadowFarAlternate) due = false;
			// (and not in the frame the near map is drawn, once: the two take turns)
			if (due && Settings.shadowNearAlternate && nearKept && !skipNear && !farPostponed) {
				due = false;
				farPostponed = true;
			} else {
				farPostponed = false;
			}
		} else {
			// (the cache off: every frame, or every other one with shadowFarAlternate, as it was before the cache)
			due = !(Settings.shadowFarAlternate && frameParity == 1 && k.run == farRun);
		}
		skipFar = !due;
		if (skipFar) {
			new Matrix4f(k.clip).translate((float) (cam.x - k.camX), (float) (cam.y - k.camY), (float) (cam.z - k.camZ)).get(MATRIX[1]);
			farNext = null;
		} else {
			farNext = next;
		}
		prepared = true;
	}

	/**
	 * A section's mesh was set or removed (OwnTerrain.setSlot; x, y, z its origin in blocks): if it lies in the kept far cascade's
	 * box, across the light (along it the box takes everything), that map is drawn again once the section table of OwnTerrain
	 * frame seenFrame, the first to hold the change, is the one the cull reads.
	 */
	public static void terrainChanged(long seenFrame, int x, int y, int z) {
		// (the map being drawn this frame too: a change between its prepare and its draw isn't in the table it's culled from)
		if (inBox(far, x, y, z) || inBox(farNext, x, y, z)) farChangeFrame = seenFrame;
	}

	private static boolean inBox(Far k, int x, int y, int z) {
		if (k == null) return false;
		double dx = x + 8 - k.ax, dy = y + 8 - k.ay, dz = z + 8 - k.az;
		double reach = k.range + 14.0;  // (a section's half diagonal)
		return Math.abs(dot(dx, dy, dz, k.x)) <= reach && Math.abs(dot(dx, dy, dz, k.y)) <= reach;
	}

	/**
	 * Narrows the near cascade's cull (CULL[0]) to the casters that can shadow something on screen. Everything that samples the
	 * near map (terrain, mobs, the shafts' rays, the contact trace) lies inside the camera's view, and a caster shadows only what
	 * lies behind it along the light, at the same place across it. So across the light the view's four corner rays, from the
	 * camera outward, bound what's needed: they span a wedge of the cascade's square (or all of it, when the view looks along the
	 * light), and the cull's box becomes the wedge's bounding rectangle plus a margin. The map itself, its texel grid and the
	 * receivers' matrix stay as they are; only fewer casters are drawn. (cx, cy, cz): the cascade's centre, camera-relative.
	 */
	private static void viewCull(float cx, float cy, float cz, double[] x, double[] y, double[] z, float range) {
		Matrix4f proj = ImagineFrame.camProj, view = ImagineFrame.camView;
		if (proj == null || view == null) return;
		// (a tenth wider than the projection: view bobbing, the TAA's jitter, a field of view on the move)
		double tx = 1.1 / Math.abs(proj.m00()), ty = 1.1 / Math.abs(proj.m11());
		if (!(tx > 0.01 && tx < 20.0 && ty > 0.01 && ty < 20.0)) return;
		if (!(Math.abs(view.determinant()) > 1e-6f)) return;
		Matrix4f inv = view.invert(new Matrix4f());
		// the camera across the light, in the cascade's square (within a texel of its centre)
		double ox = -(cx * x[0] + cy * x[1] + cz * x[2]), oy = -(cx * y[0] + cy * y[1] + cz * y[2]);
		double[] ang = new double[4];
		org.joml.Vector3f d = new org.joml.Vector3f();
		for (int i = 0; i < 4; i++) {
			inv.transformDirection(d.set((float) ((i & 1) == 0 ? -tx : tx), (float) ((i & 2) == 0 ? -ty : ty), -1f));
			double l = d.length();
			double px = dot(d.x, d.y, d.z, x), py = dot(d.x, d.y, d.z, y);
			// (a corner ray nearly along the light: its direction across it means nothing, keep the whole square)
			if (!(l > 1e-6) || Math.hypot(px, py) < 0.15 * l) return;
			ang[i] = Math.atan2(py, px);
		}
		java.util.Arrays.sort(ang);
		// the wedge is what's left of the circle after the widest gap between the four directions; a widest gap under half a turn
		// means they surround the camera (the view looks along the light): the whole square
		double gap = ang[0] + 2 * Math.PI - ang[3], start = ang[0];
		for (int i = 1; i < 4; i++) {
			if (ang[i] - ang[i - 1] > gap) {
				gap = ang[i] - ang[i - 1];
				start = ang[i];
			}
		}
		final double pad = 0.06;
		if (gap < Math.PI + 2 * pad + 0.05) return;
		start -= pad;
		double width = 2 * Math.PI - gap + 2 * pad;
		if (Math.abs(ox) >= range || Math.abs(oy) >= range) return;
		double l = ox, r = ox, b = oy, t = oy;
		// the wedge's two edges where they leave the square, and the square's corners inside the wedge
		double[] px = new double[6], py = new double[6];
		int n = 0;
		for (int i = 0; i < 2; i++) {
			double a = start + i * width, dx = Math.cos(a), dy = Math.sin(a);
			double sx = dx > 1e-9 ? (range - ox) / dx : dx < -1e-9 ? (-range - ox) / dx : Double.MAX_VALUE;
			double sy = dy > 1e-9 ? (range - oy) / dy : dy < -1e-9 ? (-range - oy) / dy : Double.MAX_VALUE;
			double s = Math.min(sx, sy);
			px[n] = ox + dx * s;
			py[n++] = oy + dy * s;
		}
		for (int i = 0; i < 4; i++) {
			double qx = (i & 1) == 0 ? -range : range, qy = (i & 2) == 0 ? -range : range;
			double rel = (Math.atan2(qy - oy, qx - ox) - start) % (2 * Math.PI);
			if (rel < 0) rel += 2 * Math.PI;
			if (rel <= width) {
				px[n] = qx;
				py[n++] = qy;
			}
		}
		for (int i = 0; i < n; i++) {
			l = Math.min(l, px[i]);
			r = Math.max(r, px[i]);
			b = Math.min(b, py[i]);
			t = Math.max(t, py[i]);
		}
		// (the margin: the receivers' normal offset and filter taps, things right at the camera, the hand)
		final double margin = 4.0;
		l = Math.max(l - margin, -range);
		r = Math.min(r + margin, range);
		b = Math.max(b - margin, -range);
		t = Math.min(t + margin, range);
		CULL[0].setOrtho((float) l, (float) r, (float) b, (float) t, 0f, 2f * DEPTH, true)
			.lookAt(cx + (float) z[0] * DEPTH, cy + (float) z[1] * DEPTH, cz + (float) z[2] * DEPTH, cx, cy, cz, (float) y[0], (float) y[1], (float) y[2]);
	}

	/** The casters' depth for this frame, before the level's passes. */
	public static void render() {
		if (!prepared || failed) return;
		long t0 = Perf.start();
		try {
			renderTimed();
		} finally {
			Perf.end(Perf.SHADOWS, t0);
		}
	}

	private static void renderTimed() {
		try {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) return;
			long enc = MetalBridge.enc(encoder);
			OwnTerrain own = OwnTerrain.get();
			if (own == null) return;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.ncalloc(1, 1024, 1);
				if (state == 0) {
					state = ImNative.shadowNew(MetalBridge.ctx(encoder), RES, err, 1024);
					if (state == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
				}
				if (pipelines == 0) {
					long lib = ImNative.terrainLibrary(own.ownHandle());
					if (ImNative.shadowPipelines(state, lib, OwnTerrain.compact(), OwnTerrain.fat(), err, 1024) != 0) {
						pipelines = -1;
						throw new IllegalStateException(MemoryUtil.memUTF8(err));
					}
					pipelines = 1;
				}
				Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
				int bx = (int) Math.floor(cam.x), by = (int) Math.floor(cam.y), bz = (int) Math.floor(cam.z);
				long f = stack.ncalloc(16, FRAME_BYTES, 1);
				MemoryUtil.memPutInt(f + 64, bx);
				MemoryUtil.memPutInt(f + 68, by);
				MemoryUtil.memPutInt(f + 72, bz);
				MemoryUtil.memPutFloat(f + 80, (float) (bx - cam.x));
				MemoryUtil.memPutFloat(f + 84, (float) (by - cam.y));
				MemoryUtil.memPutFloat(f + 88, (float) (bz - cam.z));
				boolean drew = false;
				boolean farDrawn = false;
				for (int c = 0; c < 2; c++) {
					if (c == 1 && skipFar) continue;
					if (c == 0 && skipNear) {
						nearDrawnLast = false;
						continue;
					}
					if (!own.shadowCull(c, CULL[c], LIGHT[c][0], LIGHT[c][1], LIGHT[c][2])) {
						if (c == 1) far = null;
						else nearKept = false;
						farDrawnLast = false;
						return;
					}
					long[] b = own.shadowBuffers(c);
					if (b[0] == 0 || b[4] == 0) {
						if (c == 1) far = null;
						else nearKept = false;
						farDrawnLast = false;
						return;
					}
					for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(f + i * 4L, MATRIX[c][i]);
					ImNative.shadowRender(enc, state, f, FRAME_BYTES, b[0], b[1], b[2], b[3], b[4], (int) b[5], atlas, 2.0f, c);
					drew = true;
					if (c == 0) {
						nearClip.set(CLIP[0]);
						nearCamX = cam.x;
						nearCamY = cam.y;
						nearCamZ = cam.z;
						nearOwn = own;
						nearKept = true;
						nearDrawnLast = true;
					}
					Far fn = farNext;
					if (c == 1 && fn != null) {
						fn.own = own;
						fn.ownFrame = own.frameNumber();
						fn.drawnNs = System.nanoTime();
						far = fn;
						farNext = null;
						farDrawn = true;
					}
				}
				farDrawnLast = farDrawn;
				if (farDrawn) farDraws++;
				else farSkips++;
				long now = System.nanoTime();
				if (now - farLogNs > 10_000_000_000L) {
					if (farLogNs != 0) {
						System.out.println("[Imagine Metal] shadows: far cascade drawn on " + farDraws + " of " + (farDraws + farSkips) + " frames");
					}
					farLogNs = now;
					farDraws = farSkips = 0;
				}
				everRendered |= drew;
			}
		} catch (Throwable t) {
			failed = true;
			System.out.println("[Imagine Metal] shadows off: " + t);
		}
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static double len(double[] v) {
		return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
	}

	private static void normalize(double[] v) {
		double l = len(v);
		v[0] /= l;
		v[1] /= l;
		v[2] /= l;
	}

	private static double dot(double x, double y, double z, double[] v) {
		return x * v[0] + y * v[1] + z * v[2];
	}
}
