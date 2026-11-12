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
		LodTile t = new LodTile(level, tx, tz);
		LodForest forest = exactTrees && level == 0 ? this.forest : null;
		t.impostorTrees = forest == null;
		this.noise.generate(t);
		if (forest != null) {
			try {
				forest.offer(t);
				forest.plant(t);
			} catch (RuntimeException e) {
				// (planted trees are an extra: a failure leaves the bare ground)
			}
		}
		int c = 1 << level, h = Math.max(1, height >> level);
		Rle[] out = new Rle[4];
		Rle.Builder b = new Rle.Builder(32, h, (airSky & 15) << 24);
		int[] seg = new int[24];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int q = 0; q < 4; q++) {
			int ox = (q & 1) * 32, oz = (q >> 1) * 32;
			b.reset();
			for (int z = 0; z < 32; z++) {
				for (int x = 0; x < 32; x++) {
					int i = (oz + z) * LodTile.SIZE + ox + x;
					int n = this.cell(t, i, level, c, minY, h, ids, seg, pos, airSky);
					b.cell(x, z, seg, n);
				}
			}
			out[q] = b.build();
		}
		return out;
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
	private int cell(LodTile t, int i, int level, int c, int minY, int h, Ids ids, int[] seg, BlockPos.MutableBlockPos pos, int airSky) {
		int n = 0;
		Holder<Biome> bh = t.biome[i];
		int biome = bh != null ? ids.biome(bh) : 0;
		int ground = t.ground[i];
		int top = vy(ground - 1, minY, level, h);
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
			int lo = Math.max(at, vy(t.canopyLo[i], minY, level, h)), hi = vy(t.canopyHi[i], minY, level, h);
			if (t.standing[i] && t.trunk[i] != null && lo > at) n = put(seg, n, at, lo, ids.block(t.trunk[i]) | biome << 16);
			if (hi >= lo) {
				n = put(seg, n, lo, hi + 1, ids.block(t.canopyState[i]) | biome << 16);
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
		float[] f = new float[8];
		System.arraycopy(l.topUv(), 0, f, 0, 4);
		System.arraycopy(l.sideUv(), 0, f, 4, 4);
		return f;
	}

	/** The biome's tint colours: grass (at x, z), foliage, water, dry foliage (RGB). */
	public static int[] tints(Biome b, int x, int z) {
		return new int[] {b.getGrassColor(x, z), b.getFoliageColor(), b.getWaterColor(), b.getDryFoliageColor()};
	}
}
