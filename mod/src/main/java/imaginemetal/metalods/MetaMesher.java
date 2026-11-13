package imaginemetal.metalods;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * A column's voxels as quads: a face wherever a voxel that isn't air touches air, merged greedily into rectangles of the
 * same block, biome and light (up to 32 x 32 voxels), grouped by the direction they face so the draw can skip whole groups
 * that face away from the camera.
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
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

		int total() {
			int t = 0;
			for (int c : this.counts) t += c;
			return t;
		}
	}

	/** Skirts: at the column's sides, a cell's top voxels always get their outward faces (a neighbour drawn at another level
	 * doesn't line up with this one: without them its lower ground left slits to see through). Hidden where it does line up. */
	static final int SKIRT = 2;
	private int[] grid = new int[0];
	private int[] mask = new int[0];
	private final int[] tops = new int[N * N];
	private final int[][] out = new int[6][];
	private final int[] outN = new int[6];
	private int h;

	MetaMesher() {
		for (int f = 0; f < 6; f++) this.out[f] = new int[2048];
	}

	private int at(int x, int z, int y) {
		return ((z + 1) * B + (x + 1)) * this.h + y;
	}

	/**
	 * Meshes column c. edges: the neighbouring columns' cells along each side (-x, +x, -z, +z), 32 cells of h voxels each
	 * (cell i at [i h, (i + 1) h)), or null where unknown (that side then gets no faces where the column's own edge is).
	 */
	Mesh mesh(Rle c, int @Nullable [][] edges) {
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
			for (int q = 0; q < this.outN[f]; q++) {
				int w0 = this.out[f][2 * q];
				int y = (w0 >>> 10) & 511, hh = (f == 2 || f == 3) ? 1 : ((w0 >>> 27) & 31) + 1;
				m.minY = Math.min(m.minY, y);
				m.maxY = Math.max(m.maxY, y + hh);
			}
		}
		return m;
	}

	private void border(int x, int z, int ox, int oz, int @Nullable [] edge, int i) {
		int[] g = this.grid;
		int dst = this.at(x, z, 0);
		if (edge != null && edge.length >= (i + 1) * this.h) System.arraycopy(edge, i * this.h, g, dst, this.h);
		else System.arraycopy(g, this.at(ox, oz, 0), g, dst, this.h);
	}

	private void emit(int f, int x, int z, int y, int w, int hh, int value) {
		int n = this.outN[f];
		int[] o = this.out[f];
		if (2 * n + 2 > o.length) o = this.out[f] = Arrays.copyOf(o, o.length * 2);
		o[2 * n] = x | z << 5 | y << 10 | f << 19 | (w - 1) << 22 | (hh - 1) << 27;
		o[2 * n + 1] = value;
		this.outN[f] = n + 1;
	}

	/** Top (2) or bottom (3) faces, a slice per voxel y, merged across x then z. */
	private void facesY(int f, int y0, int y1) {
		int[] g = this.grid;
		int h = this.h;
		if (this.mask.length < N * N) this.mask = new int[N * N];
		int[] m = this.mask;
		for (int y = y0; y <= y1; y++) {
			boolean any = false;
			for (int z = 0; z < N; z++) {
				for (int x = 0; x < N; x++) {
					int a = this.at(x, z, y);
					int v = g[a], value = 0;
					if (!Voxel.isAir(v)) {
						if (f == 2) {
							int front = y + 1 < h ? g[a + 1] : Voxel.SKY_AIR;
							if (Voxel.isAir(front)) value = Voxel.face(v, front);
						} else if (y > 0) {
							int front = g[a - 1];
							if (Voxel.isAir(front)) value = Voxel.face(v, front);
						}
					}
					m[x + z * N] = value;
					any |= value != 0;
				}
			}
			final int fy = y;
			if (any) this.greedy(m, N, N, (u, v, w, hh, value) -> this.emit(f, u, v, fy, w, hh, value));
		}
	}

	/** +x (0) or -x (1) faces, a slice per x, merged along z then y. */
	private void facesX(int f, int y0, int y1) {
		int[] g = this.grid;
		int rows = y1 - y0 + 1;
		if (this.mask.length < N * rows) this.mask = new int[N * rows];
		int[] m = this.mask;
		int dx = f == 0 ? 1 : -1;
		for (int x = 0; x < N; x++) {
			boolean any = false, side = f == 0 ? x == N - 1 : x == 0;
			for (int y = y0; y <= y1; y++) {
				for (int z = 0; z < N; z++) {
					int v = g[this.at(x, z, y)], value = 0;
					if (!Voxel.isAir(v)) {
						int front = g[this.at(x + dx, z, y)];
						if (Voxel.isAir(front)) value = Voxel.face(v, front);
						else if (side && y > this.tops[x + z * N] - SKIRT) value = Voxel.face(v, Voxel.SKY_AIR);
					}
					m[z + (y - y0) * N] = value;
					any |= value != 0;
				}
			}
			final int fx = x;
			if (any) this.greedy(m, N, rows, (u, v, w, hh, value) -> this.emit(f, fx, u, v + y0, w, hh, value));
		}
	}

	/** +z (4) or -z (5) faces, a slice per z, merged along x then y. */
	private void facesZ(int f, int y0, int y1) {
		int[] g = this.grid;
		int rows = y1 - y0 + 1;
		if (this.mask.length < N * rows) this.mask = new int[N * rows];
		int[] m = this.mask;
		int dz = f == 4 ? 1 : -1;
		for (int z = 0; z < N; z++) {
			boolean any = false, side = f == 4 ? z == N - 1 : z == 0;
			for (int y = y0; y <= y1; y++) {
				for (int x = 0; x < N; x++) {
					int v = g[this.at(x, z, y)], value = 0;
					if (!Voxel.isAir(v)) {
						int front = g[this.at(x, z + dz, y)];
						if (Voxel.isAir(front)) value = Voxel.face(v, front);
						else if (side && y > this.tops[x + z * N] - SKIRT) value = Voxel.face(v, Voxel.SKY_AIR);
					}
					m[x + (y - y0) * N] = value;
					any |= value != 0;
				}
			}
			final int fz = z;
			if (any) this.greedy(m, N, rows, (u, v, w, hh, value) -> this.emit(f, u, fz, v + y0, w, hh, value));
		}
	}

	private interface Sink {
		void quad(int u, int v, int w, int h, int value);
	}

	/** Greedy rectangles over a width x rows mask (value 0: nothing), each at most 32 x 32; the mask is cleared as it goes. */
	private void greedy(int[] m, int width, int rows, Sink sink) {
		for (int v = 0; v < rows; v++) {
			for (int u = 0; u < width; ) {
				int value = m[u + v * width];
				if (value == 0) {
					u++;
					continue;
				}
				int w = 1;
				while (u + w < width && w < 32 && m[u + w + v * width] == value) w++;
				int hh = 1;
				outer:
				while (v + hh < rows && hh < 32) {
					int row = (v + hh) * width;
					for (int k = 0; k < w; k++) if (m[u + k + row] != value) break outer;
					hh++;
				}
				for (int r = 0; r < hh; r++) Arrays.fill(m, u + (v + r) * width, u + w + (v + r) * width, 0);
				sink.quad(u, v, w, hh, value);
				u += w;
			}
		}
	}
}
