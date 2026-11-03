package imaginemetal;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The colour of the light each light source gives off (linear; brightest channel 1, or less for a dimmer light). Vanilla's lights by name; anything else that
 * gives off light (modded blocks included) gets a colour from its name's hints, or the warm default of a flame.
 */
public final class LightColors {
	/** name fragment -> colour; the first match wins, so the more specific ones come first. */
	private static final Object[][] TABLE = {
		{"soul", new float[] {0.25f, 0.80f, 1.00f}},
		{"redstone_lamp", new float[] {1.00f, 0.68f, 0.38f}},
		{"redstone", new float[] {1.00f, 0.12f, 0.06f}},
		{"copper_torch", new float[] {0.55f, 1.00f, 0.50f}},
		{"copper_lantern", new float[] {0.55f, 1.00f, 0.50f}},
		{"copper_bulb", new float[] {1.00f, 0.70f, 0.40f}},
		{"sea_lantern", new float[] {0.60f, 0.90f, 1.00f}},
		{"sea_pickle", new float[] {0.55f, 0.95f, 0.70f}},
		{"conduit", new float[] {0.55f, 0.85f, 1.00f}},
		{"beacon", new float[] {0.30f, 0.80f, 1.00f}},
		{"end_rod", new float[] {0.90f, 0.85f, 1.00f}},
		{"end_gateway", new float[] {0.60f, 0.40f, 1.00f}},
		{"end_portal", new float[] {0.40f, 0.70f, 0.60f}},
		{"nether_portal", new float[] {0.60f, 0.20f, 1.00f}},
		{"crying_obsidian", new float[] {0.60f, 0.20f, 1.00f}},
		{"respawn_anchor", new float[] {0.60f, 0.25f, 1.00f}},
		{"amethyst", new float[] {0.75f, 0.40f, 1.00f}},
		{"ochre_froglight", new float[] {1.00f, 0.85f, 0.45f}},
		{"verdant_froglight", new float[] {0.60f, 1.00f, 0.55f}},
		{"pearlescent_froglight", new float[] {1.00f, 0.60f, 0.90f}},
		{"glow_lichen", new float[] {0.55f, 0.95f, 0.75f}},
		{"glow_berries", new float[] {1.00f, 0.75f, 0.35f}},
		{"cave_vines", new float[] {1.00f, 0.75f, 0.35f}},
		{"sculk", new float[] {0.20f, 0.65f, 0.90f}},
		{"shroomlight", new float[] {1.00f, 0.55f, 0.28f}},
		{"glowstone", new float[] {1.00f, 0.75f, 0.42f}},
		// (lava and magma at about half strength: a lava pool is dozens of level-15 lights at once and drowned whole rooms in orange;
		// a colour's brightest channel below 1 makes that light dimmer, lighting.metal imBlockLight)
		{"magma", new float[] {0.50f, 0.20f, 0.06f}},
		{"lava", new float[] {0.55f, 0.25f, 0.07f}},
		{"enchanting_table", new float[] {0.70f, 0.50f, 1.00f}},
		{"ender_chest", new float[] {0.40f, 0.80f, 0.70f}},
		{"brewing_stand", new float[] {1.00f, 0.80f, 0.45f}},
		{"light", new float[] {1.00f, 0.90f, 0.75f}},  // (the invisible light block, last: "light" is in many names)
	};
	/** A flame: torches, lanterns, campfires, fire, candles, furnaces, and the default for unknown lights. */
	private static final float[] FLAME = {1.00f, 0.55f, 0.22f};
	private static final float[] SOUL = {0.20f, 0.75f, 1.00f}, RED = {1.00f, 0.08f, 0.04f}, COPPER = {0.55f, 1.00f, 0.50f};
	private static final ConcurrentHashMap<BlockState, float[]> CACHE = new ConcurrentHashMap<>();

	private LightColors() {
	}

	public static float[] of(BlockState state) {
		float[] c = CACHE.get(state);
		if (c == null) {
			c = find(state.getBlock().toString().toLowerCase(java.util.Locale.ROOT));
			CACHE.put(state, c);
		}
		return c;
	}

	private static float[] find(String name) {
		// flames first where a coloured word would be misleading ("redstone_torch" is red, "soul_torch" blue: those stay above)
		if (name.contains("soul")) return SOUL;
		if (name.contains("redstone_torch") || name.contains("redstone_wall_torch")) return RED;
		if (name.contains("torch") || name.contains("lantern") && !name.contains("sea_lantern") && !name.contains("copper") || name.contains("campfire")
			|| name.contains("candle") || name.contains("furnace") || name.contains("smoker") || name.contains("fire")) {
			if (name.contains("copper")) return COPPER;
			return FLAME;
		}
		for (Object[] e : TABLE) if (name.contains((String) e[0])) return (float[]) e[1];
		return FLAME;
	}
}
