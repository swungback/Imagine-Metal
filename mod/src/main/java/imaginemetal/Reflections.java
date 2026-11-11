package imaginemetal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import mcopt.metal.MetalBridge;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Screen-space reflections, the cheap way: no extra full-screen pass. After the level has drawn, its picture and depth are copied
 * (imssr.m, with blurred smaller copies for rough surfaces); the next frame's terrain traces the reflections of its shiny pixels
 * through that copy, reprojected to where the camera was (lighting.metal imReflect). Only pixels that reflect trace anything.
 */
public final class Reflections {
	private static long state;
	private static boolean failed;
	/** This frame's terrain transform (camera-relative world -> clip) and camera position, noted by the terrain (OwnTerrain). */
	private static final Matrix4f clipNow = new Matrix4f();
	private static double nowX, nowY, nowZ;
	private static boolean nowSet;
	/** The history's: the frame it was copied from. */
	static final Matrix4f CLIP = new Matrix4f(), CLIP_INV = new Matrix4f();
	static double camX, camY, camZ;
	static boolean valid;

	private Reflections() {
	}

	/** (OwnTerrain, every frame it draws) the transform the terrain draws with this frame. */
	public static void noteClip(Matrix4fc clip, double x, double y, double z) {
		clipNow.set(clip);
		nowX = x;
		nowY = y;
		nowZ = z;
		nowSet = true;
	}

	/** Whether capture will copy this frame's depth (the End's sky is drawn behind it, Sky.drawEndLate). */
	static boolean willCapture() {
		return (Settings.pbr || Settings.waterStyle > 0 || Settings.glassReflections) && !failed && Engine.TERRAIN;
	}

	private static boolean underFailed;

	/** Before the translucent pass: the opaque picture and depth, for water's refraction and shore foam (ImFrame.toggles.w). */
	public static void captureUnder() {
		boolean water = Settings.waterFx && Settings.waterStyle > 0;
		// (the depth alone is also wanted by the mob shadows: their trace reads the depth before the water was drawn, so the
		// water's surface over shallows doesn't count as something between the seabed and the sun: dark straight-edged patches)
		boolean air = airPromised;
		airPromised = false;
		if (state == 0 || underFailed || !(water || Settings.mobShadows || air) || !Engine.TERRAIN) {
			underDepthReady = false;
			return;
		}
		try {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) return;
			var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			var color = main.getColorTexture();
			var depth = main.getDepthTexture();
			if (color == null || depth == null) return;
			MetalBridge.flushClear(encoder, color);
			MetalBridge.flushClear(encoder, depth);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.ncalloc(1, 1024, 1);
				// (the air pass: this frame's terrain transform inverted, so a pixel's depth gives its place)
				long airM = 0, lib = 0;
				var own = mcopt.metal.own.OwnTerrain.get();
				if (air && nowSet && own != null && Math.abs(clipNow.determinant()) > 1e-12f) {
					airM = stack.ncalloc(4, 16, 4);
					new Matrix4f(clipNow).invert().get(MemoryUtil.memFloatBuffer(airM, 16));
					lib = ImNative.terrainLibrary(own.ownHandle());
				}
				int rc = ImNative.underCapture(MetalBridge.enc(encoder), state, water ? MetalBridge.textureHandle(color) : 0L, MetalBridge.textureHandle(depth),
					airM != 0 && lib != 0 ? MetalBridge.textureHandle(color) : 0L, lib, airM, ImagineFrame.address(), ImagineFrame.SIZE, err, 1024);
				if (rc == 1) throw new IllegalStateException(MemoryUtil.memUTF8(err));
				// (2: the open pass was ended for the copy and reopened: its pipeline, buffers and scissor bound again)
				if ((rc & 2) != 0) MetalBridge.restorePass(encoder);
				if ((rc & 4) != 0) {
					airMisses = 0;
					if (!airLogged) {
						airLogged = true;
						System.out.println("[Imagine Metal] air pass on (the haze once per pixel, after the opaque world)");
					}
				} else if (air && airM != 0 && lib != 0 && ++airMisses == 30) {
					// (promised but not drawn for 30 frames: the terrain's own fragments take the air back)
					airOff = true;
					System.out.println("[Imagine Metal] air pass off (it could not be drawn): the haze stays in the terrain's shading");
				}
			}
			underReady = water;
			underDepthReady = true;
			if (!underLogged) {
				underLogged = true;
				System.out.println("[Imagine Metal] under-water copy taken (" + (water ? "colour and depth" : "depth") + ")");
			}
		} catch (Throwable t) {
			underFailed = true;
			System.out.println("[Imagine Metal] water refraction off: " + t);
		}
	}

	static volatile boolean underReady, underDepthReady;
	private static boolean airPromised, airOff, airLogged;
	private static int airMisses;

	/**
	 * (ImagineFrame, as the frame's values are filled) whether the haze and the border fade are laid over the opaque world in the
	 * air pass this frame (then the terrain's opaque fragments leave them out, ImFrame.pbrPack.w). Only when the copy before the
	 * translucent pass can be taken: the state exists, classic transparency (the hook is in its pass).
	 */
	static boolean promiseAir(boolean ourFog) {
		boolean ok = ourFog && Settings.airPass && Settings.debug == 0 && !Live.isOff() && !airOff && state != 0 && !underFailed && Engine.TERRAIN && !failed;
		if (ok) {
			try {
				if (Minecraft.getInstance().gameRenderer.useImprovedTransparency()) ok = false;
			} catch (Throwable t) {
				ok = false;
			}
		}
		airPromised = ok;
		return ok;
	}
	private static boolean underLogged;

	/** After the level's passes, before the bloom: the picture and depth become the next frame's history. */
	public static void capture() {
		if (!nowSet || !(Settings.pbr || Settings.waterStyle > 0 || Settings.glassReflections) || failed || !Engine.TERRAIN) {
			valid = false;
			nowSet = false;
			return;
		}
		nowSet = false;
		long t0 = Perf.start();
		try {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) return;
			var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			var color = main.getColorTexture();
			var depth = main.getDepthTexture();
			if (color == null || depth == null) return;
			MetalBridge.flushClear(encoder, color);
			MetalBridge.flushClear(encoder, depth);
			if (state == 0) {
				state = ImNative.ssrNew(MetalBridge.ctx(encoder));
				if (state == 0) throw new IllegalStateException("no reflection state");
				System.out.println("[Imagine Metal] reflections on");
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.ncalloc(1, 1024, 1);
				if (ImNative.ssrCapture(MetalBridge.enc(encoder), state, MetalBridge.textureHandle(color), MetalBridge.textureHandle(depth), err, 1024) != 0)
					throw new IllegalStateException(MemoryUtil.memUTF8(err));
			}
			CLIP.set(clipNow);
			CLIP_INV.set(clipNow).invert();
			camX = nowX;
			camY = nowY;
			camZ = nowZ;
			valid = true;
		} catch (Throwable t) {
			failed = true;
			valid = false;
			System.out.println("[Imagine Metal] reflections off: " + t);
		} finally {
			Perf.end(Perf.FRAME, t0);
		}
	}
}
