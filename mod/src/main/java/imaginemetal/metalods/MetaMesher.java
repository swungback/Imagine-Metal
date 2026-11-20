package imaginemetal.metalods;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * A column's voxels as quads: a face wherever a voxel that isn't air touches air, merged greedily into rectangles of the
 * same block, biome, light and corner shading (up to 32 x 32 voxels), grouped by the direction they face so the draw can skip
 * whole groups that face away from the camera.
 *
 * (fix7) Ambient occlusion: each face voxel's four corners darkened by the voxels in front of it (vanilla's smooth-light rule:
 * the two sides and the diagonal, 3 = open, 0 = in a crease), a byte per quad (corner c = u + 2 v at bits 2c). Faces only merge
 * where every voxel has the same four values, so the shader lays the quad's byte over each voxel of it (metalods.metal ml_fs).
 *
 * A quad is two ints. First: x (5 bits), z (5), y (9), face (3: +x -x +y -y +z -z), width - 1 (5), height - 1 (5); for
 * +-x faces the width runs along z and the height along y, for +-y along x and z, for +-z along x and y. Second: the block,
 * biome and the light of the air in front (Voxel's layout).
 */
final class MetaMesher {
	static final int N = 32, B = N + 2;

	/** What came out: per face its quads (pairs of ints) and their count; the lowest and highest voxel y with a face. */
	static final class Mesh {
		final int[][] quads = new int[6][];
		final int[] counts = new int[6];
		final byte[][] ao = new byte[6][];
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

		final int[] solidCounts = new int[6];

		/** Stable partition: each material pass draws only its own quads, in the original order. */
		void splitWater() {
			for (int face = 0; face < 6; face++) {
				int count = this.counts[face], solid = 0;
				int[] src = this.quads[face];
				byte[] shading = this.ao[face];
				for (int q = 0; q < count; q++) {
					if (MetaPalette.kind(src[2 * q + 1] & 65535) != Voxel.KIND_WATER) solid++;
				}
				this.solidCounts[face] = solid;
				if (solid == 0 || solid == count) continue;
				int[] sorted = new int[count * 2];
				byte[] sortedAo = new byte[count];
				int land = 0, water = solid;
				for (int q = 0; q < count; q++) {
					int at = MetaPalette.kind(src[2 * q + 1] & 65535) == Voxel.KIND_WATER ? water++ : land++;
					sorted[2 * at] = src[2 * q];
					sorted[2 * at + 1] = src[2 * q + 1];
					sortedAo[at] = shading[q];
				}
				this.quads[face] = sorted;
				this.ao[face] = sortedAo;
			}
		}

		int total() {
			int t = 0;
			for (int c : this.counts) t += c;
			return t;
		}
	}

	/** Skirts: at the column's sides, a cell's top voxels always get their outward faces (a neighbour drawn at another level
	 * doesn't line up with this one: without them its lower ground left slits to see through). Hidden where it does line up. Water never gets solid skirts: internal water walls
	 * show as reflective rectangular strips through a neighboring translucent surface. */
	static final int SKIRT = 2;
	private int[] grid = new int[0];
	private int[] mask = new int[0], aoMask = new int[0];
	private final int[] tops = new int[N * N];
	private final int[][] out = new int[6][];
	private final byte[][] outAo = new byte[6][];
	private final int[] outN = new int[6];
	private int h;

	MetaMesher() {
		for (int f = 0; f < 6; f++) {
			this.out[f] = new int[2048];
			this.outAo[f] = new byte[1024];
		}
	}

	/** After mesh(): each cell's (x + z N) topmost voxel that isn't air (-1: none). */
	int[] tops() {
		return this.tops;
	}

	private int at(int x, int z, int y) {
		return ((z + 1) * B + (x + 1)) * this.h + y;
	}

	/**
	 * Meshes column c. edges: the neighbouring columns' cells along each side (-x, +x, -z, +z), 32 cells of h voxels each
	 * (cell i at [i h, (i + 1) h)), or null where unknown (that side then gets no faces where the column's own edge is).
	 */
	Mesh mesh(Rle c, int @Nullable [][] edges) {
		return this.mesh(c, edges, -1);
	}

	/** As above; level >= 0: the column's level (pockets of dark air are closed, see closeCaves), -1: as stored. */
	Mesh mesh(Rle c, int @Nullable [][] edges, int level) {
		int h = c.h;
		this.h = h;
		int size = B * B * h;
		if (this.grid.length < size) this.grid = new int[size];
		int[] g = this.grid;
		for (int z = 0; z < N; z++) for (int x = 0; x < N; x++) c.expand(x, z, g, this.at(x, z, 0));
		// the border ring: the neighbours' cells, or this column's own edge (no faces) where they aren't known
		for (int i = 0; i < N; i++) {
			this.border(-1, i, 0, i, edges != null ? edges[0] : null, i);
			this.border(N, i, N - 1, i, edges != null ? edges[1] : null, i);
			this.border(i, -1, i, 0, edges != null ? edges[2] : null, i);
			this.border(i, N, i, N - 1, edges != null ? edges[3] : null, i);
		}
		// (corners: never looked at by the faces)
		if (level >= 0) {
			if (SKY_FILL) this.skyLight(level);
			this.closeCaves(level);
		}
		// The y range that can hold faces: a voxel that differs from the one above it somewhere
		int lo = h, hi = -1;
		for (int z = -1; z <= N; z++) {
			for (int x = -1; x <= N; x++) {
				if ((x < 0 || x >= N) && (z < 0 || z >= N)) continue;
				int a = this.at(x, z, 0);
				for (int y = 0; y < h - 1; y++) {
					if (g[a + y] != g[a + y + 1]) {
						if (y < lo) lo = y;
						if (y + 1 > hi) hi = y + 1;
					}
				}
				if (!Voxel.isAir(g[a + h - 1])) hi = h - 1;
			}
		}
		for (int z = 0; z < N; z++) {
			for (int x = 0; x < N; x++) {
				int a = this.at(x, z, 0), t = -1;
				for (int y = h - 1; y >= 0; y--) {
					if (!Voxel.isAir(g[a + y])) {
						t = y;
						break;
					}
				}
				this.tops[x + z * N] = t;
			}
		}
		Arrays.fill(this.outN, 0);
		Mesh m = new Mesh();
		if (hi >= lo) {
			int y0 = Math.max(0, lo - 1), y1 = Math.min(h - 1, hi + 1);
			this.facesY(2, y0, y1);
			this.facesY(3, y0, y1);
			this.facesX(0, y0, y1);
			this.facesX(1, y0, y1);
			this.facesZ(4, y0, y1);
			this.facesZ(5, y0, y1);
		}
		for (int f = 0; f < 6; f++) {
			m.counts[f] = this.outN[f];
			m.quads[f] = Arrays.copyOf(this.out[f], 2 * this.outN[f]);
			m.ao[f] = Arrays.copyOf(this.outAo[f], this.outN[f]);
			for (int q = 0; q < this.outN[f]; q++) {
				int w0 = this.out[f][2 * q];
				int y = (w0 >>> 10) & 511, hh = (f == 2 || f == 3) ? 1 : ((w0 >>> 27) & 31) + 1;
				m.minY = Math.min(m.minY, y);
				m.maxY = Math.max(m.maxY, y + hh);
			}
		}
		return m;
	}

	/**
	 * (fix11) Caves aren't seen from outside, but since captured air stays air (the curtains under floating islands) every cave
	 * wall in a real chunk became faces: meshes about twice the size, the arena full and the frame slower. In this mesh's own
	 * copy (the stored voxels keep their air) a pocket of air that no sky light reaches, at most CAVE_BLOCKS tall, with solid
	 * ground under it and solid over it, is meshed as the ground under it. The open air under a floating island stays open: it
	 * is taller, or over water, or lit from the island's sides.
	 */
	private void closeCaves(int level) {
		int[] g = this.grid;
		int h = this.h, max = Math.max(1, CAVE_BLOCKS >> level);
		for (int z = -1; z <= N; z++) {
			for (int x = -1; x <= N; x++) {
				if ((x < 0 || x >= N) && (z < 0 || z >= N)) continue;
				int a = this.at(x, z, 0), y = 0;
				while (y < h) {
					if (!Voxel.isAir(g[a + y])) {
						y++;
						continue;
					}
					int s = y;
					boolean dark = true;
					while (y < h && Voxel.isAir(g[a + y])) {
						if (Voxel.sky(g[a + y]) > DIM) dark = false;
						y++;
					}
					if (dark && s > 0 && y < h && y - s <= max && Voxel.rank(g[a + s - 1]) == Voxel.KIND_SOLID
						&& Voxel.rank(g[a + y]) == Voxel.KIND_SOLID) Arrays.fill(g, a + s, a + y, g[a + s - 1]);
				}
			}
		}
	}

	/** The tallest cave pocket closeCaves fills, in blocks. */
	static final int CAVE_BLOCKS = Integer.getInteger("metalods.caveBlocks", 24);
	/** (fix21) -Dmetalods.skyFill=false: generated air keeps the full sky light it is made with (caves all lit, as before). */
	static final boolean SKY_FILL = !"false".equals(System.getProperty("metalods.skyFill"));
	/** (fix21) closeCaves fills enclosed air lit up to this (0: dark only); -Dmetalods.caveDim. */
	static final int DIM = Integer.getInteger("metalods.caveDim", 4);
	private byte[] light = new byte[0];
	private int[] queue = new int[0];

	private boolean clear(int v) {
		return Voxel.isAir(v) || Voxel.rank(v) != Voxel.KIND_SOLID;
	}

	/**
	 * (fix21) Sky light as the game spreads it, over this mesh's grid (border ring included): 15 straight down through air from
	 * the top, one less per step any other way and per block of leaves or water passed. Generated columns hold air with the full
	 * sky light (they are made without light), so their caves were all "lit": every cave wall of a near tile (the whole router,
	 * caves and all) became faces, ~31 pages a column against ~10 for a far one, and the caves' faces were lit as daylight where
	 * an entrance shows them. Air whose stored light is below 15 (a real chunk's, the game's own light) keeps it; air at 15 takes
	 * this. Cave entrances stay open and lit as far as the light reaches (the game's 15 blocks); the dark pockets behind are what
	 * closeCaves fills.
	 */
	private void skyLight(int level) {
		// (s1119: a voxel of level L is 2^L blocks: the light drops by that much per step, as the game's over as many blocks.
		// It dropped 1 a voxel, so under an overhang the coarser levels were 2 to 16 times too bright)
		int step = 1 << Math.min(level, 4);
		int[] g = this.grid;
		int h = this.h, size = B * B * h;
		if (this.light.length < size) this.light = new byte[size];
		byte[] L = this.light;
		java.util.Arrays.fill(L, 0, size, (byte) 0);
		int qn = 0;
		if (this.queue.length < size) this.queue = new int[Math.max(size, 1024)];
		int[] q = this.queue;
		for (int z = -1; z <= N; z++) {
			for (int x = -1; x <= N; x++) {
				int a = this.at(x, z, 0);
				for (int y = h - 1; y >= 0 && Voxel.isAir(g[a + y]); y--) L[a + y] = 15;
			}
		}
		// the open sky's edge: lit voxels next to unlit clear ones
		for (int z = -1; z <= N; z++) {
			for (int x = -1; x <= N; x++) {
				int a = this.at(x, z, 0);
				for (int y = h - 1; y >= 0 && L[a + y] == 15; y--) {
					int i = a + y;
					if (y > 0 && L[i - 1] == 0 && this.clear(g[i - 1]) || this.sideDark(x, z, y)) q[qn++] = i;
				}
			}
		}
		int head = 0;
		int stride = h, row = B * h;
		while (head < qn) {
			int i = q[head++];
			int l = L[i];
			if (l <= 1) continue;
			int y = i % h, col = i / h, cx = col % B, cz = col / B;
			for (int d = 0; d < 6; d++) {
				int j;
				if (d == 0) { if (y == 0) continue; j = i - 1; }
				else if (d == 1) { if (y == h - 1) continue; j = i + 1; }
				else if (d == 2) { if (cx == 0) continue; j = i - stride; }
				else if (d == 3) { if (cx == B - 1) continue; j = i + stride; }
				else if (d == 4) { if (cz == 0) continue; j = i - row; }
				else { if (cz == B - 1) continue; j = i + row; }
				int v = g[j];
				if (!this.clear(v)) continue;
				// (s1119: water and leaves take 1 a block, as the game's light dampening of 1 for both: it took 2, which with the
				// sky-light floor gone left the LOD lakes' beds darker than the real ones)
				// (open water passes the sky's 15 straight down too, for the beds' faces below: their light is the water's in front
				// of them, and the real chunks' lake beds are lit as open air by the lighting's open-air test, which LOD pixels skip)
				int nl = d == 0 && l == 15 && (Voxel.isAir(v) || Voxel.rank(v) == Voxel.KIND_WATER) ? 15 : l - step;
				if (nl > L[j]) {
					L[j] = (byte) nl;
					if (qn >= q.length) {
						// (compact: everything before head is done)
						System.arraycopy(q, head, q, 0, qn - head);
						qn -= head;
						head = 0;
						if (qn >= q.length) this.queue = q = java.util.Arrays.copyOf(q, q.length * 2);
					}
					q[qn++] = j;
				}
			}
		}
		for (int i = 0; i < size; i++) {
			int v = g[i];
			if (Voxel.isAir(v) && Voxel.sky(v) == 15 && L[i] < 15) g[i] = (v & 0xF0FFFFFF) | L[i] << 24;
			// (s1119: water takes the light worked out here where it is more: columns made from real chunks hold the game's sky light
			// in their water, low over a deep bed)
			else if (Voxel.rank(v) == Voxel.KIND_WATER && L[i] != Voxel.sky(v) && (Voxel.sky(v) == 15 || L[i] > Voxel.sky(v)))
				g[i] = (v & 0xF0FFFFFF) | L[i] << 24;  // (generated water, made at 15, takes it as the air does)
		}
	}

	private boolean sideDark(int x, int z, int y) {
		for (int d = 0; d < 4; d++) {
			int nx = x + (d == 0 ? -1 : d == 1 ? 1 : 0), nz = z + (d == 2 ? -1 : d == 3 ? 1 : 0);
			if (nx < -1 || nx > N || nz < -1 || nz > N) continue;
			int j = this.at(nx, nz, y);
			if (this.light[j] == 0 && this.clear(this.grid[j])) return true;
		}
		return false;
	}

	private void border(int x, int z, int ox, int oz, int @Nullable [] edge, int i) {
		int[] g = this.grid;
		int dst = this.at(x, z, 0);
		if (edge != null && edge.length >= (i + 1) * this.h) System.arraycopy(edge, i * this.h, g, dst, this.h);
		else System.arraycopy(g, this.at(ox, oz, 0), g, dst, this.h);
	}

	private void emit(int f, int x, int z, int y, int w, int hh, int value, int ao) {
		int n = this.outN[f];
		int[] o = this.out[f];
		if (2 * n + 2 > o.length) o = this.out[f] = Arrays.copyOf(o, o.length * 2);
		if (n + 1 > this.outAo[f].length) this.outAo[f] = Arrays.copyOf(this.outAo[f], this.outAo[f].length * 2);
		o[2 * n] = x | z << 5 | y << 10 | f << 19 | (w - 1) << 22 | (hh - 1) << 27;
		o[2 * n + 1] = value;
		this.outAo[f][n] = (byte) ao;
		this.outN[f] = n + 1;
	}

	/** Whether the voxel at x, z, y (border ring included) darkens a corner next to it: solid or leaves; outside the grid, no. */
	private boolean occ(int x, int z, int y) {
		if (x < -1 || x > N || z < -1 || z > N || y < 0 || y >= this.h) return false;
		return Voxel.rank(this.grid[this.at(x, z, y)]) >= Voxel.KIND_LEAVES;
	}

	/**
	 * The four corners' shading of the face of voxel (x, z, y) facing f: the voxel in front of it is (fx, fz, fy), the face's u
	 * and v run along (ux, uz, uy) and (vx, vz, vy). 0xFF (all open) for water.
	 */
	/**
	 * (fix22) Per-block corner shading on the LODs, off by default: faces only merge where every block's four corners shade
	 * alike, so 76-87 % of the faces stayed 1 x 1 and the merge left 32-41 % more faces than without it (the LODs' cost is their
	 * geometry). Distant Horizons draws its far land without it (Jack 15:23: "DH looks alright, do that if it's much faster").
	 * -Dmetalods.blockAo=true: as before.
	 */
	static final boolean BLOCK_AO = "true".equals(System.getProperty("metalods.blockAo")); // (s1119) was !"false": on by default, so fix22 never took effect

	private int ao(int v, int fx, int fz, int fy, int ux, int uz, int uy, int vx, int vz, int vy) {
		if (!BLOCK_AO || Voxel.rank(v) == Voxel.KIND_WATER) return 0xFF;
		int r = 0;
		for (int c = 0; c < 4; c++) {
			int du = (c & 1) != 0 ? 1 : -1, dv = (c & 2) != 0 ? 1 : -1;
			boolean s1 = this.occ(fx + du * ux, fz + du * uz, fy + du * uy), s2 = this.occ(fx + dv * vx, fz + dv * vz, fy + dv * vy);
			boolean cc = this.occ(fx + du * ux + dv * vx, fz + du * uz + dv * vz, fy + du * uy + dv * vy);
			int a = s1 && s2 ? 0 : 3 - ((s1 ? 1 : 0) + (s2 ? 1 : 0) + (cc ? 1 : 0));
			r |= a << (2 * c);
		}
		return r;
	}

	/** Water hides other water, but it must not erase the seabed or submerged cliffs. */
	private static boolean exposed(int voxel, int front) {
		return Voxel.isAir(front) || Voxel.rank(front) == Voxel.KIND_WATER && Voxel.rank(voxel) > Voxel.KIND_WATER;
	}

	/** Top (2) or bottom (3) faces, a slice per voxel y, merged across x then z. */
	private void facesY(int f, int y0, int y1) {
		int[] g = this.grid;
		int h = this.h;
		if (this.mask.length < N * N) this.mask = new int[N * N];
		if (this.aoMask.length < N * N) this.aoMask = new int[N * N];
		int[] m = this.mask, am = this.aoMask;
		int fdy = f == 2 ? 1 : -1;
		for (int y = y0; y <= y1; y++) {
			boolean any = false;
			for (int z = 0; z < N; z++) {
				for (int x = 0; x < N; x++) {
					int a = this.at(x, z, y);
					int v = g[a], value = 0;
					if (!Voxel.isAir(v)) {
						if (f == 2) {
							int front = y + 1 < h ? g[a + 1] : Voxel.SKY_AIR;
							if (exposed(v, front)) value = Voxel.face(v, front);
						} else if (y > 0) {
							int front = g[a - 1];
							if (exposed(v, front)) value = Voxel.face(v, front);
						}
					}
					m[x + z * N] = value;
					am[x + z * N] = value != 0 ? this.ao(v, x, z, y + fdy, 1, 0, 0, 0, 1, 0) : 0;
					any |= value != 0;
				}
			}
			final int fy = y;
			if (any) this.greedy(m, am, N, N, (u, v, w, hh, value, ao) -> this.emit(f, u, v, fy, w, hh, value, ao));
		}
	}

	/** +x (0) or -x (1) faces, a slice per x, merged along z then y. */
	private void facesX(int f, int y0, int y1) {
		int[] g = this.grid;
		int rows = y1 - y0 + 1;
		if (this.mask.length < N * rows) this.mask = new int[N * rows];
		if (this.aoMask.length < N * rows) this.aoMask = new int[N * rows];
		int[] m = this.mask, am = this.aoMask;
		int dx = f == 0 ? 1 : -1;
		for (int x = 0; x < N; x++) {
			boolean any = false, side = f == 0 ? x == N - 1 : x == 0;
			for (int y = y0; y <= y1; y++) {
				for (int z = 0; z < N; z++) {
					int v = g[this.at(x, z, y)], value = 0, a = 0;
					if (!Voxel.isAir(v)) {
						int front = g[this.at(x + dx, z, y)];
						if (exposed(v, front)) {
							value = Voxel.face(v, front);
							a = this.ao(v, x + dx, z, y, 0, 1, 0, 0, 0, 1);
						} else if (side && Voxel.rank(v) != Voxel.KIND_WATER && y > this.tops[x + z * N] - SKIRT) {
							value = Voxel.face(v, Voxel.SKY_AIR);
							a = 0xFF;  // (a skirt: its front is the neighbour's ground)
						}
					}
					m[z + (y - y0) * N] = value;
					am[z + (y - y0) * N] = a;
					any |= value != 0;
				}
			}
			final int fx = x;
			if (any) this.greedy(m, am, N, rows, (u, v, w, hh, value, ao) -> this.emit(f, fx, u, v + y0, w, hh, value, ao));
		}
	}

	/** +z (4) or -z (5) faces, a slice per z, merged along x then y. */
	private void facesZ(int f, int y0, int y1) {
		int[] g = this.grid;
		int rows = y1 - y0 + 1;
		if (this.mask.length < N * rows) this.mask = new int[N * rows];
		if (this.aoMask.length < N * rows) this.aoMask = new int[N * rows];
		int[] m = this.mask, am = this.aoMask;
		int dz = f == 4 ? 1 : -1;
		for (int z = 0; z < N; z++) {
			boolean any = false, side = f == 4 ? z == N - 1 : z == 0;
			for (int y = y0; y <= y1; y++) {
				for (int x = 0; x < N; x++) {
					int v = g[this.at(x, z, y)], value = 0, a = 0;
					if (!Voxel.isAir(v)) {
						int front = g[this.at(x, z + dz, y)];
						if (exposed(v, front)) {
							value = Voxel.face(v, front);
							a = this.ao(v, x, z + dz, y, 1, 0, 0, 0, 0, 1);
						} else if (side && Voxel.rank(v) != Voxel.KIND_WATER && y > this.tops[x + z * N] - SKIRT) {
							value = Voxel.face(v, Voxel.SKY_AIR);
							a = 0xFF;
						}
					}
					m[x + (y - y0) * N] = value;
					am[x + (y - y0) * N] = a;
					any |= value != 0;
				}
			}
			final int fz = z;
			if (any) this.greedy(m, am, N, rows, (u, v, w, hh, value, ao) -> this.emit(f, u, fz, v + y0, w, hh, value, ao));
		}
	}

	private interface Sink {
		void quad(int u, int v, int w, int h, int value, int ao);
	}

	/**
	 * Greedy rectangles over a width x rows mask (value 0: nothing) and its corner shading (a: equal too), each at most 32 x 32;
	 * the mask is cleared as it goes.
	 */
	private void greedy(int[] m, int[] a, int width, int rows, Sink sink) {
		for (int v = 0; v < rows; v++) {
			for (int u = 0; u < width; ) {
				int value = m[u + v * width], ao = a[u + v * width];
				if (value == 0) {
					u++;
					continue;
				}
				int w = 1;
				while (u + w < width && w < 32 && m[u + w + v * width] == value && a[u + w + v * width] == ao) w++;
				int hh = 1;
				outer:
				while (v + hh < rows && hh < 32) {
					int row = (v + hh) * width;
					for (int k = 0; k < w; k++) if (m[u + k + row] != value || a[u + k + row] != ao) break outer;
					hh++;
				}
				for (int r = 0; r < hh; r++) Arrays.fill(m, u + (v + r) * width, u + w + (v + r) * width, 0);
				sink.quad(u, v, w, hh, value, ao);
				u += w;
			}
		}
	}
}
