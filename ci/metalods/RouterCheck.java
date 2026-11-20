package mcopt.metal.lod;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.*;

/** Real compiled density graphs, caches, caves and noise. No client, server or world is opened. */
public final class RouterCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registries = VanillaRegistries.createWorldLookup();
        int cases = 0;
        for (var key : java.util.List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.AMPLIFIED,
                NoiseGeneratorSettings.FLOATING_ISLANDS, NoiseGeneratorSettings.END)) {
            var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value();
            var state = RandomState.create(registries.lookupOrThrow(Registries.NOISE), 923478761L, settings);
            var reference = state.samplersWithContext(SamplerContext.builder().enableCaches().build()).get(settings.noiseRouter().finalDensity());
            var fastSet = LodNativeNoise.wrap(state.samplersWithContext(SamplerContext.builder().enableCaches().build()), state);
            var fast = fastSet.get(settings.noiseRouter().finalDensity());
            var solids = LodSolidSampler.create(settings.noiseRouter().finalDensity(), fastSet);
            for (int mode = 0; mode < 5; mode++) {
                int step = mode == 4 ? 8 : 4 << mode;
                int y0 = settings.noiseSettings().minY(), ny = settings.noiseSettings().height() / 8;
                var v = new DensityVolume(17, ny, 17, mode == 4 ? -2051 : -2048,
                    mode == 4 ? y0 + 3 : y0, mode == 4 ? 1703 : 1696, step, 8, step);
                var a = DensityBuffer.createUnpooled(v.size());
                var b = DensityBuffer.createUnpooled(v.size());
                long t0 = System.nanoTime(); LodDensityBatch.sample(reference, a, v); long t1 = System.nanoTime();
                LodDensityBatch.sample(fast, b, v); long t2 = System.nanoTime();
                for (int i = 0; i < v.size(); i++) if (Float.floatToIntBits(a.get(i)) != Float.floatToIntBits(b.get(i)))
                    throw new AssertionError(key.identifier() + " step " + step + " index " + i + ": " + a.get(i) + " vs " + b.get(i));
                cases++;
                System.out.printf("PASS router %s step %d%s: %d bit-exact values, %.1f -> %.1f ms%n",
                    key.identifier(), step, mode == 4 ? " unaligned" : "", v.size(), (t1-t0)/1e6, (t2-t1)/1e6);
            }
            // Production nearby surface path versus the actual block density,
            // including negative coordinates and both near LOD resolutions.
            for (int cell : new int[]{1, 2}) {
                int x0 = -19, z0 = 23, size = 8;
                int minY = settings.noiseSettings().minY();
                int maxY = minY + settings.noiseSettings().height() - 1;
                short[] heights = new short[size * size];
                LodSurfaceSampler.sample(solids, heights, size, cell, x0, z0, minY, maxY);
                for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
                    int y = maxY;
                    while (y >= minY && !(reference.sampleValue(x0+x*cell+cell/2,y,z0+z*cell+cell/2) > 0)) y--;
                    int expected = y < minY ? minY : y + 1;
                    if (heights[z*size+x] != expected) throw new AssertionError("block surface mismatch " + key + " at " + x + "," + z);
                }
                System.out.printf("PASS block surfaces %s cell %d: 64 heights match actual final-density block sampling%n", key.identifier(), cell);
            }
            if (args.length > 0 && key.equals(NoiseGeneratorSettings.OVERWORLD)) {
                var v = new DensityVolume(65,49,65,-2048,-64,1696,16,8,16);
                var a = DensityBuffer.createUnpooled(v.size()); var b = DensityBuffer.createUnpooled(v.size());
                double[] before = new double[3], after = new double[3];
                for (int run = 0; run < 3; run++) {
                    long t0 = System.nanoTime(); LodDensityBatch.sample(((LodDensityBatch.SparseRoot)fast.sampler()).input().bind(fast.context()),a,v); long t1 = System.nanoTime();
                    LodDensityBatch.sample(fast,b,v); long t2 = System.nanoTime();
                    before[run]=(t1-t0)/1e6; after[run]=(t2-t1)/1e6;
                    for (int i=0;i<v.size();i++) if(Float.floatToIntBits(a.get(i))!=Float.floatToIntBits(b.get(i)))
                        throw new AssertionError("full L4 tile density mismatch");
                }
                java.util.Arrays.sort(before); java.util.Arrays.sort(after);
                System.out.printf("BENCH full L4 optimized graph, old bounded vs new bulk: median %.1f -> %.1f ms (%.1fx); 207025 values bit-exact in all 3 runs%n",
                    before[1],after[1],before[1]/after[1]);
            }
        }
        System.out.println("PASS " + cases + " real router volumes (separate cached contexts)");
    }
}
