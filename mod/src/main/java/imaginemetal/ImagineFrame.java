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
 * The lighting's per-frame values (lighting.metal ImFrame, 832 bytes): where the sun or moon is, the colours of its light, of
 * the sky's light and of torches for this time of day and weather, the cave floor, the tone curve. Made once per frame at
 * LevelRenderer.render's start (LevelRendererMixin) and handed to the backend, which binds it on every terrain draw.
 *
 * <p>The colours are linear light. The palette follows Complementary Reimagined's art direction (warm sun, sky-blue shadows,
 * a deep orange golden hour, blue-violet nights), tuned for our pipeline.
 */
public final class ImagineFrame {
	static final int SIZE = 832;
	private static final ByteBuffer BUF = MemoryUtil.memAlloc(SIZE).order(ByteOrder.LITTLE_ENDIAN);
	private static final long ADDRESS = MemoryUtil.memAddress(BUF);
	private static int frame;
	/** The sky light where the player's eyes are (0..1), eased over about a second, and the cave and night factors from it. */
	private static float eyeSky = 1f;
	private static long lastNs;
	static float cave, night;
	/** the light shafts (Bloom): the light's direction and colour, this frame's sky ours (overworld, eyes in air), rain */
	static final float[] shaftDir = new float[3], shaftColor = new float[3];
	static boolean shaftOn, underwater;
	static float shaftRain, shaftNoon, shaftSunVis, shaftCover;
	private static float wet;
	/** this frame's TAA jitter (Minecraft's clip units, y up; Bloom un-jitters the history lookup with it) */
	static float jitterX, jitterY;
	/** this frame's dimension (0 overworld, 1 Nether, 2 End) */
	static int dimNow;

	private static float halton(int i, int b) {
		float f = 1f, r = 0f;
		while (i > 0) {
			f /= b;
			r += f * (i % b);
			i /= b;
		}
		return r;
	}
	/** the rainbow (Bloom; Reimagined's, after rain): how strong now, by the sun's height and how recently it rained here */
	static float rainbow;
	private static float afterRain;
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
	private static final float[] SUN_NOON = {1.22f, 1.05f, 0.74f};
	private static final float[] SUN_GOLDEN = {1.65f, 0.78f, 0.36f};
	// night (toward Reimagined's: a lavender-blue moonlight, its red above its green, a deep blue sky light; a little darker)
	private static final float[] MOON = {0.12f, 0.11f, 0.20f};
	private static final float[] NIGHT_SKY = {0.036f, 0.039f, 0.085f};
	private static final float[] TORCH = {1.45f, 0.55f, 0.29f};  // (Reimagined's hue, a little brighter: its torch rooms are lit further)
	private static final float[] CAVE = {0.0055f, 0.0060f, 0.0085f};
	private static final float[] END_LIGHT = {0.40f, 0.30f, 0.62f};
	private static final float[] END_SKY = {0.10f, 0.07f, 0.15f};
	/** The moon's light by phase (vanilla's index: 0 full ... 4 new). */
	private static final float[] MOON_PHASE = {1f, 0.8f, 0.6f, 0.4f, 0.25f, 0.4f, 0.6f, 0.8f};

	private ImagineFrame() {
	}

	static long address() {
		return ADDRESS;
	}

	/** the aurora's visibility this frame (0: none; the clouds pass then skips it) */
	private static float auroraVis;

	private static float sunY;
	private static long auroraLastNs;
	private static float[] auroraLow = Aurora.low(0), auroraHigh = Aurora.high(0);
	private static final float[] auroraLight = new float[3];

	private static float lightDirSunY() {
		return sunY;
	}

	static boolean auroraVisible() {
		return auroraVis > 0.001f;
	}

	/** this frame's camera projection (without bob) and view rotation (the sky pass: Sky.skyRot) */
	static org.joml.Matrix4f camProj, camView;

