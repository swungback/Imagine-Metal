package mcopt.metal.lod;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import org.jspecify.annotations.Nullable;

/**
 * (s1119) The game's ground height of a column (NoiseBasedChunkGenerator.getBaseHeight: a walk down the whole noise column), kept
 * per LOD worker while it works out structure starts (LodForest.starts). Only on a thread inside LodForest.starts: the game's own
 * chunk generation is untouched. -Dmcopt.gen.baseHeightCache=false: off.
 *
 * (s1120) Batched by the noise cell. Jack's 20 Nov profiles: getBaseHeight was ~31 % of all LOD job CPU (structure starts: every
 * jigsaw piece, Ice and Fire's room checks), and the exact-column cache only hit 5-6 % (structures rarely ask the same column
 * twice, but they ask columns next to each other). In 26.3 a column's height is: a NoiseChunk over a 1 x height x 1 volume, the
 * final density sampled over it, then a walk down through the aquifer until the heightmap's predicate holds. The interpolated
 * density nodes sample the corners of the noise cells the volume touches, so a 4 x height x 4 volume on the cell grid costs the
 * same corners as one column of it: one fill answers all 16 columns of the cell (both world-gen heightmaps) for little more than
 * one walk. Same code path as the game's (same NoiseChunk, samplers, aquifer, predicate, the volume's minY and height), so the
 * answers are the game's: -Dmcopt.gen.baseHeightVerify=N also runs the game's walk for the first N misses and logs any difference.
 * -Dmcopt.gen.baseHeightBatch=1: column by column (s1119), 4 (default) or 8 / 16 (more columns, more corners).
 */
public final class LodBaseHeights {
	public static final boolean ENABLED = !"false".equals(System.getProperty("mcopt.gen.baseHeightCache"));
	public static final int BATCH = Math.max(1, Math.min(16, Integer.getInteger("mcopt.gen.baseHeightBatch", 4)));
	/** Blocks per slab of the density sampled at a time from the top (0: the whole column at once). */
	public static final int SLAB = Integer.getInteger("mcopt.gen.baseHeightSlab", 64);
	private static final int VERIFY = Integer.getInteger("mcopt.gen.baseHeightVerify", 0);
	private static final ThreadLocal<LodBaseHeights> CACHE = ThreadLocal.withInitial(LodBaseHeights::new);
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	public static final AtomicLong HITS = new AtomicLong(), MISSES = new AtomicLong(), FILLS = new AtomicLong(), FILL_NANOS = new AtomicLong(),
		WALKS = new AtomicLong(), WALK_NANOS = new AtomicLong(), VERIFIED = new AtomicLong(), WRONG = new AtomicLong();
	/** Would-hit counts for batch sizes 1, 4, 8, 16 (for choosing BATCH from a log). */
	private static final AtomicLong[] SIM = {new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};
	private static final AtomicLong ASKED = new AtomicLong();
	private static final int[] SIM_B = {1, 4, 8, 16};
	private static final int MAX = 1 << 21;
	/** (In a holder: GenMixinPlugin reads ENABLED while mixins are prepared, before Minecraft's classes may load.) */
	private static final class Wg {
		static final Heightmap.Types[] WG = {Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG};
	}

	private final Long2IntOpenHashMap map = new Long2IntOpenHashMap(1 << 14);
	private final LongOpenHashSet[] sim = {new LongOpenHashSet(), new LongOpenHashSet(), new LongOpenHashSet(), new LongOpenHashSet()};
	private @Nullable Object random;
	private int minY, height;
	/** Verify: the batch's answer for the game's walk now running on this thread, and when it started. */
	public int expected = Integer.MIN_VALUE;
	public long walkStart;

	private LodBaseHeights() {
		this.map.defaultReturnValue(Integer.MIN_VALUE);
	}

	/** Around LodForest.starts' work: the cache answers on this thread while inside. */
	static void enter() {
		if (ENABLED) DEPTH.get()[0]++;
	}

	static void exit() {
		if (ENABLED) DEPTH.get()[0]--;
	}

	/** This thread's cache, when it is inside LodForest.starts (null: the game's own call, left alone). */
	public static @Nullable LodBaseHeights active() {
		return ENABLED && DEPTH.get()[0] > 0 ? CACHE.get() : null;
	}

	private static long key(int x, int z, int type) {
		return ((long) (x & 0x3FFFFFF) << 30) | ((long) (z & 0x3FFFFFF) << 4) | (type & 15);
	}

	/** The kept height, or Integer.MIN_VALUE. A different world (random state) or height range empties it first. */
	public int get(int x, int z, int type, Object random, int minY, int height) {
		if (random != this.random || minY != this.minY || height != this.height) {
			this.map.clear();
			for (LongOpenHashSet s : this.sim) s.clear();
			this.random = random;
			this.minY = minY;
			this.height = height;
		}
		ASKED.incrementAndGet();
		for (int i = 0; i < SIM_B.length; i++) {
			int b = SIM_B[i];
			if (!this.sim[i].add(key(Math.floorDiv(x, b), Math.floorDiv(z, b), type))) SIM[i].incrementAndGet();
			if (this.sim[i].size() > MAX) this.sim[i].clear();
		}
		int v = this.map.get(key(x, z, type));
		if (v == Integer.MIN_VALUE) {
			long m = MISSES.incrementAndGet();
			if ((m & ((1 << 10) - 1)) == 0) report();
		} else HITS.incrementAndGet();
		return v;
	}

