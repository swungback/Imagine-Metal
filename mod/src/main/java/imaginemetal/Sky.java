package imaginemetal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import mcopt.metal.MetalBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Our sky (lighting.metal imSkyColor, drawn by sky.metal / imsky.m): at the start of Minecraft's sky pass, in the overworld, the
 * whole picture is filled with it; Minecraft's flat sky disc, sunset fan and dark lower disc are then skipped (SkyRendererMixin)
 * and its sun, moon and stars draw over ours as usual.
 */
public final class Sky {
	private static long state;
	private static boolean failed;
	/** This frame's sky is ours (Minecraft's disc and fan are skipped). */
	public static boolean active;
	private static boolean end;

	private Sky() {
	}

	public static void draw(SkyRenderState sky) {
		active = false;
		end = false;
		if (!Settings.sky || failed || !Engine.TERRAIN || sky.skybox != DimensionType.Skybox.OVERWORLD && sky.skybox != DimensionType.Skybox.END) return;
		if (sky.skybox == DimensionType.Skybox.END) {
			// (the End's sky is drawn at the end of Minecraft's sky pass instead, drawAfter: drawn here it was cleared over)
			end = true;
			active = state != 0 || !failed;
			if (state != 0) return;
		}
		try {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) return;
			Minecraft mc = Minecraft.getInstance();
			var target = mc.gameRenderer.mainRenderTarget().getColorTexture();
			if (target == null) return;
			// every pixel is written: the level's pending clear of the picture is dropped (or it would land on top of the sky)
			MetalBridge.dropPendingClear(encoder, target);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.ncalloc(1, 1024, 1);
				if (state == 0) {
					var source = MemoryUtil.memUTF8(Engine.resource("/imaginemetal/shaders/lighting.metal") + "\n" + Engine.resource("/imaginemetal/shaders/sky.metal"));
					try {
						state = ImNative.skyNew(MetalBridge.ctx(encoder), MemoryUtil.memAddress(source), err, 1024);
					} finally {
						MemoryUtil.memFree(source);
					}
					if (state == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
					System.out.println("[Imagine Metal] sky on");
				}
				Matrix4f rot = skyRot();
				long m = stack.nmalloc(16, 64);
				rot.getToAddress(m);
				if (ImNative.skyDraw(MetalBridge.enc(encoder), state, MetalBridge.textureHandle(target), ImagineFrame.address(), ImagineFrame.SIZE, m, 0, err, 1024) != 0)
					throw new IllegalStateException(MemoryUtil.memUTF8(err));
			}
			active = true;
		} catch (Throwable t) {
			failed = true;
			active = false;
			System.out.println("[Imagine Metal] sky off: " + t);
		}
	}

	private static boolean cloudsFailed, texturesSet;
	/**
	 * (Build C1, Settings.cloudsLate) This frame's clouds and aurora wait for the opaque world: Reflections.captureUnder draws
	 * them right before the translucent pass, behind what's nearer than they are (sky.metal im_clouds_late_fs). Whether that
	 * happened is checked once the level has drawn ({@link #lateEnd}); if it doesn't for 20 frames running, they are drawn in
	 * the sky pass again, as before, for the rest of the session.
	 */
	private static boolean latePending, lateOff, lateLogged;
	private static int lateMisses;

	static boolean latePending() {
		return latePending;
	}

	static long nativeState() {
		return state;
	}

	/** Clip -> view direction for this frame (the late clouds' rays, the same as the sky pass's). */
	static Matrix4f rotNow() {
		return skyRot();
	}

	/** (Reflections.captureUnder) the late clouds were drawn this frame. */
	static void lateDrawn() {
		latePending = false;
		lateMisses = 0;
		if (!lateLogged) {
			lateLogged = true;
			System.out.println("[Imagine Metal] clouds on (after the opaque world, behind what's in front of them)");
		}
	}

	/** After the level's passes (LevelRendererMixin): were this frame's late clouds drawn? */
	public static void lateEnd() {
		if (!latePending) return;
		latePending = false;
		if (++lateMisses >= 20 && !lateOff) {
			lateOff = true;
			System.out.println("[Imagine Metal] clouds: the pass after the opaque world didn't run for 20 frames, drawn in the sky pass again (as before)");
		}
	}

	/** this frame's bob and hurt tilt (GameRendererBobMixin), multiplied into the projection as Minecraft does; identity if none */
	private static final Matrix4f BOB = new Matrix4f();

	public static void noteBob(org.joml.Matrix4fc pose) {
		if (pose == null || !pose.isFinite()) BOB.identity();
		else {
			// its turn only: the bob's sideways and up/down shift is a few centimetres, nothing at the sky's distance, but the sky's
			// directions come from a point just past the near plane, a tenth of a block out, where that shift tilts them by tens
			// of degrees (the sky jumping with every step)
			BOB.set(pose);
			BOB.m30(0f).m31(0f).m32(0f);
		}
	}

	/**
	 * Clip -> view direction for this frame: the camera's projection, Minecraft's bob and its rotation, in the order Minecraft
	 * builds the level's projection (projection * bob, then the view rotation), so the sky bobs exactly with the land.
	 */
	private static Matrix4f skyRot() {
		Matrix4f p = ImagineFrame.camProj, v = ImagineFrame.camView;
		if (p == null || v == null)
			return Minecraft.getInstance().gameRenderer.mainCamera().getViewRotationProjectionMatrix(new Matrix4f()).invert();
		return new Matrix4f(p).mul(BOB).mul(v).invert();
	}


	private static void drawEnd() {
		try {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) return;
			var target = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
			if (target == null) return;
			MetalBridge.dropPendingClear(encoder, target);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.ncalloc(1, 1024, 1);
				long m = stack.nmalloc(16, 64);
				skyRot().getToAddress(m);
				if (ImNative.skyDraw(MetalBridge.enc(encoder), state, MetalBridge.textureHandle(target), ImagineFrame.address(), ImagineFrame.SIZE, m, 0, err, 1024) != 0)
					throw new IllegalStateException(MemoryUtil.memUTF8(err));
			}
		} catch (Throwable t) {
			failed = true;
			active = false;
			System.out.println("[Imagine Metal] End sky off: " + t);
		}
	}

	private static boolean lateFailed, loggedLate;
	/** This frame's End sky is drawn after the level (drawEndLate) rather than at the end of the sky pass. */
	private static boolean late;

	/**
	 * The End's sky once the level has drawn (before the bloom), over the pixels the level left empty (their depth still clear).
	 * Drawn in the sky pass, the End's sky never showed: something of the level's passes there cleared over it. Falls back to
	 * the sky pass's drawing when there's no depth copy this frame (Reflections off).
	 */
	public static void drawEndLate() {
		late = false;
		// (by the dimension, not the sky pass's hooks: in the End those never got as far as drawing it)
		if (ImagineFrame.dimNow != 2 || !Settings.sky || failed || lateFailed || !Engine.TERRAIN || !Reflections.valid) return;
		try {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) return;
			var target = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
			if (target == null) return;
			MetalBridge.flushClear(encoder, target);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.ncalloc(1, 1024, 1);
				if (state == 0) {
					var source = MemoryUtil.memUTF8(Engine.resource("/imaginemetal/shaders/lighting.metal") + "\n" + Engine.resource("/imaginemetal/shaders/sky.metal"));
					try {
						state = ImNative.skyNew(MetalBridge.ctx(encoder), MemoryUtil.memAddress(source), err, 1024);
					} finally {
						MemoryUtil.memFree(source);
					}
					if (state == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
					System.out.println("[Imagine Metal] sky on");
				}
				if (!loggedLate) {
					loggedLate = true;
					System.out.println("[Imagine Metal] End sky on (after the level)");
				}
				long m = stack.nmalloc(16, 64);
				skyRot().getToAddress(m);
				int r = ImNative.skyDrawEnd(MetalBridge.enc(encoder), state, MetalBridge.textureHandle(target), ImagineFrame.address(), ImagineFrame.SIZE, m, err, 1024);
				if (r == 1) throw new IllegalStateException(MemoryUtil.memUTF8(err));
			}
		} catch (Throwable t) {
			lateFailed = true;
			System.out.println("[Imagine Metal] End sky (after the level) off: " + t);
		}
	}

	/** At the end of Minecraft's sky pass (after its sun, moon and stars): our clouds and the aurora over them. */
	public static void drawAfter() {
		if (end && active && state != 0) {
			// (after the level, when this frame has a depth copy to draw it behind; else here)
			if (lateFailed || !Reflections.willCapture()) drawEnd();
			return;
		}
		if (!active || cloudsFailed || state == 0) return;
		boolean clouds = Clouds.wanted(), aurora = ImagineFrame.auroraVisible();
		if (!clouds && !aurora) return;
		try {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) return;
			Minecraft mc = Minecraft.getInstance();
			var target = mc.gameRenderer.mainRenderTarget().getColorTexture();
			if (target == null) return;
			if (!texturesSet) {
				if (!Clouds.load()) {
					cloudsFailed = true;
					return;
				}
				if (ImNative.skyTextures(state, MemoryUtil.memAddress(Clouds.cloudMap), Clouds.mapW, Clouds.mapH, MemoryUtil.memAddress(Clouds.noise),
					Clouds.NOISE, Clouds.NOISE) != 0) throw new IllegalStateException("cloud textures");
				texturesSet = true;
				System.out.println("[Imagine Metal] clouds on (" + Clouds.mapW + "x" + Clouds.mapH + " map)");
			}
			// (Build C1: after the opaque world instead, when that pass will run this frame; Minecraft's own clouds are skipped
			// either way)
			if (Settings.cloudsLate && !lateOff && Reflections.underWillRun()) {
				latePending = true;
				if (clouds) Clouds.markDrawn();
				return;
			}
			MetalBridge.flushClear(encoder, target);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.ncalloc(1, 1024, 1);
				Matrix4f rot = skyRot();
				long m = stack.nmalloc(16, 64);
				rot.getToAddress(m);
				if (ImNative.skyDraw(MetalBridge.enc(encoder), state, MetalBridge.textureHandle(target), ImagineFrame.address(), ImagineFrame.SIZE, m, 1, err, 1024) != 0)
					throw new IllegalStateException(MemoryUtil.memUTF8(err));
			}
			if (clouds) Clouds.markDrawn();
		} catch (Throwable t) {
			cloudsFailed = true;
			System.out.println("[Imagine Metal] clouds off: " + t);
		}
	}
}
