package mcopt.metal.lod;

import java.util.IdentityHashMap;
import java.util.Map;
import mcopt.metal.mixin.lod.LodVegetationPatchAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ReplaceBlobsFeature;
import net.minecraft.world.level.levelgen.feature.SimpleRandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.VegetationPatchFeature;
import net.minecraft.world.level.levelgen.feature.stateproviders.DualNoiseProvider;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.jspecify.annotations.Nullable;

/** Surface decorations used by Terralith's Savanna Badlands when a real chunk does not exist yet. */
public final class LodMesa {
    private static final ResourceKey<Biome> BIOME = ResourceKey.create(Registries.BIOME, Identifier.parse("terralith:savanna_badlands"));
    private static final ResourceKey<PlacedFeature> CLAY = ResourceKey.create(Registries.PLACED_FEATURE,
        Identifier.parse("terralith:savanna/terracotta"));
    private static final ResourceKey<PlacedFeature> GROUND = ResourceKey.create(Registries.PLACED_FEATURE,
        Identifier.parse("terralith:savanna/badlands/grass"));
    private static final boolean ENABLED = !"false".equals(System.getProperty("metalods.savannaBadlands"));

    private final ServerLevel level;
    private final Holder<Biome> biome;
    private final Map<BlockState, BlockState> bands;
    private final @Nullable LodVegetationPatchAccess patch;

    private LodMesa(ServerLevel level, Holder<Biome> biome, Map<BlockState, BlockState> bands,
                    @Nullable LodVegetationPatchAccess patch) {
        this.level = level;
        this.biome = biome;
        this.bands = bands;
        this.patch = patch;
    }

    public Holder<Biome> biome() {
        return this.biome;
    }

    public static @Nullable LodMesa of(@Nullable ServerLevel level) {
        if (!ENABLED || level == null || !level.dimension().equals(Level.OVERWORLD)) return null;
        var generator = level.getChunkSource().getGenerator();
        for (Holder<Biome> biome : generator.getBiomeSource().possibleBiomes()) {
            if (!biome.is(BIOME)) continue;
            var features = generator.getBiomeGenerationSettings(biome).features();
            if (features.isEmpty()) return null;
            Map<BlockState, BlockState> bands = new IdentityHashMap<>();
            LodVegetationPatchAccess patch = null;
            for (Holder<PlacedFeature> placed : features.getFirst()) {
                var feature = placed.value().feature().value();
                if (placed.is(CLAY) && feature instanceof SimpleRandomSelectorFeature selector) {
                    for (var choice : selector.features()) {
                        if (!choice.value().placement().isEmpty() ||
                            !(choice.value().feature().value() instanceof ReplaceBlobsFeature blob)) continue;
                        bands.put(blob.targetState(), blob.replaceState());
                    }
                }
                if (placed.is(GROUND) && feature instanceof VegetationPatchFeature vegetation
                    && (Object) vegetation instanceof LodVegetationPatchAccess access
                    && access.mcopt$groundState().value() instanceof DualNoiseProvider) patch = access;
            }
            return bands.isEmpty() ? null : new LodMesa(level, biome, bands, patch);
        }
        return null;
    }

    boolean matches(Holder<Biome> sampled) {
        return this.biome.equals(sampled);
    }

    BlockState band(BlockState original) {
        // The datapack seeds over 1,600 overlapping 7–12-block replacements per chunk. Evaluate their expected
        // surface coverage directly instead of reproducing millions of block reads in each seed tile.
        return this.bands.getOrDefault(original, original);
    }

    BlockState surface(LodTile tile, int x, int z, int bx, int by, int bz, BlockState original,
                       BlockPos.MutableBlockPos pos, RandomSource random) {
        BlockState band = this.band(original);
        if (this.patch == null || !band.is(this.patch.mcopt$replaceable())) return band;
        int center = tile.ground[x + z * tile.size];
        int cell = tile.cell();
        int range = (2 + cell - 1) / cell;
        int ceiling = center + cell - 1;
        for (int offset = 1; offset <= range; offset++) {
            if (tile.ground[Math.max(0, x - offset) + z * tile.size] > ceiling
                || tile.ground[Math.min(tile.size - 1, x + offset) + z * tile.size] > ceiling
                || tile.ground[x + Math.max(0, z - offset) * tile.size] > ceiling
                || tile.ground[x + Math.min(tile.size - 1, z + offset) * tile.size] > ceiling) return band;
        }
        // The feature's placement filter requires air around its starting point. Only its original ground provider
        // chooses the colors on the resulting terraces. Applying it to the preview also gives the tree pass viable ground.
        return this.patch.mcopt$groundState().value().getState(this.level, random, pos.set(bx, by, bz));
    }
}
