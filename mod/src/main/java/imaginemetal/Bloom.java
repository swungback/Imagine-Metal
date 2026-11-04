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

	/**
	 * How much the resolve deepens the dark parts (the grade's toe, an exponent: 0 none): Photon's contrast where the user likes it,
	 * in caves (0.4) and at night (0.3), none in daylight outside.
	 */
	private static float toe() {
		float cave = ImagineFrame.cave, night = ImagineFrame.night;
		float dd = Settings.intenseDeepDark ? Haze.deepDark : 0f;
		return 0.4f * cave + 0.3f * night * (1f - cave) + 0.35f * dd;
	}

	/**
	 * The light shafts (Reimagined's volumetric light): x 1 = eyes under water, y how much there's something overhead (a forest, a
	 * cave: Reimagined's "scene-aware" shafts, stronger there at any time of day), z strength (0 off: no shadow maps or depth copy
	 * this frame, outside the overworld), rgb the light's colour as Reimagined colours it (desaturated at noon, richer at sunrise and
	 * sunset, dim and moon-blue at night).
	 */
	private static float[] shafts() {
		float[] r = new float[6];
		if (ImagineFrame.dimNow == 1) {
			// the Nether: its storm (Reimagined's), in the same pass: x -2, z on, rgb its colour (netherColor x 3)
			if (!Settings.netherStorm || !ImagineFrame.netherHaze || !Reflections.valid) return r;
			r[0] = -2f;
			r[2] = 1f;
			System.arraycopy(ImagineFrame.netherStorm, 0, r, 3, 3);
			return r;
		}
		if (ImagineFrame.dimNow == 2) {
			// the End: Reimagined's ender beams, in the same pass: x -3, y the shadow maps' reach (0: none), z strength, rgb their
			// violet (Reimagined's: its End ambient light cubed, normalized, x 2.5)
			if (!Settings.shafts || !Reflections.valid || !Sky.active) return r;
			r[0] = -3f;
			r[1] = Shadows.valid() ? Settings.shadowRange : 0f;
			r[2] = Settings.shaftStrength;
			float[] el = {0.68f, 0.51f, 1.07f};
			float l3 = (float) Math.sqrt(Math.pow(el[0], 6) + Math.pow(el[1], 6) + Math.pow(el[2], 6));
			for (int i = 0; i < 3; i++) r[3 + i] = (float) Math.pow(el[i], 3) / l3 * 2.5f;
			return r;
		}
		boolean water = ImagineFrame.underwater;
		if (!Settings.shafts || !Reflections.valid || !(ImagineFrame.shaftOn || water) || !Shadows.valid()) return r;
		// (x: -1 eyes in water, else the sky light at the eyes, squared; sealed underground there's no air the sun lights)
		float eye = ImagineFrame.eyeSkyLight();
		if (!water && eye < 0.07f) return r;
		r[0] = water ? -1f : eye * eye;
		r[1] = ImagineFrame.shaftCover;
		r[2] = Settings.shaftStrength * 1.4f;
		float rain = ImagineFrame.shaftRain, noon = ImagineFrame.shaftNoon, invNoon = 1f - noon, sunVis = ImagineFrame.shaftSunVis;
		// (Reimagined's light shaft colours, COMPOSITE1 in lib/colors/lightAndAmbientColors.glsl: a sky blue at noon, deep orange at
		// sunrise and sunset, a dark blue at night; in rain its rain light)
		float[] l = new float[3];
		float sv2 = sunVis * sunVis;
		float[] noonC = {0.4f, 0.75f, 1.3f}, ssBase = {0.62f, 0.39f, 0.24f}, nightC = {0.08f, 0.12f, 0.23f};
		float[] rainDay = {0.21f * 0.85f * 0.6f, 0.16f * 0.85f * 0.6f, 0.13f * 0.85f * 0.6f}, rainNight = {0.0225f, 0.02625f, 0.0375f};
		for (int i = 0; i < 3; i++) {
			float sunset = (float) Math.pow(ssBase[i], 1.5f + invNoon) * 6.8f;
			float dayC = sunset + (noonC[i] - sunset) * noon * noon;
			float clear = nightC[i] + (dayC - nightC[i]) * sv2;
			float rainC = (rainNight[i] + (rainDay[i] - rainNight[i]) * sv2) * 2.5f;
			l[i] = clear + (rainC - clear) * rain;
		}
		float e = 0.5f + (0.5f + 0.25f * sunVis) * invNoon * (1f - rain) + 0.3f * rain;
		float k = 1f - (0.3f + 0.3f * noon) * rain - 0.5f * (1f - sunVis) * rain + sunVis * invNoon * invNoon * (1f - rain);
		for (int i = 0; i < 3; i++) r[3 + i] = (float) Math.pow(Math.max(l[i], 1e-4f), e) * k;
		if (sunVis < 0.5f) {
			float m = Math.max(r[3], Math.max(r[4], r[5]));
			for (int i = 0; i < 3; i++) r[3 + i] = r[3 + i] / Math.max(m, 1e-4f) * 0.12f;
		}
		if (water) {
			r[3] *= 0.8f * 0.55f * 0.8f * 0.55f;
			r[4] *= 0.87f * 0.55f * 0.87f * 0.55f;
			r[5] *= 0.97f * 0.55f * 0.97f * 0.55f;
			r[2] *= 1.5f;
		}
		return r;
	}

	private static final org.joml.Matrix4f PREV_CLIP = new org.joml.Matrix4f();
	private static double prevX, prevY, prevZ;
	private static long state;
	private static boolean failed;

	private Bloom() {
	}

	public static void render() {
		long t0 = Perf.start();
		try {
			renderTimed();
		} finally {
			Perf.end(Perf.BLOOM, t0);
		}
	}

	private static void renderTimed() {
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
				float[] sh = shafts();
				ImNative.bloomShafts(state, sh[0], sh[1], sh[2], sh[3], sh[4], sh[5], sh[0] < -2.5f ? Minecraft.getInstance().options.getEffectiveRenderDistance() * 16f
					: sh[0] < -1.5f ? ImagineFrame.netherFar : Settings.shadowRange);
				// Reimagined's bloom fog strengths (rain 8, night 3, cave 14, water 14; x its bloom strength 0.12 x 8.33), as a share
				// here: how strongly distant things dissolve into glow
				float fogK = 0f;
				if (ImagineFrame.underwater) fogK = 1.4f;
				else if (ImagineFrame.shaftOn || ImagineFrame.cave > 0f)
					fogK = (ImagineFrame.shaftRain * ImagineFrame.shaftRain * 0.8f + ImagineFrame.night * 0.3f) * (1f - ImagineFrame.cave) + ImagineFrame.cave * 1.4f;
				// (the Nether: Reimagined's strongest, 14, over the view range)
				float fogMode = ImagineFrame.underwater ? 1f : 0f;
				if (ImagineFrame.dimNow == 1) {
					fogK = 1.4f;
					fogMode = 2f + Minecraft.getInstance().options.getEffectiveRenderDistance() * 16f;
				}
				ImNative.bloomFog(state, Settings.bloomFog ? fogK : 0f, fogMode, Settings.rainbows ? ImagineFrame.rainbow : 0f);
				// (the jitter in uv: Minecraft's clip units halved; the uv's y runs with clip y)
				ImNative.bloomTaa(state, ImagineFrame.jitterX * 0.5f, ImagineFrame.jitterY * 0.5f, Settings.taa && Reflections.valid ? 1f : 0f);
				long clips = stack.nmalloc(4, 192);
				Reflections.CLIP.get(MemoryUtil.memFloatBuffer(clips, 16));
				Reflections.CLIP_INV.get(MemoryUtil.memFloatBuffer(clips + 64, 16));
				// (the last frame's clip, taking this frame's camera-relative positions: shifted by how far the camera moved)
				new org.joml.Matrix4f(PREV_CLIP).translate((float) (Reflections.camX - prevX), (float) (Reflections.camY - prevY), (float) (Reflections.camZ - prevZ))
					.get(MemoryUtil.memFloatBuffer(clips + 128, 16));
				PREV_CLIP.set(Reflections.CLIP);
				prevX = Reflections.camX;
				prevY = Reflections.camY;
				prevZ = Reflections.camZ;
				ImNative.bloomAo(state, Settings.ssao && Reflections.valid ? Settings.ssaoStrength : 0f, clips);
				float s = Settings.debug == 3 ? -Math.max(strength, 0.5f) : strength;
				if (ImNative.bloomRender(MetalBridge.enc(encoder), state, MetalBridge.textureHandle(src), MetalBridge.textureHandle(dst), s,
					hdr ? HDR_THRESHOLD : LDR_THRESHOLD, hdr ? HDR_KNEE : LDR_KNEE, (hdr ? HDR_HAZE : LDR_HAZE) * (1f - 0.75f * Math.max(ImagineFrame.cave, ImagineFrame.night * 0.6f)), toe(), err, 1024) != 0)
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
