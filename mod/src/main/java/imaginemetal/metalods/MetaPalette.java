package imaginemetal.metalods;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import mcopt.metal.lod.MetaGen;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.lwjgl.system.MemoryUtil;

/**
 * Blocks and biomes numbered for the GPU, for the session (files keep names, see MetaStore). Per block number its model entry
 * (Model in metalods.metal: the top and side sprites in the block atlas, their average colours, how the game tints them, the
 * material class Imagine Metal's lighting gives it); per biome number its tint colours. Both tables live in shared buffers
 * written as numbers are handed out, before anything that names them can reach the GPU.
 */
public final class MetaPalette {
	public static final int MAX_BLOCKS = 65536, MAX_BIOMES = 256;
	public static final int MODEL_BYTES = 64, BIOME_BYTES = 16;

	private static final ConcurrentHashMap<BlockState, Integer> IDS = new ConcurrentHashMap<>();
	private static final List<BlockState> STATES = new ArrayList<>();
	private static final byte[] KINDS = new byte[MAX_BLOCKS];
	private static final ConcurrentHashMap<Holder<Biome>, Integer> BIOME_IDS = new ConcurrentHashMap<>();
	private static final List<Holder<Biome>> BIOMES = new ArrayList<>();
	private static volatile long models, biomes;

	static {
		STATES.add(Blocks.AIR.defaultBlockState());
		BIOMES.add(null);
	}

	private MetaPalette() {
	}

	/** The tables' buffers (set once the GPU side exists): entries handed out before are written now. */
	static synchronized void bind(long modelTable, long biomeTable) {
		models = modelTable;
		biomes = biomeTable;
		MemoryUtil.memSet(modelTable, 0, (long) MAX_BLOCKS * MODEL_BYTES);
		MemoryUtil.memSet(biomeTable, 0, (long) MAX_BIOMES * BIOME_BYTES);
		for (int i = 1; i < STATES.size(); i++) writeModel(i, STATES.get(i));
		for (int i = 1; i < BIOMES.size(); i++) writeBiome(i, BIOMES.get(i));
	}

	/** Every model entry written again (a resource reload moved the sprites). */
	static synchronized void rewriteModels() {
		if (models == 0) return;
		for (int i = 1; i < STATES.size(); i++) writeModel(i, STATES.get(i));
	}

	static int kind(int id) {
		return KINDS[id & 0xFFFF];
	}

	/** What a block is to the LODs: air (plants, torches, rails: nothing to collide with), water, leaves, or a solid cube. */
	static int kindOf(BlockState s) {
		if (s.isAir()) return Voxel.KIND_AIR;
		FluidState fs = s.getFluidState();
		// (fix9) only still (source) water and lava: a waterfall's or lavafall's flowing blocks are air to the LODs
		if (s.getBlock() instanceof LiquidBlock) return !fs.isSource() ? Voxel.KIND_AIR : fs.is(FluidTags.WATER) ? Voxel.KIND_WATER : Voxel.KIND_SOLID;
		boolean empty;
		try {
			empty = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
		} catch (Throwable t) {
			empty = !s.isSolidRender();
		}
		if (empty) return !fs.isEmpty() && fs.is(FluidTags.WATER) ? Voxel.KIND_WATER : Voxel.KIND_AIR;
		if (s.is(BlockTags.LEAVES)) return Voxel.KIND_LEAVES;
		return Voxel.KIND_SOLID;
	}

	/** The block's number (0: air to the LODs). Any thread. */
	public static int id(BlockState s) {
		Integer i = IDS.get(s);
		if (i != null) return i;
		return assign(s);
	}

	private static synchronized int assign(BlockState s) {
		Integer i = IDS.get(s);
		if (i != null) return i;
		int kind = kindOf(s);
		int id;
		if (kind == Voxel.KIND_AIR) {
			id = 0;
		} else if (kind == Voxel.KIND_WATER && s != Blocks.WATER.defaultBlockState()) {
			id = id(Blocks.WATER.defaultBlockState());
		} else if (s.getBlock() == Blocks.LAVA && s != Blocks.LAVA.defaultBlockState()) {
			id = id(Blocks.LAVA.defaultBlockState());
		} else if (STATES.size() >= MAX_BLOCKS) {
			id = id(Blocks.STONE.defaultBlockState());
		} else {
			id = STATES.size();
			STATES.add(s);
			KINDS[id] = (byte) kind;
			if (models != 0) writeModel(id, s);
		}
		IDS.put(s, id);
		return id;
	}

