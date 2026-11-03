package imaginemetal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import mcopt.metal.MetalBridge;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Bloom: the soft glow around bright things and a faint haze over everything, in the spirit of Complementary Reimagined's. Run
 * right after the level's passes (LevelRendererMixin), on the game's main image; the work is imbloom.m with bloom.metal.
 */
public final class Bloom {
	/** imaginemetal.bloom (true). */
	public static final boolean ON = Engine.flag("imaginemetal.bloom", true);
	/** imaginemetal.bloomStrength: how much of the glow is added. */
	static final float STRENGTH = Config.number("imaginemetal.bloomStrength", 0.5f, 0f, 2f);
	/**
	 * The glow starts around this brightness (linear luma; the main image's white is 1), fading in over the knee. Low, because the
	 * main image is 8-bit: a torch flame or lava is barely brighter than a sunlit wall in it (0.6 left almost nothing glowing).
	 */
	private static final float THRESHOLD = 0.3f, KNEE = 0.25f;
	/** The share of every pixel's light that glows regardless of brightness (the overall soft haze). */
	private static final float HAZE = 0.06f;
	/** imaginemetal.debug=3: the glow alone, brightened, instead of the picture (to see what glows). */
	private static final boolean DEBUG = Integer.getInteger("imaginemetal.debug", 0) == 3;

	private static long state;
	private static boolean failed;

	private Bloom() {
	}

	public static void render() {
		if (!ON || failed || STRENGTH <= 0f) return;
		try {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) return;
			var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			var color = target.getColorTexture();
			if (color == null) return;
			MetalBridge.flushClear(encoder, color);
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
				if (ImNative.bloomRender(MetalBridge.enc(encoder), state, MetalBridge.textureHandle(color), DEBUG ? -STRENGTH : STRENGTH, THRESHOLD, KNEE, HAZE, err, 1024) != 0)
					throw new IllegalStateException(MemoryUtil.memUTF8(err));
			}
		} catch (Throwable t) {
			failed = true;
			System.out.println("[Imagine Metal] bloom off: " + t);
		}
	}
}
