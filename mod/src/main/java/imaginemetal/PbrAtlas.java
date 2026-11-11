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
	/**
	 * (Build B1) The generated normals' bevels are baked and bound at texture 27 (no pack's maps there): ImFrame.grade.z. The
	 * terrain then reads one texel of the bake where it used to read up to three of the atlas and compare them.
	 */
	static volatile boolean bevel;

	private PbrAtlas() {
	}

	public static void build(int width, int height, Map<Identifier, TextureAtlasSprite> regions) {
		on = false;
		bevel = false;
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
					bake(rm, width, height, regions);
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

	private static int enc(float d) {
		return Math.clamp(Math.round((d / 0.4f + 0.5f) * 254f) + 1, 1, 255);
	}

	/** lighting.metal imGnDif: how much brighter the texel (length lc) is than its neighbour, small differences dropped, big ones capped. */
	private static float dif(float lc, int nb) {
		if ((nb >>> 24) < 26) return 0f;  // (alpha under 0.1)
		int a = nb & 255, b = (nb >> 8) & 255, c = (nb >> 16) & 255;
		float d = lc - (float) Math.sqrt(a * a + b * b + c * c) / 255f;
		d = d > 0f ? Math.max(d - 0.05f, 0f) : Math.min(d + 0.05f, 0f);
		return Math.clamp(d, -0.2f, 0.2f);
	}

	/**
	 * The bevels of the generated normals, per atlas texel: its difference to the texel above (r), to the right (g), below (b) and
	 * to the left (a) in the atlas's rows, as imGnDif gives them, none across the sprite's edge. A texel left 0 isn't baked (an
	 * animated sprite, whose texels change; a sprite with no texture file of its own size): the shader works those out as before.
	 */
	private static void bake(ResourceManager rm, int width, int height, Map<Identifier, TextureAtlasSprite> regions) {
		if (!Engine.flag("imaginemetal.bakedNormals", true)) return;
		long t0 = System.nanoTime();
		long buf = MemoryUtil.nmemCalloc((long) width * height, 4);
		if (buf == 0) return;
		int baked = 0, left = 0;
		try {
			for (TextureAtlasSprite sp : regions.values()) {
				Identifier name = sp.contents().name();
				int tw = sp.contents().width(), th = sp.contents().height();
				var res = rm.getResource(Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png"));
				if (res.isEmpty() || tw < 2 || th < 2 || sp.getX() + tw > width || sp.getY() + th > height) {
					left++;
					continue;
				}
				try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
					if (img.getWidth() != tw || img.getHeight() != th) {
						left++;
						continue;
					}
					int[] px = new int[tw * th];
					for (int y = 0; y < th; y++) for (int x = 0; x < tw; x++) px[y * tw + x] = img.getPixel(x, y);
					for (int y = 0; y < th; y++)
						for (int x = 0; x < tw; x++) {
							int c = px[y * tw + x];
							int c0 = c & 255, c1 = (c >> 8) & 255, c2 = (c >> 16) & 255;
							float lc = (float) Math.sqrt(c0 * c0 + c1 * c1 + c2 * c2) / 255f;
							int up = enc(y + 1 < th ? dif(lc, px[(y + 1) * tw + x]) : 0f), right = enc(x + 1 < tw ? dif(lc, px[y * tw + x + 1]) : 0f);
							int down = enc(y > 0 ? dif(lc, px[(y - 1) * tw + x]) : 0f), lft = enc(x > 0 ? dif(lc, px[y * tw + x - 1]) : 0f);
							MemoryUtil.memPutInt(buf + ((long) (sp.getY() + y) * width + sp.getX() + x) * 4, up | right << 8 | down << 16 | lft << 24);
						}
					baked++;
				} catch (Throwable ignored) {
					left++;
				}
			}
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null || baked == 0) return;
			if (ImNative.bevelUpload(MetalBridge.ctx(encoder), width, height, buf) != 0) {
				bevel = true;
				System.out.println("[Imagine Metal] generated normals baked: " + baked + " textures (" + left + " worked out live), " + width + "x" + height + ", "
					+ (System.nanoTime() - t0) / 1_000_000 + " ms");
			}
		} catch (Throwable t) {
			System.out.println("[Imagine Metal] baked normals off: " + t);
		} finally {
			MemoryUtil.nmemFree(buf);
		}
	}
}