	public static void update(CameraRenderState camera) {
		camProj = camera.projectionMatrix != null ? new org.joml.Matrix4f(camera.projectionMatrix) : null;
		camView = camera.viewRotationMatrix != null ? new org.joml.Matrix4f(camera.viewRotationMatrix) : null;
		Sky.active = false;
		Clouds.frameStart();
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
		dimNow = dim;

		float[] lightDir = {0f, 1f, 0f}, light = new float[3], up = new float[3], down = new float[3];
		float day = 0f, sunFlag = 0f;
		// the sky (imSkyColor, Reimagined's): its colours by day and by night, the sunset band, the time-of-day factors
		float[] dayUp = new float[3], dayMid = new float[3], dayDown = new float[3], nightUp = new float[3], nightMid = new float[3],
			nightDown = new float[3], sunsetP = new float[3], sunDir = {0f, 1f, 0f};
		float golden = 0f, sunFactor = 0f, sunVis = 0f, shadowTime = 0f, noonF = 0f, nightF = 0f, invNoon = 1f;
		if (dim == 0) {
			double sa = sky.sunAngle, ma = sky.moonAngle;
			float sx = (float) -Math.sin(sa), sy = (float) Math.cos(sa);
			sunY = sy;
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
				for (int i = 0; i < 3; i++) light[i] = MOON[i] * phase * Settings.nightBrightness;
			}
			normalize(lightDir);
			// rain: Reimagined's rain light (lib/colors/lightAndAmbientColors.glsl dayRainLightColor, its shadows faded 40 %, x 2.5):
			// a dim warm grey by day, a faint blue by night, not the clear light turned down
			float[] rainLight = {0.268f, 0.204f + 0.03f * day, 0.166f + 0.09f * day};
			for (int i = 0; i < 3; i++) {
				float rl = mix(new float[] {0.026f, 0.031f, 0.044f}[i], rainLight[i], day);
				light[i] = mix(light[i], rl, rain) * switchFade;
			}

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
				float dayAmb = (float) Math.pow(Math.max(skyc[i], 0f), 1.65) * 0.52f * mix(1f, sunsetTint[i], golden);
				up[i] = mix(NIGHT_SKY[i] * Settings.nightBrightness, dayAmb, day);
			}
			// (rain: Reimagined's rain ambient, a light blue-grey, brighter than the clear sky's shadows: an overcast day is evenly lit;
			// converted like the clear one for the linear colours)
			float[] rainAmb = {0.22f, 0.22f, 0.36f};
			for (int i = 0; i < 3; i++) up[i] = mix(up[i], mix(NIGHT_SKY[i] * 1.2f * Settings.nightBrightness, rainAmb[i], day), rain);

