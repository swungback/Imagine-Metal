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

	private Sky() {
	}

	public static void draw(SkyRenderState sky) {
		active = false;
		if (!Settings.sky || failed || !Engine.TERRAIN || sky.skybox != DimensionType.Skybox.OVERWORLD) return;
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
				Matrix4f rot = mc.gameRenderer.mainCamera().getViewRotationProjectionMatrix(new Matrix4f()).invert();
				long m = stack.nmalloc(16, 64);
				rot.getToAddress(m);
				if (ImNative.skyDraw(MetalBridge.enc(encoder), state, MetalBridge.textureHandle(target), ImagineFrame.address(), ImagineFrame.SIZE, m, err, 1024) != 0)
					throw new IllegalStateException(MemoryUtil.memUTF8(err));
			}
			active = true;
		} catch (Throwable t) {
			failed = true;
			active = false;
			System.out.println("[Imagine Metal] sky off: " + t);
		}
	}
}
