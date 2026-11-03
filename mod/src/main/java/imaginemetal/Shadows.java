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
 * {@link #NEAR_RANGE} blocks each way at full sharpness, the far one {@link #RANGE}. Each is snapped to its own world-fixed texel
 * grid, so a block casts the same texels wherever the player stands and however they look around: shadows don't shimmer while
 * walking (the earlier single map with a radial warp re-centred on the player re-rasterized every leaf at every step).
 *
 * <p>Per frame: {@link #prepare} at LevelRenderer.render's start (ImagineFrame) fixes the light matrices the receivers use;
 * {@link #render} right before the level's frame graph runs culls the terrain from the light for each cascade (OwnTerrain.shadowCull,
 * mcopt's own cull with the cascade's box, one slot each) and draws the casters' depth into the cascade's slice (imshadow.m).
 * Terrain drawn after it samples both.
 */
public final class Shadows {
	/** imaginemetal.shadows (true). */
	public static final boolean ON = Engine.TERRAIN && Engine.flag("imaginemetal.shadows", true);
	/** Each cascade's size (texels) and the half-width the far one covers (blocks). */
	static final int RES = (int) Config.number("imaginemetal.shadowRes", 2048, 512, 8192);
	static final float RANGE = Config.number("imaginemetal.shadowRange", 160, 64, 256);
	/** The near cascade's half-width (blocks): 32 at 2048 texels is 32 texels per block, two per texture pixel. */
	static final float NEAR_RANGE = 32f;
	/** Depth range along the light (blocks before and behind the camera). */
	private static final float DEPTH = 256f;
	/** The light is kept at least this high for the projection (shadows of a grazing sun would stretch to infinity). */
	private static final float MIN_ELEVATION = 0.08f;
	private static final int FRAME_BYTES = 128;
	/**
	 * The light direction the maps use moves in steps of this angle (radians; the sun crosses one in ~0.15 s). Not every frame (the
	 * maps re-rasterized every frame shimmered), and not in big steps either (0.25 degrees: the leaf pattern visibly hopped every
	 * ~0.8 s): small steps that, with the receivers' filter, read as the shadows creeping with the sun.
	 */
	private static final double ANGLE_STEP = Math.toRadians(0.045);

	/** This frame's light clips (camera-relative world -> cascade clip), column-major: [0] near, [1] far. */
	static final float[][] MATRIX = {new float[16], new float[16]};
	private static final Matrix4f[] CLIP = {new Matrix4f(), new Matrix4f()};
	private static final float[] LIGHT = new float[3];
	private static boolean prepared, everRendered, failed;
	private static long state, atlas;
	private static int pipelines; // 1 ready, -1 failed

	private Shadows() {
	}

	/** The receivers' normal offset for cascade c (blocks): about one and a half of its texels. */
	static float normalOffset(int c) {
		return 1.5f * 2f * (c == 0 ? NEAR_RANGE : RANGE) / RES;
	}

	/** The block atlas as the terrain's last opaque draw bound it (the cutout casters' test reads it). */
	public static void atlas(long texture) {
		atlas = texture;
	}

	/** Whether the receivers may sample the map this frame (ImFrame.shadow.y). */
	static boolean valid() {
		return ON && prepared && everRendered && !failed;
	}

	/**
	 * This frame's light matrix. lightDir: unit direction toward the light; dim: 0 overworld, 1 Nether (no shadows), 2 End.
	 */
	static void prepare(float[] lightDir, int dim) {
		prepared = false;
		if (!ON || failed || dim == 1) return;
		Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
		// the sun and moon move in the x-y plane (the End's light is fixed): quantize the angle there
		double angle = Math.atan2(lightDir[1], lightDir[0]);
		angle = Math.round(angle / ANGLE_STEP) * ANGLE_STEP;
		double r = Math.hypot(lightDir[0], lightDir[1]);
		double lx = r * Math.cos(angle), ly = Math.max(r * Math.sin(angle), MIN_ELEVATION), lz = lightDir[2];
		double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
		lx /= ll;
		ly /= ll;
		lz /= ll;
		// the light's basis: z toward the light, x across (the sun path lies in the x-y plane, so world z is never parallel)
		double[] z = {lx, ly, lz}, x = cross(new double[] {0, 0, 1}, z);
		if (len(x) < 1e-4) x = cross(new double[] {1, 0, 0}, z);
		normalize(x);
		double[] y = cross(z, x);
		// each cascade's centre snapped to its own texel grid in the light's plane (world-fixed: the camera's world position is
		// projected, not its offset), so moving never shifts a caster's texels
		double px = dot(cam.x, cam.y, cam.z, x), py = dot(cam.x, cam.y, cam.z, y);
		for (int c = 0; c < 2; c++) {
			float range = c == 0 ? NEAR_RANGE : RANGE;
			double step = 2.0 * range / RES;
			double dx = Math.floor(px / step) * step - px, dy = Math.floor(py / step) * step - py;
			float cx = (float) (dx * x[0] + dy * y[0]), cy = (float) (dx * x[1] + dy * y[1]), cz = (float) (dx * x[2] + dy * y[2]);
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
