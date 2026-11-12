package imaginemetal;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The air fog's coefficients (Photon's, include/weather/fog.glsl): Rayleigh scattering per block (blue: what makes distant land
 * turn blue) by biome, and Mie (white mist that glows toward the sun, lying low in valleys) by time of day and weather, more in
 * humid biomes. The biome is the one around the camera, eased over a few seconds as you walk between biomes. Modded biomes with
 * the usual words in their names (desert, jungle, swamp, taiga...) get the matching air.
 */
public final class Haze {
	/** linear Rayleigh scattering coefficient per block (rgb) */
	static final float[] rayleigh = new float[3];
	/** extra Mie from humidity (jungles, swamps), per block */
	static float humidMie;
	/** how much the camera is in a cold (snowy) biome, eased: the aurora shows there */
	static float cold;
	/** how much the camera is in a dry biome (desert, badlands, savanna: no rain falls), eased */
	static float dry;
	private static float targetDry;
	/** how much the camera is in the deep dark (eased): Euphoria Patches' intense deep dark */
	static float deepDark;
	private static float targetDeepDark;
	/**
	 * Reimagined's netherColor for the biome around the camera (NETHER_COLOR_MODE 3, its gamma-space colour), eased as you walk
	 * between biomes; netherKnown: how much the camera is in one of the five it knows (elsewhere: from the fog colour, ImagineFrame)
	 */
	static final float[] netherPreset = {0.4f, 0.14f, 0.06f};
	static float netherKnown;
	private static final float[] netherTarget = {0.4f, 0.14f, 0.06f};
	private static float targetKnown;
	private static final float[] target = new float[3];
	private static float targetHumid, targetCold;
	private static int counter;
	private static boolean warned, started;
	private static String lastName = "";

	// Photon's defaults: {r, g, b (sRGB), density per block}
	private static final float[] TEMPERATE = {0.31f, 0.67f, 1.00f, 0.0005f};
	private static final float[] ARID = {0.80f, 0.80f, 0.84f, 0.0003f};  // (a light, dusty haze, neither cold blue nor yellow)
	private static final float[] SNOWY = {0.50f, 0.81f, 1.00f, 0.0010f};
	private static final float[] TAIGA = {0.31f, 0.85f, 1.00f, 0.0006f};
	private static final float[] JUNGLE = {0.40f, 0.95f, 1.00f, 0.0013f};
	private static final float[] SWAMP = {0.67f, 1.00f, 0.94f, 0.0014f};
	// (Photon's is 0.03: a wall of grey; a fifth of it keeps the pale garden misty without drowning it)
	private static final float[] PALE_GARDEN = {0.90f, 0.86f, 1.00f, 0.0060f};

	/**
	 * Each biome's own light by day, eased as you walk between them: x3 the sun's tint, x3 the shade's colour (the sky light
	 * pulled toward it, its brightness included), and how far the shade is pulled. Deserts hot and golden with warm shade,
	 * jungles and swamps lush and green in the shade, taigas cool, snow crisp and blue, cherry groves pink, the pale garden grey.
	 */
	static final float[] look = {1f, 1f, 1f, 1f, 1f, 1f, 0f};
	private static final float[] lookTarget = {1f, 1f, 1f, 1f, 1f, 1f, 0f};
	private static final float[] LOOK_TEMPERATE = {1.04f, 1.0f, 0.92f, 0.95f, 1.0f, 1.06f, 0.15f};
	// (warm sun, but the shade kept cool and nearly the sky's: pulled most of the way to the sun's own yellow, the whole desert
	// came out one flat piss-yellow, its shadows no different from its light)
	private static final float[] LOOK_DRY = {1.05f, 1.0f, 0.9f, 0.94f, 0.97f, 1.06f, 0.15f};
	/**
	 * (Build C1, Settings.desertLook) toward Reimagined's desert, Jack's reference shot: the sun a touch warmer (peach, not lemon),
	 * the shade a cold blue-grey and darker, pulled half way instead of a seventh.
	 */
	private static final float[] LOOK_DRY_C1 = {1.07f, 0.99f, 0.86f, 0.5f, 0.68f, 1.15f, 0.5f};
	// (the shade a warm olive green, not the sky's blue pulled toward green: stone in a jungle's shade read teal-cyan)
	private static final float[] LOOK_JUNGLE = {1.03f, 1.0f, 0.88f, 0.98f, 1.04f, 0.72f, 0.4f};
	private static final float[] LOOK_SWAMP = {1.0f, 0.98f, 0.85f, 0.8f, 0.95f, 0.72f, 0.45f};
	private static final float[] LOOK_TAIGA = {1.0f, 0.98f, 0.95f, 0.84f, 0.99f, 1.08f, 0.25f};
	private static final float[] LOOK_SNOWY = {0.97f, 0.99f, 1.04f, 0.84f, 0.99f, 1.25f, 0.3f};
	private static final float[] LOOK_CHERRY = {1.04f, 0.97f, 0.97f, 1.1f, 0.92f, 1.06f, 0.35f};
	private static final float[] LOOK_PALE = {0.95f, 0.95f, 0.97f, 0.95f, 0.96f, 0.98f, 0.6f};
	private static final float[] LOOK_DARK_FOREST = {1.02f, 1.0f, 0.9f, 0.95f, 1.0f, 0.78f, 0.35f};
	private static final float[] LOOK_MUSHROOM = {1.02f, 0.98f, 1.0f, 1.05f, 0.9f, 1.12f, 0.3f};
	private static final float[] LOOK_WATER = {1.02f, 1.0f, 0.95f, 0.9f, 1.02f, 1.1f, 0.2f};

