package imaginemetal.metalods;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

/** Reads existing singleplayer chunks at LOD priority, without making generation tickets. */
final class MetaSavedChunks {
    static final boolean ENABLED = !"false".equals(System.getProperty("metalods.savedChunks"));
    private static final int MAX_REMEMBERED = 32_768;
    private record Request(ChunkPos pos, long epoch) {}
    private final ArrayBlockingQueue<Request> requests = new ArrayBlockingQueue<>(256);
    private final Object submissions = new Object();
    private final LongLinkedOpenHashSet attempted = new LongLinkedOpenHashSet();
    private final ServerLevel level;
    private final MetaStore store;
    private final int minY, height;
    private final boolean sky;
    private final BiConsumer<ChunkPos, MetaStore.RealChunk> publish;
    private volatile boolean closed;
    private int imported;
    private long epoch;
    private int failures;

    MetaSavedChunks(ServerLevel level, MetaStore store, int minY, int height, boolean sky,
                    BiConsumer<ChunkPos, MetaStore.RealChunk> publish) {
        this.level = level;
        this.store = store;
        this.minY = minY;
        this.height = height;
        this.sky = sky;
        this.publish = publish;
        Thread worker = new Thread(this::read, "MetaLODS saved chunks");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    boolean request(int chunkX, int chunkZ) {
        synchronized (this.submissions) {
            if (this.closed) return false;
            long key = MetaStore.key(chunkX, chunkZ);
            if (this.attempted.contains(key)) return true;
            if (!this.requests.offer(new Request(new ChunkPos(chunkX, chunkZ), this.epoch))) return false;
            this.attempted.add(key);
            if (this.attempted.size() > MAX_REMEMBERED) this.attempted.removeFirstLong();
            return true;
        }
    }

    private void read() {
        mcopt.metal.lod.LodGenQos.set(0x11);
        var factory = PalettedContainerFactory.create(this.level.registryAccess());
        var storage = this.level.getChunkSource().chunkMap;
        int currentVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        while (!this.closed) {
            try {
                Request request = this.requests.poll(100, TimeUnit.MILLISECONDS);
                if (request == null) continue;
                ChunkPos pos = request.pos();
                if (this.store.real(pos.x(), pos.z()) != null) continue;
                var loaded = storage.read(pos).get();
                if (this.closed || loaded.isEmpty()) continue;
                var tag = loaded.get();
                // Older chunks are upgraded by Minecraft when it loads them normally.
                if (NbtUtils.getDataVersion(tag) != currentVersion) continue;
                var status = SerializableChunkData.getChunkStatusFromTag(tag);
                if (status == null || !status.isOrAfter(ChunkStatus.FEATURES)) continue;
                var data = SerializableChunkData.parse(this.level, factory, tag);
                if (data == null || !pos.equals(data.chunkPos())) continue;
                var voxels = MetaChunks.build(MetaChunks.saved(this.level, data), this.minY, this.height, this.sky);
                if (voxels != null) this.finished(request, voxels);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | RuntimeException e) {
                if (!this.closed && this.failures++ < 3) System.out.println("[MetaLODS] saved chunk read: " + e);
            }
        }
    }

    private synchronized void finished(Request request, MetaStore.RealChunk voxels) {
        if (this.closed || request.epoch() != this.epoch) return;
        this.publish.accept(request.pos(), voxels);
        this.imported++;
        if (this.imported == 1 || this.imported % 128 == 0) {
            System.out.println("[MetaLODS] existing save chunks processed: " + this.imported);
        }
    }

    synchronized void forget() {
        synchronized (this.submissions) {
            this.epoch++;
            this.attempted.clear();
            this.requests.clear();
        }
    }

    synchronized void close() {
        this.closed = true;
        this.forget();
    }
}
