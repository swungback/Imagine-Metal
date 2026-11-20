package mcopt.metal.lod;

import imaginemetal.metalods.Rle;
import imaginemetal.metalods.Voxel;
import org.jspecify.annotations.Nullable;
import imaginemetal.metalods.VoxelLogs;
import java.lang.reflect.Method;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Optional Streams integration. Read its public channel plans from the existing
 * world engine; never create a second river engine or modify the actual world. */
public final class LodStreams {
    private final ServerLevel world;
    private final Method engine, plan, inChannel, bedY, waterY, platesBox, platesReady;
    private final java.util.Map<String, Object> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.Iterator<java.util.Map.Entry<String,Object>> pendingScan;
    private long nextPoll;
    private final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean closed;
    public record Ready(int level,int cx,int cz) {}
    private final java.util.concurrent.ConcurrentLinkedQueue<Ready> completed=new java.util.concurrent.ConcurrentLinkedQueue<>();
    public Ready pollCompleted() { return this.completed.poll(); }
    public long revision() { return this.revision.get(); }
    public void close() { this.closed = true; this.pending.clear(); this.adapted.clear(); }
    private volatile boolean failed;
    private boolean said;
    private static final Object UNCHANGED = new Object();
    private final java.util.Map<Rle, Object> adapted = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.concurrent.atomic.AtomicLong coldSkips=new java.util.concurrent.atomic.AtomicLong(), columns=new java.util.concurrent.atomic.AtomicLong(), planNanos=new java.util.concurrent.atomic.AtomicLong();
    public String stats() { return String.format(java.util.Locale.ROOT,"Streams: %d cold lookups skipped, %d ready columns, %.1f ms/column",coldSkips.get(),columns.get(),planNanos.get()/1e6/Math.max(1,columns.get())); }

    private LodStreams(ServerLevel world) throws ReflectiveOperationException {
        this(world, Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen"),
            Class.forName("dev.streamsreflowing.core.RiverEngine"), Class.forName("dev.streamsreflowing.core.compose.ColumnPlan"));
    }

    LodStreams(ServerLevel world, Class<?> entry, Class<?> river, Class<?> column) throws ReflectiveOperationException {
        this.world = world;
        this.engine = entry.getMethod("engineIfPresent", ServerLevel.class);
        this.plan = river.getMethod("plan", int.class, int.class);
        this.inChannel = column.getMethod("inChannel");
        this.bedY = column.getMethod("bedY");
        this.waterY = column.getMethod("waterSurfaceY");
        this.platesBox = river.getMethod("platesTouchingBox", int.class,int.class,int.class,int.class);
        this.platesReady = river.getMethod("platesReady", this.platesBox.getReturnType());

    }

