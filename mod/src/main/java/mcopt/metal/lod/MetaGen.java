package mcopt.metal.lod;

import imaginemetal.metalods.Rle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jspecify.annotations.Nullable;

/**
 * (MetaLODS) Far terrain straight from the world seed, for Imagine Metal's voxel LODs: mcopt's own generator (LodNoise: the
 * game's density functions sampled at the LOD's resolution, its biomes from the climate noise, the top blocks from the
 * dimension's surface rules, the sea and the aquifers; LodForest: the game's own trees on the nearest level) run for one
 * 64 x 64-cell tile, and the tile's columns turned into voxel columns: stone, the block under the surface, the surface block,
 * water up to its level (ice where it freezes), the trees' trunks and leaves, air with the sky's light over all of it.
 *
 * A tile of level L is 2 x 2 MetaLODS columns of 32 x 32 cells, each cell 2^L blocks wide and its voxels 2^L blocks tall.
 */
public final class MetaGen {
	/** Ids for blocks and biomes, as MetaLODS numbers them. */
	public interface Ids {
		int block(BlockState state);

		int biome(Holder<Biome> biome);
	}

	private final LodNoise noise;
	private final @Nullable LodForest forest;
	private final BlockState stone, water, ice, snow;
	/** Where the time goes, per level (0-7): nanoseconds in the surface (LodNoise), the trees, the 3D density, the columns; tiles. */
	public static final java.util.concurrent.atomic.AtomicLongArray NANOS = new java.util.concurrent.atomic.AtomicLongArray(8 * 5);

	/** LodNoise's own stages per level with tiles: "L0 n: density a + biomes b + materials c ms" (the tree runs' ground tiles count too). */
	public String stages() {
		StringBuilder b = new StringBuilder();
		for (int l = 0; l < 8; l++) {
			long n = this.noise.stageNanos.get(l * 4 + 3);
			if (n == 0) continue;
			if (b.length() > 0) b.append("; ");
			b.append(String.format(java.util.Locale.ROOT, "L%d %d: density %.1f + biomes %.1f + materials %.1f ms", l, n, this.noise.stageNanos.get(l * 4) / 1e6 / n,
				this.noise.stageNanos.get(l * 4 + 1) / 1e6 / n, this.noise.stageNanos.get(l * 4 + 2) / 1e6 / n));
		}
		if (this.forest != null && this.forest.runs.get() > 0) b.append(String.format(java.util.Locale.ROOT, "; tree chunks %d: %.2f ms each", this.forest.runs.get(),
			this.forest.runNanos.get() / 1e6 / this.forest.runs.get()));
		return b.toString();
	}

	public void resetStages() {
		for (int i = 0; i < this.noise.stageNanos.length(); i++) this.noise.stageNanos.set(i, 0);
		if (this.forest != null) {
			this.forest.runs.set(0);
			this.forest.runNanos.set(0);
		}
	}

	/** NANOS as text: per level with tiles, "L0 n tiles: surface a + trees b + 3D c + columns d ms". */
	public static String timings() {
		StringBuilder b = new StringBuilder();
		for (int l = 0; l < 8; l++) {
			long n = NANOS.get(l * 5 + 4);
			if (n == 0) continue;
			if (b.length() > 0) b.append("; ");
			b.append(String.format(java.util.Locale.ROOT, "L%d %d tiles: surface %.1f + trees %.1f + 3D %.1f + columns %.1f ms", l, n, NANOS.get(l * 5) / 1e6 / n,
				NANOS.get(l * 5 + 1) / 1e6 / n, NANOS.get(l * 5 + 2) / 1e6 / n, NANOS.get(l * 5 + 3) / 1e6 / n));
		}
		return b.toString();
	}

	private MetaGen(LodNoise noise, @Nullable LodForest forest) {
		this.noise = noise;
		this.forest = forest;
		this.stone = noise.settings.defaultBlock();
		this.water = Blocks.WATER.defaultBlockState();
		this.ice = Blocks.ICE.defaultBlockState();
		this.snow = Blocks.SNOW_BLOCK.defaultBlockState();
	}

	/** A generator for the level, or null where it can't be done (not noise based, a ceiling: the Nether). */
	public static @Nullable MetaGen of(ServerLevel level, boolean trees) {
		LodNoise n = LodNoise.of(level);
		if (n == null) return null;
		// (the surface from the terrain's shape at every level: see LodNoise.fineLevels)
		n.fineLevels = 0;
		LodForest f = null;
		if (trees) {
			try {
				f = new LodForest(n);
				if (!f.available()) f = null;
			} catch (RuntimeException e) {
				System.out.println("[MetaLODS] the game's own trees aren't available (" + e + "): the biomes' estimate everywhere");
				f = null;
			}
		}
		return new MetaGen(n, f);
	}

