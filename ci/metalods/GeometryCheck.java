package imaginemetal.metalods;

/** CPU fixtures exercise the production RLE and mesher, without booting Minecraft or Metal. */
public final class GeometryCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static Rle column(int x, int z, int lo, int hi, int id) {
        Rle.Builder b = new Rle.Builder(32, 16);
        b.cell(x, z, new int[]{lo, hi, id}, 3);
        return b.build();
    }
    public static void main(String[] args) {
        MetaMesher mesher = new MetaMesher();
        check(Rle.empty(32,16).sameData(Rle.empty(32,16)), "identical snapshots deduplicate");
        check(!Rle.empty(32,16).sameData(column(0,0,0,1,3)), "edited snapshots do not deduplicate");
        check(mesher.mesh(Rle.empty(32,16), null).total() == 0, "empty replacement must contain no geometry");
        // A floating shelf must retain air underneath, not become a ground-to-shelf wall.
        Rle island = column(10, 10, 10, 12, 3);
        check(Voxel.isAir(island.value(10, 9, 10)), "island air gap");
        MetaMesher.Mesh shelf = mesher.mesh(island, null);
        check(shelf.counts[3] == 1 && shelf.minY == 10, "floating shelf underside");
        // A border face below the skirt is hidden by a solid neighbour. When that neighbour
        // is removed, remeshing must expose it; retaining the old mesh leaves a hole.
        Rle edge = column(31, 10, 2, 12, 3);
        int[][] neighbours = new int[4][];
        neighbours[1] = new int[32 * 16];
        java.util.Arrays.fill(neighbours[1], Voxel.SKY_AIR);
        java.util.Arrays.fill(neighbours[1], 10 * 16 + 2, 10 * 16 + 12, 3);
        MetaMesher.Mesh before = mesher.mesh(edge, neighbours);
        int beforeMin = lowest(before, 0);
        java.util.Arrays.fill(neighbours[1], Voxel.SKY_AIR);
        MetaMesher.Mesh after = mesher.mesh(edge, neighbours);
        check(beforeMin >= 10 && lowest(after, 0) == 2, "neighbour edits require border remesh");
        // Equal water columns must not create the opaque internal skirt walls that show
        // through translucent near water as rectangles. Exposed water edges still need faces.
        Rle waterEdge = column(31, 10, 2, 12, 1);
        java.util.Arrays.fill(neighbours[1], 10 * 16 + 2, 10 * 16 + 12, 1);
        check(mesher.mesh(waterEdge, neighbours).counts[0] == 0, "no internal water skirts");
        java.util.Arrays.fill(neighbours[1], Voxel.SKY_AIR);
        check(mesher.mesh(waterEdge, neighbours).counts[0] > 0, "exposed water side retained");
        // Every horizontal edge: internal water remains open, exposed walls survive.
        for (int side=0; side<4; side++) {
            int face = new int[]{1,0,5,4}[side];
            int x = side==0 ? 0 : side==1 ? 31 : 10;
            int z = side==2 ? 0 : side==3 ? 31 : 10;
            int[][] ring = new int[4][];
            ring[side] = new int[32*16];
            java.util.Arrays.fill(ring[side], Voxel.SKY_AIR);
            java.util.Arrays.fill(ring[side], 10*16+2, 10*16+12, 1);
            Rle edgeWater = column(x,z,2,12,1);
            check(mesher.mesh(edgeWater,ring).counts[face]==0, "internal water edge " + side);
            java.util.Arrays.fill(ring[side], Voxel.SKY_AIR);
            check(mesher.mesh(edgeWater,ring).counts[face]>0, "exposed water edge " + side);
        }
        // A solid surrounded by water still needs all six submerged faces. Conversely,
        // water against solid must not add a coplanar opaque water wall over the rock.
        Rle.Builder submerged = new Rle.Builder(32,16);
        for (int z=9;z<=11;z++) for(int x=9;x<=11;x++) {
            int[] values = new int[16];
            java.util.Arrays.fill(values,Voxel.SKY_AIR);
            java.util.Arrays.fill(values,5,8,1);
            if(x==10 && z==10) values[6]=3;
            submerged.column(x,z,values);
        }
        MetaMesher.Mesh underwater = mesher.mesh(submerged.build(),null);
        for(int face=0;face<6;face++) {
            int rock=0;
            for(int i=0;i<underwater.counts[face];i++)
                if((underwater.quads[face][2*i+1]&65535)==3) rock++;
            check(rock==1,"submerged solid face " + face);
        }
        int[][] original = underwater.quads.clone();
        byte[][] originalAo = underwater.ao.clone();
        underwater.splitWater();
        for (int face = 0; face < 6; face++) {
            int land = 0, water = underwater.solidCounts[face];
            for (int q = 0; q < underwater.counts[face]; q++) {
                boolean isWater = Voxel.id(original[face][q * 2 + 1]) == 1;
                int to = isWater ? water++ : land++;
                check(underwater.quads[face][to * 2] == original[face][q * 2]
                    && underwater.quads[face][to * 2 + 1] == original[face][q * 2 + 1]
                    && underwater.ao[face][to] == originalAo[face][q], "stable material split retains geometry and AO");
            }
            check(land == underwater.solidCounts[face] && water == underwater.counts[face], "both material spans cover the mesh");
        }
        // fix9's thin-waterfall rule and half-filled water layer must both survive.
        Rle.Builder thin = new Rle.Builder(2,2);
        thin.cell(0,0,new int[]{0,2,1},3);
        check(Voxel.isAir(thin.build().mip().value(0,0,0)), "thin waterfall must not grow into coarse pillar");
        Rle.Builder sea = new Rle.Builder(2,2);
        for(int z=0;z<2;z++) for(int x=0;x<2;x++) sea.cell(x,z,new int[]{0,1,1},3);
        check(Voxel.id(sea.build().mip().value(0,0,0)) == 1, "half water occupancy preserves sea");
        Rle[] children = {
            column(0, 0, 4, 6, 3), column(31, 0, 4, 6, 3),
            column(0, 31, 4, 6, 3), column(31, 31, 4, 6, 3)
        };
        Rle parent = Rle.mipChildren(children);
        check(parent.n == 32 && parent.h == 8, "parent mip dimensions");
        for (int z : new int[]{0, 31}) for (int x : new int[]{0, 31}) {
            check(Voxel.id(parent.value(x, 2, z)) == 3, "each fine quadrant keeps its corner");
        }
        check(Voxel.isAir(parent.value(16, 2, 16)), "parent mip keeps empty space");
        System.out.println("PASS: empty replacement, floating shelf, neighbour remesh, thin waterfall, water layer, internal/exposed water sides, all six submerged rock faces, capture deduplication");
    }
    private static int lowest(MetaMesher.Mesh mesh, int face) {
        int result = Integer.MAX_VALUE;
        for(int i=0;i<mesh.counts[face];i++) result=Math.min(result,(mesh.quads[face][2*i]>>>10)&511);
        return result;
    }
}
