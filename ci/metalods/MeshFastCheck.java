package imaginemetal.metalods;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

/**
 * (s1121) MetaMesher's fast path (-Dmetalods.meshFast, on by default: the face range from where air, water and the rest meet,
 * the sky light's edge searched only where a side can be dark, its last pass stopping under the open sky) must give the same
 * quads as the old one. check.sh runs this twice (meshFast=false, then the default) and compares the digests it prints.
 * Columns: hills of stone in varied biomes (values that differ without a kind change, as ores and stone kinds do), caves of
 * varied light, a sea, leaf blobs, floating land, with and without known neighbours, levels 0-3.
 */
public final class MeshFastCheck {
	static final int AIR = 0, WATER = 1, LEAVES = 2, SOLID = 3;

	static int vox(int kind, int biome, int sky) {
		return kind == AIR ? (sky & 15) << 24 : (sky & 15) << 24 | (biome & 255) << 16 | kind;
	}

	static int[][] column(Random r, int h, int level) {
		int n = 32;
		int[][] cells = new int[n * n][];
		int sea = Math.max(2, 62 >> level), base = Math.max(4, 70 >> level);
		double fx = 0.05 + r.nextDouble() * 0.2, fz = 0.05 + r.nextDouble() * 0.2, ph = r.nextDouble() * 6;
		int caves = r.nextInt(6), islands = r.nextInt(2);
		int[][] cave = new int[caves][];
		for (int k = 0; k < caves; k++) cave[k] = new int[] {r.nextInt(n), r.nextInt(Math.max(1, base)), r.nextInt(n), 2 + r.nextInt(6), r.nextInt(16)};
		for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; x++) {
				int[] col = new int[h];
				int ground = Math.min(h - 2, Math.max(1, base + (int) (Math.sin(x * fx + ph) * 9 + Math.cos(z * fz) * 7) + r.nextInt(2)));
				for (int y = 0; y < h; y++) {
					int v;
					if (y < ground) v = vox(SOLID, (y * 7 + x / 5) % 5 == 0 ? 3 : 1 + (z / 9) % 2, 0);  // stone kinds/ores: same kind
					else if (y < sea) v = vox(WATER, 1, Math.max(0, 15 - (sea - y)));
					else v = vox(AIR, 0, 15);
					col[y] = v;
				}
				for (int[] c : cave) {
					int dx = x - c[0], dz = z - c[2];
					for (int y = Math.max(1, c[1] - c[3]); y < Math.min(ground, c[1] + c[3]); y++) {
						int dy = y - c[1];
						if (dx * dx + dy * dy + dz * dz < c[3] * c[3]) col[y] = vox(AIR, 0, c[4]);
					}
				}
				if (ground >= sea && r.nextInt(23) == 0) {
					for (int y = ground + 2; y < Math.min(h, ground + 6); y++) col[y] = vox(LEAVES, 2, 0);
				}
				if (islands > 0 && x > 8 && x < 20 && z > 10 && z < 24) {
					for (int y = Math.min(h - 3, ground + 12); y < Math.min(h - 1, ground + 15); y++) col[y] = vox(SOLID, 4, 0);
				}
				if (r.nextInt(400) == 0) col[h - 1] = vox(SOLID, 5, 0);  // land to the top: the top face, the skirts
				cells[x + z * n] = col;
			}
		}
		return cells;
	}

	static Rle rle(int[][] cells, int h) {
		int n = 32;
		int[] off = new int[n * n + 1];
		int[] runs = new int[2 * n * n * h];
		int k = 0;
		for (int c = 0; c < n * n; c++) {
			int[] col = cells[c];
			for (int y = 0; y < h; y++) {
				if (y + 1 == h || col[y + 1] != col[y]) {
					runs[2 * k] = col[y];
					runs[2 * k + 1] = y + 1;
					k++;
				}
			}
			off[c + 1] = k;
		}
		return new Rle(n, h, off, java.util.Arrays.copyOf(runs, 2 * k));
	}

	public static void main(String[] args) throws Exception {
		Random r = new Random(1121);
		MetaMesher m = new MetaMesher();
		MessageDigest all = MessageDigest.getInstance("SHA-1");
		long quads = 0;
		for (int t = 0; t < 160; t++) {
			int level = t % 4, h = Math.max(24, 384 >> level);
			int[][] center = column(r, h, level);
			int[][] edges = new int[4][];
			for (int s = 0; s < 4; s++) {
				if (r.nextInt(4) == 0) continue;
				int[][] nb = column(r, h, level);
				int[] e = new int[32 * h];
				for (int i = 0; i < 32; i++) {
					int x = s == 0 ? 31 : s == 1 ? 0 : i, z = s == 2 ? 31 : s == 3 ? 0 : i;
					System.arraycopy(nb[x + z * 32], 0, e, i * h, h);
				}
				edges[s] = e;
			}
			MetaMesher.Mesh mesh = m.mesh(rle(center, h), edges, level);
			quads += mesh.total();
			java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(8);
			for (int f = 0; f < 6; f++) {
				for (int q = 0; q < 2 * mesh.counts[f]; q++) {
					bb.clear();
					bb.putInt(mesh.quads[f][q]);
					all.update(bb.array(), 0, 4);
				}
				all.update(mesh.ao[f]);
			}
			for (int top : m.tops()) {
				bb.clear();
				bb.putInt(top);
				all.update(bb.array(), 0, 4);
			}
			bb.clear();
			bb.putInt(mesh.minY).putInt(mesh.maxY);
			all.update(bb.array());
		}
		System.out.println("MeshFastCheck meshFast=" + MetaMesher.meshFast + " quads " + quads + " digest " + HexFormat.of().formatHex(all.digest()));
	}
}
