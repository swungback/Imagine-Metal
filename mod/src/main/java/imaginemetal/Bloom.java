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

	private static long state;
	private static boolean failed;

	private Bloom() {
	}

	public static void render() {
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
				float s = Settings.debug == 3 ? -Math.max(strength, 0.5f) : strength;
				if (ImNative.bloomRender(MetalBridge.enc(encoder), state, MetalBridge.textureHandle(src), MetalBridge.textureHandle(dst), s,
					hdr ? HDR_THRESHOLD : LDR_THRESHOLD, hdr ? HDR_KNEE : LDR_KNEE, hdr ? HDR_HAZE : LDR_HAZE, err, 1024) != 0)
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
