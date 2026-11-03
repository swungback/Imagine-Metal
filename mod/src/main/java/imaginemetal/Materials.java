package imaginemetal;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Each block state's material class (lighting.metal IM_MAT_*), the low 4 bits of every terrain face's material byte (mcopt's
 * OwnMaterials stores it per face while a section compiles; the shader reads it back per fragment). Decided from what the game
 * says about the block, so modded blocks get classes too: anything that gives off light glows, anything named an ore glows faintly.
 */
public final class Materials {
	public static final byte NONE = 0, EMITTER = 1, LAVA = 2, FLAME = 3, ORE = 4, DIM = 5, NETHER_ORE = 6, SCULK = 7, METAL = 8, POLISHED = 9, GLOSSY = 10;
	/** name fragments: polished and glazed stone, quartz and the like (smooth stone with a sheen) */
	private static final String[] POLISHED_NAMES = {"polished_", "glazed_terracotta", "quartz", "prismarine", "purpur", "smooth_stone",
		"smooth_basalt", "concrete", "calcite", "dripstone_block", "end_stone_bricks", "mud_bricks", "tuff_bricks", "terracotta"};
	/** metal blocks and things made of metal */
	private static final String[] METAL_NAMES = {"iron_block", "gold_block", "copper_block", "cut_copper", "chiseled_copper", "copper_grate",
		"copper_door", "copper_trapdoor", "copper_bulb", "copper_chest", "netherite_block", "raw_iron_block", "raw_gold_block", "raw_copper_block",
		"iron_bars", "iron_door", "iron_trapdoor", "iron_chain", ":chain}", "anvil", "bell", "cauldron", "hopper", "heavy_core", "lightning_rod",
		"exposed_copper", "weathered_copper", "oxidized_copper", "copper_bars", "copper_lantern", "lantern"};
	/** glassy and gem blocks (glass itself is see-through, drawn elsewhere) */
	private static final String[] GLOSSY_NAMES = {"ice", "obsidian", "amethyst_block", "budding_amethyst", "amethyst_cluster", "diamond_block",
		"emerald_block", "lapis_block", "redstone_block", "honey_block", "slime_block"};

	private static final ConcurrentHashMap<BlockState, Byte> CACHE = new ConcurrentHashMap<>();

	private Materials() {
	}

	/** The class of state (cached; called from the section compile threads). */
	public static byte of(BlockState state) {
		Byte c = CACHE.get(state);
		if (c == null) {
			c = classify(state);
			CACHE.put(state, c);
		}
		return c;
	}

	private static byte classify(BlockState state) {
		if (state.getBlock() == Blocks.LAVA) return LAVA;
		String name = state.getBlock().toString().toLowerCase(java.util.Locale.ROOT); // "Block{minecraft:diamond_ore}"
		// ores first: a lit redstone ore gives off light, but only its specks should glow, not the stone around them
		if (name.contains("ancient_debris") || name.contains("nether") && name.contains("_ore")) return NETHER_ORE;
		if (name.contains("_ore")) return ORE;
		// sculk: its cyan specks glow like stars in the deep dark (the blocks themselves give off no light)
		if (name.contains("sculk") && state.getLightEmission() == 0) return SCULK;
		int light = state.getLightEmission();
		if (name.contains("sea_lantern")) return EMITTER; // (a whole glowing block, pale: the flame rule would miss it)
		if (light == 0) {
			// (raw ore blocks are metal before "ore" catches them; waxed copper counts as copper)
			for (String m : METAL_NAMES) if (name.contains(m)) return METAL;
			for (String m : GLOSSY_NAMES) if (name.contains(m)) return GLOSSY;
			if (!name.contains("powder")) for (String m : POLISHED_NAMES) if (name.contains(m)) return POLISHED;
		}
		if (light > 0) {
			// a flame or a small lit part on an unlit body: only its bright, saturated texels glow
			if (name.contains("torch") || name.contains("lantern") || name.contains("candle") || name.contains("fire") || name.contains("campfire")
				|| name.contains("furnace") || name.contains("smoker")) return FLAME;
			return light <= 7 ? DIM : EMITTER;
		}
		return NONE;
	}
}
