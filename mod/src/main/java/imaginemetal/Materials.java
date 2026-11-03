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
	public static final byte NONE = 0, EMITTER = 1, LAVA = 2, FLAME = 3, ORE = 4, DIM = 5, NETHER_ORE = 6;

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
		int light = state.getLightEmission();
		if (name.contains("sea_lantern")) return EMITTER; // (a whole glowing block, pale: the flame rule would miss it)
		if (light > 0) {
			// a flame or a small lit part on an unlit body: only its bright, saturated texels glow
			if (name.contains("torch") || name.contains("lantern") || name.contains("candle") || name.contains("fire") || name.contains("campfire")
				|| name.contains("furnace") || name.contains("smoker")) return FLAME;
			return light <= 7 ? DIM : EMITTER;
		}
		return NONE;
	}
}
