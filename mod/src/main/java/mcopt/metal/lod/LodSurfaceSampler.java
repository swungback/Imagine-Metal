package mcopt.metal.lod;

import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/** Nearby heightfields must find solid blocks after the world's complete density
 * calculation. Interpolating already squeezed/caved corner values changes shores.
 * One horizontal stripe at a time bounds temporary storage even in tall dimensions. */
final class LodSurfaceSampler {
    private LodSurfaceSampler() {}

    static void sample(DensitySampler.Bound sampler, short[] heights, int size, int cell,
                       int x0, int z0, int minY, int maxY) {
        sample(sampler, heights, size, cell, x0, z0, minY, maxY, false);
    }

    static float[] sample(DensitySampler.Bound sampler, short[] heights, int size, int cell,
                          int x0, int z0, int minY, int maxY, boolean keepDensity) {
        return sample(sampler, heights, size, cell, x0, z0, minY, maxY, keepDensity, 1);
    }

    /** Sample only the vertical voxel centers when the output uses coarser voxels. */
    static float[] sample(DensitySampler.Bound sampler, short[] heights, int size, int cell,
                          int x0, int z0, int minY, int maxY, boolean keepDensity, int stepY) {
        int firstY = minY + stepY / 2;
        int ny = samplesY(minY, maxY, stepY);
        float[] density = keepDensity ? LodBuffers.floats(0, size * size * ny) : null;
        for (int z = 0; z < size; z += 4) {
            int nz = Math.min(4, size - z);
            DensityVolume v = new DensityVolume(size, ny, nz,
                x0 + cell / 2, firstY, z0 + z * cell + cell / 2, cell, stepY, cell);
            try (var d = LodBuffers.acquire(v.size())) {
            if(!keepDensity && sampler.sampler() instanceof LodSolidSampler solid) solid.sampleSurface(sampler.context(),d,v);
            else LodDensityBatch.sample(sampler, d, v);
            for (int dz = 0; dz < nz; dz++) for (int x = 0; x < size; x++) {
                if (density != null) for (int k = 0; k < ny; k++)
                    density[((z + dz) * size + x) * ny + k] = d.get(v.indexUnchecked(x, k, dz));
                int y = ny - 1;
                while (y >= 0 && !(d.get(v.indexUnchecked(x, y, dz)) > 0.0f)) y--;
                heights[(z + dz) * size + x] = (short)(y < 0 ? minY : firstY + y * stepY + 1);
            }
            }
        }
        return density;
    }

    static int samplesY(int minY, int maxY, int stepY) {
        return Math.max(1, Math.floorDiv(maxY - minY - stepY / 2, stepY) + 1);
    }
}
