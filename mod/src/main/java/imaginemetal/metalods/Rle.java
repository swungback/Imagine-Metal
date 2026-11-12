package imaginemetal.metalods;

import java.util.Arrays;

/**
 * A square of n x n voxel cells, each a column of h voxels kept as runs (the terrain's layers: stone, dirt, grass, water, air
 * are a handful of runs per column whatever the height). A run is (value, end): value the voxel (Voxel's encoding), end the
 * voxel y it stops before; a cell's runs go up from y 0 and the last ends at h. Immutable once built.
 */
public final class Rle {
	public final int n, h;
	/** Cell (x, z) = x + z n: its runs are runs[2 offsets[c] ..] up to runs[2 offsets[c + 1]]. */
	final int[] offsets;
	final int[] runs;

	Rle(int n, int h, int[] offsets, int[] runs) {
		this.n = n;
		this.h = h;
		this.offsets = offsets;
		this.runs = runs;
	}

	/** All air with the sky's light. */
	public static Rle empty(int n, int h) {
		int[] off = new int[n * n + 1];
		int[] runs = new int[2 * n * n];
		for (int c = 0; c < n * n; c++) {
			off[c + 1] = c + 1;
			runs[2 * c] = Voxel.SKY_AIR;
			runs[2 * c + 1] = h;
		}
		return new Rle(n, h, off, runs);
	}

	public int value(int x, int y, int z) {
		int c = x + z * this.n;
		for (int r = this.offsets[c]; r < this.offsets[c + 1]; r++) {
			if (y < this.runs[2 * r + 1]) return this.runs[2 * r];
		}
		return Voxel.SKY_AIR;
	}

	/** Cell (x, z)'s voxels into dst[at .. at + h). */
	public void expand(int x, int z, int[] dst, int at) {
		int c = x + z * this.n, y = 0;
		for (int r = this.offsets[c]; r < this.offsets[c + 1]; r++) {
			int v = this.runs[2 * r], e = Math.min(this.runs[2 * r + 1], this.h);
			if (e > y) {
				Arrays.fill(dst, at + y, at + e, v);
				y = e;
			}
		}
		if (y < this.h) Arrays.fill(dst, at + y, at + this.h, Voxel.SKY_AIR);
	}

	/** The highest non-air voxel of cell (x, z), -1 if none. */
	public int top(int x, int z) {
		int c = x + z * this.n;
		int best = -1, from = 0;
		for (int r = this.offsets[c]; r < this.offsets[c + 1]; r++) {
			int v = this.runs[2 * r], e = this.runs[2 * r + 1];
			if (!Voxel.isAir(v) && e > from) best = e - 1;
			from = e;
		}
		return best;
	}

	/** Bytes this takes in memory (about). */
	public long bytes() {
		return 64 + 4L * (this.offsets.length + this.runs.length);
	}

	/**
	 * The next coarser level: n / 2 cells of h / 2 voxels. Of a voxel's eight children the most solid wins (solid over leaves
	 * over water over air), ties to the upper ones (the surface is what's seen from far away); eight of air give air with the
	 * brightest of their light (a lit place stays lit from afar).
	 */
	public Rle mip() {
		int m = Math.max(1, this.n / 2), hh = Math.max(1, this.h / 2);
		Builder b = new Builder(m, hh);
		int[] a = new int[this.h], c2 = new int[this.h], d = new int[this.h], e = new int[this.h];
		int[] col = new int[hh];
		for (int z = 0; z < m; z++) {
			for (int x = 0; x < m; x++) {
				int x0 = Math.min(2 * x, this.n - 1), x1 = Math.min(2 * x + 1, this.n - 1);
				int z0 = Math.min(2 * z, this.n - 1), z1 = Math.min(2 * z + 1, this.n - 1);
				this.expand(x0, z0, a, 0);
				this.expand(x1, z0, c2, 0);
				this.expand(x0, z1, d, 0);
				this.expand(x1, z1, e, 0);
				for (int y = 0; y < hh; y++) {
					int y0 = Math.min(2 * y, this.h - 1), y1 = Math.min(2 * y + 1, this.h - 1);
					col[y] = pick(a[y0], c2[y0], d[y0], e[y0], a[y1], c2[y1], d[y1], e[y1]);
				}
				b.column(x, z, col);
			}
		}
		return b.build();
	}