	private Haze() {
	}

	private static float[] lookOf(String n) {
		if (n.contains("pale_garden")) return LOOK_PALE;
		if (n.contains("swamp") || n.contains("mangrove")) return LOOK_SWAMP;
		if (n.contains("jungle") || n.contains("bamboo")) return LOOK_JUNGLE;
		if (n.contains("desert") || n.contains("badlands") || n.contains("mesa") || n.contains("savanna")) return Settings.desertLook ? LOOK_DRY_C1 : LOOK_DRY;
		if (n.contains("cherry")) return LOOK_CHERRY;
		if (n.contains("snowy") || n.contains("frozen") || n.contains("ice") || n.contains("grove") || n.contains("jagged") || n.contains("peaks"))
			return LOOK_SNOWY;
		if (n.contains("taiga")) return LOOK_TAIGA;
		if (n.contains("dark_forest")) return LOOK_DARK_FOREST;
		if (n.contains("mushroom")) return LOOK_MUSHROOM;
		if (n.contains("ocean") || n.contains("beach") || n.contains("river")) return LOOK_WATER;
		return LOOK_TEMPERATE;
	}

	private static float[] biome(String n) {
		if (n.contains("pale_garden")) return PALE_GARDEN;
		if (n.contains("swamp") || n.contains("mangrove")) return SWAMP;
		if (n.contains("jungle") || n.contains("bamboo")) return JUNGLE;
		if (n.contains("desert") || n.contains("badlands") || n.contains("mesa") || n.contains("savanna")) return ARID;
		if (n.contains("snowy") || n.contains("frozen") || n.contains("ice") || n.contains("grove") || n.contains("jagged")) return SNOWY;
		if (n.contains("taiga")) return TAIGA;
		return TEMPERATE;
	}

	static void update(ClientLevel level, Vec3 cam, float dt) {
		if (level != null && (counter++ & 7) == 0) {
			String name = "";
			try {
				name = level.getBiome(BlockPos.containing(cam)).getRegisteredName().toLowerCase(java.util.Locale.ROOT);
			} catch (Throwable t) {
				if (!warned) System.out.println("[Imagine Metal] air fog: biome name unavailable: " + t);
				warned = true;
			}
			if (!name.equals(lastName)) {
				System.out.println("[Imagine Metal] air fog: biome " + name);
				lastName = name;
			}
			float[] b = biome(name);
			for (int i = 0; i < 3; i++) target[i] = (float) Math.pow(b[i], 2.2) * b[3];
			targetHumid = b == JUNGLE || b == SWAMP ? 8f * 0.0001f : 0f;
			targetCold = b == SNOWY ? 1f : 0f;
			targetDry = b == ARID ? 1f : 0f;
			targetDeepDark = name.contains("deep_dark") ? 1f : 0f;
			System.arraycopy(lookOf(name), 0, lookTarget, 0, 7);
			float[] nc = name.contains("nether_wastes") ? new float[] {0.4f, 0.14f, 0.06f}
				: name.contains("crimson_forest") ? new float[] {0.36f, 0.07f, 0.05f}
				: name.contains("warped_forest") ? new float[] {0.18f, 0.1f, 0.25f}
				: name.contains("basalt_deltas") ? new float[] {0.25f, 0.235f, 0.23f}
				: name.contains("soul_sand_valley") ? new float[] {0.1f, 0.24f, 0.24f} : null;
			targetKnown = nc != null ? 1f : 0f;
			if (nc != null) System.arraycopy(nc, 0, netherTarget, 0, 3);
		}
		float k = started ? 1f - (float) Math.exp(-dt / 2.5f) : 1f;
		started = true;
		for (int i = 0; i < 3; i++) rayleigh[i] += (target[i] - rayleigh[i]) * k;
		humidMie += (targetHumid - humidMie) * k;
		cold += (targetCold - cold) * k;
		dry += (targetDry - dry) * k;
		deepDark += (targetDeepDark - deepDark) * k;
		for (int i = 0; i < 7; i++) look[i] += (lookTarget[i] - look[i]) * k;
		// (Reimagined eases these over seconds too: crossing into a soul sand valley the haze turns teal as you walk in)
		float kn = k >= 1f ? 1f : 1f - (float) Math.exp(-dt / 4f);
		for (int i = 0; i < 3; i++) netherPreset[i] += (netherTarget[i] - netherPreset[i]) * kn;
		netherKnown += (targetKnown - netherKnown) * kn;
	}

	/** Photon's Mie density for this time of day (sunY, sunX: the sun's direction) and rain. */
	static float mie(float sunX, float sunY, float rain) {
		float fade = sunY < 0.18f ? 0.37f + 1.2f * Math.max(0f, -sunY) : 1.7f;
		float me = Math.clamp(1f - fade * Math.abs(sunY - 0.18f), 0f, 1f);
		me *= me;
		float sunrise = sunX > 0f ? me : 0f, sunset = sunX < 0f ? me : 0f;
		float noon = sunY > 0f ? 1f - me : 0f, midnight = sunY < 0f ? 1f - me : 0f;
		float bh = sunY + 0.07283f;
		float blueHour = Math.clamp(((float) Math.exp(-190f * bh * bh) - 0.05f) / 0.95f, 0f, 1f);
		// (Photon's dawn mist halved: by a river at sunrise, looking toward the sun, it whited the whole screen out pink)
		float m = 0.0035f * sunrise + 0.0001f * noon + 0.0025f * sunset + 0.0040f * midnight + 0.0015f * blueHour + humidMie * 0.7f;
		return m + (0.030f - m) * rain;
	}
}
