package imaginemetal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/**
 * The lighting's per-frame values (lighting.metal ImFrame, 416 bytes): where the sun or moon is, the colours of its light, of
 * the sky's light and of torches for this time of day and weather, the cave floor, the tone curve. Made once per frame at
 * LevelRenderer.render's start (LevelRendererMixin) and handed to the backend, which binds it on every terrain draw.
 *
 * <p>The colours are linear light. The palette follows Complementary Reimagined's art direction (warm sun, sky-blue shadows,
 * a deep orange golden hour, blue-violet nights), tuned for our pipeline.
 */
public final class ImagineFrame {
	static final int SIZE = 416;
	private static final ByteBuffer BUF = MemoryUtil.memAlloc(SIZE).order(ByteOrder.LITTLE_ENDIAN);
	private static final long ADDRESS = MemoryUtil.memAddress(BUF);
	private static int frame;
	/** The sky light where the player's eyes are (0..1), eased over about a second, and the cave and night factors from it. */
	private static float eyeSky = 1f;
	private static long lastNs;
	static float cave, night;
	private static boolean failed;

	// Tone curve (Lottes 2016): mid-grey 0.25 stays 0.25, white at 8 (highlights roll off), contrast 1.05.
	private static final float TM_A = 1.05f, TM_D = 1f, TM_MAX = 8f, TM_MID = 0.25f;
	private static final float TM_B, TM_C;

	static {
		double a = TM_A, d = TM_D, hm = TM_MAX, mi = TM_MID, mo = TM_MID;
		double den = (Math.pow(hm, a * d) - Math.pow(mi, a * d)) * mo;
		TM_B = (float) ((-Math.pow(mi, a) + Math.pow(hm, a) * mo) / den);
		TM_C = (float) ((Math.pow(hm, a * d) * Math.pow(mi, a) - Math.pow(hm, a) * Math.pow(mi, a * d) * mo) / den);
	}

	// the palette (linear)
	private static final float[] SUN_NOON = {1.12f, 0.98f, 0.72f};
	private static final float[] SUN_GOLDEN = {1.65f, 0.78f, 0.36f};
	private static final float[] MOON = {0.17f, 0.20f, 0.32f};
	private static final float[] NIGHT_SKY = {0.065f, 0.08f, 0.14f};
	private static final float[] TORCH = {1.30f, 0.70f, 0.33f};
	private static final float[] CAVE = {0.0055f, 0.0060f, 0.0085f};
	private static final float[] END_LIGHT = {0.40f, 0.30f, 0.62f};
	private static final float[] END_SKY = {0.10f, 0.07f, 0.15f};
	private static final float[] SKY_HAZE = {0.72f, 0.82f, 1.0f};
	private static final float[] NIGHT_ZENITH = {0.008f, 0.012f, 0.032f};
	private static final float[] NIGHT_HORIZON = {0.03f, 0.04f, 0.08f};
	private static final float[] SUNSET_HORIZON = {0.95f, 0.55f, 0.35f};
	private static final float[] SUNSET = {1.25f, 0.46f, 0.16f};
	/** The moon's light by phase (vanilla's index: 0 full ... 4 new). */
	private static final float[] MOON_PHASE = {1f, 0.8f, 0.6f, 0.4f, 0.25f, 0.4f, 0.6f, 0.8f};

	private ImagineFrame() {
	}

	static long address() {
		return ADDRESS;
	}

	public static void update(CameraRenderState camera) {
		Sky.active = false;
		if (failed || !Engine.TERRAIN) return;
		try {
			long t0 = Perf.start();
			Perf.frame();
			fill(camera);
			ImNative.frame(ADDRESS, SIZE);
			Perf.end(Perf.FRAME, t0);
		} catch (Throwable t) {
			failed = true;
			System.out.println("[Imagine Metal] frame values off: " + t);
		}
	}

	private static void fill(CameraRenderState camera) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		GameRenderState game = mc.gameRenderer.gameRenderState();
		LevelRenderState state = game.levelRenderState;
		SkyRenderState sky = state.skyRenderState;
		float partial = state.worldPartialTicks;
		float rain = level != null ? level.getRainLevel(partial) : 0f;
		float thunder = level != null ? level.getThunderLevel(partial) : 0f;
		rain = Math.max(rain, thunder);
		int dim = sky.skybox == DimensionType.Skybox.OVERWORLD ? 0 : sky.skybox == DimensionType.Skybox.END ? 2 : 1;

