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
		{"sea_lantern", new float[] {0.55f, 0.75f, 1.00f}},
		{"sea_pickle", new float[] {0.55f, 0.95f, 0.70f}},
		{"conduit", new float[] {0.55f, 0.85f, 1.00f}},
		{"beacon", new float[] {0.10f, 0.42f, 1.00f}},
		{"end_rod", new float[] {0.90f, 0.85f, 1.00f}},
		{"end_gateway", new float[] {0.60f, 0.40f, 1.00f}},
		{"end_portal", new float[] {0.25f, 0.90f, 0.85f}},  // (a cyan glow over the room, as Euphoria's)
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
		// (lava and magma at about 70 % strength: a lava pool is dozens of level-15 lights at once and drowned whole rooms in orange;
		// a colour's brightest channel below 1 makes that light dimmer, lighting.metal imBlockLight)
		{"magma", new float[] {0.60f, 0.24f, 0.07f}},
		{"lava", new float[] {0.95f, 0.36f, 0.10f}},  // (a deeper red-orange: lava-lit stone read yellow)
		{"enchanting_table", new float[] {0.70f, 0.50f, 1.00f}},
		{"ender_chest", new float[] {0.40f, 0.80f, 0.70f}},
		{"brewing_stand", new float[] {1.00f, 0.80f, 0.45f}},
		{"light", new float[] {1.00f, 0.90f, 0.75f}},  // (the invisible light block, last: "light" is in many names)
	};
	/** A flame: torches, lanterns, campfires, fire, candles, furnaces, and the default for unknown lights. */
	// (after the shader's saturation push, imBlockLight, this comes out as Reimagined's torch light, measured on matching
	// screenshots: redder, and with some blue kept, where the old colour's blue was pushed to nothing and floors turned mustard)
	// (Reimagined's fire light, linear: a deep orange, so a torch room glows orange, not pinkish white)
	private static final float[] FLAME = {1.00f, 0.37f, 0.12f};  // (Reimagined's fire light, 2.25 0.83 0.27: a deeper orange)
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

	private static final ConcurrentHashMap<BlockState, float[]> EXTRA = new ConcurrentHashMap<>();
	private static final float[] NO_LIGHT = new float[0];

	/**
	 * Blocks that give off no light in Minecraft but do in Reimagined's coloured light (its floodfill, blocklightColors.glsl):
	 * the glowing ores a faint glow of their own colour on the stone around them, the redstone and lapis blocks a stronger one.
	 * {r, g, b (brightest 1), level}, or null.
	 */
	static float[] extra(BlockState state) {
		float[] c = EXTRA.get(state);
		if (c == null) {
			String n = state.getBlock().toString().toLowerCase(java.util.Locale.ROOT);
			c = n.contains("redstone_block") ? new float[] {1f, 0.025f, 0.025f, 10}
				: n.contains("lapis_block") ? new float[] {0.25f, 0.25f, 1f, 9}
				: n.contains("redstone_ore") ? new float[] {1f, 0.025f, 0.025f, 4}
				: n.contains("diamond_ore") ? new float[] {0.25f, 1f, 1f, 4}
				: n.contains("emerald_ore") ? new float[] {0.14f, 1f, 0.14f, 4}
				: n.contains("lapis_ore") ? new float[] {0.25f, 0.25f, 1f, 4}
				: n.contains("nether_gold_ore") || n.contains("gilded_blackstone") ? new float[] {1f, 0.65f, 0.12f, 3}
				: n.contains("gold_ore") ? new float[] {1f, 0.65f, 0.12f, 4}
				: n.contains("copper_ore") ? new float[] {1f, 0.47f, 0.24f, 3}
				: n.contains("iron_ore") ? new float[] {1f, 0.53f, 0.24f, 3}
				: n.contains("nether_quartz_ore") ? new float[] {1f, 1f, 1f, 3}
				: NO_LIGHT;
			EXTRA.put(state, c);
		}
		return c.length == 0 ? null : c;
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

	private static final String[] DYES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
		"purple", "blue", "brown", "green", "red", "black"};
	// (each dye's glass colour, brightest channel 255: what light coming through it turns)
	private static final int[] DYE_RGB = {0xF0F0F0, 0xFF8A2E, 0xE04CFF, 0x66BFFF, 0xFFE83A, 0x9CFF2E, 0xFF8FC0, 0x8C8C8C, 0xC8C8C8, 0x2EC8D8,
		0x9A3AFF, 0x3A5AFF, 0xB0703C, 0x5AA02E, 0xFF2A2A, 0x3A3A3A};
	private static final java.util.concurrent.ConcurrentHashMap<net.minecraft.world.level.block.Block, Integer> GLASS = new java.util.concurrent.ConcurrentHashMap<>();

	/** A stained glass block's or pane's colour as 0xBBGGRR (0: not stained glass), for the coloured light grid. */
	static int glass(net.minecraft.world.level.block.state.BlockState state) {
		return GLASS.computeIfAbsent(state.getBlock(), b -> {
			String n = b.toString().toLowerCase(java.util.Locale.ROOT);  // ("block{minecraft:red_stained_glass}")
			int colon = n.indexOf(':'), end = n.indexOf('}', colon + 1);
			n = colon >= 0 ? n.substring(colon + 1, end > colon ? end : n.length()) : n;
			if (n.equals("tinted_glass")) return 0x3C3050;
			if (!n.contains("stained_glass")) return 0;
			int best = -1, len = -1;
			for (int k = 0; k < DYES.length; k++)
				if (n.startsWith(DYES[k] + "_") && DYES[k].length() > len) {
					best = k;
					len = DYES[k].length();
				}
			if (best < 0) return 0;
			int rgb = DYE_RGB[best];
			return (rgb >> 16 & 255) | (rgb >> 8 & 255) << 8 | (rgb & 255) << 16;
		});
	}
}
