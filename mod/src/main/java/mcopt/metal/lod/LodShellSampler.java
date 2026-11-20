package mcopt.metal.lod;

import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/**
 * (fix17, MetaLODS) Block-exact surfaces and solid/air for levels 0-1 of the cave-free far terrain, at a fraction of
 * LodSurfaceSampler's cost and with the same result. The terrain's density is first taken at the corners of the game's own 4 x 4
 * x 4 cells (Terralith's terrain is one InterpolatedFunction over such cells, the blend parts over 4 x 8: the game interpolates
 * between these very points); a cell whose eight corners agree in sign is solid or air throughout, so blocks are sampled one by one
 * only through the y span of the cells whose corners differ (one cell of margin above and below), per 4 x 4-block column.
 * About 4 % of a tile's blocks. Only for functions without per-block parts (the far terrain: no noodle caves, which are thin enough
 * to pass between corners). CostBench (claude-scratch/s1117e): 16 tiles per level, heights and every voxel identical; ShellCheck.
 */
final class LodShellSampler {
	private LodShellSampler() {
	}

	/**
	 * As LodSurfaceSampler.sample: heights (the block over the topmost solid one, minY where none) of size x size cells of `cell`
	 * blocks from (x0, z0), and with keepDensity the volume (index ((z size + x) ny + y - minY), ny = maxY - minY + 1): the
	 * density where sampled, +1 / -1 inside cells that are solid / air throughout. corners: the same function (unsplit), exact:
	 * the solid sampler LodSurfaceSampler would use.
	 */
	static float[] sample(DensitySampler.Bound corners, DensitySampler.Bound exact, short[] heights, int size, int cell, int x0, int z0,
		int minY, int maxY, boolean keepDensity) {
		int span = size * cell, g = span / 4 + 1;
		int nyb = maxY - minY + 1, ny = (nyb + 3) / 4 + 1;
		DensityVolume cv = new DensityVolume(g, ny, g, x0, minY, z0, 4, 4, 4);
		var cb = LodBuffers.acquire(cv.size());
		LodDensityBatch.sample(corners, cb, cv);
		float[] density = keepDensity ? LodBuffers.floats(0, size * size * nyb) : null;
		float[] col = new float[nyb];
		int per = Math.max(1, 4 / cell);
		boolean[] mixed = new boolean[ny - 1];
		for (int gz = 0; gz < g - 1; gz++) {
			for (int gx = 0; gx < g - 1; gx++) {
				int lo = Integer.MAX_VALUE, hi = -1;
				for (int k = 0; k < ny - 1; k++) {
					boolean pos = false, neg = false;
					for (int q = 0; q < 8; q++) {
						if (cb.get(cv.indexUnchecked(gx + (q & 1), k + ((q >> 1) & 1), gz + (q >> 2))) > 0.0F) pos = true;
						else neg = true;
					}
					mixed[k] = pos && neg;
					if (mixed[k]) {
						lo = Math.min(lo, k);
						hi = Math.max(hi, k);
					}
				}
				int yLo = 1, yHi = 0;
				if (hi >= 0) {
					yLo = minY + Math.max(0, lo - 1) * 4;
					yHi = Math.min(maxY, minY + Math.min(ny - 2, hi + 1) * 4 + 4);
				}
				net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer d = null;
				DensityVolume pv = null;
				if (yHi >= yLo) {
					pv = new DensityVolume(per, yHi - yLo + 1, per, x0 + gx * 4 + cell / 2, yLo, z0 + gz * 4 + cell / 2, cell, 1, cell);
					d = LodBuffers.acquire(pv.size());
					LodDensityBatch.sample(exact, d, pv);
				}
				for (int dz = 0; dz < per; dz++) {
					for (int dx = 0; dx < per; dx++) {
						int cx = gx * per + dx, cz = gz * per + dz;
						if (cx >= size || cz >= size) continue;
						for (int y = 0; y < nyb; y++) {
							int wy = minY + y;
							if (d != null && wy >= yLo && wy <= yHi) col[y] = d.get(pv.indexUnchecked(dx, wy - yLo, dz));
							else col[y] = cb.get(cv.indexUnchecked(gx, Math.min(ny - 2, y >> 2), gz)) > 0.0F ? 1.0F : -1.0F;
						}
						int top = nyb - 1;
						while (top >= 0 && !(col[top] > 0.0F)) top--;
						heights[cz * size + cx] = (short) (top < 0 ? minY : minY + top + 1);
						if (density != null) System.arraycopy(col, 0, density, (cz * size + cx) * nyb, nyb);
					}
				}
				if (d != null) d.close();
			}
		}
		cb.close();
		return density;
	}
}