	static synchronized BlockState state(int id) {
		return id > 0 && id < STATES.size() ? STATES.get(id) : Blocks.AIR.defaultBlockState();
	}

	public static int biome(Holder<Biome> b) {
		Integer i = BIOME_IDS.get(b);
		if (i != null) return i;
		return assignBiome(b);
	}

	private static synchronized int assignBiome(Holder<Biome> b) {
		Integer i = BIOME_IDS.get(b);
		if (i != null) return i;
		int id = BIOMES.size() < MAX_BIOMES ? BIOMES.size() : 0;
		if (id > 0) {
			BIOMES.add(b);
			if (biomes != 0) writeBiome(id, b);
		}
		BIOME_IDS.put(b, id);
		return id;
	}

	static synchronized Holder<Biome> biomeHolder(int id) {
		return id > 0 && id < BIOMES.size() ? BIOMES.get(id) : null;
	}

	/** The block's name with its properties (as commands write it), for files. */
	static String blockKey(int id) {
		BlockState s = state(id);
		return BlockStateParser.serialize(s);
	}

	static int blockFromKey(String key) {
		try {
			BlockState s = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, key, false).blockState();
			return id(s);
		} catch (Exception e) {
			return id(Blocks.STONE.defaultBlockState());
		}
	}

	static String biomeKey(int id) {
		Holder<Biome> b = biomeHolder(id);
		if (b == null) return "";
		return b.unwrapKey().map(k -> k.identifier().toString()).orElse("");
	}

	static int biomeFromKey(String key, RegistryAccess access) {
		if (key.isEmpty()) return 0;
		try {
			var lookup = access.lookupOrThrow(Registries.BIOME);
			var h = lookup.get(ResourceKey.create(Registries.BIOME, Identifier.parse(key)));
			return h.isPresent() ? biome(h.get()) : 0;
		} catch (RuntimeException e) {
			return 0;
		}
	}

	private static void writeModel(int id, BlockState s) {
		long at = models + (long) id * MODEL_BYTES;
		int[] ints = new int[6];
		float[] uv;
		try {
			uv = MetaGen.look(s, ints);
		} catch (Throwable t) {
			uv = new float[12];
			ints = new int[] {0x7F7F7F, 0x6F6F6F, 0, 0, 0, 0};
		}
		int kind = KINDS[id];
		int mat;
		try {
			mat = imaginemetal.Materials.of(s) & 0x3F;
		} catch (Throwable t) {
			mat = 0;
		}
		if (kind == Voxel.KIND_WATER) mat = 9;  // (POLISHED: the far sea gets a sheen)
		int flags = (ints[2] & 7) | (ints[3] & 7) << 3 | (mat & 0xFF) << 8 | (kind == Voxel.KIND_LEAVES ? 1 << 16 : 0)
			| (kind == Voxel.KIND_WATER ? 1 << 17 : 0) | (ints[5] & 7) << 18;
		for (int i = 0; i < 8; i++) MemoryUtil.memPutFloat(at + i * 4L, uv[i]);
		for (int i = 0; i < 4; i++) MemoryUtil.memPutFloat(at + 48 + i * 4L, uv.length >= 12 ? uv[8 + i] : 0f);  // (fix7: the side's overlay)
		MemoryUtil.memPutInt(at + 32, ints[0]);
		MemoryUtil.memPutInt(at + 36, ints[1]);
		MemoryUtil.memPutInt(at + 40, flags);
		MemoryUtil.memPutInt(at + 44, ints[4]);
	}

	private static void writeBiome(int id, Holder<Biome> b) {
		long at = biomes + (long) id * BIOME_BYTES;
		int[] t;
		try {
			t = MetaGen.tints(b.value(), 0, 0);
		} catch (Throwable e) {
			t = new int[] {0x79C05A, 0x59AE30, 0x3F76E4, 0x8C6B3A};
		}
		for (int i = 0; i < 4; i++) MemoryUtil.memPutInt(at + i * 4L, t[i]);
	}
}
