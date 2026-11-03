package imaginemetal;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/**
 * Our clouds (Complementary Reimagined's blocky clouds, sky.metal imClouds): Minecraft's own cloud map (textures/environment/
 * clouds.png, so the layout and any resource pack's clouds stay), at Minecraft's cloud height and wind, traced as rounded blocks,
 * lit by the sun and the sky and fading into the sky with distance. And the noise the aurora is made of. Drawn by the sky pass
 * after Minecraft's sun, moon and stars (Sky.drawAfter); Minecraft's own clouds are skipped (CloudRendererMixin).
 */
public final class Clouds {
	/** this frame, from Minecraft's cloud renderer: clouds on (the video setting), their height, the time for the wind */
	private static boolean on;
	static float height = 192.33f;
	private static long gameTime;
	private static float partial;
	/** our clouds were drawn this frame (Minecraft's are skipped) */
	private static boolean drawnNow;
	static ByteBuffer cloudMap, noise;
	static int mapW, mapH;
	private static boolean loaded;
	static final int NOISE = 256;

	private Clouds() {
	}

	public static void note(boolean enabled, float h, long time, float pt) {
		on = enabled;
		height = h;
		gameTime = time;
		partial = pt;
	}

	public static boolean drawn() {
		return drawnNow;
	}

	static void frameStart() {
		drawnNow = false;
	}

	static void markDrawn() {
		drawnNow = true;
	}

	static boolean wanted() {
		return on && Settings.clouds;
	}

	/** The cloud map's offset (texels, wrapped) for camera x, z: Minecraft's wind, 0.03 blocks a tick along x; 12 blocks a texel. */
	static float[] offset(double camX, double camZ) {
		double wind = (gameTime % (long) (mapW * 12 / 0.03 * 100) + partial) * 0.03;
		double x = (camX + wind) / 12.0, z = camZ / 12.0;
		x -= Math.floor(x / mapW) * mapW;
		z -= Math.floor(z / mapH) * mapH;
		return new float[] {(float) x, (float) z};
	}

	/** The cloud map (one byte a texel: cloud or not) and the aurora's noise, once. */
	static boolean load() {
		if (loaded) return cloudMap != null;
		loaded = true;
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(Identifier.withDefaultNamespace("textures/environment/clouds.png"));
			NativeImage img = NativeImage.read(in)) {
			mapW = img.getWidth();
			mapH = img.getHeight();
			cloudMap = MemoryUtil.memAlloc(mapW * mapH * 4);
			for (int y = 0; y < mapH; y++) {
				for (int x = 0; x < mapW; x++) {
					int a = img.getPixel(x, y) >>> 24;
					int v = a >= 128 ? 255 : 0;
					cloudMap.put((byte) v).put((byte) v).put((byte) v).put((byte) 255);
				}
			}
			cloudMap.flip();
		} catch (Throwable t) {
			System.out.println("[Imagine Metal] clouds off: no cloud map: " + t);
			cloudMap = null;
			return false;
		}
		// the aurora's noise: smooth value noise in every channel (a blurred random field, tiling), 256 x 256
		java.util.Random r = new java.util.Random(0x1A6E7A1L);
		float[][] raw = new float[4][NOISE * NOISE];
		for (int c = 0; c < 4; c++) for (int i = 0; i < NOISE * NOISE; i++) raw[c][i] = r.nextFloat();
		noise = MemoryUtil.memAlloc(NOISE * NOISE * 4);
		for (int y = 0; y < NOISE; y++) {
			for (int x = 0; x < NOISE; x++) {
				for (int c = 0; c < 4; c++) {
					float s = 0f;
					for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) s += raw[c][((y + j) & 255) * NOISE + ((x + i) & 255)];
					noise.put((byte) Math.round(Math.clamp((s / 9f - 0.5f) * 2.2f + 0.5f, 0f, 1f) * 255f));
				}
			}
		}
		noise.flip();
		return true;
	}

}
