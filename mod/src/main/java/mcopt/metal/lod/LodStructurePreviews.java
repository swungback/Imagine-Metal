package mcopt.metal.lod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * The surface structures on the LODs, from the structures the game itself makes there.
 *
 * (fix24) Before: each structure set's possible chunks were taken as sites and a village's centre plus random houses from its
 * pool (or a vanilla template) were stamped there, checked only against the biome at a guessed ground height. The game's own
 * check (Structure.generate: the weighted pick in the set, the start's ground and terrain checks, the jigsaw assembly) wasn't
 * run, so the LODs showed houses where the real world has none: Jack's sandstone houses near -165 75 550 on New World (10)
 * (21.19.12, 21.19.15), where the save holds no structure at all, while the Ice and Fire witch cottage beside it was missing.
 * Now the starts are LodForest.starts (as the game's createStructures picks them, cached per chunk) and their pieces are
 * stamped where the game puts them: the jigsaw pieces' templates at their positions and rotations, the template pieces
 * (igloos, shipwrecks, ruined portals, the mods' cottages) the same. Paths that follow the terrain, coded pieces (temples,
 * mineshafts) and the templates' processors (mossy or cracked variants) are left out.
 * Level 0: the blocks themselves (as the exact trees, LodTile.treeBlocks); levels 1-3: a column per cell, its lowest block above
 * the ground (the walls) up to its top block (the roof).
 *
 * (s1121) Levels 1-3 as voxels: of each cube of 2^L blocks its most solid block, ties to the upper one (Rle.mip's rule, so a
 * village far away looks as its near self mipped). The column per cell was filled solid from the ground to the roof in its
 * lowest block: a stone corner post made a grey pillar, a floor a band of planks round the house, a foundation of bricks a
 * tall brown post, and cells side by side stood at their own heights: Jack's "village looks broken, pieces overlapping"
 * (2026-11-20 16.38.53, -505 123 1335 west, the village ~290 blocks out). And at every level: where a piece's lowest block
 * stands a little over the LODs' ground (the game raises the ground under its pieces, the LODs' noise doesn't), the gap is
 * ground (BEARD blocks at most), not air under a floating house.
 */
final class LodStructurePreviews {
    private static final int CACHE_MAX = 512;
    /** (s1121) levels 1-3 as voxels (-Dmetalods.structureVoxels=false: the old solid column per cell) */
    static final boolean VOXELS = !"false".equals(System.getProperty("metalods.structureVoxels"));
    /** (s1121) a gap under a piece's lowest block up to this many blocks is ground (the game's raised ground under its pieces) */
    static final int BEARD = Integer.getInteger("metalods.structureBeard", 8);

    private final ServerLevel level;
    private final Field palettes, tTemplate, tSettings, tPos;
    private final Method poolTemplate;
    /** Per start: its blocks as x, y, z, block state id. */
    private final ConcurrentHashMap<StructureStart, int[]> blocks = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean failure = new java.util.concurrent.atomic.AtomicBoolean();

    LodStructurePreviews(ServerLevel level, LodNoise noise) throws ReflectiveOperationException {
        this.level = level;
        this.palettes = StructureTemplate.class.getDeclaredField("palettes");
        this.palettes.setAccessible(true);
        this.poolTemplate = SinglePoolElement.class.getDeclaredMethod("getTemplate", net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager.class);
        this.poolTemplate.setAccessible(true);
        this.tTemplate = TemplateStructurePiece.class.getDeclaredField("template");
        this.tTemplate.setAccessible(true);
        this.tSettings = TemplateStructurePiece.class.getDeclaredField("placeSettings");
        this.tSettings.setAccessible(true);
        this.tPos = TemplateStructurePiece.class.getDeclaredField("templatePosition");
        this.tPos.setAccessible(true);
    }

    void dress(LodTile tile, LodForest forest) {
        if (forest == null || !this.level.structureManager().shouldGenerateStructures()) return;
        int span = tile.size << tile.level, reach = LodForest.STRUCT_REACH;
        BoundingBox box = new BoundingBox(tile.x0, this.level.getMinY(), tile.z0, tile.x0 + span - 1, this.level.getMaxY(), tile.z0 + span - 1);
        int c0x = (tile.x0 >> 4) - reach, c0z = (tile.z0 >> 4) - reach, c1x = ((tile.x0 + span - 1) >> 4) + reach, c1z = ((tile.z0 + span - 1) >> 4) + reach;
        java.util.ArrayList<int[]> hits = null;
        for (int cz = c0z; cz <= c1z; cz++) {
            for (int cx = c0x; cx <= c1x; cx++) {
                for (StructureStart st : forest.starts(cx, cz)) {
                    if (!st.getBoundingBox().intersects(box)) continue;
                    int[] b = this.blocks(st);
                    if (b.length == 0) continue;
                    if (hits == null) hits = new java.util.ArrayList<>(2);
                    hits.add(b);
                }
            }
        }
        if (hits == null) return;
        if (tile.level == 0) stamp0(tile, hits, span);
        else if (VOXELS) stampVoxels(tile, hits, span, this.level.getMinY());
        else stampColumns(tile, hits, span);
    }

    private int[] blocks(StructureStart st) {
        int[] got = this.blocks.get(st);
        if (got != null) return got;
        int[] out = new int[0];
        try {
            out = this.collect(st);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (this.failure.compareAndSet(false, true)) System.out.println("[MetaLODS] a structure's blocks could not be read: " + e);
        }
        if (this.blocks.size() > CACHE_MAX) this.blocks.clear();
        this.blocks.put(st, out);
        return out;
    }

    private int[] collect(StructureStart st) throws ReflectiveOperationException {
        var manager = this.level.getStructureTemplateManager();
        int[] out = new int[1024];
        int n = 0;
        for (StructurePiece piece : st.getPieces()) {
            StructureTemplate template;
            StructurePlaceSettings settings;
            BlockPos origin;
            if (piece instanceof PoolElementStructurePiece pe) {
                if (!(pe.getElement() instanceof SinglePoolElement single)
                    || single.getProjection() == StructureTemplatePool.Projection.TERRAIN_MATCHING) continue;
                template = (StructureTemplate) this.poolTemplate.invoke(single, manager);
                settings = new StructurePlaceSettings().setRotation(pe.getRotation());
                origin = pe.getPosition();
            } else if (piece instanceof TemplateStructurePiece tp) {
                template = (StructureTemplate) this.tTemplate.get(tp);
                settings = (StructurePlaceSettings) this.tSettings.get(tp);
                origin = (BlockPos) this.tPos.get(tp);
            } else {
                continue;
            }
            if (template == null || settings == null || origin == null) continue;
            @SuppressWarnings("unchecked")
            var choices = (List<StructureTemplate.Palette>) this.palettes.get(template);
            if (choices.isEmpty()) continue;
            for (var info : choices.getFirst().blocks()) {
                BlockState state = info.state();
                if (state.isAir() || state.is(Blocks.STRUCTURE_BLOCK) || state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.JIGSAW)
                    || !state.getFluidState().isEmpty()) continue;
                var shape = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (shape.isEmpty() || shape.max(net.minecraft.core.Direction.Axis.Y) < 0.5) continue;
                BlockPos rel = StructureTemplate.calculateRelativePosition(settings, info.pos());
                state = state.mirror(settings.getMirror()).rotate(settings.getRotation());
                if (n + 4 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                out[n++] = origin.getX() + rel.getX();
                out[n++] = origin.getY() + rel.getY();
                out[n++] = origin.getZ() + rel.getZ();
                out[n++] = Block.getId(state);
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** Level 0: the structure's own blocks over the ground; a tree in a column a structure stands in is taken out. */
    private static void stamp0(LodTile tile, List<int[]> hits, int span) {
        int cells = tile.cells();
        if (tile.treeBlocks == null) tile.treeBlocks = new BlockState[cells][];
        boolean[] mine = new boolean[cells];
        for (int[] b : hits) {
            for (int k = 0; k < b.length; k += 4) {
                int x = b[k] - tile.x0, y = b[k + 1], z = b[k + 2] - tile.z0;
                if (x < 0 || z < 0 || x >= span || z >= span) continue;
                int i = z * tile.size + x;
                int g = tile.ground[i];
                if (tile.water[i] != LodTile.DRY && tile.water[i] > g) continue;
                BlockState st = Block.stateById(b[k + 3]);
                if (y < g) {
                    // (a floor or foundation at the ground's own top: the surface takes it)
                    if (y == g - 1 && st.isSolidRender()) tile.state[i] = st;
                    continue;
                }
                int offset = y - g;
                if (offset > 255) continue;
                BlockState[] col = tile.treeBlocks[i];
                if (!mine[i]) {
                    mine[i] = true;
                    col = new BlockState[Math.max(16, offset + 1)];
                    tile.plantLower[i] = null;
                    tile.plantUpper[i] = null;
                } else if (offset >= col.length) {
                    col = java.util.Arrays.copyOf(col, Math.max(col.length * 2, offset + 1));
                }
                col[offset] = st;
                tile.treeBlocks[i] = col;
            }
        }
        for (int i = 0; i < cells; i++) {
            if (!mine[i]) continue;
            BlockState[] col = tile.treeBlocks[i];
            int lo = -1, hi = -1;
            for (int y = 0; y < col.length; y++) if (col[y] != null) {
                if (lo < 0) lo = y;
                hi = y;
            }
            if (hi < 0) {
                tile.treeBlocks[i] = null;
                continue;
            }
            if (lo > 0 && lo <= BEARD) {
                // (s1121) the gap under the piece: ground, as the game raises it under its pieces
                beard(tile, i, col, 0, lo);
                lo = 0;
            }
            tile.canopyLo[i] = (short) (tile.ground[i] + lo);
            tile.canopyHi[i] = (short) (tile.ground[i] + hi);
            tile.canopyState[i] = col[hi];
            tile.trunk[i] = col[lo];
            tile.standing[i] = lo == 0;
        }
    }

    /** (s1121) voxels from..to of a structure column: the ground's under block, its surface block on top. */
    private static void beard(LodTile tile, int i, BlockState[] col, int from, int to) {
        BlockState surface = tile.state[i], under = tile.under[i] != null ? tile.under[i] : surface;
        if (surface == null || !surface.isSolidRender()) return;
        for (int y = from; y < to; y++) col[y] = y == to - 1 ? surface : under;
    }

    /**
     * (s1121) Levels 1-3 as voxels, as MetaGen.cell lays a column: its first voxel over the ground is the one over the voxel
     * holding the ground's top block. Per voxel the most solid of its blocks (solid over leaves), ties to the upper one, then to
     * the first.
     */
    private static void stampVoxels(LodTile tile, List<int[]> hits, int span, int minY) {
        int cells = tile.cells(), L = tile.level, c = 1 << L;
        int[][] score = new int[cells][];
        BlockState[][] cols = new BlockState[cells][];
        int[] hiY = new int[cells];
        java.util.Arrays.fill(hiY, Integer.MIN_VALUE);
        BlockState[] top = new BlockState[cells];
        for (int[] b : hits) {
            for (int k = 0; k < b.length; k += 4) {
                int x = b[k] - tile.x0, y = b[k + 1], z = b[k + 2] - tile.z0;
                if (x < 0 || z < 0 || x >= span || z >= span) continue;
                int i = (z >> L) * tile.size + (x >> L);
                int g = tile.ground[i];
                if (y < g || tile.water[i] != LodTile.DRY && tile.water[i] > g) continue;
                int at = Math.floorDiv(g - 1 - minY, c) + 1, vy = Math.floorDiv(y - minY, c), off = vy - at;
                if (off < 0 || off > 255) continue;
                BlockState st = Block.stateById(b[k + 3]);
                int sc = (st.is(net.minecraft.tags.BlockTags.LEAVES) ? 1 : 2) * 1024 + (y - minY - vy * c) + 1;
                int[] sv = score[i];
                if (sv == null || off >= sv.length) {
                    int len = Math.max(16, Math.max(off + 1, sv == null ? 0 : sv.length * 2));
                    score[i] = sv = sv == null ? new int[len] : java.util.Arrays.copyOf(sv, len);
                    cols[i] = cols[i] == null ? new BlockState[len] : java.util.Arrays.copyOf(cols[i], len);
                }
                if (sc > sv[off]) {
                    sv[off] = sc;
                    cols[i][off] = st;
                }
                if (y > hiY[i]) {
                    hiY[i] = y;
                    top[i] = st;
                }
            }
        }
        if (tile.treeBlocks == null) tile.treeBlocks = new BlockState[cells][];
        for (int i = 0; i < cells; i++) {
            BlockState[] col = cols[i];
            if (col == null) continue;
            if (tile.canopyHi[i] >= tile.canopyLo[i] && hiY[i] <= tile.canopyHi[i]) continue;
            int lo = -1, hi = -1;
            for (int y = 0; y < col.length; y++) if (col[y] != null) {
                if (lo < 0) lo = y;
                hi = y;
            }
            if (hi < 0) continue;
            col = java.util.Arrays.copyOf(col, hi + 1);
            if (lo > 0 && lo * c <= Math.max(BEARD, c)) {
                beard(tile, i, col, 0, lo);
                lo = 0;
            }
            tile.treeBlocks[i] = col;
            tile.canopyLo[i] = tile.ground[i];
            tile.canopyHi[i] = (short) hiY[i];
            tile.canopyState[i] = top[i];
            tile.trunk[i] = col[lo];
            tile.standing[i] = lo == 0;
        }
    }

    /** Levels 1-3: per cell its lowest block above the ground (the walls' block) up to its highest (the roof's). */
    private static void stampColumns(LodTile tile, List<int[]> hits, int span) {
        int cells = tile.cells(), L = tile.level;
        int[] hi = new int[cells];
        java.util.Arrays.fill(hi, Integer.MIN_VALUE);
        int[] lo = new int[cells];
        java.util.Arrays.fill(lo, Integer.MAX_VALUE);
        BlockState[] top = new BlockState[cells], base = new BlockState[cells];
        for (int[] b : hits) {
            for (int k = 0; k < b.length; k += 4) {
                int x = b[k] - tile.x0, y = b[k + 1], z = b[k + 2] - tile.z0;
                if (x < 0 || z < 0 || x >= span || z >= span) continue;
                int i = (z >> L) * tile.size + (x >> L);
                if (y < tile.ground[i]) continue;
                if (y < lo[i]) {
                    lo[i] = y;
                    base[i] = Block.stateById(b[k + 3]);
                }
                if (y > hi[i]) {
                    hi[i] = y;
                    top[i] = Block.stateById(b[k + 3]);
                }
            }
        }
        for (int i = 0; i < cells; i++) {
            if (hi[i] == Integer.MIN_VALUE || tile.water[i] != LodTile.DRY && tile.water[i] > tile.ground[i]) continue;
            if (tile.canopyHi[i] >= tile.canopyLo[i] && hi[i] <= tile.canopyHi[i]) continue;
            if (tile.treeBlocks != null) tile.treeBlocks[i] = null;
            tile.canopyLo[i] = tile.ground[i];
            tile.canopyHi[i] = (short) hi[i];
            tile.canopyState[i] = top[i];
            tile.trunk[i] = base[i];
            tile.standing[i] = true;
        }
    }
}