	/**
	 * Generates tile (tx, tz) of level `level` (64 x 64 cells of 2^level blocks) and returns its four columns, in the order
	 * (0, 0), (1, 0), (0, 1), (1, 1) of (cx - 2 tx, cz - 2 tz). minY / height: the dimension's, in blocks (height a multiple of
	 * 32). exactTrees: the game's own trees where they can be (level 0 only), else the biome's estimate.
	 */
	public Rle[] generate(int level, int tx, int tz, int minY, int height, boolean exactTrees, int airSky, Ids ids) {
		return this.generate(level, tx, tz, minY, height, exactTrees, airSky, false, ids);
	}

	/**
	 * As above; with overhangs, the terrain's density is also sampled in 3D at the tile's resolution and carves what lies under
	 * the surface (overhangs, arches, cliffs' hollows) and adds what floats above it (islands, lips LodNoise's heightfield left
	 * out). The surface block LodNoise found stays where it is, with a voxel of skin under it.
	 */
	public Rle[] generate(int level, int tx, int tz, int minY, int height, boolean exactTrees, int airSky, boolean overhangs, Ids ids) {
		LodTile t = new LodTile(level, tx, tz);
		LodForest forest = exactTrees && level == 0 ? this.forest : null;
		t.impostorTrees = forest == null;
		// (with overhangs the surface is found in the very samples the 3D carve uses: one volume for both)
		t.keepVolume = overhangs;
		long n0 = System.nanoTime();
		this.noise.generate(t);
		long n1 = System.nanoTime();
		if (forest != null) {
			try {
				forest.offer(t);
				forest.plant(t);
			} catch (RuntimeException e) {
				// (planted trees are an extra: a failure leaves the bare ground)
			}
		}
		long n2 = System.nanoTime();
		int c = 1 << level, h = Math.max(1, height >> level);
		Rle[] out = new Rle[4];
		Rle.Builder b = new Rle.Builder(32, h, (airSky & 15) << 24);
		int[] seg = new int[24];
		int[] info = new int[4];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// (3D: the density at the sample grid LodNoise uses, every max(c, 4) blocks across, 8 up)
		float[] dv = null;
		int s = Math.max(c, 4), g = LodTile.SIZE * c / s + 1, ny = 0, y0 = 0;
		if (overhangs) {
			try {
				dv = t.volume != null ? t.volume : this.noise.terrainVolume(t.x0, t.z0, g, s);
				ny = this.noise.volumeNy();
				y0 = this.noise.volumeY0();
			} catch (RuntimeException e) {
				dv = null;
			}
		}
		long n3 = System.nanoTime();
		int[] col = new int[h];
		float[] dens = new float[h];
		float[] cd = new float[Math.max(2, ny)];
		for (int q = 0; q < 4; q++) {
			int ox = (q & 1) * 32, oz = (q >> 1) * 32;
			b.reset();
			for (int z = 0; z < 32; z++) {
				for (int x = 0; x < 32; x++) {
					int i = (oz + z) * LodTile.SIZE + ox + x;
					int n = this.cell(t, i, level, c, minY, h, ids, seg, pos, airSky, info);
					if (dv == null || info[0] < 0) {
						b.cell(x, z, seg, n);
						continue;
					}
					// the column as voxels, then the density's say
					java.util.Arrays.fill(col, (airSky & 15) << 24);
					for (int k = 0; k + 2 < n; k += 3) java.util.Arrays.fill(col, Math.max(0, seg[k]), Math.min(h, seg[k + 1]), seg[k + 2]);
					float bx = ((ox + x) * c + c * 0.5F) / s, bz = ((oz + z) * c + c * 0.5F) / s;
					int ix = Math.min(g - 2, (int) bx), iz = Math.min(g - 2, (int) bz);
					float fx = bx - ix, fz = bz - iz;
					int a00 = (ix + iz * g) * ny, a10 = (ix + 1 + iz * g) * ny, a01 = (ix + (iz + 1) * g) * ny, a11 = (ix + 1 + (iz + 1) * g) * ny;
					// (the column's own samples first, then each voxel between two of them: the same values as interpolating per voxel)
					for (int k = 0; k < ny; k++) cd[k] = lerp2(dv[a00 + k], dv[a10 + k], dv[a01 + k], dv[a11 + k], fx, fz);
					for (int v = 0; v < h; v++) {
						float wy = minY + (v + 0.5F) * c;
						float kf = (wy - y0) / 8.0F;
						int k0 = Math.clamp((int) Math.floor(kf), 0, ny - 2);
						float fk = Math.clamp(kf - k0, 0.0F, 1.0F);
						dens[v] = cd[k0] + (cd[k0 + 1] - cd[k0]) * fk;
					}
					this.carve(col, dens, h, info, (airSky & 15) << 24);
					b.column(x, z, col);
				}
			}
			out[q] = b.build();
		}
		int tl = Math.min(7, level) * 5;
		NANOS.addAndGet(tl, n1 - n0);
		NANOS.addAndGet(tl + 1, n2 - n1);
		NANOS.addAndGet(tl + 2, n3 - n2);
		NANOS.addAndGet(tl + 3, System.nanoTime() - n3);
		NANOS.incrementAndGet(tl + 4);
		return out;
	}