		float[] lightDir = {0f, 1f, 0f}, light = new float[3], up = new float[3], down = new float[3];
		float day = 0f, sunFlag = 0f;
		// the sky (imSkyColor): zenith, horizon, sunset band, the sun's direction and glow
		float[] zenith = new float[3], horizon = new float[3], sunset = new float[3], sunDir = {0f, 1f, 0f}, glow = new float[3];
		float golden = 0f, glowShows = 0f;
		if (dim == 0) {
			double sa = sky.sunAngle, ma = sky.moonAngle;
			float sx = (float) -Math.sin(sa), sy = (float) Math.cos(sa);
			float mx = (float) -Math.sin(ma), my = (float) Math.cos(ma);
			boolean sunUp = sy > -0.05f;
			sunFlag = sunUp ? 1f : 0f;
			day = smoothstep(-0.10f, 0.25f, sy);
			// golden hour: the sun low, a little before it touches the horizon to a while after
			golden = smoothstep(-0.10f, 0.04f, sy) * (1f - smoothstep(0.05f, 0.45f, sy));
			// the direct light fades out and back in around the sun/moon switch, which hides it
			float switchFade = smoothstep(0.0f, 0.10f, Math.abs(sunUp ? sy : my));
			if (sunUp) {
				lightDir[0] = sx;
				lightDir[1] = sy;
				for (int i = 0; i < 3; i++) light[i] = mix(SUN_NOON[i], SUN_GOLDEN[i], golden);
			} else {
				lightDir[0] = mx;
				lightDir[1] = my;
				float phase = MOON_PHASE[Math.floorMod(sky.moonPhase.index(), 8)];
				for (int i = 0; i < 3; i++) light[i] = MOON[i] * phase;
			}
			normalize(lightDir);
			float direct = switchFade * (1f - 0.85f * rain);
			for (int i = 0; i < 3; i++) light[i] *= direct;

			// the sky's light: the biome's sky colour, blue in the shadows; warmer at sunset; grey under rain
			float[] skyc = {0.47f, 0.65f, 1.0f};
			Vector3fc sc = sky.skyColor;
			if (sc != null && sc.x() + sc.y() + sc.z() > 0.05f) {
				skyc[0] = sc.x();
				skyc[1] = sc.y();
				skyc[2] = sc.z();
			}
			float[] sunsetTint = {1.21f, 0.92f, 0.76f};
			for (int i = 0; i < 3; i++) {
				float dayAmb = (float) Math.pow(Math.max(skyc[i], 0f), 1.65) * 0.62f * mix(1f, sunsetTint[i], golden);
				up[i] = mix(NIGHT_SKY[i], dayAmb, day);
			}
			float grey = luma(up) * 0.85f;
			for (int i = 0; i < 3; i++) up[i] = mix(up[i], grey, 0.7f * rain);

			// the sky itself: the biome's sky hue (brightest channel 1, so time of day is ours alone), a deep blue overhead, paler
			// toward the horizon; a dark blue night; a warm band along the horizon and a darker, dusky zenith at golden hour
			float hm = Math.max(skyc[0], Math.max(skyc[1], skyc[2]));
			float[] dzl = new float[3];
			for (int i = 0; i < 3; i++) dzl[i] = (float) Math.pow(skyc[i] / hm, 2.2) * 0.80f;
			float dzLuma = luma(dzl);
			float skyDay = smoothstep(-0.20f, 0.20f, sy);
			for (int i = 0; i < 3; i++) {
				float dz = Math.max(dzLuma + 1.3f * (dzl[i] - dzLuma), 0.004f);  // (a richer blue than Minecraft's)
				float dh = mix(dz, SKY_HAZE[i], 0.62f);
				zenith[i] = mix(NIGHT_ZENITH[i], dz * (1f - 0.35f * golden), skyDay);
				horizon[i] = mix(NIGHT_HORIZON[i], mix(dh, SUNSET_HORIZON[i], 0.55f * golden), skyDay);
				sunset[i] = SUNSET[i];
			}
			float gz = luma(zenith) * 0.8f, gh = luma(horizon) * 0.8f;
			for (int i = 0; i < 3; i++) {
				zenith[i] = mix(zenith[i], gz, 0.8f * rain);
				horizon[i] = mix(horizon[i], gh, 0.8f * rain);
			}
			golden *= 1f - rain;
			sunDir[0] = sx;
			sunDir[1] = sy;
			normalize(sunDir);
			glowShows = smoothstep(-0.15f, 0.02f, sy) * (1f - 0.9f * rain);
			for (int i = 0; i < 3; i++) glow[i] = mix(SUN_NOON[i], SUN_GOLDEN[i], golden) * 0.65f;
		} else if (dim == 1) {
			// the Nether: no sun; its haze colour lights everything, warm from below (the lava sea)
			Vector4f fc = camera.fogData.color;
			float[] f = {fc.x, fc.y, fc.z};
			for (int i = 0; i < 3; i++) up[i] = (float) Math.pow(Math.max(f[i], 0f), 2.2) * 0.9f + new float[] {0.08f, 0.03f, 0.015f}[i];
		} else {
			// the End: a lavender light from above, a violet sky
			lightDir[0] = 0.3f;
			lightDir[1] = 0.85f;
			lightDir[2] = 0.45f;
			normalize(lightDir);
			for (int i = 0; i < 3; i++) {
				light[i] = END_LIGHT[i];
				up[i] = END_SKY[i];
			}
		}
		Shadows.prepare(lightDir, dim);
		// the light bounced up from the ground: the sky's, dimmer, and a little of the sun's
		for (int i = 0; i < 3; i++) down[i] = (dim == 1 ? 1.1f : 0.6f) * up[i] + 0.10f * light[i];

