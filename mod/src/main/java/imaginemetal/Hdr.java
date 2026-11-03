package imaginemetal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import imaginemetal.mixin.RenderTargetAccessor;
import net.minecraft.client.Minecraft;

/**
 * The HDR picture: while the level draws (LevelRenderer.render), the game's main target draws into a 16-bit float image of the
 * same size instead of its 8-bit one, so anything that gives off light can be brighter than white (the terrain's emission). Right
 * after the level's passes, Bloom adds the glow and maps the result into the 8-bit image, which the hand, the HUD, screenshots and
 * the screen then use as always. Everything the level draws goes through the swapped texture: the sky, the terrain, entities,
 * particles, clouds, the weather; the clear at the start of the level's passes clears it.
 */
public final class Hdr {
	/** imaginemetal.hdr (true; read at startup): false draws the level straight into the 8-bit image (bloom then works in place). */
	public static final boolean ON = Engine.flag("imaginemetal.hdr", true);

	private static GpuTexture texture;
	private static GpuTextureView view;
	private static GpuTexture saved;
	private static GpuTextureView savedView;
	private static RenderTarget target;
	private static boolean failed;

	private Hdr() {
	}

	/** At LevelRenderer.render's start: the main target's colour becomes the HDR image. */
	public static void begin() {
		if (!ON || failed || target != null) return;
		try {
			RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			GpuTexture color = main.getColorTexture();
			GpuTextureView colorView = main.getColorTextureView();
			if (color == null || colorView == null) return;
			int w = color.getWidth(0), h = color.getHeight(0);
			if (texture == null || texture.getWidth(0) != w || texture.getHeight(0) != h) {
				if (view != null) view.close();
				if (texture != null) texture.close();
				var device = RenderSystem.getDevice();
				texture = device.createTexture(() -> "Imagine Metal HDR", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
					| GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
				view = device.createTextureView(texture);
				System.out.println("[Imagine Metal] HDR on (" + w + "x" + h + ")");
			}
			saved = color;
			savedView = colorView;
			target = main;
			((RenderTargetAccessor) main).imaginemetal$setColorTexture(texture);
			((RenderTargetAccessor) main).imaginemetal$setColorTextureView(view);
		} catch (Throwable t) {
			failed = true;
			end();
			System.out.println("[Imagine Metal] HDR off: " + t);
		}
	}

	/** After the level's passes (and at render's end, in case they threw): the main target gets its own colour back. */
	public static void end() {
		if (target == null) return;
		((RenderTargetAccessor) target).imaginemetal$setColorTexture(saved);
		((RenderTargetAccessor) target).imaginemetal$setColorTextureView(savedView);
		target = null;
	}

	/** No HDR from now on (the resolve failed: the level goes straight into the 8-bit image again). */
	static void disable() {
		failed = true;
		end();
	}

	/** Whether texture is the HDR image (FrontendRenderPassMixin). */
	public static boolean isHdrImage(GpuTexture t) {
		return t != null && t == texture;
	}

	/** Whether the level is drawing into the HDR image right now. */
	static boolean active() {
		return target != null;
	}

	/** The HDR image (between begin and end). */
	static GpuTexture texture() {
		return texture;
	}

	/** The main target's own (8-bit) colour texture (between begin and end). */
	static GpuTexture output() {
		return saved;
	}
}