			// the sky itself: Complementary Reimagined's sky colours (lib/colors/skyColors.glsl), from Minecraft's sky colour for this
			// biome and time (so every biome's sky keeps its own hue): a deep colour overhead, a bright band at the horizon, a
			// separate set for sunrise and sunset and for the night
			// (linear: matched against Reimagined's own frames, whose sky overhead at noon is a deep blue, about (67, 100, 166),
			// fading to (192, 201, 224) at the horizon; fed Minecraft's colour as it is, the port came out a pale (137, 165, 190))
			float[] raw = new float[3];
			if (sc != null) {
				raw[0] = (float) Math.pow(Math.max(sc.x(), 0f), 2.2);
				raw[1] = (float) Math.pow(Math.max(sc.y(), 0f), 2.2);
				raw[2] = (float) Math.pow(Math.max(sc.z(), 0f), 2.2);
			}
			float sunH = sy;
			sunFactor = sunH < 0f ? Math.clamp(sunH + 0.375f, 0f, 0.75f) / 0.75f : Math.clamp(sunH + 0.03125f, 0f, 0.0625f) / 0.0625f;
			sunVis = Math.clamp(sunH + 0.0625f, 0f, 0.125f) / 0.125f;
			float st1 = Math.abs(sunVis - 0.5f) * 2f, st2 = st1 * st1;
			shadowTime = st2 * st2;
			noonF = (float) Math.sqrt(Math.max(sunH, 0f));
			nightF = Math.max(-sunH, 0f);
			invNoon = 1f - noonF;
			float invNoon2 = invNoon * invNoon, rain2 = rain * rain, invRain2 = (1f - rain) * (1f - rain);
			float[] M = new float[3], M2 = new float[3];
			float[] floorM = {0.63f, 0.67f, 0.73f}, floorM2 = {0.265f, 0.295f, 0.35f};
			float[] nuscW = {0.1f, 0f, 0.1f};
			float[] nmscW = {-0.1f, -0.4f + 0.06f * noonF, -0.6f + 0.12f * noonF};
			float[] ndscW = {-0.15f, -0.3f + 0.02f * noonF, -0.42f + 0.08f * noonF};
			float[] noonUpK = {0.85f, 0.92f, 0.81f}, noonMidK = {1.4f, 1.3f, 1.3f};
			float[] ssUpK = {0.72f, 0.522f, 0.47f}, ssUpR = {0.1f, 0.2f, 0.35f}, ssMidK = {1.8f, 1.3f, 1.2f}, ssMidR = {0.15f, 0.25f, -0.05f};
			float[] ssDownP = {1.45f, 0.86f, 0.5f}, ssDownR = {0.8f, 0.3f, 0f}, nightK = {0.07f, 0.14f, 0.24f}, nightDownK = {0.82f, 0.82f, 0.88f};
			for (int i = 0; i < 3; i++) {
				float sq = (float) Math.sqrt(raw[i]);
				M[i] = mix(Math.max(sq, floorM[i]), sq, invRain2);
				M2[i] = mix(Math.max(raw[i], sunFactor * floorM2[i]), raw[i], invRain2);
				float noonUp = (float) Math.pow(M[i], 2.9) * (noonUpK[i] + rain * nuscW[i]);
				float noonMid = M[i] * (noonMidK[i] + rain * nmscW[i]) + noonUp * 0.25f;
				float noonDown = M[i] * (0.9f + rain * ndscW[i]) + noonUp * 0.25f;
				float ssUp = M2[i] * (ssUpK[i] + ssUpR[i] * rain2);
				float ssMid = M2[i] * (ssMidK[i] + ssMidR[i] * rain2);
				sunsetP[i] = ssDownP[i] - ssDownR[i] * rain;
				float ssDown = sunsetP[i] * 0.5f + 0.25f * ssMid;
				dayUp[i] = mix(noonUp, ssUp, invNoon2);
				dayMid[i] = mix(noonMid, ssMid, invNoon2);
				dayDown[i] = mix(noonDown, ssDown, invNoon2);
				float ncf = 0.9f * nightK[i] * (1f - 0.5f * rain) + raw[i];
				nightUp[i] = (float) Math.pow(ncf, 0.9) * 0.45f;
				nightMid[i] = (float) Math.sqrt(nightUp[i]) * 0.65f;
				nightDown[i] = nightMid[i] * nightDownK[i];
				// (a deeper, more violet night than Reimagined's: red kept, green pulled back, a little darker)
				float[] violet = {0.92f, 0.78f, 1.0f};
				nightUp[i] *= violet[i] * 0.85f;
				nightMid[i] *= violet[i] * 0.85f;
				nightDown[i] *= violet[i] * 0.85f;
			}
			sunDir[0] = sx;
			sunDir[1] = sy;
			normalize(sunDir);
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
		// the aurora: at night in cold biomes, out of rain; its pink-violet and green light added to the sky's (aurora influence)
		// where and when (the setting: every night, cold biomes, full moon, or either), eased both ways over a few seconds, so it
		// fades in and out instead of popping when you cross into a snowy biome or the night starts
		float fullMoon = dim == 0 && sky.moonPhase != null && sky.moonPhase.index() == 0 ? 1f : 0f;
		float where = switch (Settings.auroraWhen) {
			case 0 -> 1f;
			case 1 -> Haze.cold;
			case 2 -> fullMoon;
			default -> Math.max(Haze.cold, fullMoon);
		};
		float auroraTarget = dim == 0 && Settings.aurora && Settings.sky ? where * (1f - smoothstep(-0.12f, 0.02f, lightDirSunY())) * (1f - rain) : 0f;
		long nowA = System.nanoTime();
		float dtA = auroraLastNs == 0 ? 1f : Math.min((nowA - auroraLastNs) / 1e9f, 0.25f);
		auroraLastNs = nowA;
		auroraVis += (auroraTarget - auroraVis) * (1f - (float) Math.exp(-dtA / 4f));
		if (auroraVis < 0.002f && auroraTarget == 0f) auroraVis = 0f;
		int scheme = Math.clamp(Settings.auroraColors, 0, Aurora.NAMES.length - 1);
		auroraLow = Aurora.low(scheme);
		auroraHigh = Aurora.high(scheme);
		// the aurora's light on the world (Euphoria Patches' aurora influence): a light from the sky above, in its colours, strongest
		// on what faces up (the snow's tops) and none on what faces down (lighting.metal); slowly pulsing with the curtains
		auroraLight[0] = auroraLight[1] = auroraLight[2] = 0f;
		if (auroraVis > 0f && Settings.auroraInfluence > 0f) {
			double tA = System.nanoTime() / 1e9;
			float pulse = 0.8f + 0.2f * (float) Math.sin(tA * 0.35) * (float) Math.sin(tA * 0.13 + 1.3);
			float k = auroraVis * Settings.auroraInfluence * Settings.auroraBrightness * pulse;
			float[] lo = auroraLow, hi = auroraHigh;
			float ml = Math.max(lo[0], Math.max(lo[1], lo[2])), mh = Math.max(hi[0], Math.max(hi[1], hi[2]));
			for (int i = 0; i < 3; i++) auroraLight[i] = (0.65f * lo[i] / ml + 0.35f * hi[i] / mh) * 0.075f * k;
		}

