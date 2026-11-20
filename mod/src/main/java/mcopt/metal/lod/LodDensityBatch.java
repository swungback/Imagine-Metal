package mcopt.metal.lod;

import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/** Keep interpolated density nodes from expanding a sparse far tile into a huge dense volume.
 * The requested positions and world router stay unchanged; only the batch boundaries change. */
final class LodDensityBatch {
    private LodDensityBatch() {}

    /** A copied graph whose interpolators all support direct lattice sampling. */
    record SparseRoot(DensitySampler input, int cellXz, int cellY) implements DensitySampler {
        private boolean aligned(int origin, int step, int size, int cell) {
            return Math.floorMod(origin, cell) == 0 && (size == 1 || step % cell == 0);
        }
        boolean bounded(DensityVolume v) {
            long x = (long)(v.sizeX()-1)*v.stepBlockX()+2;
            long y = (long)(v.sizeY()-1)*v.stepBlockY()+2;
            long z = (long)(v.sizeZ()-1)*v.stepBlockZ()+2;
            // Even a unit-cell interpolator fits this bound. All inspected
            // interpolators now evaluate only the requested samples.
            return x > 0 && y > 0 && z > 0 && x <= 1_000_000/y/z;
        }
        boolean aligned(DensityVolume v) {
            return aligned(v.minBlockX(), v.stepBlockX(), v.sizeX(), cellXz)
                && aligned(v.minBlockY(), v.stepBlockY(), v.sizeY(), cellY)
                && aligned(v.minBlockZ(), v.stepBlockZ(), v.sizeZ(), cellXz);
        }
        @Override public float sampleValue(net.minecraft.world.level.levelgen.densityfunction.SamplerContext ctx, int x, int y, int z) {
            return input.sampleValue(ctx, x, y, z);
        }
        @Override public void sampleVolume(net.minecraft.world.level.levelgen.densityfunction.SamplerContext ctx, DensityBuffer out, DensityVolume v) {
            input.sampleVolume(ctx, out, v);
        }
    }

    static void sample(DensitySampler.Bound sampler, DensityBuffer out, DensityVolume v) {
        if (sampler.sampler() instanceof LodSolidSampler) {
            sampler.sampleVolume(out,v);
            return;
        }
        if (sampler.sampler() instanceof SparseRoot root && (root.aligned(v) || root.bounded(v))) {
            sampler.sampleVolume(out, v);
            return;
        }
        // InterpolatedFunction samples all cell corners between the first and last point.
        // A level-5 tile spans 2048 blocks despite requesting only 65 points per side.
        // Bound that hidden expansion to 16 blocks per side, independently of LOD level.
        int nx = Math.max(1, 16 / v.stepBlockX() + 1);
        int nz = Math.max(1, 16 / v.stepBlockZ() + 1);
        if (v.sizeX() <= nx && v.sizeZ() <= nz) {
            sampler.sampleVolume(out, v);
            return;
        }
        for (int z = 0; z < v.sizeZ(); z += nz) {
            for (int x = 0; x < v.sizeX(); x += nx) {
                int sx = Math.min(nx, v.sizeX() - x), sz = Math.min(nz, v.sizeZ() - z);
                DensityVolume part = new DensityVolume(sx, v.sizeY(), sz,
                    v.minBlockX() + x * v.stepBlockX(), v.minBlockY(), v.minBlockZ() + z * v.stepBlockZ(),
                    v.stepBlockX(), v.stepBlockY(), v.stepBlockZ());
                try (var tmp = LodBuffers.acquire(part.size())) {
                sampler.sampleVolume(tmp, part);
                for (int pz = 0; pz < sz; pz++) for (int px = 0; px < sx; px++) for (int y = 0; y < v.sizeY(); y++) {
                    out.set(v.indexUnchecked(x + px, y, z + pz), tmp.get(part.indexUnchecked(px, y, pz)));
                }
                }
            }
        }
    }
}