	private static void report() {
		long h = HITS.get(), m = MISSES.get(), a = Math.max(1, ASKED.get()), f = Math.max(1, FILLS.get()), w = WALKS.get();
		StringBuilder sb = new StringBuilder("[MetaLODS] structure ground heights: ").append(a).append(" asked, ").append(h).append(" kept (")
			.append(Math.round(100.0 * h / a)).append(" %), batch ").append(BATCH).append(" slab ").append(SLAB).append(": ").append(FILLS.get()).append(" fills at ")
			.append(String.format("%.2f", FILL_NANOS.get() / 1e6 / f)).append(" ms");
		if (w > 0) sb.append(", game walks ").append(w).append(" at ").append(String.format("%.2f", WALK_NANOS.get() / 1e6 / w)).append(" ms");
		if (VERIFY > 0) sb.append(", verified ").append(VERIFIED.get()).append(" (").append(WRONG.get()).append(" different)");
		sb.append("; would keep at 1/4/8/16:");
		for (AtomicLong s : SIM) sb.append(' ').append(Math.round(100.0 * s.get() / a)).append('%');
		System.out.println(sb);
	}

	public void put(int x, int z, int type, int value) {
		if (this.map.size() >= MAX) this.map.clear();
		this.map.put(key(x, z, type), value);
	}

	/** Verify the next miss on this thread against the game's walk (first N misses), else answer from a batch fill. */
	public boolean verifyNext() {
		return VERIFY > 0 && VERIFIED.get() < VERIFY;
	}

	public void verifyDone(int x, int z, int game) {
		WALKS.incrementAndGet();
		WALK_NANOS.addAndGet(System.nanoTime() - this.walkStart);
		if (this.expected == Integer.MIN_VALUE) return;
		VERIFIED.incrementAndGet();
		if (this.expected != game && WRONG.incrementAndGet() <= 20)
			System.out.println("[MetaLODS] structure ground height differs at " + x + "," + z + ": batch " + this.expected + ", game " + game);
		this.expected = Integer.MIN_VALUE;
	}

	/**
	 * Fills the BATCH x BATCH columns around (x, z) as NoiseBasedChunkGenerator.iterateNoiseColumn walks one (26.3: the same
	 * NoiseChunk constructor arguments, the final density from its caching samplers over the volume, the aquifer's substance or
	 * the default block per y from the top, the predicate's first hit + 1, else the level's min y) for the type asked and both
	 * world-gen heightmaps, keeps them all, and gives back the one asked for.
	 */
	public int fill(Holder<NoiseGeneratorSettings> settingsHolder, Supplier<Aquifer.FluidPicker> fluids, int x, int z, Heightmap.Types type,
		LevelHeightAccessor level, RandomState random) {
		long t0 = System.nanoTime();
		NoiseGeneratorSettings settings = settingsHolder.value();
		NoiseSettings ns = settings.noiseSettings().clampToHeightAccessor(level);
		int b = BATCH, bx = Math.floorDiv(x, b) * b, bz = Math.floorDiv(z, b) * b;
		Heightmap.Types[] wg = Wg.WG, types = type == wg[0] || type == wg[1] ? wg : new Heightmap.Types[] {type, wg[0], wg[1]};
		int n = types.length;
		int[] got = new int[b * b * n];
		java.util.Arrays.fill(got, Integer.MIN_VALUE);
		if (ns.height() > 0) {
			DensityVolume volume = new DensityVolume(b, ns.height(), b, bx, ns.minY(), bz);
			try (NoiseChunk chunk = new NoiseChunk(random, null, settings, fluids.get(), Blender.empty(), volume)) {
				Aquifer aquifer = chunk.aquifer();
				BlockState fallback = settings.defaultBlock();
				DensitySampler.Bound density = chunk.cachingSamplers().get(settings.noiseRouter().finalDensity());
				int[] left = new int[b * b];
				java.util.Arrays.fill(left, n);
				int open = b * b;
				// (s1120: the column's density in slabs from the top down, until every column has met its heights: the ground is
				// usually far below the top, and the density below it isn't needed; each value is the same wherever its slab starts)
				int slab = SLAB > 0 ? SLAB : ns.height();
				for (int hi = ns.minY() + ns.height(); hi > ns.minY() && open > 0; hi -= slab) {
					int lo = Math.max(ns.minY(), hi - slab);
					DensityVolume part = new DensityVolume(b, hi - lo, b, bx, lo, bz);
					try (ScopedDensityBuffer buffer = density.sampleVolume(part)) {
						for (int dz = 0; dz < b; dz++) for (int dx = 0; dx < b; dx++) {
							int col = dz * b + dx, base = col * n, cx = bx + dx, cz = bz + dz;
							if (left[col] == 0) continue;
							for (int i = part.sizeY() - 1; i >= 0 && left[col] > 0; i--) {
								float d = buffer.get(part.indexUnchecked(dx, i, dz));
								int y = part.blockY(i);
								BlockState s = aquifer.computeSubstance(cx, y, cz, d);
								BlockState state = s == null ? fallback : s;
								for (int t = 0; t < n; t++) if (got[base + t] == Integer.MIN_VALUE && types[t].isOpaque().test(state)) {
									got[base + t] = y + 1;
									if (--left[col] == 0) open--;
								}
							}
						}
					}
				}
			}
		}
		int asked = Integer.MIN_VALUE, minY = level.getMinY();
		for (int dz = 0; dz < b; dz++) for (int dx = 0; dx < b; dx++) for (int t = 0; t < n; t++) {
			int v = got[(dz * b + dx) * n + t];
			if (v == Integer.MIN_VALUE) v = minY;
			this.put(bx + dx, bz + dz, types[t].ordinal(), v);
			if (types[t] == type && bx + dx == x && bz + dz == z) asked = v;
		}
		FILLS.incrementAndGet();
		FILL_NANOS.addAndGet(System.nanoTime() - t0);
		return asked;
	}
}