    /** Observe completion only. Even whenPlatesReady() can request generation,
     * so do not subscribe through that API from an LOD worker. */
    public void pollReady() {
        long now=System.nanoTime();
        if(this.closed || this.failed || now<this.nextPoll || this.pending.isEmpty()) return;
        this.nextPoll=now+250_000_000L;
        try {
            Object active=this.engine.invoke(null,this.world);
            if(active==null) return;
            if(this.pendingScan==null || !this.pendingScan.hasNext()) this.pendingScan=this.pending.entrySet().iterator();
            for(int i=0;i<16 && this.pendingScan.hasNext();i++) {
                var e=this.pendingScan.next();
                if((boolean)this.platesReady.invoke(active,e.getValue()) && this.pending.remove(e.getKey(),e.getValue())) {
                    String[] p=e.getKey().split(":");
                    this.completed.add(new Ready(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2])));
                    this.revision.incrementAndGet();
                }
            }
        } catch(ReflectiveOperationException | RuntimeException e) {
            this.failed=true;
            System.out.println("[MetaLODS] Streams readiness polling disabled: "+e);
        }
    }

    public static LodStreams create(ServerLevel world) {
        try { return new LodStreams(world); }
        catch (ClassNotFoundException e) { return null; }
        catch (ReflectiveOperationException | LinkageError e) {
            System.out.println("[MetaLODS] Streams channel API unavailable: " + e);
            return null;
        }
    }

    static final byte NONE = 0, CHANNEL = 1, CORRIDOR = 2;
    /** How far (blocks) from a channel Streams' corridor can reach (measured: 99.7% within 12). */
    static final int CORRIDOR_REACH = 12;
    /**
     * (fix16) The ground's top over the water in Streams' corridor (the strip beside its channel it reshapes) by the distance
     * (blocks, 8-neighbour) to the channel: fitted to the real world (New World (12), 384 x 384 at 13 -167, 12,405 corridor
     * columns: real ground within 2 blocks of the LOD's 40% unchanged -> 67% with this, where the seed's ground is higher).
     */
    private static final int[] BANK = {2, 2, 3, 8, 19, 21, 25, 26, 27, 28, 31, 31, 31};

    static int bankTop(int waterY, int dBlocks) {
        return waterY + BANK[Math.min(Math.max(dBlocks, 0), BANK.length - 1)];
    }

    /** Chebyshev distance (cells) from every cell of an e x e grid to the nearest CHANNEL cell (Integer.MAX_VALUE / 2 if none). */
    static int[] channelDistance(byte[] kind, int e) {
        int inf = Integer.MAX_VALUE / 2;
        int[] d = new int[e * e];
        for (int i = 0; i < d.length; i++) d[i] = kind[i] == CHANNEL ? 0 : inf;
        for (int z = 0; z < e; z++) for (int x = 0; x < e; x++) {
            int i = z * e + x, v = d[i];
            if (x > 0) v = Math.min(v, d[i - 1] + 1);
            if (z > 0) {
                v = Math.min(v, d[i - e] + 1);
                if (x > 0) v = Math.min(v, d[i - e - 1] + 1);
                if (x < e - 1) v = Math.min(v, d[i - e + 1] + 1);
            }
            d[i] = v;
        }
        for (int z = e - 1; z >= 0; z--) for (int x = e - 1; x >= 0; x--) {
            int i = z * e + x, v = d[i];
            if (x < e - 1) v = Math.min(v, d[i + 1] + 1);
            if (z < e - 1) {
                v = Math.min(v, d[i + e] + 1);
                if (x < e - 1) v = Math.min(v, d[i + e + 1] + 1);
                if (x > 0) v = Math.min(v, d[i + e - 1] + 1);
            }
            d[i] = v;
        }
        return d;
    }

    public Rle apply(Rle base, int level, int cx, int cz, int minY, int sky,
                                  MetaGen.Ids ids) {
        if (this.failed || this.closed) return base;
        Object previous = this.adapted.get(base);
        if (previous != null) return previous instanceof Rle r ? r : base;
        try {
            Object active = this.engine.invoke(null, this.world);
            if (active == null) return base;
            int cell = 1 << level, x0 = cx * 32 * cell, z0 = cz * 32 * cell;
            int m = (CORRIDOR_REACH + cell - 1) / cell, e = 32 + 2 * m;
            // plan() is a generating API: it can construct a whole drainage
            // network. Only query regions already prepared by Streams itself.
            String pendingKey = level+":"+cx+":"+cz;
            if(this.pending.containsKey(pendingKey)) { this.coldSkips.incrementAndGet(); return base; }
            Object plates = this.platesBox.invoke(active, x0 - m * cell, z0 - m * cell, x0 + (32 + m) * cell, z0 + (32 + m) * cell);
            if (!(boolean)this.platesReady.invoke(active, plates)) {
                this.pending.putIfAbsent(pendingKey, plates);
                this.coldSkips.incrementAndGet();
                return base;
            }
            if (!this.said) {
                this.said = true;
                System.out.println("[MetaLODS] Streams channel plans enabled (existing world engine; captured chunks remain authoritative)");
            }
            long started=System.nanoTime();
            int h = base.h;
            byte[] kind = new byte[e * e];
            int[] bedB = new int[e * e], waterB = new int[e * e];
            boolean anyCorridor = false, any = false;
            // the column's own cells first; the margin only where a corridor lies in the column (its channel may be outside)
            for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                int i = (z + m) * e + x + m;
                this.query(active, x0 + x * cell + cell / 2, z0 + z * cell + cell / 2, minY, h, cell, kind, bedB, waterB, i);
                any |= kind[i] != NONE;
                anyCorridor |= kind[i] == CORRIDOR;
            }
            if (anyCorridor) {
                for (int gz = 0; gz < e; gz++) for (int gx = 0; gx < e; gx++) {
                    if (gx >= m && gz >= m && gx < m + 32 && gz < m + 32) continue;
                    this.query(active, x0 + (gx - m) * cell + cell / 2, z0 + (gz - m) * cell + cell / 2, minY, h, cell, kind, bedB, waterB, gz * e + gx);
                }
            }
            this.columns.incrementAndGet();
            if (!any) { this.planNanos.addAndGet(System.nanoTime()-started); this.adapted.put(base,UNCHANGED); return base; }
            int[] dist = anyCorridor ? channelDistance(kind, e) : null;
            Rle.Builder builder = new Rle.Builder(32, h);
            int[] col = new int[h], old = new int[h];
            int water = ids.block(Blocks.WATER.defaultBlockState());
            int bed = ids.block(Blocks.GRAVEL.defaultBlockState());
            int stone = ids.block(Blocks.STONE.defaultBlockState());
            int layers = level == 0 ? 4 : level == 1 ? 2 : 1;
            boolean changed = false;
            for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                int i = (z + m) * e + x + m;
                base.expand(x, z, col, 0);
                System.arraycopy(col, 0, old, 0, h);
                if (kind[i] == CHANNEL) {
                    channel(col, minY, cell, bedB[i], waterB[i], water, bed, stone, sky);
                } else if (kind[i] == CORRIDOR && dist != null && dist[i] * cell <= CORRIDOR_REACH + cell) {
                    // (fix16) Streams lowers its corridor toward the water: the LOD kept the seed's ground there, a wall over
                    // the channel's edge (the "ledge")
                    int top = bankTop(waterB[i], dist[i] * cell);
                    lower(col, Math.floorDiv(top - minY, cell), layers, sky);
                }
                changed |= !java.util.Arrays.equals(col, old);
                builder.column(x, z, col);
            }
            this.planNanos.addAndGet(System.nanoTime()-started);
            if (!changed) { this.adapted.put(base,UNCHANGED); return base; }
            Rle result = builder.build();
            this.adapted.put(base, result);
            return result;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            this.failed = true;
            System.out.println("[MetaLODS] Streams channel adapter disabled: " + ex + "; real chunks still authoritative");
            return base;
        }
    }

    private @Nullable Method corridor;
    private boolean corridorLooked;

    /** One plan answer into the grid: CHANNEL (water over its bed), CORRIDOR (the strip beside it), else NONE. */
    private void query(Object active, int bx, int bz, int minY, int h, int cell, byte[] kind, int[] bedB, int[] waterB, int i)
        throws ReflectiveOperationException {
        Object p = this.plan.invoke(active, bx, bz);
        boolean ch = (boolean)this.inChannel.invoke(p);
        if (!ch) {
            if (!this.corridorLooked) {
                this.corridorLooked = true;
                try { this.corridor = this.inChannel.getDeclaringClass().getMethod("inCorridor"); } catch (NoSuchMethodException e) { this.corridor = null; }
            }
            if (this.corridor == null || !(boolean)this.corridor.invoke(p)) return;
        }
        int by = (int)this.bedY.invoke(p), wy = (int)this.waterY.invoke(p);
        if (wy < minY || wy >= minY + h * cell) return;
        if (ch) {
            if (by < minY || by >= minY + h * cell || wy < by) return;
            kind[i] = CHANNEL;
        } else {
            kind[i] = CORRIDOR;
        }
        bedB[i] = by;
        waterB[i] = wy;
    }

    /**
     * The column's ground brought down to voxel `top` where it stands higher: its top `layers` voxels (the surface block and
     * what lies under it) move down with it, everything over them becomes air with the sky's light (trees on the cut ground go
     * with it). A column with water over its ground (a lake) is left as it is.
     */
    static void lower(int[] col, int top, int layers, int sky) {
        int h = col.length;
        if (top < 0 || top >= h - 1) return;
        int g = -1;
        for (int y = h - 1; y >= 0; y--) {
            int v = col[y];
            if (Voxel.isAir(v)) continue;
            int r = Voxel.rank(v);
            if (r == Voxel.KIND_WATER) return;
            if (r == Voxel.KIND_LEAVES || r == Voxel.KIND_AIR || VoxelLogs.isTreePart(v)) continue;
            g = y;
            break;
        }
        if (g <= top) return;
        int air = (sky & 15) << 24;
        int n = Math.min(layers, g + 1);
        int[] skin = new int[n];
        for (int k = 0; k < n; k++) skin[k] = col[g - k];
        for (int y = top + 1; y < h; y++) col[y] = air;
        for (int k = 0; k < n && top - k >= 0; k++) col[top - k] = skin[k];
    }

    /**
     * (fix15, diagnostics) Streams' plan for every block of a size x size square from (x0, z0), as CSV: x, z, inChannel,
     * inCorridor, bedY, waterSurfaceY (public accessors of its plan record, read only). Only where its plates are ready; returns
     * false when they aren't yet (try again later).
     */
    public boolean dump(int x0, int z0, int size, java.nio.file.Path out) {
        try {
            Object active = this.engine.invoke(null, this.world);
            if (active == null) return false;
            Object plates = this.platesBox.invoke(active, x0, z0, x0 + size, z0 + size);
            if (!(boolean)this.platesReady.invoke(active, plates)) return false;
            Method corridor = null;
            try { corridor = this.inChannel.getDeclaringClass().getMethod("inCorridor"); } catch (NoSuchMethodException e) { corridor = null; }
            java.nio.file.Files.createDirectories(out.getParent());
            try (var w = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(out))) {
                w.println("x,z,inChannel,inCorridor,bedY,waterY");
                for (int z = z0; z < z0 + size; z++) for (int x = x0; x < x0 + size; x++) {
                    Object p = this.plan.invoke(active, x, z);
                    boolean ch = (boolean)this.inChannel.invoke(p), co = corridor != null && (boolean)corridor.invoke(p);
                    if (!ch && !co) continue;
                    w.println(x + "," + z + "," + (ch ? 1 : 0) + "," + (co ? 1 : 0) + "," + this.bedY.invoke(p) + "," + this.waterY.invoke(p));
                }
            }
            System.out.println("[MetaLODS] Streams plan dumped: " + size + " x " + size + " from " + x0 + ", " + z0 + " to " + out);
            return true;
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException e) {
            System.out.println("[MetaLODS] Streams plan dump failed: " + e);
            return true;
        }
    }

    static void channel(int[] col, int minY, int cell, int bedY, int waterY, int water, int bed, int stone, int sky) {
        int b = Math.clamp(Math.floorDiv(bedY - minY, cell), 0, col.length - 1);
        int w = Math.clamp(Math.floorDiv(waterY - minY, cell), b, col.length - 1);
        int biome = col[b] & 0x00ff0000;
        // Plans expose the bed block and highest water block. Fill newly raised
        // banks only where needed, then clear the previous canopy/channel cover.
        for (int y = Math.max(0, b - 3); y < b; y++) if (Voxel.isAir(col[y])) col[y] = biome | stone;
        col[b] = biome | bed;
        for (int y = b + 1; y <= w; y++) col[y] = biome | water;
        for (int y = w + 1; y < col.length; y++) col[y] = (sky & 15) << 24;
    }
}