	private static float lerp2(float a, float b, float c, float d, float fx, float fz) {
		float ab = a + (b - a) * fx, cd = c + (d - c) * fx;
		return ab + (cd - ab) * fz;
	}

	/**
	 * 3D on a heightfield column: under the surface (keeping it and a voxel under it), voxels the density calls air are hollowed
	 * out, and the solid ground left with a hollow over it gets the surface block (a ledge under an overhang grows grass);
	 * over everything the column holds (water, trees), voxels the density calls solid are added (floating ground), with the
	 * surface block on top, the block under it below, stone inside. info: top voxel, surface id, under id, first voxel over the
	 * column's contents.
	 */
	private void carve(int[] col, float[] dens, int h, int[] info, int air) {
		int top = info[0], surface = info[1], under = info[2], at = info[3];
		int stone = col[0];
		for (int v = 0; v < top - 1 && v < h; v++) if (dens[v] <= 0.0F && v > 0) col[v] = air;
		for (int v = 1; v < top - 1 && v < h; v++) {
			if (!imaginemetal.metalods.Voxel.isAir(col[v]) && imaginemetal.metalods.Voxel.isAir(col[v + 1])) col[v] = (col[v] & 0xFF0000) | (surface & 0xFFFF);
		}
		for (int v = Math.max(at, top + 1); v < h; v++) {
			if (dens[v] > 0.0F && imaginemetal.metalods.Voxel.isAir(col[v])) col[v] = (stone & 0xFF0000) | (stone & 0xFFFF);
		}
		for (int v = h - 1; v > Math.max(at, top + 1) - 1 && v >= 0; v--) {
			if (v <= top || !(dens[v] > 0.0F)) continue;
			boolean airAbove = v + 1 >= h || imaginemetal.metalods.Voxel.isAir(col[v + 1]);
			if (airAbove) {
				col[v] = (stone & 0xFF0000) | (surface & 0xFFFF);
				if (v - 1 > top && dens[v - 1] > 0.0F) col[v - 1] = (stone & 0xFF0000) | (under & 0xFFFF);
			}
		}
	}

	/** Voxel y of block y at this level (clamped into the column, -1: below it). */
	private static int vy(int y, int minY, int level, int h) {
		int v = Math.floorDiv(y - minY, 1 << level);
		return Math.min(v, h - 1);
	}