	private static int pick(int l0, int l1, int l2, int l3, int u0, int u1, int u2, int u3) {
		int best = u0, rank = Voxel.rank(u0);
		int r;
		if ((r = Voxel.rank(u1)) > rank) { best = u1; rank = r; }
		if ((r = Voxel.rank(u2)) > rank) { best = u2; rank = r; }
		if ((r = Voxel.rank(u3)) > rank) { best = u3; rank = r; }
		if ((r = Voxel.rank(l0)) > rank) { best = l0; rank = r; }
		if ((r = Voxel.rank(l1)) > rank) { best = l1; rank = r; }
		if ((r = Voxel.rank(l2)) > rank) { best = l2; rank = r; }
		if ((r = Voxel.rank(l3)) > rank) { best = l3; rank = r; }
		if (rank > 0) return best;
		// all air: the brightest sky and block light of the eight
		int sky = 0, blk = 0;
		for (int v : new int[] {l0, l1, l2, l3, u0, u1, u2, u3}) {
			sky = Math.max(sky, Voxel.sky(v));
			blk = Math.max(blk, Voxel.block(v));
		}
		return Voxel.air(sky, blk);
	}

	/** Copies cells of src (an n' x n' grid at the same height) into a copy of this at (dx, dz). */
	public Rle overlay(Rle src, int dx, int dz) {
		Builder b = new Builder(this.n, this.h);
		int[] col = new int[this.h];
		for (int z = 0; z < this.n; z++) {
			for (int x = 0; x < this.n; x++) {
				int sx = x - dx, sz = z - dz;
				if (sx >= 0 && sz >= 0 && sx < src.n && sz < src.n) src.expand(sx, sz, col, 0);
				else this.expand(x, z, col, 0);
				b.column(x, z, col);
			}
		}
		return b.build();
	}

	/** Builds an Rle cell by cell (cells may come in any order; every cell is set before build). */
	public static final class Builder {
		final int n, h;
		/** What fills gaps and the rest of a cell given as segments (air with this light). */
		final int fill;
		private final int[][] cellRuns;
		private final int[] cellLen;

		public Builder(int n, int h) {
			this(n, h, Voxel.SKY_AIR);
		}

		public Builder(int n, int h, int fill) {
			this.n = n;
			this.h = h;
			this.fill = fill;
			this.cellRuns = new int[n * n][];
			this.cellLen = new int[n * n];
		}

		public void reset() {
			Arrays.fill(this.cellLen, 0);
		}

		private void add(int c, int value, int end) {
			int[] r = this.cellRuns[c];
			int len = this.cellLen[c];
			if (len > 0 && r[2 * len - 2] == value) {
				r[2 * len - 1] = end;
				return;
			}
			if (r == null) r = this.cellRuns[c] = new int[16];
			else if (2 * len + 2 > r.length) r = this.cellRuns[c] = Arrays.copyOf(r, r.length * 2);
			r[2 * len] = value;
			r[2 * len + 1] = end;
			this.cellLen[c] = len + 1;
		}

		/** Cell (x, z) from segments (from, to, value) x count/3, ascending; gaps and the rest up to h are air with the sky's light. */
		public void cell(int x, int z, int[] seg, int count) {
			int c = x + z * this.n;
			this.cellLen[c] = 0;
			int y = 0;
			for (int i = 0; i + 2 < count; i += 3) {
				int from = Math.max(seg[i], y), to = Math.min(seg[i + 1], this.h);
				if (to <= from) continue;
				if (from > y) this.add(c, this.fill, from);
				this.add(c, seg[i + 2], to);
				y = to;
			}
			if (y < this.h) this.add(c, this.fill, this.h);
		}

		/** Cell (x, z) from h voxels. */
		public void column(int x, int z, int[] col) {
			int c = x + z * this.n;
			this.cellLen[c] = 0;
			int y = 0;
			while (y < this.h) {
				int v = col[y], e = y + 1;
				while (e < this.h && col[e] == v) e++;
				this.add(c, v, e);
				y = e;
			}
		}

		/** Cell (x, z) from raw runs (value, end) pairs. */
		void runs(int x, int z, int[] src, int from, int pairs) {
			int c = x + z * this.n;
			this.cellLen[c] = 0;
			for (int p = 0; p < pairs; p++) this.add(c, src[from + 2 * p], Math.min(this.h, src[from + 2 * p + 1]));
			int len = this.cellLen[c];
			if (len == 0 || this.cellRuns[c][2 * len - 1] < this.h) this.add(c, Voxel.SKY_AIR, this.h);
		}

		public Rle build() {
			int cells = this.n * this.n;
			int[] off = new int[cells + 1];
			int total = 0;
			for (int c = 0; c < cells; c++) {
				off[c] = total;
				total += this.cellLen[c] == 0 ? 1 : this.cellLen[c];
			}
			off[cells] = total;
			int[] runs = new int[2 * total];
			for (int c = 0; c < cells; c++) {
				int len = this.cellLen[c];
				if (len == 0) {
					runs[2 * off[c]] = Voxel.SKY_AIR;
					runs[2 * off[c] + 1] = this.h;
				} else {
					System.arraycopy(this.cellRuns[c], 0, runs, 2 * off[c], 2 * len);
				}
			}
			return new Rle(this.n, this.h, off, runs);
		}
	}
}
