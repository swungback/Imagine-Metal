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
 * The sun/moon shadow map: one orthographic view along the light, centred on the camera and snapped to its own texel grid (so
 * shadow edges don't crawl while walking), with a radial warp that spends most of its texels near the player (Complementary's
 * idea; ours is lighting.metal imWarp / shadow.metal imShadowWarp).
 *
 * <p>Per frame: {@link #prepare} at LevelRenderer.render's start (ImagineFrame) fixes the light matrix the receivers use;
 * {@link #render} right before the level's frame graph runs culls the terrain from the light (OwnTerrain.shadowCull, mcopt's
 * own cull with the light's box) and draws the casters' depth (imshadow.m). Terrain drawn after it samples the map.
 */
public final class Shadows {
	/** imaginemetal.shadows (true). */
	public static final boolean ON = Engine.TERRAIN && Engine.flag("imaginemetal.shadows", true);
	/** Map size (texels) and the half-width it covers (blocks). */
	static final int RES = Math.clamp(Integer.getInteger("imaginemetal.shadowRes", 2048), 512, 8192);
	static final float RANGE = 160f;
	/** Warp strength: the share of the map's resolution pulled toward the centre (0 none). */
	static final float WARP = 0.85f;
	/** Depth range along the light (blocks before and behind the camera). */
	private static final float DEPTH = 256f;
	/** The light is kept at least this high for the projection (shadows of a grazing sun would stretch to infinity). */
	private static final float MIN_ELEVATION = 0.08f;
	private static final int FRAME_BYTES = 128;
	/**
	 * The light direction the map uses moves in steps of this angle (radians; the sun crosses one in ~0.8 s): drawn from a direction
	 * that changed every frame, the map's texels (leaf holes above all) shifted every frame and shadows shimmered even standing still.
	 */
	private static final double ANGLE_STEP = Math.toRadians(0.25);

	/** This frame's light clip (camera-relative world -> light clip, before the warp), column-major. */
	static final float[] MATRIX = new float[16];
	private static final Matrix4f CLIP = new Matrix4f();
	private static final float[] LIGHT = new float[3];
	private static boolean prepared, everRendered, failed;
	private static long state, atlas;
	private static int pipelines; // 1 ready, -1 failed

	private Shadows() {
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
		// snap the box's centre to the map's finest texel (at its centre, where the warp magnifies most)
		double step = 2.0 * RANGE / RES * (1.0 - WARP);
		double px = dot(cam.x, cam.y, cam.z, x), py = dot(cam.x, cam.y, cam.z, y);
		double dx = Math.floor(px / step) * step - px, dy = Math.floor(py / step) * step - py;
		float cx = (float) (dx * x[0] + dy * y[0]), cy = (float) (dx * x[1] + dy * y[1]), cz = (float) (dx * x[2] + dy * y[2]);
		CLIP.setOrtho(-RANGE, RANGE, -RANGE, RANGE, 0f, 2f * DEPTH, true)
			.lookAt(cx + (float) z[0] * DEPTH, cy + (float) z[1] * DEPTH, cz + (float) z[2] * DEPTH, cx, cy, cz, (float) y[0], (float) y[1], (float) y[2]);
		CLIP.get(MATRIX);
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
				if (!own.shadowCull(CLIP, LIGHT[0], LIGHT[1], LIGHT[2])) return;
				long[] b = own.shadowBuffers();
				if (b[0] == 0 || b[4] == 0) return;
				Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
				int bx = (int) Math.floor(cam.x), by = (int) Math.floor(cam.y), bz = (int) Math.floor(cam.z);
				long f = stack.ncalloc(16, FRAME_BYTES, 1);
				for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(f + i * 4L, MATRIX[i]);
				MemoryUtil.memPutInt(f + 64, bx);
				MemoryUtil.memPutInt(f + 68, by);
				MemoryUtil.memPutInt(f + 72, bz);
				MemoryUtil.memPutFloat(f + 80, (float) (bx - cam.x));
				MemoryUtil.memPutFloat(f + 84, (float) (by - cam.y));
				MemoryUtil.memPutFloat(f + 88, (float) (bz - cam.z));
				MemoryUtil.memPutFloat(f + 96, WARP);
				ImNative.shadowRender(enc, state, f, FRAME_BYTES, b[0], b[1], b[2], b[3], b[4], (int) b[5], atlas, 2.0f);
				everRendered = true;
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
