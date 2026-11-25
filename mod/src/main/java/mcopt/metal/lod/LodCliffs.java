package mcopt.metal.lod;

import imaginemetal.metalods.Voxel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.jspecify.annotations.Nullable;

/** Covers the dense Terralith Arid Highlands cliff decoration in seed previews. */
public final class LodCliffs {
	private static final ResourceKey<Biome> BIOME = ResourceKey.create(Registries.BIOME, Identifier.parse("terralith:arid_highlands"));
	private static final ResourceKey<PlacedFeature> FEATURE = ResourceKey.create(Registries.PLACED_FEATURE,
		Identifier.parse("terralith:highlands/arid/cliff"));

	private static final boolean ENABLED = !"false".equals(System.getProperty("metalods.aridCliffs"));

	private LodCliffs() {
	}

	public static @Nullable Holder<Biome> biome(@Nullable ServerLevel level) {
		if (!ENABLED || level == null || !level.dimension().equals(Level.OVERWORLD)) return null;
		var generator = level.getChunkSource().getGenerator();
		for (Holder<Biome> biome : generator.getBiomeSource().possibleBiomes()) {
			if (!biome.is(BIOME)) continue;
			var features = generator.getBiomeGenerationSettings(biome).features();
			if (features.isEmpty()) return null;
			for (Holder<PlacedFeature> placed : features.getFirst()) {
				if (placed.is(FEATURE) && placed.value().feature().value() instanceof SimpleBlockFeature block
					&& block.toPlace().value() instanceof SimpleStateProvider provider && provider.state().is(Blocks.STONE)) return biome;
			}
			break;
		}
		return null;
	}

	static boolean applies(Holder<Biome> active, Holder<Biome> sampled, int level) {
		return level == 0 && active.equals(sampled);
	}

	/**
	 * The feature rolls 65,280 placements per chunk in a narrow band below the surface. Reproduce its dense coverage
	 * using the seed tile's ground heights. Its block predicate looks for soil with ground two blocks above it in
	 * any of the eight neighboring columns. The sixteen-block search depth and the target block list match Terralith.
	 */
	static void paint(LodTile tile, int i, int minY, int top, int[] column, MetaGen.Ids ids) {
		int x = i % tile.size, z = i / tile.size;
		int highestNeighbor = Integer.MIN_VALUE;
		for (int nz = Math.max(0, z - 1); nz <= Math.min(tile.size - 1, z + 1); nz++) {
			for (int nx = Math.max(0, x - 1); nx <= Math.min(tile.size - 1, x + 1); nx++) {
				if (nx == x && nz == z) continue;
				highestNeighbor = Math.max(highestNeighbor, tile.ground[nx + nz * tile.size] - 1 - minY);
			}
		}
		int first = Math.max(0, top - 15);
		int last = Math.min(Math.min(column.length - 1, top), highestNeighbor - 2);
		if (first > last) return;

		int stone = ids.block(Blocks.STONE.defaultBlockState());
		int dirt = ids.block(Blocks.DIRT.defaultBlockState());
		int grass = ids.block(Blocks.GRASS_BLOCK.defaultBlockState());
		int coarse = ids.block(Blocks.COARSE_DIRT.defaultBlockState());
		int rooted = ids.block(Blocks.ROOTED_DIRT.defaultBlockState());
		int moss = ids.block(Blocks.MOSS_BLOCK.defaultBlockState());
		for (int y = first; y <= last; y++) {
			int value = column[y];
			int id = Voxel.id(value);
			if (id == dirt || id == grass || id == coarse || id == rooted || id == moss) {
				column[y] = (value & 0xFFFF0000) | stone;
			}
		}
	}
}
