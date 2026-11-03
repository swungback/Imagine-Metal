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
	private static final float[] target = new float[3];
	private static float targetHumid;
	private static int counter;
	private static boolean warned, started;
	private static String lastName = "";

	// Photon's defaults: {r, g, b (sRGB), density per block}
	private static final float[] TEMPERATE = {0.31f, 0.67f, 1.00f, 0.0005f};
	private static final float[] ARID = {0.65f, 0.80f, 1.00f, 0.0003f};
	private static final float[] SNOWY = {0.50f, 0.81f, 1.00f, 0.0010f};
	private static final float[] TAIGA = {0.31f, 0.85f, 1.00f, 0.0006f};
	private static final float[] JUNGLE = {0.40f, 0.95f, 1.00f, 0.0013f};
	private static final float[] SWAMP = {0.67f, 1.00f, 0.94f, 0.0014f};
	// (Photon's is 0.03: a wall of grey; a fifth of it keeps the pale garden misty without drowning it)
	private static final float[] PALE_GARDEN = {0.90f, 0.86f, 1.00f, 0.0060f};

	private Haze() {
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
		}
		float k = started ? 1f - (float) Math.exp(-dt / 2.5f) : 1f;
		started = true;
		for (int i = 0; i < 3; i++) rayleigh[i] += (target[i] - rayleigh[i]) * k;
		humidMie += (targetHumid - humidMie) * k;
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
		float m = 0.0070f * sunrise + 0.0001f * noon + 0.0050f * sunset + 0.0050f * midnight + 0.0020f * blueHour + humidMie;
		return m + (0.030f - m) * rain;
	}
}
