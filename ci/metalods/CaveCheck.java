package imaginemetal.metalods;

/** fix11: closeCaves meshes dark enclosed pockets as ground, keeps island undersides, water and lit or tall air open. */
public final class CaveCheck {
    private static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }
    private static Rle col(int[] v) {
        Rle.Builder b = new Rle.Builder(32, v.length, Voxel.SKY_AIR);
        int[] empty = new int[v.length]; java.util.Arrays.fill(empty, Voxel.SKY_AIR);
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) b.column(x, z, x == 10 && z == 10 ? v : empty);
        return b.build();
    }
    private static int[] stack(int h, int[][] runs) {
        int[] c = new int[h]; java.util.Arrays.fill(c, Voxel.SKY_AIR);
        for (int[] r : runs) java.util.Arrays.fill(c, r[0], r[1], r[2]);
        return c;
    }
    public static void main(String[] a) {
        MetaMesher m = new MetaMesher();
        int dark = Voxel.air(0, 0), dim = Voxel.air(MetaMesher.DIM, 0), lit = Voxel.air(MetaMesher.DIM + 1, 0);
        // ground 0-10 with a dark 4-tall cave at 4-8: closed at level 0, open when meshed as stored
        Rle cave = col(stack(64, new int[][]{{0, 10, 3}, {4, 8, dark}}));
        int open = m.mesh(cave, null).total(), closed = m.mesh(cave, null, 0).total();
        check(closed < open && closed == m.mesh(col(stack(64, new int[][]{{0, 10, 3}})), null, 0).total(), "cave closed: " + open + " -> " + closed);
        // island over water: dark air between water and island stays open
        Rle island = col(stack(64, new int[][]{{0, 4, 3}, {4, 8, 1}, {8, 20, dark}, {20, 23, 3}}));
        check(m.mesh(island, null, 0).total() == m.mesh(island, null).total(), "island over water kept");
        // island over land, 30 tall dark gap: taller than CAVE_BLOCKS, kept
        Rle tall = col(stack(64, new int[][]{{0, 6, 3}, {6, 36, dark}, {36, 40, 3}}));
        check(m.mesh(tall, null, 0).total() == m.mesh(tall, null).total(), "tall dark gap kept");
        // a lit pocket (cave mouth) stays open; one lit no more than DIM (fix21: deep in, unseen) is closed
        Rle litP = col(stack(64, new int[][]{{0, 10, 3}, {4, 8, lit}}));
        check(m.mesh(litP, null, 0).total() == m.mesh(litP, null).total(), "lit pocket kept");
        Rle dimP = col(stack(64, new int[][]{{0, 10, 3}, {4, 8, dim}}));
        check(m.mesh(dimP, null, 0).total() < m.mesh(dimP, null).total(), "dim pocket closed");
        // level 2: CAVE_BLOCKS >> 2 = 6 voxels
        Rle c2 = col(stack(64, new int[][]{{0, 20, 3}, {5, 11, dark}}));
        check(m.mesh(c2, null, 2).total() < m.mesh(c2, null).total(), "level-2 pocket closed");
        Rle c3 = col(stack(64, new int[][]{{0, 20, 3}, {5, 12, dark}}));
        check(m.mesh(c3, null, 2).total() == m.mesh(c3, null).total(), "level-2 tall pocket kept");
        // A Nether passage has dark air even where a player can see straight through it.
        Rle.Builder cavern = new Rle.Builder(32, 128, dark);
        int[] passage = stack(128, new int[][]{{0, 32, 3}, {32, 50, dark}, {50, 128, 3}});
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) cavern.column(x, z, passage);
        Rle roof = cavern.build();
        check(m.mesh(roof, null, 0).counts[3] == 0, "overworld cleanup removes the covered pocket");
        check(m.mesh(roof, null, 0, true).counts[3] == 1, "Nether keeps the downward-facing cavern ceiling");
        System.out.println("PASS cave closing: " + open + " -> " + closed + " quads; island/water, tall, lit, level-scaled cases kept");
    }
}
