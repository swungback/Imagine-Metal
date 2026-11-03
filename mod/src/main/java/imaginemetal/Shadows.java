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
 */
public final class Shadows {
	/** Each cascade's size (texels; read at startup). The far cascade's half-width is Settings.shadowRange (live). */
	static final int RES = (int) Config.number("imaginemetal.shadowRes", 2048, 512, 8192);
	/** The near cascade's half-width (blocks): 32 at 2048 texels is 32 texels per block, two per texture pixel. */
	static final float NEAR_RANGE = 32f;
	/** Depth range along the light (blocks before and behind the camera). */
	private static final float DEPTH = 256f;
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
	private static final float[] LIGHT = new float[3];
	/** Each cascade's grid anchor (world position, kept within half a texel of the camera across the light's plane). */
	private static final double[][] ANCHOR = new double[2][];
	private static boolean prepared, everRendered, failed;
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
		// the sun and moon move in the x-y plane (the End's light is fixed): quantize the shadows' run along x per block of height
		double ly0 = Math.max(lightDir[1], MIN_ELEVATION);
		double run = Math.round(lightDir[0] / ly0 / RUN_STEP) * RUN_STEP;
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
		// each cascade's centre: an anchor kept next to the camera that only ever moves by whole texels of its own grid (and freely
		// along the light), so the grid stays put in the world while walking. The grid hangs on the anchor, not on the world origin:
		// when the light turns a step, a grid through a point thousands of blocks away swung a random part of a texel here and every
		// edge re-rasterized at each step; through a point next to the player only what's above or below it moves, by the true
		// amount the shadows move.
		for (int c = 0; c < 2; c++) {
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
			CLIP[c].setOrtho(-range, range, -range, range, 0f, 2f * DEPTH, true)
				.lookAt(cx + (float) z[0] * DEPTH, cy + (float) z[1] * DEPTH, cz + (float) z[2] * DEPTH, cx, cy, cz, (float) y[0], (float) y[1], (float) y[2]);
			CLIP[c].get(MATRIX[c]);
		}
		LIGHT[0] = (float) lx;
		LIGHT[1] = (float) ly;
		LIGHT[2] = (float) lz;
		prepared = true;
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
				for (int c = 0; c < 2; c++) {
					if (!own.shadowCull(c, CLIP[c], LIGHT[0], LIGHT[1], LIGHT[2])) return;
					long[] b = own.shadowBuffers(c);
					if (b[0] == 0 || b[4] == 0) return;
					for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(f + i * 4L, MATRIX[c][i]);
					ImNative.shadowRender(enc, state, f, FRAME_BYTES, b[0], b[1], b[2], b[3], b[4], (int) b[5], atlas, 2.0f, c);
					drew = true;
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
