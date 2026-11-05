package imaginemetal;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.io.InputStream;
import java.util.Map;
import mcopt.metal.MetalBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.system.MemoryUtil;

/**
 * PBR resource packs (LabPBR): each block texture's _n (normal xy, ambient occlusion, height) and _s (smoothness, f0 or metal,
 * porosity, emission) maps, laid out exactly as the block atlas lays out the textures, so the terrain reads them at its own uv
 * (lighting.metal imShade, ImFrame.pbrPack). Built when the atlas is; nothing when no pack has such maps.
 */
public final class PbrAtlas {
	static volatile boolean on;

	private PbrAtlas() {
	}

	public static void build(int width, int height, Map<Identifier, TextureAtlasSprite> regions) {
		on = false;
		try {
			ResourceManager rm = Minecraft.getInstance().getResourceManager();
			long nBuf = 0, sBuf = 0;
			int found = 0;
			try {
				for (TextureAtlasSprite sp : regions.values()) {
					Identifier name = sp.contents().name();
					for (int k = 0; k < 2; k++) {
						Identifier id = Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + (k == 0 ? "_n.png" : "_s.png"));
						var res = rm.getResource(id);
						if (res.isEmpty()) continue;
						try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
							if (nBuf == 0) {
								nBuf = MemoryUtil.nmemCalloc((long) width * height, 4);
								sBuf = MemoryUtil.nmemCalloc((long) width * height, 4);
								// (no normal map: flat, full ambient occlusion, full height)
								for (long i = 0; i < (long) width * height; i++) MemoryUtil.memPutInt(nBuf + i * 4, 0xFFFF8080);
							}
							long dst = k == 0 ? nBuf : sBuf;
							int w = Math.min(sp.contents().width(), img.getWidth()), h = Math.min(sp.contents().height(), img.getHeight());
							// (a map at another resolution than its texture: sampled to the texture's size)
							float sx = img.getWidth() / (float) sp.contents().width(), sy = img.getWidth() / (float) sp.contents().width();
							int tw = sp.contents().width(), th = sp.contents().height();
							for (int y = 0; y < th; y++)
								for (int x = 0; x < tw; x++) {
									int ix = Math.min((int) (x * sx), img.getWidth() - 1), iy = Math.min((int) (y * sy), img.getHeight() - 1);
									int ax = sp.getX() + x, ay = sp.getY() + y;
									if (ax >= width || ay >= height) continue;
									MemoryUtil.memPutInt(dst + ((long) ay * width + ax) * 4, img.getPixel(ix, iy));
								}
							found++;
						} catch (Throwable ignored) {
						}
					}
				}
				if (found == 0) {
					System.out.println("[Imagine Metal] PBR resource pack: none found");
					return;
				}
				Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
				if (encoder == null) return;
				if (ImNative.pbrUpload(MetalBridge.ctx(encoder), width, height, nBuf, sBuf) != 0) {
					on = true;
					System.out.println("[Imagine Metal] PBR resource pack: " + found + " maps (" + width + "x" + height + ")");
				}
			} finally {
				if (nBuf != 0) MemoryUtil.nmemFree(nBuf);
				if (sBuf != 0) MemoryUtil.nmemFree(sBuf);
			}
		} catch (Throwable t) {
			System.out.println("[Imagine Metal] PBR resource pack off: " + t);
		}
	}
}
