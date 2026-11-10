package imaginemetal;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Each block state's material class (lighting.metal IM_MAT_*), the low 4 bits of every terrain face's material byte (mcopt's
 * OwnMaterials stores it per face while a section compiles; the shader reads it back per fragment). Decided from what the game
 * says about the block, so modded blocks get classes too: anything that gives off light glows, anything named an ore glows faintly.
 */
public final class Materials {
	public static final byte NONE = 0, EMITTER = 1, LAVA = 2, FLAME = 3, ORE = 4, DIM = 5, NETHER_ORE = 6, SCULK = 7, METAL = 8, POLISHED = 9, GEM = 10,
		STONE = 11, SAND = 12, WOOD = 13, FOLIAGE = 14,
		/** dirt: the foliage class's non-green rule is dirt's own (lighting.metal), so dirt shares it */
		DIRT = FOLIAGE, GLOW_GEM = 15;

	// by name first (vanilla's known blocks), then by the block's sound (what it's made of: modded blocks get families too)
	private static final String[] METAL_NAMES = {"iron_block", "gold_block", "copper_block", "cut_copper", "chiseled_copper", "copper_grate",
		"copper_door", "copper_trapdoor", "copper_bulb", "netherite_block", "raw_iron_block", "raw_gold_block", "raw_copper_block", "iron_bars",
		"iron_door", "iron_trapdoor", "iron_chain", ":chain}", "anvil", ":bell}", "cauldron", "hopper", "heavy_core", "lightning_rod",
		"exposed_copper", "weathered_copper", "oxidized_copper", "copper_bars", "weighted_pressure_plate"};
	private static final String[] GEM_NAMES = {"obsidian", "amethyst_block", "budding_amethyst", "amethyst_cluster", "diamond_block",
		"emerald_block", "lapis_block", "redstone_block", "packed_ice", "blue_ice", "glass"};
	private static final String[] POLISHED_NAMES = {"polished_", "glazed_terracotta", "quartz", "prismarine", "purpur", "smooth_basalt",
		"concrete", "calcite", "dripstone_block"};
	private static final String[] DIRT_NAMES = {":dirt}", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "farmland", "dirt_path", ":mud}",
		"packed_mud", "clay}"};
	private static final String[] SAND_NAMES = {"sandstone", ":sand}", "red_sand", "suspicious_sand", "gravel"};
	private static final String[] STONE_NAMES = {"stone", "cobble", "deepslate", "andesite", "granite", "diorite", "tuff", "blackstone",
		"basalt", "bricks", "furnace", "dispenser", "dropper", "observer", "piston", "lodestone"};
	private static final String[] WOOD_NAMES = {"planks", "_log", "_wood", "stripped_", "bookshelf", "crafting_table", "_stem", "_hyphae",
		"bamboo_block", "barrel", "lectern", "loom", "composter", "note_block", "jukebox"};
	private static final String[] FOLIAGE_NAMES = {"leaves", "grass", "fern", "vine", "azalea", "moss", "bush", "sapling", "dripleaf",
		"lily_pad", "kelp", "seagrass"};

	/** the sound a block makes -> the family it belongs to (SoundType's constants by name, looked up once) */
	private static final java.util.Map<SoundType, Byte> BY_SOUND = new java.util.IdentityHashMap<>();

	static {
		for (var f : SoundType.class.getFields()) {
			if (f.getType() != SoundType.class || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
			String n = f.getName();
			byte c = n.contains("WOOD") || n.equals("STEM") || n.equals("NETHER_WOOD") || n.contains("BAMBOO") && !n.contains("SAPLING") ? WOOD
				: n.contains("COPPER") || n.equals("METAL") || n.equals("ANVIL") || n.equals("CHAIN") || n.contains("NETHERITE") || n.contains("IRON")
					|| n.equals("HEAVY_CORE") ? METAL
				: n.contains("AMETHYST") ? GEM
				: n.contains("POLISHED") ? POLISHED
				: n.equals("STONE") || n.contains("DEEPSLATE") || n.contains("TUFF") || n.equals("BASALT") || n.contains("BRICKS") || n.equals("CALCITE")
					|| n.contains("DRIPSTONE") || n.equals("NETHER_ORE") || n.equals("GILDED_BLACKSTONE") ? STONE
				: n.equals("SAND") || n.equals("GRAVEL") || n.contains("SUSPICIOUS") ? SAND
				: n.equals("GRASS") || n.contains("LEAVES") || n.equals("VINE") || n.equals("CAVE_VINES") || n.contains("AZALEA") || n.equals("MOSS")
					|| n.equals("MOSS_CARPET") || n.equals("SWEET_BERRY_BUSH") || n.equals("CROP") || n.contains("DRIPLEAF") ? FOLIAGE
				: n.equals("ROOTED_DIRT") || n.equals("MUD") || n.equals("PACKED_MUD") ? DIRT
				: NONE;
			try {
				if (c != NONE) BY_SOUND.put((SoundType) f.get(null), c);
			} catch (ReflectiveOperationException ignored) {
			}
		}
	}

	private static final ConcurrentHashMap<BlockState, Byte> CACHE = new ConcurrentHashMap<>();

	private Materials() {
	}

	/**
	 * The class of state (low 4 bits) and how it waves (bits 4-5: 0 not, 1 a grounded plant's top, 2 the whole block: leaves, vines,
	 * a tall plant's upper half). Cached; called from the section compile threads.
	 */
	public static byte of(BlockState state) {
		Byte c = CACHE.get(state);
		if (c == null) {
			c = (byte) (classify(state) | (Settings.waving ? wave(state) : 0) << 4);
			CACHE.put(state, c);
		}
		return c;
	}

	/** Forget every block's class (a setting that changes them: the chunks are rebuilt after). */
	public static void clearCache() {
		CACHE.clear();
	}

	private static int wave(BlockState state) {
		try {
			if (state.is(BlockTags.LEAVES)) return 2;
			String name = state.getBlock().toString().toLowerCase(java.util.Locale.ROOT);
			// (vines lie flat on a wall: swaying, they cut into it)
			if (name.contains(":vine}")) return 0;
			if (name.contains("weeping_vines") || name.contains("twisting_vines")) return 2;
			boolean plant = state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.CROPS)
				|| state.is(BlockTags.REPLACEABLE_BY_TREES) || name.contains("grass}") || name.contains("fern") || name.contains("bush")
				|| name.contains("seagrass");
			// (not things flat on a wall or the ground: their top edge would flap)
			if (name.contains("lichen") || name.contains("vein") || name.contains("carpet") || name.contains("roots") || name.contains("clump")
				|| name.contains("lily_pad") || name.contains("litter")) return 0;
			if (!plant || state.isSolidRender()) return 0;
			if (!state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) return 0;
			if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
				return 2;
			return 1;
		} catch (Throwable t) {
			return 0;
		}
	}