	/**
	 * Cell i's segments as (from, to exclusive, value) triples into seg, ascending, from voxel 0; returns how many ints were
	 * written. What's not covered is air (with the sky's full light).
	 */
	private int cell(LodTile t, int i, int level, int c, int minY, int h, Ids ids, int[] seg, BlockPos.MutableBlockPos pos, int airSky, int[] info) {
		int n = 0;
		Holder<Biome> bh = t.biome[i];
		int biome = bh != null ? ids.biome(bh) : 0;
		int ground = t.ground[i];
		int top = vy(ground - 1, minY, level, h);
		info[0] = top;
		if (top < 0) return 0;
		BlockState surface = t.state[i] != null ? t.state[i] : this.stone;
		BlockState under = t.under[i] != null ? t.under[i] : this.stone;
		int bx = t.x0 + (i % t.size) * c + c / 2, bz = t.z0 + (i / t.size) * c + c / 2;
		boolean wet = t.water[i] != LodTile.DRY && t.water[i] > ground;
		boolean cold = false;
		if (bh != null) {
			pos.set(bx, wet ? t.water[i] : ground, bz);
			try {
				cold = bh.value().coldEnoughToSnow(pos, this.noise.seaLevel);
			} catch (RuntimeException e) {
				cold = false;
			}
		}
		if (!wet && cold && surface.isSolidRender()) {
			if (surface.hasProperty(BlockStateProperties.SNOWY)) surface = surface.setValue(BlockStateProperties.SNOWY, true);
			else surface = this.snow;
		}
		int underVox = level == 0 ? 3 : level == 1 ? 1 : 0;
		int stoneTop = Math.max(0, top - underVox);
		int sky = (airSky & 15) << 24;
		if (stoneTop > 0) n = put(seg, n, 0, stoneTop, ids.block(this.stone) | biome << 16);
		if (top > stoneTop) n = put(seg, n, stoneTop, top, ids.block(under) | biome << 16);
		n = put(seg, n, top, top + 1, ids.block(surface) | biome << 16);
		info[1] = ids.block(surface);
		info[2] = ids.block(under);
		int at = top + 1;
		if (wet) {
			int wTop = vy(t.water[i] - 1, minY, level, h);
			if (wTop >= at) {
				if (cold) {
					if (wTop > at) n = put(seg, n, at, wTop, ids.block(this.water) | biome << 16);
					n = put(seg, n, Math.max(at, wTop), wTop + 1, ids.block(this.ice) | biome << 16);
				} else {
					n = put(seg, n, at, wTop + 1, ids.block(this.water) | biome << 16);
				}
				at = wTop + 1;
			}
		} else if (t.canopyHi[i] >= t.canopyLo[i] && t.canopyState[i] != null) {
			// the game's own trees (level 0)
			// (a column the tree stands in: its trunk up to its top block, as LodNoise.dressTrees draws it; a crown floating over
			// the ground: its leaves from its lowest block to its top)
			int lo = Math.max(at, vy(t.canopyLo[i], minY, level, h)), hi = vy(t.canopyHi[i], minY, level, h);
			if (hi >= at) {
				if (t.standing[i] && t.trunk[i] != null) {
					if (hi > at) n = put(seg, n, at, hi, ids.block(t.trunk[i]) | biome << 16);
					n = put(seg, n, hi, hi + 1, ids.block(t.canopyState[i]) | biome << 16);
				} else if (hi >= lo) {
					n = put(seg, n, lo, hi + 1, ids.block(t.canopyState[i]) | biome << 16);
				}
				at = hi + 1;
			}
		} else if (t.impostorTrees && t.height[i] > ground && bh != null) {
			// the biome's estimate of its woods
			int hi = vy(t.height[i] - 1, minY, level, h);
			if (hi >= at) {
				BlockState leaves = LodTrees.profile(bh).leaves();
				if (!leaves.isAir()) {
					n = put(seg, n, at, hi + 1, ids.block(leaves) | biome << 16);
					at = hi + 1;
				}
			}
		}
		info[3] = at;
		if (at < h) n = put(seg, n, at, h, sky);
		return n;
	}

	private static int put(int[] seg, int n, int from, int to, int value) {
		if (to <= from || n + 3 > seg.length) return n;
		seg[n] = from;
		seg[n + 1] = to;
		seg[n + 2] = value;
		return n + 3;
	}

	/**
	 * What a block looks like for the LODs, from its baked model (mcopt's LodColors): top and side sprites' rectangles in the
	 * block atlas (u0 v0 u1 v1 each), their average colours (sRGB), the tint kinds (LodColors.TINT_*) and a constant tint.
	 * {topUv[4], sideUv[4]} as floats; ints: {topAvg, sideAvg, topTint, sideTint, constant}.
	 */
	public static float[] look(BlockState state, int[] ints) {
		LodColors.Look l = LodColors.look(state);
		ints[0] = l.top();
		ints[1] = l.side();
		ints[2] = l.topTint();
		ints[3] = l.sideTint();
		ints[4] = l.constant();
		if (ints.length > 5) ints[5] = l.overTint();
		float[] f = new float[12];
		System.arraycopy(l.topUv(), 0, f, 0, 4);
		System.arraycopy(l.sideUv(), 0, f, 4, 4);
		System.arraycopy(l.overUv(), 0, f, 8, 4);  // (fix7: the side's tinted overlay, MetaLODS)
		return f;
	}

	/** The biome's tint colours: grass (at x, z), foliage, water, dry foliage (RGB). */
	public static int[] tints(Biome b, int x, int z) {
		return new int[] {b.getGrassColor(x, z), b.getFoliageColor(), b.getWaterColor(), b.getDryFoliageColor()};
	}
}