		var lightmap = game.lightmapRenderState;
		float brightness = lightmap.brightness, nightVision = lightmap.nightVisionEffectIntensity;
		float[] floor = new float[3];
		float[] floorTint = {0.45f, 0.475f, 0.6f};
		for (int i = 0; i < 3; i++) floor[i] = CAVE[i] + 0.03f * brightness * floorTint[i] + nightVision * (i == 2 ? 0.3f : 0.25f);

		for (int i = 0; i < 3; i++) {
			shaftDir[i] = lightDir[i];
			shaftColor[i] = light[i];
		}
		shaftRain = rain;
		shaftNoon = dim == 0 ? noonF : 0f;
		shaftSunVis = sunVis;
		BUF.clear();
		put(lightDir[0], lightDir[1], lightDir[2], sunFlag);
		put(light[0], light[1], light[2], rain);
		put(up[0], up[1], up[2], 0f);
		put(down[0], down[1], down[2], 0f);
		put(TORCH[0], TORCH[1], TORCH[2], dim == 0 ? day * (1f - 0.5f * rain) : 0f);
		put(floor[0], floor[1], floor[2], 0.9f); // w: exposure
		put(TM_A, TM_D, TM_B, TM_C);
		// saturation, vibrance: gentle (measured against Reimagined's frames, more crushed the blue out of grass and leaves)
		put(1.04f, 0.08f, 0f, 0f);
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
		// something overhead (leaves, a cave roof, an arch: the sky light where the player stands below full): Reimagined's
		// scene-aware light shafts, eased in and out over a couple of seconds
		shaftCover += ((eyeNow < 0.97f ? 1f : 0f) - shaftCover) * (1f - (float) Math.exp(-dt / 2f));
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
		shaftOn = ourFog;
		underwater = dim == 0 && Settings.sky && camera.fogType == net.minecraft.world.level.material.FogType.WATER;
		Haze.update(level, cam, dt);
		put(dayUp[0], dayUp[1], dayUp[2], rain);
		put(dayMid[0], dayMid[1], dayMid[2], sunFactor);
		put(dayDown[0], dayDown[1], dayDown[2], noonF);
		put(nightUp[0], nightUp[1], nightUp[2], nightF);
		put(nightMid[0], nightMid[1], nightMid[2], sunVis);
		put(nightDown[0], nightDown[1], nightDown[2], shadowTime);
		put(sunsetP[0], sunsetP[1], sunsetP[2], invNoon);
		put(sunDir[0], sunDir[1], sunDir[2], ourFog ? 1f : 0f);
		// the air fog (Photon's): Rayleigh by biome (rgb), Mie by time of day and weather (w)
		put(Haze.rayleigh[0], Haze.rayleigh[1], Haze.rayleigh[2], dim == 0 ? Haze.mie(sunDir[0], sunDir[1], rain) : 0f);
		put(mc.options.getEffectiveRenderDistance() * 16f, (float) cam.y, 1f - cave, Settings.haze);
		// the reflections' history (Reflections): its transform and inverse, and how far the camera has moved since (w 1 = valid)
		for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) BUF.putFloat(Reflections.CLIP.get(c, r));
		for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) BUF.putFloat(Reflections.CLIP_INV.get(c, r));
		boolean ssr = Reflections.valid;
		put((float) (cam.x - Reflections.camX), (float) (cam.y - Reflections.camY), (float) (cam.z - Reflections.camZ), ssr ? 1f : 0f);
		// our clouds: the camera's place on the cloud map, their height, on; the aurora: visibility, drift, the camera on its plane
		boolean cloudsOn = Clouds.wanted() && dim == 0 && ourFog;
		float[] co = Clouds.mapW > 0 ? Clouds.offset(cam.x, cam.z) : new float[2];
		put(co[0], co[1], Clouds.height, cloudsOn && Clouds.mapW > 0 ? 1f : 0f);
		double secs = (System.nanoTime() / 1e9) % 100000.0;
		double ax = cam.x * 0.0075 + secs * 0.8 * 0.04, az = cam.z * 0.0075;
		ax -= Math.floor(ax / 1000.0) * 1000.0;
		az -= Math.floor(az / 1000.0) * 1000.0;
		put(auroraVis, (float) ((secs * 0.001) % 1000.0), (float) ax, (float) az);
		put((float) (cam.x - Math.floor(cam.x / 4096.0) * 4096.0), (float) (cam.y), (float) (cam.z - Math.floor(cam.z / 4096.0) * 4096.0),
			(float) (secs % 3600.0));
		// the aurora's colours (below, above; w its style and brightness)
		put(auroraLow[0], auroraLow[1], auroraLow[2], Settings.auroraStyle);
		put(auroraHigh[0], auroraHigh[1], auroraHigh[2], Settings.auroraBrightness);
		// the light in the player's hands (the brighter of the two): its colour, its level / 15
		float[] held = HeldLight.get();
		put(held[0], held[1], held[2], held[3]);
		put(auroraLight[0], auroraLight[1], auroraLight[2], (Settings.stars == 1 ? 1 : 0) + (Settings.shootingStars ? 2 : 0));
		// water and glass: the water and portal sprites, the water style, glass reflections, eyes in water
		WaterSprites.update();
		put(WaterSprites.STILL[0], WaterSprites.STILL[1], WaterSprites.STILL[2], WaterSprites.STILL[3]);
		put(WaterSprites.FLOW[0], WaterSprites.FLOW[1], WaterSprites.FLOW[2], WaterSprites.FLOW[3]);
		put(WaterSprites.PORTAL[0], WaterSprites.PORTAL[1], WaterSprites.PORTAL[2], WaterSprites.PORTAL[3]);
		// (wet: with the rain, quickly; drying slowly after; none where it doesn't rain or snows)
		float wetTarget = Settings.wet && dim == 0 ? rain * (1f - Haze.dry) * (1f - Haze.cold) : 0f;
		wet += (wetTarget - wet) * (1f - (float) Math.exp(-dt / (wetTarget > wet ? 8f : 40f)));
		// (after rain: builds while it rains, fades over a minute and a half once it stops; the rainbow shows as the rain clears,
		// with the sun up but not too high)
		float rainHere = dim == 0 ? rain * (1f - Haze.dry) * (1f - Haze.cold) : 0f;
		afterRain = rainHere > 0.5f ? Math.min(1f, afterRain + dt / 20f) : Math.max(0f, afterRain - dt / 90f);
		float rbTime = Math.clamp((sunDir[1] - 0.1f) / 0.15f, 0f, 1f);
		rbTime = Math.clamp(rbTime - (float) Math.pow(noonF, 8) * 8f, 0f, 0.85f);
		rainbow = rbTime * (float) Math.sqrt(Math.max(0f, afterRain - 0.333f) * 1.5f) * (1f - rain) * (1f - cave) * (camera.fogType == net.minecraft.world.level.material.FogType.NONE ? 1f : 0f);
		put(Settings.waterStyle, Settings.glassReflections ? 1f : 0f, camera.fogType == net.minecraft.world.level.material.FogType.WATER ? 1f : 0f, wet);
		// TAA: a Halton (2, 3) sub-pixel offset, a different one each frame of eight (within half a pixel each way)
		boolean taaOn = Settings.taa && Hdr.active();
		int tw = 1, th = 1;
		var rt = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (rt != null) {
			tw = Math.max(rt.width, 1);
			th = Math.max(rt.height, 1);
		}
		int hi = (frame & 7) + 1;
		jitterX = taaOn ? (halton(hi, 2) - 0.5f) * 2f / tw : 0f;
		jitterY = taaOn ? (halton(hi, 3) - 0.5f) * 2f / th : 0f;
		put(jitterX, jitterY, taaOn ? 1f : 0f, 0f);
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
