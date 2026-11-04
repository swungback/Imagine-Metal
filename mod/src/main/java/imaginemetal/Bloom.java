package imaginemetal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import mcopt.metal.MetalBridge;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Bloom (the soft glow around bright things and a faint haze over everything, in the spirit of Complementary Reimagined's) and the
 * HDR resolve. Run right after the level's passes (LevelRendererMixin), with the work in imbloom.m and bloom.metal: with the HDR
 * image (Hdr) the glow is added to it and the result mapped into the game's 8-bit main image; without it the glow is added to the
 * 8-bit image in place.
 */
public final class Bloom {
	// on/off and strength: Settings.bloom, Settings.bloomStrength (live)
	/**
	 * Where the glow starts (linear luma; white is 1) and the soft knee around it. With HDR only what's brighter than white glows
	 * in full (emissive blocks, lava, flames); without it everything is at most white, so the threshold sits much lower.
	 */
	private static final float HDR_THRESHOLD = 1.0f, HDR_KNEE = 0.5f, LDR_THRESHOLD = 0.3f, LDR_KNEE = 0.25f;
	/** The share of every pixel's light that glows regardless of brightness (the overall soft haze). */
	private static final float HDR_HAZE = 0.04f, LDR_HAZE = 0.06f;

	/**
	 * How much the resolve deepens the dark parts (the grade's toe, an exponent: 0 none): Photon's contrast where the user likes it,
	 * in caves (0.4) and at night (0.3), none in daylight outside.
	 */
	private static float toe() {
		float cave = ImagineFrame.cave, night = ImagineFrame.night;
		return 0.4f * cave + 0.3f * night * (1f - cave);
	}

	/**
	 * The light shafts' sun: its place on screen (uv, the backend's: y from the bottom of the picture), strength (0 off: behind the
	 * camera, underground, in water, no reflections history to read the depth from), the light's colour.
	 */
	private static float[] shafts() {
		float[] r = new float[6];
		if (!Settings.shafts || !Reflections.valid || !ImagineFrame.shaftOn || ImagineFrame.camProj == null || ImagineFrame.camView == null)
			return r;
		float[] l = ImagineFrame.shaftDir;
		org.joml.Vector4f c = new org.joml.Matrix4f(ImagineFrame.camProj).mul(ImagineFrame.camView).transform(new org.joml.Vector4f(l[0], l[1], l[2], 0f));
		if (c.w <= 1e-4f) return r;
		float x = c.x / c.w, y = c.y / c.w;
		// facing it: full; the sun far off the screen's edge: fading out
		float off = Math.max(Math.abs(x), Math.abs(y));
		float k = off < 1.2f ? 1f : Math.max(0f, 1f - (off - 1.2f) / 1.3f);
		k *= (1f - ImagineFrame.cave) * (1f - 0.7f * ImagineFrame.shaftRain);
		if (k <= 0.001f) return r;
		r[0] = x * 0.5f + 0.5f;
		r[1] = y * 0.5f + 0.5f;
		r[2] = k * Settings.shaftStrength;
		r[3] = ImagineFrame.shaftColor[0];
		r[4] = ImagineFrame.shaftColor[1];
		r[5] = ImagineFrame.shaftColor[2];
		return r;
	}

	private static long state;
	private static boolean failed;

	private Bloom() {
	}

	public static void render() {
		long t0 = Perf.start();
		try {
			renderTimed();
		} finally {
			Perf.end(Perf.BLOOM, t0);
		}
	}

	private static void renderTimed() {
		boolean hdr = Hdr.active();
		GpuTexture src = hdr ? Hdr.texture() : null, dst = hdr ? Hdr.output() : null;
		Hdr.end();
		float strength = Settings.bloom ? Settings.bloomStrength : 0f;
		if (!hdr && (failed || strength <= 0f)) return;
		try {
			if (failed) throw new IllegalStateException("bloom failed earlier");
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) return;
			if (!hdr) {
				src = dst = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
				if (src == null) return;
			}
			MetalBridge.flushClear(encoder, src);
			if (hdr) MetalBridge.dropPendingClear(encoder, dst);  // (written whole by the resolve)
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.ncalloc(1, 1024, 1);
				if (state == 0) {
					var source = MemoryUtil.memUTF8(Engine.resource("/imaginemetal/shaders/bloom.metal"));
					try {
						state = ImNative.bloomNew(MetalBridge.ctx(encoder), MemoryUtil.memAddress(source), err, 1024);
					} finally {
						MemoryUtil.memFree(source);
					}
					if (state == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
					System.out.println("[Imagine Metal] bloom on");
				}
				float[] sh = shafts();
				ImNative.bloomShafts(state, sh[0], sh[1], sh[2], sh[3], sh[4], sh[5]);
				float s = Settings.debug == 3 ? -Math.max(strength, 0.5f) : strength;
				if (ImNative.bloomRender(MetalBridge.enc(encoder), state, MetalBridge.textureHandle(src), MetalBridge.textureHandle(dst), s,
					hdr ? HDR_THRESHOLD : LDR_THRESHOLD, hdr ? HDR_KNEE : LDR_KNEE, hdr ? HDR_HAZE : LDR_HAZE, toe(), err, 1024) != 0)
					throw new IllegalStateException(MemoryUtil.memUTF8(err));
			}
		} catch (Throwable t) {
			failed = true;
			// without the resolve the HDR picture never reaches the screen: back to drawing the level into the 8-bit image
			if (hdr) Hdr.disable();
			System.out.println("[Imagine Metal] bloom off" + (hdr ? " (and HDR)" : "") + ": " + t);
		}
	}
}
