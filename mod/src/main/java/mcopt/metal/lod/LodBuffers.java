package mcopt.metal.lod;

import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

/**
 * (fix21) Per-thread reuse of the far terrain's temporary arrays. Measured offline (AllocBench, Terralith, New World's seed): a
 * near level-0 tile allocated 187 MB, a far level-0 68 MB, level 1 106 MB, levels 2-3 61 MB, 45 % of it the density functions'
 * intermediate buffers (SamplerContext's default arena allocates every buffer anew), the rest our own stripes and volumes. At the
 * 4-7 tiles/s of a view filling that is the 0.85-1.1 GB/s F3 showed and the G1 pauses behind the lag spikes. Every worker now
 * has one DensityBufferPool (the game's own, as its chunk generation uses) for the samplers' context and our stripes, aged out
 * after AGE tiles unused (tick), and one reused array per slot for the per-tile volumes.
 */
public final class LodBuffers {
	private LodBuffers() {
	}

	/** Tiles a free buffer may sit unused before it is dropped. */
	static final int AGE = Integer.getInteger("metalods.bufferAge", 6);
	/** -Dmetalods.pool=false: allocate as before (for comparison). */
	static final boolean ON = !"false".equals(System.getProperty("metalods.pool"));

	private static final ThreadLocal<DensityBufferPool> POOL = ThreadLocal.withInitial(() -> new DensityBufferPool(AGE));
	private static final ThreadLocal<float[][]> FLOATS = ThreadLocal.withInitial(() -> new float[4][]);

	/** This thread's pool (the arena of its samplers' context). */
	static DensityBufferPool pool() {
		return POOL.get();
	}

	/** A buffer of n values (contents undefined): close it when done (try-with-resources). */
	static ScopedDensityBuffer acquire(int n) {
		return ON ? POOL.get().acquire(n) : new DensityBufferPool(0).acquire(n);
	}

	/**
	 * This thread's array for `slot` with at least n values (contents undefined, zero only when newly made). Valid until the
	 * thread asks for the same slot again: slot 0 is a tile's kept block volume (LodTile.blockVolume / volume), used up by
	 * MetaGen before the thread starts its next tile.
	 */
	static float[] floats(int slot, int n) {
		if (!ON) return new float[n];
		float[][] a = FLOATS.get();
		float[] f = a[slot];
		if (f == null || f.length < n) a[slot] = f = new float[n];
		return f;
	}

	/** After each tile: buffers unused for AGE tiles go. */
	public static void tick() {
		if (ON) POOL.get().garbageCollect();
	}
}