		var lightmap = game.lightmapRenderState;
		float brightness = lightmap.brightness, nightVision = lightmap.nightVisionEffectIntensity;
		float[] floor = new float[3];
		float[] floorTint = {0.45f, 0.475f, 0.6f};
		for (int i = 0; i < 3; i++) floor[i] = CAVE[i] + 0.03f * brightness * floorTint[i] + nightVision * (i == 2 ? 0.3f : 0.25f);

		BUF.clear();
		put(lightDir[0], lightDir[1], lightDir[2], sunFlag);
		put(light[0], light[1], light[2], rain);
		put(up[0], up[1], up[2], 0f);
		put(down[0], down[1], down[2], 0f);
		put(TORCH[0], TORCH[1], TORCH[2], dim == 0 ? day * (1f - 0.5f * rain) : 0f);
		put(floor[0], floor[1], floor[2], 0.9f); // w: exposure
		put(TM_A, TM_D, TM_B, TM_C);
		put(1.04f, 0.08f, 0f, 0f); // saturation, vibrance (the blue sky light greys the greens a little)
		BUF.putInt(dim).putInt(Settings.debug).putInt(1).putInt(frame++);
		// how far underground the player is (by the sky light at their eyes, eased so walking into a cave fades) and how much it's
		// night: the picture's grade (Bloom's resolve) darkens the dark parts more in both
		long now = System.nanoTime();
		float dt = lastNs == 0 ? 0f : Math.min((now - lastNs) / 1e9f, 0.25f);
		lastNs = now;
		float eyeNow = 1f;
		if (level != null) {
			var eye = net.minecraft.core.BlockPos.containing(mc.gameRenderer.mainCamera().position());
			eyeNow = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eye) / 15f;
		}
		eyeSky += (eyeNow - eyeSky) * (1f - (float) Math.exp(-dt / 1.2f));
		cave = dim == 0 ? 1f - smoothstep(0.25f, 0.75f, eyeSky) : 0f;
		night = dim == 0 ? 1f - day : 0f;
		put(cave, night, 0f, 0f);
		for (int c = 0; c < 2; c++) for (int i = 0; i < 16; i++) BUF.putFloat(Shadows.MATRIX[c][i]);
		// shadow: near cascade normal offset, valid, far cascade normal offset
		put(Shadows.normalOffset(0), Shadows.valid() ? 1f : 0f, Shadows.normalOffset(1), Settings.pbr ? 1f : 0f);
		// the coloured light grid: its low corner camera-relative, valid; its size
		LightVolume.prepare();
		var cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
		put((float) (LightVolume.originX() - cam.x), (float) (LightVolume.originY() - cam.y), (float) (LightVolume.originZ() - cam.z), LightVolume.valid() ? 1f : 0f);
		put(LightVolume.SX, LightVolume.SY, LightVolume.SZ, Settings.normals);
		// the sky and the haze (ours only in the overworld with the eyes in air: in water, lava or snow Minecraft's fog stays)
		boolean ourFog = dim == 0 && Settings.sky && camera.fogType == net.minecraft.world.level.material.FogType.NONE;
		float haze = 0.0011f * (1f + 3f * rain) * (1f - cave);
		put(zenith[0], zenith[1], zenith[2], rain);
		put(horizon[0], horizon[1], horizon[2], golden);
		put(sunset[0], sunset[1], sunset[2], haze);
		put(sunDir[0], sunDir[1], sunDir[2], glowShows);
		put(glow[0], glow[1], glow[2], ourFog ? 1f : 0f);
		if (BUF.position() != SIZE) throw new IllegalStateException("ImFrame is " + BUF.position() + " bytes, not " + SIZE);
	}

	private static void put(float x, float y, float z, float w) {
		BUF.putFloat(x).putFloat(y).putFloat(z).putFloat(w);
	}

	private static void normalize(float[] v) {
		float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		if (l > 1e-6f) {
			v[0] /= l;
			v[1] /= l;
			v[2] /= l;
		}
	}

	private static float luma(float[] c) {
		return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
	}

	private static float mix(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static float smoothstep(float a, float b, float x) {
		float t = Math.clamp((x - a) / (b - a), 0f, 1f);
		return t * t * (3f - 2f * t);
	}
}
