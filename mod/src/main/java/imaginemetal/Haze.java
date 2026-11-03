package imaginemetal;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The haze's character in the biome around the camera: a tint and a thickness (lighting.metal's atmospheric fog multiplies its
 * colour and density by them). Eased over a few seconds, so walking from a plain into a swamp thickens the air gradually.
 * Decided from the biome's name, so modded biomes with the usual words (swamp, jungle, desert...) get it too.
 */
public final class Haze {
	/** linear tint (1 = the sky's own colour), density multiplier (1 = Reimagined's) */
	static final float[] tint = {1f, 1f, 1f};
	static float density = 1f;
	private static final float[] targetTint = {1f, 1f, 1f};
	private static float targetDensity = 1f;
	private static int counter;

	/** name fragment -> {r, g, b, density} */
	private static final Object[][] BIOMES = {
		{"mangrove", new float[] {0.80f, 0.92f, 0.62f, 2.4f}},
		{"swamp", new float[] {0.78f, 0.90f, 0.60f, 2.4f}},
		{"pale_garden", new float[] {0.88f, 0.88f, 0.90f, 2.2f}},
		{"jungle", new float[] {0.86f, 1.00f, 0.84f, 1.7f}},
		{"bamboo", new float[] {0.86f, 1.00f, 0.84f, 1.7f}},
		{"dark_forest", new float[] {0.86f, 0.94f, 0.86f, 1.4f}},
		{"cherry", new float[] {1.00f, 0.80f, 0.90f, 1.3f}},
		{"badlands", new float[] {1.00f, 0.78f, 0.58f, 1.6f}},
		{"desert", new float[] {1.00f, 0.86f, 0.66f, 1.6f}},
		{"savanna", new float[] {1.00f, 0.90f, 0.74f, 1.3f}},
		{"snowy", new float[] {0.92f, 0.97f, 1.05f, 1.4f}},
		{"frozen", new float[] {0.92f, 0.97f, 1.05f, 1.4f}},
		{"ice_spikes", new float[] {0.92f, 0.97f, 1.05f, 1.4f}},
		{"grove", new float[] {0.92f, 0.97f, 1.05f, 1.3f}},
		{"mushroom", new float[] {0.95f, 0.85f, 1.00f, 1.3f}},
		{"ocean", new float[] {0.90f, 0.97f, 1.05f, 1.2f}},
		{"peaks", new float[] {0.95f, 0.98f, 1.03f, 0.8f}},
	};

	private Haze() {
	}

	static void update(ClientLevel level, Vec3 cam, float dt) {
		if (level != null && (counter++ & 7) == 0) {
			String name = "";
			try {
				name = level.getBiome(BlockPos.containing(cam)).getRegisteredName().toLowerCase(java.util.Locale.ROOT);
			} catch (Throwable ignored) {
			}
			float[] t = {1f, 1f, 1f, 1f};
			for (Object[] e : BIOMES) {
				if (name.contains((String) e[0])) {
					t = (float[]) e[1];
					break;
				}
			}
			targetTint[0] = t[0];
			targetTint[1] = t[1];
			targetTint[2] = t[2];
			targetDensity = t[3];
		}
		float k = 1f - (float) Math.exp(-dt / 2.5f);
		for (int i = 0; i < 3; i++) tint[i] += (targetTint[i] - tint[i]) * k;
		density += (targetDensity - density) * k;
	}
}
