package imaginemetal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import imaginemetal.mixin.RenderTargetAccessor;
import net.minecraft.client.Minecraft;

/**
 * The HDR picture: while the level draws (LevelRenderer.render), the game's main target draws into a 16-bit float image instead
 * of its 8-bit one, so anything that gives off light can be brighter than white (the terrain's emission). Right after the level's
 * passes, Bloom adds the glow and maps the result into the 8-bit image, which the hand, the HUD, screenshots and the screen then
 * use as always. Everything the level draws goes through the swapped texture: the sky, the terrain, entities, particles, clouds,
 * the weather; the clear at the start of the level's passes clears it.
 * The render scale (Settings.renderScale): below 100 % the HDR image is that share of the main target's size on each axis, and
 * the main target's depth is swapped for one of the same size as well, so the whole world is drawn with fewer pixels (every
 * pass takes its size from its attachments); Bloom's TAA brings it up to the main target's full size, where the hand and the HUD
 * are then drawn sharp.
 */
public final class Hdr {
	/** imaginemetal.hdr (true; read at startup): false draws the level straight into the 8-bit image (bloom then works in place). */
	public static final boolean ON = Engine.flag("imaginemetal.hdr", true);

	private static GpuTexture texture;
	private static GpuTextureView view;
	private static GpuTexture depth;
	private static GpuTextureView depthView;
	private static GpuTexture saved;
	private static GpuTextureView savedView;
	private static GpuTexture savedDepth;
	private static GpuTextureView savedDepthView;
	private static RenderTarget target;
	private static boolean failed;
	private static int loggedW, loggedH;

	private Hdr() {
	}

	/** Whether the level will draw into the HDR image (known before begin: ImagineFrame's jitter). */
	static boolean usable() {
		return ON && !failed;
	}

	/**
	 * The world's render scale this frame (1: every pixel of the main target). 1 without the HDR image (nothing to scale up from)
	 * and with Minecraft's improved transparency (its own targets are made at the main target's size and share its depth).
	 */
	static float scale() {
		if (!usable()) return 1f;
		float s = Math.clamp(Settings.renderScale, 50, 100) / 100f;
		if (s >= 0.995f) return 1f;
		try {
			if (Minecraft.getInstance().gameRenderer.useImprovedTransparency()) return 1f;
		} catch (Throwable t) {
			return 1f;
		}
		return s;
	}

	/** A side of the main target -> the world's (even, so the half-size passes line up). */
	static int world(int full, float scale) {
		if (scale >= 0.995f) return full;
		return Math.min(full, Math.max(16, Math.round(full * scale / 2f) * 2));
	}

	/** At LevelRenderer.render's start: the main target's colour becomes the HDR image (and its depth the smaller one). */
	public static void begin() {
		if (!ON || failed || target != null) return;
		try {
			RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			GpuTexture color = main.getColorTexture();
			GpuTextureView colorView = main.getColorTextureView();
			if (color == null || colorView == null) return;
			GpuTexture mainDepth = main.getDepthTexture();
			GpuTextureView mainDepthView = main.getDepthTextureView();
			float s = mainDepth == null || mainDepthView == null ? 1f : scale();
			int fw = color.getWidth(0), fh = color.getHeight(0);
			int w = world(fw, s), h = world(fh, s);
			boolean scaled = w != fw || h != fh;
			var device = RenderSystem.getDevice();
			if (texture == null || texture.getWidth(0) != w || texture.getHeight(0) != h) {
				if (view != null) view.close();
				if (texture != null) texture.close();
				view = null;
				texture = null;
				texture = device.createTexture(() -> "Imagine Metal HDR", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
					| GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
				view = device.createTextureView(texture);
			}
			if (scaled && (depth == null || depth.getWidth(0) != w || depth.getHeight(0) != h || depth.getFormat() != mainDepth.getFormat())) {
				if (depthView != null) depthView.close();
				if (depth != null) depth.close();
				depthView = null;
				depth = null;
				depth = device.createTexture(() -> "Imagine Metal world depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
					| GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST, mainDepth.getFormat(), w, h, 1, 1);
				depthView = device.createTextureView(depth);
			}
			if (w != loggedW || h != loggedH) {
				loggedW = w;
				loggedH = h;
				System.out.println("[Imagine Metal] HDR on (" + w + "x" + h + (scaled ? ", the world at " + Math.round(s * 100f) + " % of " + fw + "x" + fh : "") + ")");
			}
			saved = color;
			savedView = colorView;
			savedDepth = scaled ? mainDepth : null;
			savedDepthView = scaled ? mainDepthView : null;
			target = main;
			((RenderTargetAccessor) main).imaginemetal$setColorTexture(texture);
			((RenderTargetAccessor) main).imaginemetal$setColorTextureView(view);
			if (scaled) {
				((RenderTargetAccessor) main).imaginemetal$setDepthTexture(depth);
				((RenderTargetAccessor) main).imaginemetal$setDepthTextureView(depthView);
			}
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
		if (savedDepth != null) {
			((RenderTargetAccessor) target).imaginemetal$setDepthTexture(savedDepth);
			((RenderTargetAccessor) target).imaginemetal$setDepthTextureView(savedDepthView);
		}
		savedDepth = null;
		savedDepthView = null;
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