	private static boolean any(String name, String[] parts) {
		for (String p : parts) if (name.contains(p)) return true;
		return false;
	}

	/**
	 * Which ore (bits 4-7 of an ore's material byte; lighting.metal imOre has each one's glow, Reimagined's): 0 one we don't know
	 * (modded: glows by its own colours), 1 iron, 2 copper, 3 gold, 4 diamond, 5 emerald, 6 lapis, 7 redstone, 8 redstone lit,
	 * 9 coal, 10 nether quartz, 11 nether gold, 12 ancient debris, 13 gilded blackstone, 14 crimson stem, 15 warped stem.
	 */
	private static int oreType(String n, BlockState state) {
		if (n.contains("ancient_debris")) return 12;
		if (n.contains("gilded_blackstone")) return 13;
		if (n.contains("nether_quartz")) return 10;
		if (n.contains("nether_gold")) return 11;
		if (n.contains("redstone")) {
			boolean lit = false;
			try {
				lit = state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
			} catch (Throwable ignored) {
			}
			return lit ? 8 : 7;
		}
		if (n.contains("iron_ore")) return 1;
		if (n.contains("copper_ore")) return 2;
		if (n.contains("gold_ore")) return 3;
		if (n.contains("diamond_ore")) return 4;
		if (n.contains("emerald_ore")) return 5;
		if (n.contains("lapis_ore")) return 6;
		if (n.contains("coal_ore")) return 9;
		return 0;
	}

	private static byte classify(BlockState state) {
		if (state.getBlock() == Blocks.LAVA) return LAVA;
		String name = state.getBlock().toString().toLowerCase(java.util.Locale.ROOT); // "Block{minecraft:diamond_ore}"
		// ores first: a lit redstone ore gives off light, but only its specks should glow, not the stone around them
		if (name.contains("ancient_debris") || name.contains("nether") && name.contains("_ore") || name.contains("gilded_blackstone"))
			return (byte) (NETHER_ORE | oreType(name, state) << 4);
		if (name.contains("_ore")) return (byte) (ORE | oreType(name, state) << 4);
		// crimson and warped stems: their veins glow (Reimagined's terrainIPBR "Emissive Part"), through the ore path, which
		// carries a type per block (14 crimson, 15 warped)
		// Reimagined's glowing nether plants: the fungi's and weeping vines' bright orange spots (NETHER_ORE type 1)
		if (name.contains("crimson_fungus") || name.contains("warped_fungus") || name.contains("weeping_vines")) return (byte) (NETHER_ORE | 1 << 4);
		if (!name.contains("stripped") && (name.contains("crimson_stem") || name.contains("crimson_hyphae"))) return (byte) (NETHER_ORE | 14 << 4);
		if (!name.contains("stripped") && (name.contains("warped_stem") || name.contains("warped_hyphae"))) return (byte) (NETHER_ORE | 15 << 4);
		// sculk: its cyan specks glow like stars in the deep dark (the blocks themselves give off no light)
		if (name.contains("sculk") && state.getLightEmission() == 0) return SCULK;
		int light = state.getLightEmission();
		if (name.contains("sea_lantern")) return EMITTER; // (a whole glowing block, pale: the flame rule would miss it)
		if (name.contains("crying_obsidian")) return GLOW_GEM;
		if (name.contains("end_portal_frame")) return (byte) (NETHER_ORE | 2 << 4);  // (Reimagined's: the eye glows, lighting.metal imShade)
		// (Reimagined's redstone and lapis blocks glow their colour and light the room around them)
		if (name.contains("redstone_block") || name.contains("lapis_block")) return DIM;
		if (light > 0) {
			// a flame or a small lit part on an unlit body: only its bright, saturated texels glow
			if (name.contains("torch") || name.contains("lantern") || name.contains("candle") || name.contains("fire") || name.contains("campfire")
				|| name.contains("furnace") || name.contains("smoker")) return FLAME;
			return light <= 7 ? DIM : EMITTER;
		}
		if (any(name, METAL_NAMES)) return METAL;
		if (any(name, GEM_NAMES)) return GEM;
		if (any(name, POLISHED_NAMES) && !name.contains("powder")) return POLISHED;
		if (any(name, DIRT_NAMES)) return DIRT;
		if (any(name, SAND_NAMES)) return SAND;  // (before stone: "sandstone")
		if (any(name, STONE_NAMES)) return STONE;
		if (any(name, WOOD_NAMES)) return WOOD;
		if (any(name, FOLIAGE_NAMES)) return FOLIAGE;
		Byte bySound = BY_SOUND.get(state.getSoundType());
		return bySound != null ? bySound : NONE;
	}
}
