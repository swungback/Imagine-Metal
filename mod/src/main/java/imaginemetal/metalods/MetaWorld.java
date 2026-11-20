package imaginemetal.metalods;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import mcopt.metal.lod.MetaGen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.FrustumIntersection;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * MetaLODS for one dimension of one world.
 *
 * Columns: level L covers 32 x 32 cells of 2^L blocks (a square 32 << L blocks wide) and the dimension's full height in
 * voxels 2^L blocks tall. Each frame the columns are chosen top down from level TOP (Voxy's way, in two dimensions: terrain
 * is wide, not tall): a column closer than `detail` of its own widths is replaced by its four children once all four are
 * meshed, else it stays drawn (so nothing ever goes missing while the finer ones are made).
 *
 * A column's voxels are what the world generator says from the seed (MetaGen, cached on disk) with every real chunk ever
 * seen there laid over it (MetaStore: real chunks keep their own LODs at levels 0-4). Workers build and mesh columns; the
 * render thread uploads the meshes and draws.
 */
final class MetaWorld {
	static final int TOP = 5;
	final ClientLevel level;
	final int minY, height;
	final boolean skyLight;
	/** Generated LODs in 3D (MetaSettings.overhangs as the world was loaded). */
	private final boolean overhangs;
	final MetaStore store;
    private final mcopt.metal.lod.LodStreams streams;
    private final MetaCanopy canopies=new MetaCanopy();
    private long streamsRevision;
	private final MetaArena arena;
	private final @Nullable ServerLevel server;
	private volatile @Nullable MetaGen gen;
	private volatile boolean genTried, genFailed;
	private final Object genLock = new Object();
	private volatile int saidLevel;

	// ---- render thread only ----
	private final Long2ObjectOpenHashMap<Col> cols = new Long2ObjectOpenHashMap<>();
	private long frame;
	private int lastDrawPages, lastDrawCols, lastQuads;
	private long nextPrefetch;
	private long nextStats = System.currentTimeMillis() + 10000L, nextFlush = System.currentTimeMillis() + 5000L, nextEvict;
	private final int[] maskBits = new int[MetaArena.MASK_MAX * MetaArena.MASK_MAX / 32];
	private int maskX, maskZ, maskSize, maskWords;
	private boolean arenaFull;
	/** -Dmetalods.handoffDrawn=false: the LODs step back from every loaded chunk in the render distance (the first build's rule). */
	/** -Dmetalods.coarseFirst=true: the old order (every level from the coarsest down, huge blocks first). */
	private static final boolean COARSE_FIRST = Boolean.getBoolean("metalods.coarseFirst");
	private static final boolean HANDOFF_DRAWN = !"false".equals(System.getProperty("metalods.handoffDrawn"));
	private static final boolean MASK_LOADED = Boolean.getBoolean("metalods.maskLoaded");
	private boolean saidMask, maskFromOwn;
	private long statsTiles;

	// ---- shared ----
	private final TileJobs<Job> jobs = new TileJobs<>();
	private final LinkedBlockingQueue<Runnable> ingest = new LinkedBlockingQueue<>();
	private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
	private final List<Thread> workers = new ArrayList<>();
	private volatile boolean closed;
	private final LatestIngest latestIngest = new LatestIngest();
	private final Object publicationLock = new Object();
	private final AtomicLong seq = new AtomicLong();
	static volatile long frameNow;
	private final Long2ObjectLinkedOpenHashMap<Rle> genCache = new Long2ObjectLinkedOpenHashMap<>(1024);
	private final TileGate tileGate = new TileGate();
    private final DetailJobs<Refinement> details=new DetailJobs<>();
    private final ConcurrentLinkedQueue<long[]> refined=new ConcurrentLinkedQueue<>();
    private volatile boolean detailAllowed,detailFailed;
    private volatile long cacheEpoch;
    private final AtomicLong detailedTiles=new AtomicLong();
    private record Refinement(int level,int tx,int tz,long key,double distance) implements Comparable<Refinement> {
        public int compareTo(Refinement other) {
            int l=Integer.compare(this.level,other.level);
            return l!=0?l:Double.compare(this.distance,other.distance);
        }
    }
    /** Older surface previews finish once. Completed columns are independent of camera position. */
    private void refineIfDue(int level, int cx, int cz) {
        if (this.store.isSurface(level, cx, cz)) this.queueDetail(level, cx, cz);
    }
    private final AtomicLong mipColumns = new AtomicLong();
    private void queueDetail(int level,int cx,int cz) {
        if(this.closed || this.detailFailed || !MetaSettings.generate)return;
        int tx=Math.floorDiv(cx,2),tz=Math.floorDiv(cz,2);
        long key=key(level,tx,tz);
        double w=64L<<level;
        double dx=(tx+.5)*w-this.camX,dz=(tz+.5)*w-this.camZ;
        this.details.offer(key,new Refinement(level,tx,tz,key,dx*dx+dz*dz));
    }
    private MetaGen.Ids ids() { return new MetaGen.Ids() {
        public int block(BlockState state) { return MetaPalette.id(state); }
        public int biome(Holder<Biome> biome) { return MetaPalette.biome(biome); }
    }; }
    private double detailDistance(Refinement r) {
        double w=64L<<r.level,x=r.tx*w,z=r.tz*w;
        double dx=Math.max(0,Math.max(x-this.camX,this.camX-(x+w)));
        double dz=Math.max(0,Math.max(z-this.camZ,this.camZ-(z+w)));
        return dx*dx+dz*dz;
    }
    private boolean refineOne() {
        int limit=this.jobs.size()==0?Math.max(1,this.workers.size()-2):1;
        boolean enabled=!this.closed && !this.detailFailed && MetaSettings.generate;
        double near=Math.max(192,this.transitionLimit+96);
        var lease=this.details.takeNearest(enabled,limit,r->this.detailDistance(r)<=near*near,r->this.detailDistance(r)+r.level*4096);
        if(lease==null && (this.detailAllowed || this.jobs.size()==0))lease=this.details.take(enabled,limit);
        if(lease==null)return false;
        Refinement r=lease.value();
        long epoch=this.cacheEpoch;
        if(!this.tileGate.enter(r.key)) {
            this.details.retry(lease);return false;
        }
        try {
            if(this.closed)return true;
            MetaGen g=this.generator();if(g==null)return true;
            Rle[] four = g.generateFar(r.level, r.tx, r.tz, this.minY, this.height,
                MetaSettings.exactTrees, this.airSky(), this.overhangs, this.ids());
            synchronized(this.publicationLock) {
                if(this.closed || epoch!=this.cacheEpoch)return true;
                long[] keys=new long[4];
                for(int q=0;q<4;q++) {
                    int x=r.tx*2+(q&1),z=r.tz*2+(q>>1);
                    keys[q]=key(r.level,x,z);
                    Rle existing = this.cachedGen(r.level, x, z);
                    if (existing != null && !this.store.isSurface(r.level, x, z)) continue;
                    this.cache(keys[q],four[q]);
                    this.store.saveGen(r.level, x, z, four[q]);
                }
                this.refined.add(keys);this.detailedTiles.incrementAndGet();
            }
        } catch(Throwable t) {
            if(epoch==this.cacheEpoch) {this.detailFailed=true;this.details.clear();}
            System.out.println("[MetaLODS] detail refinement stopped; surface retained: "+t);
        } finally {this.tileGate.leave(r.key);this.details.complete(lease);}
        return true;
    }

	private static final class TileBusy extends RuntimeException {
		TileBusy() { super(null, null, false, false); }
	}
	private static final TileBusy TILE_BUSY = new TileBusy();
	final AtomicLong generatedTiles = new AtomicLong(), genNanos = new AtomicLong(), meshed = new AtomicLong(), meshNanos = new AtomicLong(),
		ingested = new AtomicLong();
	/** Chunks' worth of ground the generated tiles cover (a level-L tile: 16 x 4^L chunks), summed over the levels; when the first tile began and the last ended. */
	final AtomicLong generatedChunks = new AtomicLong();
	private volatile long genFirstNs, genLastNs;

	static final class Col {
		final int level, cx, cz;
		final long key;
		int[] pages = new int[0];
		final int[] faceFirst = new int[7];
		final int[] faceWater = new int[6];
		int minYb, maxYb;
		/** 0 nothing, 1 asked, 2 meshed */
		int state;
		boolean dirty;
		int version;
		int edges;
		volatile long wanted;
		/** (no room in the arena: not asked for again before this frame; was re-meshed every frame, 112k meshes in 2 min) */
		long noRoomUntil;

		Col(int level, int cx, int cz, long key) {
			this.level = level;
			this.cx = cx;
			this.cz = cz;
			this.key = key;
		}

		/** Has a mesh to draw (perhaps an older one while a new one is made). */
		boolean ready() {
			return this.pagesValid;
		}

		boolean pagesValid;
		/** (fix18) the last frame drawn (not fading out), when it started showing / started dissolving out (ms, 0 not). */
		long drawnFrame = -2, shownAt, hiddenAt, selectedFrame;
	}

	record Job(Col col, int version, double prio, long seq) implements Comparable<Job> {
		@Override
		public int compareTo(Job o) {
			int c = Double.compare(this.prio, o.prio);
			return c != 0 ? c : Long.compare(this.seq, o.seq);
		}
	}

	/** A worker's answer: the column's mesh (null: dropped), the sides it knew, columns it generated. */
	record Result(@Nullable Col col, int version, MetaMesher.@Nullable Mesh mesh, int edges, long @Nullable [] generated, long realChunk) {
	}

	/** (fix15) Diagnostics: CPU by thread group, GC, what the workers do; a one-time dump of Streams' plan around the player. */
	private final @Nullable MetaDiag diag = MetaSettings.stats ? new MetaDiag() : null;
	private Path diagDir;
	private volatile int planDump;  // 0 not yet, 1 running, 2 done

	MetaWorld(ClientLevel level, Path dir, MetaArena arena, @Nullable ServerLevel server) {
		this.diagDir = dir.resolve("claude-diag");
		this.level = level;
		this.minY = level.getMinY();
		this.height = Math.clamp(level.getHeight() / 32 * 32, 32, 512);
		this.skyLight = level.dimensionType().hasSkyLight();
		this.arena = arena;
		this.server = server;
        if(server!=null)mcopt.metal.lod.LodTrees.configure(server);
        this.streams = server == null ? null : mcopt.metal.lod.LodStreams.create(server);
		this.overhangs = MetaSettings.overhangs;
		this.store = new MetaStore(dir, level.registryAccess(), this.overhangs);
		int n = MetaSettings.workerThreads();
		for (int i = 0; i < n; i++) {
			final int index = i;
			Thread t = new Thread(() -> this.work(index), "MetaLODS worker " + i);
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			t.start();
			this.workers.add(t);
		}
		System.out.println("[MetaLODS] " + level.dimension().identifier() + ": y " + this.minY + " to " + (this.minY + this.height) + ", " + n + " workers, "
			+ (server != null ? "generation from the seed available" : "no generation (not singleplayer)") + ", stored in " + dir);
	}

	/** (fix14) -Dmetalods.burstQos=default|initiated|utility|off: the workers' class while many jobs wait (default: default). */
	private static final int BURST_QOS = switch (System.getProperty("metalods.burstQos", "default")) {
		case "off", "utility" -> 0;
		case "initiated" -> 0x19;
		default -> 0x15;
	};
	private static final int BURST_JOBS = Integer.getInteger("metalods.burstJobs", 24);
	/**
	 * (18 Nov) -Dmetalods.pressureQos=utility|background|off: the workers' class while the game's chunks are missing near the
	 * player (default utility: below the server's chunk generation, never the burst class). Background was tried: 1.2-2.4 s
	 * frame stalls right after teleports, against ~0.2 s at most without it.
	 */
	private static final int PRESSURE_QOS = switch (System.getProperty("metalods.pressureQos", "utility")) {
		case "off" -> 0;
		case "background" -> 0x09;
		default -> 0x11;
	};
	private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();
	/** (fix14) Generation's thread CPU time against its wall time: how much of a core a worker really got while making a tile. */
	final AtomicLong genCpuNanos = new AtomicLong();

	static long key(int level, int cx, int cz) {
		return (long) level << 58 | (long) (cx & 0x1FFFFFFF) << 29 | (cz & 0x1FFFFFFF);
	}

	int airSky() {
		return this.skyLight ? 15 : 0;
	}

	// ======================================================= workers

	/**
	 * (fix21) The game's own chunks first: while the client lacks chunks inside the render distance (flying into new land: the
	 * server is generating them), only YIELD_WORKERS workers keep making LODs (the nearest first, they are what shows where the
	 * real chunks aren't yet) and the rest wait; real chunks' summaries always go on. The 17 Nov measurement (mcopt LodYield): with
	 * the far terrain generating, a flight left twice as many of the render distance's chunks missing (36-40% against 19%), the
	 * LODs standing in for them up close (Jack's 14.12.21 "in an lod"). -Dmetalods.yieldWorkers=N (0: off).
	 */
	static final int YIELD_WORKERS = Integer.getInteger("metalods.yieldWorkers", 2);
	/** (18 Nov) -Dmetalods.pressureWorkers=N: how many workers may still make LODs under pressure (default YIELD_WORKERS). */
	static final int PRESSURE_WORKERS = Math.max(0, Integer.getInteger("metalods.pressureWorkers", YIELD_WORKERS));
	private volatile int allowedWorkers = Integer.MAX_VALUE;
	private long missingSum, missingSamples, pressureSamples;
	private long bandFrames, bandNoCandidate, bandUncovered;

	/** Every 10 frames: the chunks within the render distance less one that the client doesn't have. */
	private void pressure(double camX, double camZ, int rd) {
		if (this.frame % 10 != 0) return;
		if (YIELD_WORKERS <= 0 || rd < 3) {
			this.allowedWorkers = Integer.MAX_VALUE;
			return;
		}
		int r = rd - 1, ccx = (int) Math.floor(camX) >> 4, ccz = (int) Math.floor(camZ) >> 4, missing = 0;
		var source = this.level.getChunkSource();
		for (int z = -r; z <= r; z++) for (int x = -r; x <= r; x++) {
			if (x * x + z * z > r * r) continue;
			if (source.getChunk(ccx + x, ccz + z, false) == null) missing++;
		}
		this.missingSum += missing;
		this.missingSamples++;
		boolean under = missing > 2;
		if (under) this.pressureSamples++;
		this.allowedWorkers = under ? PRESSURE_WORKERS : Integer.MAX_VALUE;
	}

	private void work(int index) {
		// (Apple silicon: the utility QoS class, so the game's own threads always come first; background would keep the
		// workers on the efficiency cores only, too slow to fill the view)
		mcopt.metal.lod.LodGenQos.once(0x11, "MetaLODS worker");
		MetaMesher mesher = new MetaMesher();
		int turn = 0;
		while (!this.closed) {
			try {
				// (fix14) while a view is being filled (many jobs waiting) the workers run at the default QoS class, as the
				// game's chunk generation does, else at utility. fix13's log: workers busy all the time yet a far level-0 tile's
				// surface took 2-3.6 s against ~0.2 s single-threaded offline, and dropping the trees didn't raise tiles/s
				// (1.9): starved on the efficiency cores, not short of work. The render thread stays above (user-interactive).
				// (18 Nov cold teleport) while the game's own chunks are missing near the player, the workers stay in
				// PRESSURE_QOS (utility), never the burst class: at the default class the two LOD workers that keep going took
				// 1.2-1.9 cores from the server's chunk generation (then stuck below them at C2ME's fixed priority 23, see
				// mcopt's C2mePriorityMixin); the chunk under the player came 33.7 s after a teleport, 16.7 s with LOD
				// generation off. The game's chunks first, LODs after.
				boolean pressured = this.allowedWorkers != Integer.MAX_VALUE;
				if (pressured && PRESSURE_QOS != 0) mcopt.metal.lod.LodGenQos.set(PRESSURE_QOS);
				else if (BURST_QOS != 0) mcopt.metal.lod.LodGenQos.set(this.jobs.size() + this.details.size() > BURST_JOBS ? BURST_QOS : 0x11);
				// Real chunks and columns in turn (real chunks first once many wait: each holds a copy of its chunk). Real chunks
				// alone going first starved the generation: joining a world queues ~1000 of them, a flight a steady stream.
				int phase = turn++;
				Runnable r = ((phase & 1) == 0 || this.ingest.size() > 192 && (phase & 3) != 3 || index >= this.allowedWorkers) ? this.ingest.poll() : null;
				if (r != null) {
					r.run();
					continue;
				}
				if (index >= this.allowedWorkers) {
					TimeUnit.MILLISECONDS.sleep(15);
					continue;
				}
                if(((phase&3)==3 || this.detailAllowed || this.jobs.size()==0) && this.refineOne())continue;
				Job j = this.jobs.poll();
				if (j == null) {
					if (this.ingest.isEmpty()) TimeUnit.MILLISECONDS.sleep(10);
					continue;
				}
				if (this.closed) return;
				Col c = j.col;
				if (frameNow - c.wanted > 240) {
					this.results.add(new Result(c, j.version, null, 0, null, Long.MIN_VALUE));
					continue;
				}
				try {
					this.job(c, j, mesher);
				} catch (TileBusy busy) {
					// Its sibling is already generating the shared 64-cell tile. Keep
					// this request alive and let this worker make another tile meanwhile.
					this.jobs.defer(j);
					java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
				} catch (Throwable t) {
					// (dropped: asked for again when next wanted)
					this.results.add(new Result(c, j.version, null, 0, null, Long.MIN_VALUE));
					throw t;
				}
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				System.out.println("[MetaLODS] worker: " + t);
				t.printStackTrace(System.out);
			}
		}
	}

	/** (fix21) Bytes allocated by jobs (derive: store reads, generation, mips; then the mesh) and by real chunks' summaries. */
	final AtomicLong jobAlloc = new AtomicLong(), meshAlloc = new AtomicLong(), ingestAlloc = new AtomicLong(), ingestBuilt = new AtomicLong();

	private void job(Col c, Job j, MetaMesher mesher) {
		if (this.closed) return;
		long a0 = mcopt.metal.lod.MetaGen.allocated();
		List<Long> generated = new ArrayList<>(4);
		Rle center = this.derive(c.level, c.cx, c.cz, true, generated);
		int[][] edges = new int[4][];
		int known = 0;
		int[][] nb = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
		for (int s = 0; s < 4; s++) {
			edges[s] = this.edge(c.level, c.cx + nb[s][0], c.cz + nb[s][1], s, center.h);
			if (edges[s] != null) known |= 1 << s;
		}
		long t1 = System.nanoTime(), am = mcopt.metal.lod.MetaGen.allocated();
		MetaMesher.Mesh m = mesher.mesh(center, edges, c.level);
		m.splitWater();
		long am2 = mcopt.metal.lod.MetaGen.allocated();
		this.meshAlloc.addAndGet(am2 - am);
		this.jobAlloc.addAndGet(am2 - a0);
		synchronized (this.publicationLock) {
			if (this.closed) return;
			this.arena.heights(c.cx * (32 << c.level), c.cz * (32 << c.level), c.level, mesher.tops(), this.minY);
		}  // (fix7: far shadows)
		this.meshNanos.addAndGet(System.nanoTime() - t1);
		this.meshed.incrementAndGet();
		long[] gk = generated.isEmpty() ? null : generated.stream().mapToLong(Long::longValue).toArray();
		this.results.add(new Result(c, j.version, m, known, gk, Long.MIN_VALUE));
	}

	private @Nullable MetaGen generator() {
		if (this.closed) return null;
		if (this.gen != null || this.genFailed || this.server == null) return this.gen;
		synchronized (this.genLock) {
			if (this.closed) return null;
			if (this.gen != null || this.genTried) return this.gen;
			this.genTried = true;
			try {
				this.gen = MetaGen.of(this.server, MetaSettings.exactTrees);
				if (this.gen == null) System.out.println("[MetaLODS] this dimension's generator can't be sampled: only real chunks become LODs");
			} catch (Throwable t) {
				this.genFailed = true;
				System.out.println("[MetaLODS] generation from the seed failed to start (" + t + "): only real chunks become LODs");
				t.printStackTrace(System.out);
			}
			return this.gen;
		}
	}

	private @Nullable Rle cachedGen(int level, int cx, int cz) {
		long k = key(level, cx, cz);
		synchronized (this.genCache) {
			Rle r = this.genCache.getAndMoveToLast(k);
            if(r!=null) {
                this.refineIfDue(level,cx,cz);
                return r;
            }
		}
		Rle r = this.store.loadGen(level, cx, cz);
		if (r != null && r.n == 32 && r.h == Math.max(1, this.height >> level)) {
            synchronized(this.genCache) {
                // A full refinement may have published while disk was reading
                // an older surface. Never overwrite that newer publication.
                Rle current=this.genCache.getAndMoveToLast(k);
                if(current!=null)return current;
                this.cache(k,r);
            }
            this.refineIfDue(level,cx,cz);
			return r;
		}
		return null;
	}

	private void cache(long k, Rle r) {
		synchronized (this.genCache) {
			this.genCache.putAndMoveToLast(k, r);
			if (this.genCache.size() > 1024) {
				while (this.genCache.size() > 900) this.genCache.removeFirst();
			}
		}
	}

	/** The seed's column, from memory, disk, or generated now (with its tile's three others) if allowed. */
	private @Nullable Rle generated(int level, int cx, int cz, boolean generate, List<Long> made) {
		Rle r = this.cachedGen(level, cx, cz);
		if (r != null || !generate || !MetaSettings.generate || this.genFailed) return r;
		MetaGen g = this.generator();
		if (g == null) return null;
		int tx = Math.floorDiv(cx, 2), tz = Math.floorDiv(cz, 2);
		long tileKey = key(level, tx, tz);
		if (!this.tileGate.enter(tileKey)) throw TILE_BUSY;
		try {
			if (this.closed || this.genFailed) return null;
			r = this.cachedGen(level, cx, cz);
			if (r != null) return r;
            long epoch = this.cacheEpoch;
            Rle mip = this.generatedMip(level, cx, cz);
            if (mip != null) {
                synchronized (this.publicationLock) {
                    if (this.closed || epoch != this.cacheEpoch) return null;
                    this.cache(key(level, cx, cz), mip);
                    this.store.saveGen(level, cx, cz, mip);
                    this.mipColumns.incrementAndGet();
                    made.add(key(level, cx, cz));
                }
                return mip;
            }
            long t0 = System.nanoTime();
            long cpu0 = THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1;
            Rle[] four;
            try {
                four = g.generateFar(level, tx, tz, this.minY, this.height,
                    MetaSettings.exactTrees, this.airSky(), this.overhangs, this.ids());
			} catch (Throwable t) {
				if (!this.genFailed) {
					this.genFailed = true;
					this.gen = null;
					System.out.println("[MetaLODS] generation from the seed stopped (" + t + "): only real chunks become LODs");
					t.printStackTrace(System.out);
				}
				return null;
			}
			if (this.closed || epoch!=this.cacheEpoch) return null;
			long t1 = System.nanoTime(), ns = t1 - t0;
			if (cpu0 >= 0) this.genCpuNanos.addAndGet(THREADS.getCurrentThreadCpuTime() - cpu0);
			this.genNanos.addAndGet(ns);
			if (this.genFirstNs == 0) this.genFirstNs = t0;
			this.genLastNs = t1;
			this.generatedChunks.addAndGet(16L << (2 * level));
			if (this.generatedTiles.incrementAndGet() <= 3 || (this.saidLevel & (1 << level)) == 0) {
				this.saidLevel |= 1 << level;
				System.out.println(String.format(java.util.Locale.ROOT, "[MetaLODS] generated a level-%d tile at %d, %d in %.1f ms", level, tx, tz, ns / 1e6));
			}
            synchronized (this.publicationLock) {
                if (this.closed || epoch != this.cacheEpoch) return null;
                for (int q = 0; q < 4; q++) {
                    int qx = 2 * tx + (q & 1), qz = 2 * tz + (q >> 1);
                    boolean requested = qx == cx && qz == cz;
                    if (!requested && this.cachedGen(level, qx, qz) != null) continue;
                    long k = key(level, qx, qz);
                    this.cache(k, four[q]);
                    this.store.saveGen(level, qx, qz, four[q]);
                    made.add(k);
                    if (requested) r = four[q];
                }
            }
			return r;
		} finally {
			this.tileGate.leave(tileKey);
		}
	}

    /** Reuse finer seed data when all four children are complete. Real chunks are overlaid separately. */
    private @Nullable Rle generatedMip(int level, int cx, int cz) {
        if (level == 0) return null;
        Rle[] children = new Rle[4];
        int childLevel = level - 1;
        for (int q = 0; q < 4; q++) {
            int x = cx * 2 + (q & 1), z = cz * 2 + (q >> 1);
            Rle child = this.cachedGen(childLevel, x, z);
            if (child == null || this.store.isSurface(childLevel, x, z)) return null;
            children[q] = child;
        }
        return Rle.mipChildren(children);
    }

	/** Chunks per column side at this level, and cells per chunk side. */
	private static int chunksPer(int level) {
		return 2 << level;
	}

    private Rle streamChannels(Rle base, int level, int cx, int cz) {
        if(base==null || this.closed)return base;
        // (fix13: far level-0 columns are made without the game's trees: the biomes' estimate, as at levels 1+)
        if(level>0 || this.store.isSurface(level,cx,cz) || !MetaSettings.exactTrees)
            base=this.canopies.apply(base,level,cx,cz,this.minY,this.airSky());
        if (this.streams == null) return base;
        return this.streams.apply(base, level, cx, cz, this.minY, this.airSky(), new MetaGen.Ids() {
            public int block(BlockState state) { return MetaPalette.id(state); }
            public int biome(Holder<Biome> biome) { return MetaPalette.biome(biome); }
        });
    }

	/** The column as drawn: the seed's (or air) with every real chunk seen there over it (levels 0 to 4). */
	Rle derive(int level, int cx, int cz, boolean generate, List<Long> made) {
		int h = Math.max(1, this.height >> level);
		if (this.closed) return Rle.empty(32, h);
		if (level > 4) {
			Rle base = this.generated(level, cx, cz, generate, made);
			return base != null ? this.streamChannels(base, level, cx, cz) : Rle.empty(32, h);
		}
		int cpc = 16 >> level, cps = chunksPer(level);
		int c0x = cx * cps, c0z = cz * cps;
		MetaStore.RealChunk[] real = null;
		int realCount = 0;
		for (int j = 0; j < cps; j++) {
			for (int i = 0; i < cps; i++) {
				MetaStore.RealChunk rc = this.store.hasRegion((c0x + i) >> 5, (c0z + j) >> 5) ? this.store.real(c0x + i, c0z + j) : null;
				if (rc == null || rc.levels()[level].h != h) continue;
				if (real == null) real = new MetaStore.RealChunk[cps * cps];
				real[i + j * cps] = rc;
				realCount++;
			}
		}
		// A fully captured column is authoritative. Generating a seed tile first
		// wastes density/tree work whose every voxel is immediately overwritten.
		Rle base = realCount == cps * cps ? null : this.generated(level, cx, cz, generate, made);
        if (base != null) base = this.streamChannels(base, level, cx, cz);
		if (base == null) base = Rle.empty(32, h);
		if (real == null) return base;
		Rle.Builder b = new Rle.Builder(32, h);
		int[] col = new int[h];
		for (int z = 0; z < 32; z++) {
			for (int x = 0; x < 32; x++) {
				MetaStore.RealChunk rc = real[x / cpc + (z / cpc) * cps];
				Rle src = rc != null ? rc.levels()[level] : null;
				if (src != null && src.h == h) src.expand(x % cpc, z % cpc, col, 0);
				else base.expand(x, z, col, 0);
				b.column(x, z, col);
			}
		}
		return b.build();
	}

	/**
	 * Neighbour (ncx, ncz)'s cells along its side facing the column being meshed (side s of that column: 0 -x, 1 +x, 2 -z,
	 * 3 +z), 32 cells of h voxels, or null if nothing is known there yet (nothing is generated for it).
	 */
	private int @Nullable [] edge(int level, int ncx, int ncz, int s, int h) {
		Rle base = this.cachedGen(level, ncx, ncz);
        if (base != null) base = this.streamChannels(base, level, ncx, ncz);
		int[] out = new int[32 * h];
		int cps = chunksPer(level), cpc = 16 >> level;
		boolean any = base != null;
		for (int i = 0; i < 32; i++) {
			int x = s == 0 ? 31 : s == 1 ? 0 : i, z = s == 2 ? 31 : s == 3 ? 0 : i;
			Rle src = null;
			int sx = x, sz = z;
			if (level <= 4) {
				int chx = ncx * cps + x / cpc, chz = ncz * cps + z / cpc;
				MetaStore.RealChunk rc = this.store.hasRegion(chx >> 5, chz >> 5) ? this.store.real(chx, chz) : null;
				if (rc != null && rc.levels()[level].h == h) {
					src = rc.levels()[level];
					sx = x % cpc;
					sz = z % cpc;
				}
			}
			if (src != null) {
				src.expand(sx, sz, out, i * h);
				any = true;
			} else if (base != null) {
				base.expand(x, z, out, i * h);
			} else {
				// (unknown here: filled from the column's own edge by the mesher, so mark it so)
				return null;
			}
		}
		return any ? out : null;
	}

	// ======================================================= real chunks

	/** A real chunk's blocks and light (copied on the render thread), made into LODs on a worker. */
	void ingest(MetaChunks.Snapshot s) {
		if (this.closed) return;
		long key = MetaStore.key(s.chunkX(), s.chunkZ());
		Object ticket = this.latestIngest.submit(key);
		this.ingest.add(() -> {
			try {
				if (!this.latestIngest.current(key, ticket)) return;
				long a0 = mcopt.metal.lod.MetaGen.allocated();
				MetaStore.RealChunk rc = MetaChunks.build(s, this.minY, this.height, this.skyLight);
				this.ingestAlloc.addAndGet(mcopt.metal.lod.MetaGen.allocated() - a0);
				this.ingestBuilt.incrementAndGet();
				if (rc == null) return;
				this.latestIngest.publish(key, ticket, () -> {
					MetaStore.RealChunk previous = this.store.real(s.chunkX(), s.chunkZ());
					if (previous != null && previous.levels()[0].sameData(rc.levels()[0])) return;
					this.store.putReal(s.chunkX(), s.chunkZ(), rc);
					this.ingested.incrementAndGet();
					this.results.add(new Result(null, 0, null, 0, null, key));
				});
			} catch (Throwable t) {
				System.out.println("[MetaLODS] chunk " + s.chunkX() + ", " + s.chunkZ() + ": " + t);
			} finally {
				this.latestIngest.finish(key, ticket);
			}
		});
	}

	// ======================================================= render thread

	private Col col(int level, int cx, int cz) {
		long k = key(level, cx, cz);
		Col c = this.cols.get(k);
		if (c == null) {
			c = new Col(level, cx, cz, k);
			this.cols.put(k, c);
		}
		return c;
	}

	private void request(Col c, double prio) {
		c.wanted = this.frame;
		if (c.state == 1) return;
		if (c.state == 2 && !c.dirty) return;
		if (c.state == 0 && this.frame < c.noRoomUntil) return;
		c.pagesValid = c.state == 2;
		c.state = 1;
		c.dirty = false;
		c.version++;
		this.jobs.add(new Job(c, c.version, prio, this.seq.incrementAndGet()), c.level >= 2);
	}

	private void dirtyColumn(int level, int x, int z) {
		Col c = this.cols.get(key(level, x, z));
		if (c != null) c.dirty = true;
	}

	/** Applies what the workers finished, for at most `budgetNs`. */
	private void integrate(long budgetNs) {
		long end = System.nanoTime() + budgetNs;
        long[] updated;
        while((updated=this.refined.poll())!=null)for(long k:updated) {
            int l=(int)(k>>>58),x=(int)(k<<6>>35),z=(int)(k<<35>>35);
            this.dirtyColumn(l,x,z);this.dirtyColumn(l,x-1,z);this.dirtyColumn(l,x+1,z);
            this.dirtyColumn(l,x,z-1);this.dirtyColumn(l,x,z+1);
        }
		Result r;
		while (System.nanoTime() < end && (r = this.results.poll()) != null) {
			if (r.realChunk != Long.MIN_VALUE) {
				int chx = (int) (r.realChunk >> 32), chz = (int) r.realChunk;
				for (int l = 0; l <= 4; l++) {
					int cps = chunksPer(l);
					// A mesh also reads the one-cell border of each neighbour. Rebuild both sides
					// when an ingested chunk touches a column edge (including negative coordinates).
					int cx = Math.floorDiv(chx, cps), cz = Math.floorDiv(chz, cps);
					this.dirtyColumn(l, cx, cz);
					if (Math.floorMod(chx, cps) == 0) this.dirtyColumn(l, cx - 1, cz);
					if (Math.floorMod(chx, cps) == cps - 1) this.dirtyColumn(l, cx + 1, cz);
					if (Math.floorMod(chz, cps) == 0) this.dirtyColumn(l, cx, cz - 1);
					if (Math.floorMod(chz, cps) == cps - 1) this.dirtyColumn(l, cx, cz + 1);
				}
				continue;
			}
			Col c = r.col;
			if (c == null || r.version != c.version) continue;
			if (r.generated != null) {
				// columns that meshed without these as neighbours: again with them
				for (long k : r.generated) {
					int l = (int) (k >>> 58), gx = (int) (k << 6 >> 35), gz = (int) (k << 35 >> 35);
					this.edgeArrived(l, gx - 1, gz, 1);
					this.edgeArrived(l, gx + 1, gz, 0);
					this.edgeArrived(l, gx, gz - 1, 3);
					this.edgeArrived(l, gx, gz + 1, 2);
				}
			}
			if (r.mesh == null) {
				c.state = c.pagesValid ? 2 : 0;
				continue;
			}
			this.upload(c, r.mesh);
			c.edges = r.edges;
		}
	}

	private void edgeArrived(int level, int cx, int cz, int side) {
		Col c = this.cols.get(key(level, cx, cz));
		if (c != null && c.state == 2 && (c.edges & (1 << side)) == 0) c.dirty = true;
	}

	private void upload(Col c, MetaMesher.Mesh m) {
		int need = 0;
		for (int f = 0; f < 6; f++) {
			int solid = m.solidCounts[f], water = m.counts[f] - solid;
			need += Math.ceilDiv(solid, MetaArena.QUADS_PER_PAGE) + Math.ceilDiv(water, MetaArena.QUADS_PER_PAGE);
		}
		if (need > this.arena.freePages()) {
			if (!this.arenaFull) {
				this.arenaFull = true;
				System.out.println("[MetaLODS] the GPU arena is full (" + this.arena.pages + " pages): freeing columns out of view first");
			}
			c.state = c.pagesValid ? 2 : 0;
			if (!c.pagesValid) c.noRoomUntil = this.frame + 90;
			this.nextEvict = 0;
			return;
		}
		int[] pages = new int[need];
		int n = 0;
		int ox = c.cx * (32 << c.level), oz = c.cz * (32 << c.level);
		for (int f = 0; f < 6; f++) {
			c.faceFirst[f] = n;
			for (int material = 0; material < 2; material++) {
				int start = 0, end = m.solidCounts[f];
				if (material == 1) {
					c.faceWater[f] = n;
					start = end;
					end = m.counts[f];
				}
				for (int q = start; q < end; q += MetaArena.QUADS_PER_PAGE) {
					int page = this.arena.alloc();
					int count = Math.min(MetaArena.QUADS_PER_PAGE, end - q);
					this.arena.fill(page, m.quads[f], m.ao[f], q, count, ox, this.minY, oz, c.level, f);
					pages[n++] = page;
				}
			}
		}
		c.faceFirst[6] = n;
		this.arena.release(c.pages, this.frame);
		c.pages = pages;
		c.minYb = m.minY == Integer.MAX_VALUE ? this.minY : this.minY + (m.minY << c.level);
		c.maxYb = m.maxY == Integer.MIN_VALUE ? this.minY : this.minY + (m.maxY << c.level);
		c.state = 2;
		c.pagesValid = true;
	}

	/** (ColdTp) this frame's hand-over mask: the real terrain draws the chunk whole. */
	boolean drawnChunk(int cx, int cz) {
		int mx = cx - this.maskX, mz = cz - this.maskZ;
		if (this.maskSize == 0 || mx < 0 || mz < 0 || mx >= this.maskSize || mz >= this.maskSize) return false;
		int bit = mz * this.maskWords * 32 + mx;
		return (this.maskBits[bit >> 5] >>> (bit & 31) & 1) != 0;
	}

	/** Where the real terrain is drawn: loaded chunks inside the render distance (as the far terrain's mask in mcopt's LOD). */
	private void updateMask(double camX, double camY, double camZ, int rd) {
		// The chunks our terrain really draws whole this frame (mcopt's OwnSeam: every section of the chunk that can show is
		// compiled, inside the game's own distance rule). Loaded isn't drawn: the client holds a ring of chunks it never draws and
		// new chunks take a while to compile; masking those left a strip of sky between the real terrain and the LODs.
		if (HANDOFF_DRAWN) {
			mcopt.metal.own.OwnSeam.Window w = null;
			try {
				w = mcopt.metal.own.OwnSeam.mask(camX, camY, camZ, this.maskBits, MetaArena.MASK_MAX);
			} catch (RuntimeException e) {
				if (!this.saidMask) {
					this.saidMask = true;
					System.out.println("[MetaLODS] the drawn-chunk mask failed (" + e + "): LOD coverage retained");
				}
			}
			if (w != null) {
				this.maskX = w.x0;
				this.maskZ = w.z0;
				this.maskSize = w.size;
				this.maskWords = w.words;
				this.maskFromOwn = true;
				// (fix2: and every loaded chunk 2 or more inside the render distance: a chunk with a section the real terrain never
				// compiles (deep air, a cave) never counts as drawn whole, so its LOD stayed in it for good and showed through:
				// the LOD's filled-in stone in ravines and caves, LOD trees inside the near land (Jack's 13.57.16, 13.58.58,
				// 14.00.42, 14.06.06). The strip at the edge stays on the drawn-whole rule)
				// (no longer by default: loaded isn't compiled, and while flying a loaded chunk inside that ring not drawn yet was
				// a strip of sky between the near land and the LODs (Jack's 16.44.17). The drawn rule now looks only at the
				// sections holding the surface, which covers the ravine and cave cases. -Dmetalods.maskLoaded=true: as fix2)
				if (MASK_LOADED) this.addLoaded(camX, camZ, Math.max(0, rd - 2));
				return;
			}
		}
		this.maskFromOwn = false;
		if (HANDOFF_DRAWN) {
			// No proof that near geometry is ready. Loaded chunks are not a safe replacement.
			java.util.Arrays.fill(this.maskBits, 0);
			this.maskSize = this.maskWords = 0;
			return;
		}
		int size = Math.min(MetaArena.MASK_MAX, 2 * rd + 5);
		int ccx = (int) Math.floor(camX) >> 4, ccz = (int) Math.floor(camZ) >> 4;
		this.maskX = ccx - size / 2;
		this.maskZ = ccz - size / 2;
		this.maskSize = size;
		this.maskWords = (size + 31) / 32;
		java.util.Arrays.fill(this.maskBits, 0);
		double max = rd * 16.0, max2 = max * max;
		var source = this.level.getChunkSource();
		for (int mz = 0; mz < size; mz++) {
			int oz = (this.maskZ + mz) * 16;
			double dz = Math.max(0, Math.max(oz - camZ, camZ - (oz + 16)));
			if (dz >= max) continue;
			for (int mx = 0; mx < size; mx++) {
				int ox = (this.maskX + mx) * 16;
				double dx = Math.max(0, Math.max(ox - camX, camX - (ox + 16)));
				if (dx * dx + dz * dz >= max2) continue;
				if (source.getChunk(this.maskX + mx, this.maskZ + mz, false) == null) continue;
				int bit = mz * this.maskWords * 32 + mx;
				this.maskBits[bit >> 5] |= 1 << (bit & 31);
			}
		}
	}

	/** Sets the mask's bit for every loaded chunk within `r` chunks (circle) of the camera, inside the mask's window. */
	private void addLoaded(double camX, double camZ, int r) {
		if (r <= 0) return;
		int ccx = (int) Math.floor(camX) >> 4, ccz = (int) Math.floor(camZ) >> 4;
		double max = r * 16.0, max2 = max * max;
		var source = this.level.getChunkSource();
		for (int cz = ccz - r; cz <= ccz + r; cz++) {
			int mz = cz - this.maskZ;
			if (mz < 0 || mz >= this.maskSize) continue;
			int oz = cz * 16;
			double dz = Math.max(0, Math.max(oz - camZ, camZ - (oz + 16)));
			if (dz >= max) continue;
			for (int cx = ccx - r; cx <= ccx + r; cx++) {
				int mx = cx - this.maskX;
				if (mx < 0 || mx >= this.maskSize) continue;
				int ox = cx * 16;
				double dx = Math.max(0, Math.max(ox - camX, camX - (ox + 16)));
				if (dx * dx + dz * dz >= max2) continue;
				int bit = mz * this.maskWords * 32 + mx;
				if ((this.maskBits[bit >> 5] >>> (bit & 31) & 1) != 0) continue;
				if (source.getChunk(cx, cz, false) == null) continue;
				this.maskBits[bit >> 5] |= 1 << (bit & 31);
			}
		}
	}

	// Two land passes with five index counts each, followed by the two ordered water passes.
	static final int WATER_DRAW = MetaArena.LAND_BUCKETS * 2, DRAW_GROUPS = WATER_DRAW + 2;
	final int[] drawCount = new int[DRAW_GROUPS], drawOffset = new int[DRAW_GROUPS];
	private final int[][] pageLists = new int[DRAW_GROUPS][];

	/** (s1119) the last frame's drawn pages: quads by level and by face (top, side, bottom), and how many are leaves or water */
	private String quadTally() {
		int slot = (int) (this.frame % MetaArena.RING);
		long vis = this.arena.vis[slot];
		long[] byLevel = new long[8];
		long tops = 0, sides = 0, bottoms = 0, leaves = 0, water = 0, all = 0;
		for (int i = 0; i < this.lastDrawPages; i++) {
			int page = MemoryUtil.memGetInt(vis + 4L * i) & 0x1FFFF;
			int packed = MemoryUtil.memGetInt(this.arena.infos + (long) page * MetaArena.INFO_BYTES + 12);
			int level = packed & 15, face = (packed >> 4) & 7, n = (packed >> 8) & 0x1FF;
			byLevel[Math.min(level, 7)] += n;
			if (face == 2) tops += n;
			else if (face == 3) bottoms += n;
			else sides += n;
			all += n;
			long q = this.arena.quads + (long) page * MetaArena.PAGE_BYTES;
			for (int k = 0; k < n; k++) {
				int f = MetaPalette.flagsOf(MemoryUtil.memGetInt(q + 8L * k + 4) & 0xFFFF);
				if ((f & (1 << 16)) != 0) leaves++;
				if ((f & (1 << 17)) != 0) water++;
			}
		}
		StringBuilder b = new StringBuilder();
		b.append(all).append(" (by level");
		for (int l = 0; l < 8; l++) if (byLevel[l] > 0) b.append(' ').append(l).append(':').append(byLevel[l]);
		b.append("; tops ").append(tops).append(", sides ").append(sides).append(", bottoms ").append(bottoms)
			.append("; leaves ").append(leaves).append(", water ").append(water).append(')');
		return b.toString();
	}

	/** (s1120) shadow caster groups: the land buckets, unmasked then masked */
	static final int SHADOW_GROUPS = MetaArena.LAND_BUCKETS * 2;
	private final int[][] shadowLists = new int[SHADOW_GROUPS][];
	private final int[] shadowCount = new int[SHADOW_GROUPS];
	long shadowPages;

	/**
	 * (s1120) The land pages of the columns last drawn (prepare's selection) that a shadow cascade sees, as casters: m the
	 * cascade's camera-relative world -> light clip (column-major, orthographic), the camera. Only faces turned toward the light
	 * (closed voxel land: the first surface the light meets), water left out (the real water casts none). Columns the real terrain
	 * draws whole are left out; partly drawn ones go in the masked groups (ml_shadow_fs drops their LOD where the real one is).
	 * Writes arena.shadowVis[slot], arena.shadowGroups and arena.shadowFrame; the number of groups (0: nothing to draw).
	 */
	int shadowList(long enc, float[] m, double camX, double camY, double camZ, int slot) {
		java.util.Arrays.fill(this.shadowCount, 0);
		float lx = -m[2], ly = -m[6], lz = -m[10];  // (toward the light: depth grows away from it)
		boolean[] facing = {lx > 0f, lx < 0f, ly > 0f, ly < 0f, lz > 0f, lz < 0f};
		boolean hide = MetaSettings.hideUnderReal;
		for (Col c : this.draw) {
			int w = 32 << c.level;
			int bx = c.cx * w, bz = c.cz * w;
			float x0 = (float) (bx - camX), z0 = (float) (bz - camZ), y0 = (float) (c.minYb - camY), y1 = (float) (c.maxYb - camY);
			// (orthographic: the box's x and y on the map, from its centre and half size; the depth isn't tested, casters between the
			// light and the near plane still cast, the encoder clamps them)
			float cx = x0 + w * 0.5f, cy = (y0 + y1) * 0.5f, cz = z0 + w * 0.5f, hx = w * 0.5f, hy = (y1 - y0) * 0.5f, hz = hx;
			float px = m[0] * cx + m[4] * cy + m[8] * cz + m[12], ex = Math.abs(m[0]) * hx + Math.abs(m[4]) * hy + Math.abs(m[8]) * hz;
			float py = m[1] * cx + m[5] * cy + m[9] * cz + m[13], ey = Math.abs(m[1]) * hx + Math.abs(m[5]) * hy + Math.abs(m[9]) * hz;
			if (px - ex > 1f || px + ex < -1f || py - ey > 1f || py + ey < -1f) continue;
			boolean masked = false;
			if (hide && this.anyMasked(bx, bz, w)) {
				if (this.allMasked(bx, bz, w)) continue;
				masked = true;
			}
			for (int f = 0; f < 6; f++) {
				if (!facing[f]) continue;
				for (int i = c.faceFirst[f]; i < c.faceWater[f]; i++) {
					int page = c.pages[i];
					int g = this.arena.pageBucket[page] + (masked ? MetaArena.LAND_BUCKETS : 0);
					int n = this.shadowCount[g];
					int[] list = this.shadowLists[g];
					if (list == null) list = this.shadowLists[g] = new int[256];
					else if (n == list.length) list = this.shadowLists[g] = java.util.Arrays.copyOf(list, n * 2);
					list[n] = page;
					this.shadowCount[g] = n + 1;
				}
			}
		}
		MetaArena a = this.arena;
		if (a.shadowVisBuf[slot] == 0) {
			a.shadowVisBuf[slot] = MetaNative.buffer(enc, (long) a.pages * 4);
			if (a.shadowVisBuf[slot] == 0) return 0;
			a.shadowVis[slot] = MetaNative.contents(a.shadowVisBuf[slot]);
		}
		long vis = a.shadowVis[slot];
		int groups = 0, off = 0;
		for (int g = 0; g < SHADOW_GROUPS; g++) {
			int n = this.shadowCount[g];
			if (n == 0) continue;
			if (off + n > a.pages) break;
			MemoryUtil.memIntBuffer(vis + 4L * off, n).put(this.shadowLists[g], 0, n);
			long e = a.shadowGroups + 16L * groups;
			MemoryUtil.memPutInt(e, off);
			MemoryUtil.memPutInt(e + 4, n);
			MemoryUtil.memPutInt(e + 8, quadCapacity(g % MetaArena.LAND_BUCKETS));
			MemoryUtil.memPutInt(e + 12, g >= MetaArena.LAND_BUCKETS ? 1 : 0);
			off += n;
			groups++;
		}
		this.shadowPages = off;
		// the frame: the drawn frame's (its mask), the cascade's matrix and this camera
		MemoryUtil.memCopy(a.frame, a.shadowFrame, MetaArena.FRAME_BYTES);
		for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(a.shadowFrame + i * 4L, m[i]);
		int ibx = (int) Math.floor(camX), iby = (int) Math.floor(camY), ibz = (int) Math.floor(camZ);
		MemoryUtil.memPutInt(a.shadowFrame + 64, ibx);
		MemoryUtil.memPutInt(a.shadowFrame + 68, iby);
		MemoryUtil.memPutInt(a.shadowFrame + 72, ibz);
		MemoryUtil.memPutFloat(a.shadowFrame + 80, (float) (camX - ibx));
		MemoryUtil.memPutFloat(a.shadowFrame + 84, (float) (camY - iby));
		MemoryUtil.memPutFloat(a.shadowFrame + 88, (float) (camZ - ibz));
		return groups;
	}

	private void addPage(int group, int page, int fade) {
		int count = this.drawCount[group];
		int[] list = this.pageLists[group];
		if (list == null) list = this.pageLists[group] = new int[128];
		else if (count == list.length) list = this.pageLists[group] = java.util.Arrays.copyOf(list, count * 2);
		list[count] = page | fade;
		this.drawCount[group] = count + 1;
	}

	/** Whether the real terrain draws any chunk of the square (x0, z0, w blocks). */
	private boolean anyMasked(int x0, int z0, int w) {
		int c0x = Math.max(x0 >> 4, this.maskX), c0z = Math.max(z0 >> 4, this.maskZ);
		int c1x = Math.min((x0 + w - 1) >> 4, this.maskX + this.maskSize - 1), c1z = Math.min((z0 + w - 1) >> 4, this.maskZ + this.maskSize - 1);
		for (int z = c0z; z <= c1z; z++) for (int x = c0x; x <= c1x; x++) if (this.masked(x, z)) return true;
		return false;
	}

	private boolean masked(int chx, int chz) {
		int mx = chx - this.maskX, mz = chz - this.maskZ;
		if (mx < 0 || mz < 0 || mx >= this.maskSize || mz >= this.maskSize) return false;
		int bit = mz * this.maskWords * 32 + mx;
		return (this.maskBits[bit >> 5] >>> (bit & 31) & 1) != 0;
	}

	/** Whether the real terrain draws every chunk of the square (x0, z0, w blocks). */
	private boolean allMasked(int x0, int z0, int w) {
        if (this.transitionCandidate > 0 && this.intersectsTransition(x0, z0, w)) return false;
		int c0x = x0 >> 4, c0z = z0 >> 4, n = Math.max(1, w >> 4);
		if (n > this.maskSize) return false;
		for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
			if (!this.masked(c0x + i, c0z + j)) return false;
			int at = (c0z + j - this.maskZ) * this.maskSize + c0x + i - this.maskX;
			if (this.fadeAmounts[at] != 0) return false;
		}
		return true;
	}

	private final List<Col> draw = new ArrayList<>();
	/** This frame's view in chunks' worth of area: all the columns it wants, and those of them with a mesh (the on-screen progress). */
	private long areaAll, areaReady, colsAll, colsReady;
	private final long[] progT = new long[8], progA = new long[8];
	private int progN;
	private long progDoneSince, progNext, progBigSince;
	private boolean progDone;
	private double progRate;
	private double camX, camY, camZ;
	private float reach;
    private float transitionCandidate, transitionLimit;
    float transitionEnd;

    /**
     * (fix21) The width (blocks) of the band where the real terrain dissolves into the LODs (both sides: lighting.metal's
     * imLodNearVisible from ImFrame.lodFog.zw, metalods.metal's ml_fs from MLFrame.camBlock.w). 16 blocks read as a seam that
     * pops; two chunks blend. -Dmetalods.edgeBand=N.
     */
    static final float EDGE_BAND = MetaLods.EDGE_BAND;

    private boolean intersectsTransition(int x0, int z0, int w) {
        double far = Math.max(Math.max(Math.abs(x0 - this.camX), Math.abs(x0 + w - this.camX)),
                              Math.max(Math.abs(z0 - this.camZ), Math.abs(z0 + w - this.camZ)));
        return far >= this.transitionCandidate - EDGE_BAND;
    }

    /** Every chunk that can contain faded near geometry must have a selected,
     * nonempty LOD mesh. No fading while generation still has holes. */
    private boolean transitionCovered() {
        if (this.transitionCandidate <= 0) return false;
        int x0 = (int)Math.floor((this.camX - this.transitionLimit) / 16), x1 = (int)Math.floor((this.camX + this.transitionLimit) / 16);
        int z0 = (int)Math.floor((this.camZ - this.transitionLimit) / 16), z1 = (int)Math.floor((this.camZ + this.transitionLimit) / 16);
        for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            if (!this.intersectsTransition(x * 16, z * 16, 16)) continue;
            if (!this.hasSelectedLod(x, z)) return false;
        }
        return true;
    }

    private final LongOpenHashSet previouslyReady = new LongOpenHashSet(), previouslyCovered = new LongOpenHashSet();
    private final Long2LongOpenHashMap fadeStarts = new Long2LongOpenHashMap();
    private final byte[] fadeAmounts = new byte[MetaArena.MASK_MAX * MetaArena.MASK_MAX];
    /** (fix24) How long a real chunk takes to dissolve in over its LOD: 300 ms read as a pop (Jack 21:26 "couldn't see the blend,
     * looked like straight pop in"). -Dmetalods.chunkFadeMs=N. */
    private static final long CHUNK_FADE_MS = Math.clamp(Long.getLong("metalods.chunkFadeMs", 900L), 50L, 5000L);
    private boolean chunkFadeActive;

    private boolean hasSelectedLod(int x, int z) {
        for (int l = 0; l <= TOP; l++) {
            Col c = this.cols.get(key(l, x >> (l + 1), z >> (l + 1)));
            if (c != null && c.selectedFrame == this.frame) return true;
        }
        return false;
    }

    /** Start the handoff only when last frame had an LOD and this frame gained a drawable real chunk. */
    private void beginChunkFades(long now) {
        java.util.Arrays.fill(this.fadeAmounts, 0, this.maskSize * this.maskSize, (byte) 0);
        this.chunkFadeActive = false;
        if (!this.maskFromOwn || !MetaSettings.edgeDither || !MetaSettings.hideUnderReal
            || MetaSettings.coverageDebug != 0 || MetaLods.probe == 3) {
            this.previouslyReady.clear();
            this.previouslyCovered.clear();
            this.fadeStarts.clear();
            return;
        }
        for (int z = 0; z < this.maskSize; z++) {
            int cz = this.maskZ + z;
            for (int x = 0; x < this.maskSize; x++) {
                int cx = this.maskX + x;
                if (!this.masked(cx, cz)) continue;
                long chunk = MetaStore.key(cx, cz);
                if (!this.previouslyReady.contains(chunk) && this.previouslyCovered.contains(chunk)) {
                    this.fadeStarts.put(chunk, now);
                }
                long started = this.fadeStarts.get(chunk);
                if (started == 0 || now - started >= CHUNK_FADE_MS) continue;
                double t = (double) (now - started) / CHUNK_FADE_MS;
                this.fadeAmounts[z * this.maskSize + x] = (byte) Math.round(255 * (1 - t * t * (3 - 2 * t)));
            }
        }
    }

    private void recordChunkCoverage(long now) {
        this.previouslyReady.clear();
        this.previouslyCovered.clear();
        if (!this.maskFromOwn || !MetaSettings.edgeDither || !MetaSettings.hideUnderReal
            || MetaSettings.coverageDebug != 0 || MetaLods.probe == 3) return;
        var entries = this.fadeStarts.long2LongEntrySet().fastIterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            long key = entry.getLongKey();
            if (now - entry.getLongValue() >= CHUNK_FADE_MS || !this.masked((int) (key >> 32), (int) key)) entries.remove();
        }
        for (int z = 0; z < this.maskSize; z++) {
            int cz = this.maskZ + z;
            for (int x = 0; x < this.maskSize; x++) {
                int cx = this.maskX + x, at = z * this.maskSize + x;
                long chunk = MetaStore.key(cx, cz);
                boolean ready = this.masked(cx, cz);
                if (ready) this.previouslyReady.add(chunk);
                if ((!ready || this.fadeAmounts[at] != 0) && this.hasSelectedLod(cx, cz)) {
                    this.previouslyCovered.add(chunk);
                    this.chunkFadeActive |= this.fadeAmounts[at] != 0;
                } else {
                    this.fadeAmounts[at] = 0;
                }
            }
        }
        if (this.chunkFadeActive) {
            long data = this.arena.fade[this.slot()];
            MemoryUtil.memPutFloat(data, (float) (this.camX - this.maskX * 16.0));
            MemoryUtil.memPutFloat(data + 4, (float) (this.camZ - this.maskZ * 16.0));
            MemoryUtil.memPutInt(data + 8, this.maskSize);
            MemoryUtil.memByteBuffer(data + 16, this.maskSize * this.maskSize)
                .put(this.fadeAmounts, 0, this.maskSize * this.maskSize);
        }
    }

    long chunkFadeBuffer() {
        return this.chunkFadeActive ? this.arena.fadeBuf[this.slot()] : 0;
    }

	/** The current level's sea level (the y above its topmost sea water block), 0 if there's no level. */
	private static float seaLevel() {
		ClientLevel l = Minecraft.getInstance().level;
		return l == null ? 0f : (float) l.getSeaLevel();
	}
	private int detail, farDetail;
	private final FrustumIntersection frustum = new FrustumIntersection();

	/**
	 * This frame: finished work in, the columns chosen, the pages to draw listed into the arena's ring slot. Returns the
	 * number of pages listed (and fills the frame constants).
	 */
	int prepare(Matrix4fc clip, double camX, double camY, double camZ, int renderDistance) {
        if (this.streams != null) this.streams.pollReady();
        if (this.streams != null && this.streams.revision() != this.streamsRevision) {
            this.streamsRevision = this.streams.revision();
            mcopt.metal.lod.LodStreams.Ready ready;
            while((ready=this.streams.pollCompleted())!=null) {
                int l=ready.level(),x=ready.cx(),z=ready.cz();
                this.dirtyColumn(l,x,z);
                this.dirtyColumn(l,x-1,z);this.dirtyColumn(l,x+1,z);
                this.dirtyColumn(l,x,z-1);this.dirtyColumn(l,x,z+1);
            }
        }
		this.frame++;
		frameNow = this.frame;
		this.arena.reclaim(this.frame);
		this.integrate(3_000_000L);
		this.camX = camX;
		this.camY = camY;
		this.camZ = camZ;
		this.reach = MetaSettings.distance * 16f;
        this.transitionLimit = (renderDistance + 2) * 16f;
        this.transitionCandidate = MetaSettings.edgeDither && MetaSettings.hideUnderReal && MetaSettings.coverageDebug == 0
            && renderDistance >= 4 && this.reach > this.transitionLimit * 1.42f ? (renderDistance - 1) * 16f : 0f;
		this.detail = MetaSettings.detail;
		this.farDetail = MetaSettings.farDetail;
		this.frustum.set(clip, false);
		long nowMs = System.currentTimeMillis();
		this.updateMask(camX, camY, camZ, renderDistance);
		this.beginChunkFades(nowMs);
		this.pressure(camX, camZ, renderDistance);
		this.draw.clear();
		this.areaAll = 0;
		this.areaReady = 0;
		this.colsAll = 0;
		this.colsReady = 0;
		int wTop = 32 << TOP;
		int x0 = (int) Math.floor((camX - this.reach) / wTop), x1 = (int) Math.floor((camX + this.reach) / wTop);
		int z0 = (int) Math.floor((camZ - this.reach) / wTop), z1 = (int) Math.floor((camZ + this.reach) / wTop);
		for (int cz = z0; cz <= z1; cz++) for (int cx = x0; cx <= x1; cx++) this.visit(TOP, cx, cz);
		this.detailAllowed = this.areaAll>0 && this.areaReady>=this.areaAll*.95;
		this.prefetch(nowMs);
		this.recordChunkCoverage(nowMs);
        // (fix18: the edge's dither band slides in from past the real terrain over ~0.6 s when it becomes possible, instead of the
        // whole outer ring of real chunks turning into LODs in one frame; off at once when a LOD is missing in it (else holes))
        boolean covered = this.transitionCovered();
        float transitionTarget = covered ? this.transitionCandidate : 0f;
        // (fix22) how often the blend band is on, and why not: no candidate (settings, render distance, reach) or a LOD missing
        this.bandFrames++;
        if (this.transitionCandidate <= 0f) this.bandNoCandidate++;
        else if (!covered) this.bandUncovered++;
        long tNow = System.nanoTime();
        float tDt = this.transitionNs == 0L ? 0f : Math.min(0.1f, (tNow - this.transitionNs) / 1e9f);
        this.transitionNs = tNow;
        if (transitionTarget <= 0f || !FADES) this.transitionShown = transitionTarget;
        else {
            float from = this.transitionLimit + 16f;
            if (this.transitionShown <= 0f || this.transitionShown > from) this.transitionShown = from;
            this.transitionShown = Math.max(transitionTarget, this.transitionShown - TRANSITION_SPEED * tDt);
        }
        this.transitionEnd = this.transitionShown;
        this.crossfade(nowMs);
		// the pages, face groups turned toward the camera only
		int slot = (int) (this.frame % MetaArena.RING);
		long vis = this.arena.vis[slot];
		int colsDrawn = 0;
		java.util.Arrays.fill(this.drawCount, 0);
		for (Col c : this.draw) {
			int w = 32 << c.level;
			int bx = c.cx * w, bz = c.cz * w;
			float rx = (float) (bx - camX), rz = (float) (bz - camZ);
			if (!this.frustum.testAab(rx, (float) (c.minYb - camY), rz, rx + w, (float) (c.maxYb - camY), rz + w)) continue;
			// Adjacent water faces and dissolving columns also need the mask.
			int fade = this.fadeCode(c, nowMs);
			boolean cut = MetaSettings.hideUnderReal && this.anyMasked(bx - 1, bz - 1, w + 2) || fade != 0;
			int landGroup = cut ? 0 : MetaArena.LAND_BUCKETS;
			int waterGroup = cut ? WATER_DRAW : WATER_DRAW + 1;
			colsDrawn++;
			for (int f = 0; f < 6; f++) {
				boolean facing = switch (f) {
					case 0 -> camX > bx;
					case 1 -> camX < bx + w;
					case 2 -> camY > c.minYb;
					case 3 -> camY < c.maxYb;
					case 4 -> camZ > bz;
					default -> camZ < bz + w;
				};
				if (!facing) continue;
				for (int i = c.faceFirst[f]; i < c.faceWater[f]; i++) {
					int page = c.pages[i];
					this.addPage(landGroup + this.arena.pageBucket[page], page, fade);
				}
				// Water's depth/thickness reads from earlier water fragments. Keep its original draw order.
				for (int i = c.faceWater[f]; i < c.faceFirst[f + 1]; i++) this.addPage(waterGroup, c.pages[i], fade);
			}
		}
		if (MetaLods.probe == 3) java.util.Arrays.fill(this.drawCount, 0);
		int n = 0, quads = 0;
		for (int group = 0; group < DRAW_GROUPS; group++) {
			int count = this.drawCount[group];
			this.drawOffset[group] = n;
			if (count == 0) continue;
			MemoryUtil.memIntBuffer(vis + 4L * n, count).put(this.pageLists[group], 0, count);
			n += count;
			quads += count * quadCapacity(group);
		}
		this.lastDrawPages = n;
		this.lastDrawCols = colsDrawn;
		this.lastQuads = quads;
		// frame constants (MLFrame)
		long fr = this.arena.frame;
		clip.getToAddress(fr);
		int bx = (int) Math.floor(camX), by = (int) Math.floor(camY), bz = (int) Math.floor(camZ);
		MemoryUtil.memPutInt(fr + 64, bx);
		MemoryUtil.memPutInt(fr + 68, by);
		MemoryUtil.memPutInt(fr + 72, bz);
		MemoryUtil.memPutInt(fr + 76, (int) EDGE_BAND);
		MemoryUtil.memPutFloat(fr + 80, (float) (camX - bx));
		MemoryUtil.memPutFloat(fr + 84, (float) (camY - by));
		MemoryUtil.memPutFloat(fr + 88, (float) (camZ - bz));
		MemoryUtil.memPutFloat(fr + 92, this.transitionEnd);
		MemoryUtil.memSet(fr + 96, 0, 16);  // (the TAA's offset: filled in natively from ImFrame)
		MemoryUtil.memPutInt(fr + 112, this.maskX);
		MemoryUtil.memPutInt(fr + 116, this.maskZ);
		MemoryUtil.memPutInt(fr + 120, this.maskSize);
		MemoryUtil.memPutInt(fr + 124, this.maskWords);
		MemoryUtil.memPutFloat(fr + 128, MetaSettings.hideUnderReal ? 1f : 0f);
		MemoryUtil.memPutFloat(fr + 132, this.reach);
		int probe = MetaLods.probe;
		MemoryUtil.memPutFloat(fr + 136, probe == 1 || probe == 2 ? 10f + probe : probe == 4 || probe == 5 ? 9f + probe
			: MetaSettings.coverageDebug != 0 ? 1f + MetaSettings.coverageDebug : MetaSettings.showLevels ? 1f : 0f);
		MemoryUtil.memPutFloat(fr + 140, seaLevel());  // (ml_vs: water tops in a voxel holding the sea's surface go to it)
		// (fix7: the far shadows' ImHeightP at frame + 160: the camera's block and on, its fraction and the world's bottom, the
		// distance they start at; handed to the deferred lighting by MetaLods.drawInG)
		long hp = fr + 160;
		MemoryUtil.memPutInt(hp, bx);
		MemoryUtil.memPutInt(hp + 4, by);
		MemoryUtil.memPutInt(hp + 8, bz);
		MemoryUtil.memPutInt(hp + 12, MetaSettings.farShadows ? 1 : 0);
		MemoryUtil.memPutFloat(hp + 16, (float) (camX - bx));
		MemoryUtil.memPutFloat(hp + 20, (float) (camY - by));
		MemoryUtil.memPutFloat(hp + 24, (float) (camZ - bz));
		MemoryUtil.memPutFloat(hp + 28, (float) this.minY);
		MemoryUtil.memPutFloat(hp + 32, Math.max(96f, imaginemetal.Settings.shadowRange * 0.7f));  // (inside that the shadow maps cover it)
		// (fix9: the highest land in the height map, world y: the far shadows' march stops once its ray is above it)
		MemoryUtil.memPutFloat(hp + 36, this.arena.maxTop > 0 ? (float) (this.minY + this.arena.maxTop) : 0f);
		MemoryUtil.memPutFloat(hp + 40, probe == 4 ? 1f : 0f);  // (s1119: the Benchmark probe "LOD pixels lit cheaply")
		MemoryUtil.memPutFloat(hp + 44, 0f);
		for (int i = 0; i < this.maskSize * this.maskWords; i++) MemoryUtil.memPutInt(this.arena.mask + 4L * i, this.maskBits[i]);
		this.housekeeping();
		return n;
	}

	// ---- (fix18) crossfades: a column dissolves in over FADE_MS where it first shows (four finer ones replacing a coarser one,
	// new land), and the one it replaces dissolves out over the same time with the complementary pixels (metalods.metal: the
	// visible list's bits 17-24, the same screen noise as the edge dither), so nothing pops. -Dmetalods.fades=false: off.
	static final boolean FADES = !"false".equals(System.getProperty("metalods.fades"));
	static final long FADE_MS = Long.getLong("metalods.fadeMs", 500L);
	/** Blocks a second the edge's dither band slides in by. */
	static final float TRANSITION_SPEED = 110f;
	private float transitionShown;
	private long transitionNs;
	/** Columns drawn (not fading) last frame, and the ones dissolving out. */
	private final ArrayList<Col> drawnBefore = new ArrayList<>(), fadingOut = new ArrayList<>();

	private void crossfade(long now) {
		if (!FADES) return;
		for (Col c : this.draw) {
			if (c.drawnFrame != this.frame - 1) {
				// newly shown; back while dissolving out: from as far as it had gone
				long left = c.hiddenAt != 0L ? Math.max(0L, FADE_MS - (now - c.hiddenAt)) : 0L;
				c.shownAt = now - left;
			}
			c.drawnFrame = this.frame;
			c.hiddenAt = 0L;
		}
		for (Col c : this.drawnBefore) {
			if (c.drawnFrame == this.frame || c.hiddenAt != 0L) continue;
			long shown = Math.min(FADE_MS, now - c.shownAt);  // (half dissolved in: out from there)
			c.hiddenAt = now - (FADE_MS - shown);
			this.fadingOut.add(c);
		}
		this.drawnBefore.clear();
		this.drawnBefore.addAll(this.draw);
		Iterator<Col> it = this.fadingOut.iterator();
		while (it.hasNext()) {
			Col c = it.next();
			if (c.hiddenAt == 0L || now - c.hiddenAt >= FADE_MS || !c.ready() || c.pages.length == 0 || this.cols.get(c.key) != c) {
				c.hiddenAt = c.drawnFrame == this.frame ? c.hiddenAt : 0L;
				it.remove();
				continue;
			}
			c.wanted = this.frame;  // (not given back while it still shows)
			this.draw.add(c);
		}
	}

	/** The visible-list bits for c this frame: 0 none; bit 23 fading, 24 out, 17-22 how far (0-63). */
	private int fadeCode(Col c, long now) {
		if (!FADES) return 0;
		if (c.hiddenAt != 0L && c.drawnFrame != this.frame) {
			int q = (int) Math.clamp((now - c.hiddenAt) * 63L / FADE_MS, 0L, 63L);
			return (1 << 23) | (1 << 24) | q << 17;
		}
		long t = now - c.shownAt;
		if (t >= FADE_MS) return 0;
		int q = (int) Math.clamp(t * 63L / FADE_MS, 0L, 63L);
		return (1 << 23) | q << 17;
	}

	static int quadCapacity(int group) {
		return group >= WATER_DRAW ? MetaArena.QUADS_PER_PAGE : 16 << (group % MetaArena.LAND_BUCKETS);
	}

	int slot() {
		return (int) (this.frame % MetaArena.RING);
	}

	private void visit(int level, int cx, int cz) {
		int w = 32 << level;
		int x0 = cx * w, z0 = cz * w;
        double d = this.distanceToColumn(x0, z0, w);
		if (d > this.reach) return;
		if (MetaSettings.hideUnderReal && MetaSettings.coverageDebug != 2 && w <= 256 && this.allMasked(x0, z0, w)) return;
		Col c = this.col(level, cx, cz);
		c.wanted = this.frame;
		// (fix17: past the full-detail ring the detail falls off faster (far detail, levels 2-4); level 5 never drawn, as before)
		// (fix20: far detail on its own, no longer capped by detail: detail sets only the full-detail ring (level 0 to
		// detail x 64 blocks), far detail everything past it; Jack 13:55 "we don't need full detail a kilometer away")
		int kd = level <= 1 || level >= TOP ? this.detail : this.farDetail;
		boolean refine = level > 0 && d < kd * w;
		if (!refine) {
			this.count(level, c.ready());
			if (c.state == 0 || c.state == 2 && c.dirty) this.request(c, this.priority(x0, z0, w, d));
			if (c.ready()) this.select(c);
			return;
		}
		// the four children: drawn instead once they are all there
		int cl = level - 1, cw = w / 2;
		boolean all = true;
		for (int k = 0; k < 4; k++) {
			int kx = 2 * cx + (k & 1), kz = 2 * cz + (k >> 1);
			int ax = kx * cw, az = kz * cw;
			double ex = Math.max(0, Math.max(ax - this.camX, this.camX - (ax + cw)));
			double ez = Math.max(0, Math.max(az - this.camZ, this.camZ - (az + cw)));
			if (Math.sqrt(ex * ex + ez * ez) > this.reach) continue;
			if (MetaSettings.hideUnderReal && MetaSettings.coverageDebug != 2 && cw <= 256 && this.allMasked(ax, az, cw)) continue;
			Col child = this.cols.get(key(cl, kx, kz));
			if (child == null || !child.ready()) all = false;
		}
		if (all || !c.ready()) {
			for (int k = 0; k < 4; k++) this.visit(cl, 2 * cx + (k & 1), 2 * cz + (k >> 1));
			// (a coarser level only as a stand-in while its children are made: off by default (Jack 2026-11-13, DH + SeedGen
			// "doesn't load in huge blocks"): land appears at its own level, nearest first, and no tiles are made only to be
			// replaced. A column made earlier, at a time it was the right level, still stands in (see below))
			if (COARSE_FIRST && !all && c.state == 0) this.request(c, this.priority(x0, z0, w, d));
			return;
		}
		// not all children yet: this column stays, the children are asked for
		if (c.state == 2 && c.dirty) this.request(c, this.priority(x0, z0, w, d));
		this.select(c);
		double childViewWeight = 0;
		for (int k = 0; k < 4; k++) {
			int kx = 2 * cx + (k & 1), kz = 2 * cz + (k >> 1);
			int ax = kx * cw, az = kz * cw;
			double ex = Math.max(0, Math.max(ax - this.camX, this.camX - (ax + cw)));
			double ez = Math.max(0, Math.max(az - this.camZ, this.camZ - (az + cw)));
			double cd = Math.sqrt(ex * ex + ez * ez);
			if (cd > this.reach || MetaSettings.hideUnderReal && MetaSettings.coverageDebug != 2 && cw <= 256 && this.allMasked(ax, az, cw)) continue;
			Col child = this.col(cl, kx, kz);
			this.count(cl, child.ready());
			if (child.state == 0) {
				if (childViewWeight == 0) childViewWeight = this.inView(x0, z0, w) ? 1.0 : 3.0;
				this.request(child, (cd + 16.0) * childViewWeight);
			} else if (child.state == 1) {
				child.wanted = this.frame;
			}
		}
	}

	private void select(Col c) {
		this.draw.add(c);
		if (c.pages.length > 0) c.selectedFrame = this.frame;
	}

	private boolean inView(int x, int z, int width) {
		float bottom = (float) (this.minY - this.camY);
		return this.frustum.testAab((float) (x - this.camX), bottom, (float) (z - this.camZ),
			(float) (x + width - this.camX), bottom + this.height, (float) (z + width - this.camZ));
	}

	private double priority(int x, int z, int width, double distance) {
		boolean inBand = this.transitionCandidate > 0 && width <= 128 && distance <= this.transitionLimit
			&& this.intersectsTransition(x, z, width);
		return (distance + 16.0) * (inBand ? 0.25 : this.inView(x, z, width) ? 1.0 : 3.0);
	}

    /**
     * Warm one extra band of columns per level. Only schedule it once the live view is nearly filled and
     * keep the extra work and meshes bounded by the normal queue and arena pressure.
     */
    private void prefetch(long now) {
        if (now < this.nextPrefetch || this.server == null || !MetaSettings.generate
            || this.colsReady < this.colsAll * 0.95 || this.jobs.size() > 8
            || this.ingest.size() > 16 || this.arena.freePages() < this.arena.pages / 3) return;
        this.nextPrefetch = now + 250L;
        for (int level = 0; level <= TOP; level++) {
            int w = 32 << level;
            int group = level == TOP ? w : 2 * w;
            int detail = level <= 1 || level + 1 == TOP ? this.detail : this.farDetail;
            double inner = level == TOP ? this.reach : Math.min(this.reach, detail * group);
            double outer = inner + Math.min(128, group);
            int x0 = (int) Math.floor((this.camX - outer) / group);
            int z0 = (int) Math.floor((this.camZ - outer) / group);
            int x1 = (int) Math.floor((this.camX + outer) / group);
            int z1 = (int) Math.floor((this.camZ + outer) / group);
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    double distance = this.distanceToColumn(x * group, z * group, group);
                    if (distance < inner || distance >= outer) continue;
                    int children = level == TOP ? 1 : 4;
                    for (int q = 0; q < children; q++) {
                        int cx = level == TOP ? x : 2 * x + (q & 1);
                        int cz = level == TOP ? z : 2 * z + (q >> 1);
                        if (w <= 256 && MetaSettings.hideUnderReal && MetaSettings.coverageDebug != 2 && this.allMasked(cx * w, cz * w, w)) continue;
                        Col c = this.cols.get(key(level, cx, cz));
                        if (c != null) c.wanted = this.frame;
                        if (this.jobs.size() < 12 && (c == null || c.state == 0)) {
                            this.request(c == null ? this.col(level, cx, cz) : c, this.reach * 4 + distance);
                        }
                    }
                }
            }
        }
    }

    private double distanceToColumn(int x, int z, int width) {
        double dx = Math.max(0, Math.max(x - this.camX, this.camX - (x + width)));
        double dz = Math.max(0, Math.max(z - this.camZ, this.camZ - (z + width)));
        return Math.sqrt(dx * dx + dz * dz);
    }

	/** A column the view wants at this level: its area in chunks into the progress. */
	private void count(int level, boolean ready) {
		long a = 4L << (2 * level);  // ((32 << level) / 16)^2
		this.areaAll += a;
		if (ready) this.areaReady += a;
		// (fix17: the HUD's percentage by columns, ~ the work: by area it stayed at 0-3 % until the near rings were all made)
		this.colsAll++;
		if (ready) this.colsReady++;
	}

	/**
	 * The on-screen progress line while the view isn't made yet, else null (also for 2 s after it is done, then gone until a
	 * tenth of the view or more is missing again). The speed: chunks' worth of area made ready per second over the last ~3 s.
	 */
	@Nullable String progressLine() {
		if (this.genFailed) return "MetaLODS: seed generation stopped (see log); showing cached/visited terrain";
		long now = System.currentTimeMillis();
		if (now >= this.progNext) {
			this.progNext = now + 500;
			int i = this.progN++ % this.progT.length;
			this.progT[i] = now;
			this.progA[i] = this.areaReady;
			int oldest = this.progN <= this.progT.length ? 0 : this.progN % this.progT.length;
			long dt = now - this.progT[oldest];
			if (dt > 0) this.progRate = Math.max(0.0, (this.areaReady - this.progA[oldest]) * 1000.0 / dt);
		}
		long left = Math.max(0, this.areaAll - this.areaReady);
        int remainingDetail=this.details.size()+this.details.activeCount();
        if(left==0 && this.detailFailed)return "MetaLODS: surfaces ready; detail refinement stopped (see log)";
        if(left==0 && remainingDetail>0)return "MetaLODS: surfaces ready; "+remainingDetail+" detail tiles pending";
		if (this.progDone) {
			// (once the view was made: back only for a real backlog, a tenth of the view or more missing for over a second, as
			// after a teleport or joining; not for the bits that come in at the edge while walking or flying (Jack's 16.43.33
			// "keep getting metal making chunks notification")
			if (left * 10 < this.areaAll) {
				this.progBigSince = 0;
				return null;
			}
			if (this.progBigSince == 0) this.progBigSince = now;
			if (now - this.progBigSince < 1000) return null;
			this.progDone = false;
			this.progDoneSince = 0;
		}
		if (left == 0) {
			if (this.progDoneSince == 0) this.progDoneSince = now;
			if (now - this.progDoneSince > 2000) {
				this.progDone = true;
				this.progBigSince = 0;
				return null;
			}
		} else this.progDoneSince = 0;
		return String.format(java.util.Locale.ROOT,"MetaLODS: current view %.0f%% ready; %,d mesh jobs, %,d detail jobs; %,d seed tiles this session",
            this.colsAll>0?100.0*this.colsReady/this.colsAll:100.0,this.jobs.size(),remainingDetail,this.generatedTiles.get());
	}

	private void housekeeping() {
		long now = System.currentTimeMillis();
		if (now >= this.nextEvict) {
			// Under pressure (an eighth or less free, or an upload just failed) columns out of the view for ~1 s give their pages
			// back, and so do parents whose four children are all meshed (they aren't drawn: the children are; asked for again
			// when the view next wants the parent). Otherwise columns not wanted for ~10 s.
			boolean pressure = this.arenaFull || this.arena.freePages() < this.arena.pages / 8;
			this.nextEvict = now + (pressure ? 250L : 2000L);
			long age = pressure ? 60 : 600;
			Iterator<Col> it = this.cols.values().iterator();
			while (it.hasNext()) {
				Col c = it.next();
				if (this.frame - c.wanted > age && c.state != 1) {
					this.arena.release(c.pages, this.frame);
					c.pages = new int[0];
					it.remove();
				}
			}
			if (pressure) {
				for (Col c : this.cols.values()) {
					if (c.level == 0 || c.state != 2 || c.dirty || c.pages.length == 0) continue;
					boolean all = true;
					for (int k = 0; k < 4 && all; k++) {
						Col child = this.cols.get(key(c.level - 1, 2 * c.cx + (k & 1), 2 * c.cz + (k >> 1)));
						all = child != null && child.ready();
					}
					if (!all) continue;
					this.arena.release(c.pages, this.frame);
					c.pages = new int[0];
					c.pagesValid = false;
					c.state = 0;
				}
			}
			if (this.arenaFull && this.arena.freePages() > this.arena.pages / 6) this.arenaFull = false;
		}
		if (now >= this.nextFlush) {
			this.nextFlush = now + 5000L;
			this.store.flush();
		}
		if (MetaSettings.stats && now >= this.nextStats) {
			this.nextStats = now + 10000L;
			int ready = 0, asked = 0;
			for (Col c : this.cols.values()) {
				if (c.state == 2) ready++;
				else if (c.state == 1) asked++;
			}
            System.out.println(String.format(java.util.Locale.ROOT, "[MetaLODS] disk cache: %d columns reused, %d misses, %d read/write errors; meshes rebuild from cached voxels on reopening",
                this.store.cacheHits.get(), this.store.cacheMisses.get(), this.store.cacheErrors.get()));
            if(this.streams!=null)System.out.println("[MetaLODS] "+this.streams.stats());
            if(this.diag!=null)System.out.println(this.diag.line(this.workers.size()));
            if(this.streams!=null && this.planDump==0) {
                this.planDump=1;
                int px=(int)Math.floor(this.camX)-192,pz=(int)Math.floor(this.camZ)-192;
                Path out=this.diagDir.resolve("streams-plan-"+px+"_"+pz+".csv");
                var st=this.streams;
                Thread t=new Thread(()->this.planDump=st.dump(px,pz,384,out)?2:0,"MetaLODS diag plan dump");
                t.setDaemon(true);t.start();
            }
            System.out.println("[MetaLODS] " + this.mipColumns.get() + " columns built from finer seed data; "
                + this.detailedTiles.get() + " old previews completed; " + this.details.size() + " queued");
			long tiles = this.generatedTiles.get(), meshes = this.meshed.get();
			System.out.println(String.format(java.util.Locale.ROOT,
				"[MetaLODS] stats: %d columns meshed, %d asked, %d jobs queued, %d drawn (%d pages, <= %d quads), arena %d of %d pages free; "
					+ "tiles generated %d (%.1f ms each), meshes %d (%.1f ms each), real chunks %d; allocated per job %.2f MB (mesh %.2f), per real chunk %.2f MB; "
					+ "chunks missing in the render distance %.1f on average, workers held back %.0f%% of the time; "
					+ "edge blend on %.0f%% of frames (off: %.0f%% not possible, %.0f%% a LOD missing in the band)",
				ready, asked, this.jobs.size(), this.lastDrawCols, this.lastDrawPages, this.lastQuads, this.arena.freePages(), this.arena.pages, tiles,
				tiles > 0 ? this.genNanos.get() / 1e6 / tiles : 0.0, meshes, meshes > 0 ? this.meshNanos.get() / 1e6 / meshes : 0.0, this.ingested.get(),
				meshes > 0 ? this.jobAlloc.get() / 1e6 / meshes : 0.0, meshes > 0 ? this.meshAlloc.get() / 1e6 / meshes : 0.0,
				this.ingestBuilt.get() > 0 ? this.ingestAlloc.get() / 1e6 / this.ingestBuilt.get() : 0.0,
				this.missingSamples > 0 ? (double) this.missingSum / this.missingSamples : 0.0, this.missingSamples > 0 ? 100.0 * this.pressureSamples / this.missingSamples : 0.0,
				this.bandFrames > 0 ? 100.0 * (this.bandFrames - this.bandNoCandidate - this.bandUncovered) / this.bandFrames : 0.0,
				this.bandFrames > 0 ? 100.0 * this.bandNoCandidate / this.bandFrames : 0.0, this.bandFrames > 0 ? 100.0 * this.bandUncovered / this.bandFrames : 0.0));
			System.out.println("[MetaLODS] drawn quads: " + this.quadTally());
			this.bandFrames = this.bandNoCandidate = this.bandUncovered = 0;
			this.missingSum = this.missingSamples = this.pressureSamples = 0;
			if (tiles > this.statsTiles) {
				// (only while tiles are being made) where the time went and the rate: tiles per level x chunks per tile, over the time
				// from the first tile's start to the last one's end
				this.statsTiles = tiles;
				double span = Math.max(1e-3, (this.genLastNs - this.genFirstNs) / 1e9);
				System.out.println(String.format(java.util.Locale.ROOT, "[MetaLODS] generation: %d tiles in %.1f s with %d workers = %.1f tiles/s, %.0f chunk-equivalents/s (%d area, levels summed); CPU %.0f%% of the tiles' wall time; %s; %s",
					tiles, span, this.workers.size(), tiles / span, this.generatedChunks.get() / span, this.generatedChunks.get(), 100.0 * this.genCpuNanos.get() / Math.max(1, this.genNanos.get()),
					this.gen!=null?this.gen.timings():"", this.gen != null ? this.gen.stages() : ""));
			}
		}
	}

	/** Everything meshed made again (a setting that changes what the columns hold). */
	void remeshAll() {
		for (Col c : this.cols.values()) c.dirty = true;
	}

	void close() {
		synchronized (this.publicationLock) { this.closed = true; this.cacheEpoch++; }
		this.latestIngest.close();
        if (this.diag != null) this.diag.close();
        if (this.streams != null) this.streams.close();
		this.ingest.clear();
		// Do not interrupt: lazy texture/structure reads can share a ZipFS FileChannel
		// with Minecraft. Interrupting one reader can close it for the entire process.
		// Idle workers wake within 100ms; busy workers finish their current operation.
		this.jobs.clear();
        this.details.close();this.refined.clear();
		this.results.clear();
		for (Col c : this.cols.values()) this.arena.release(c.pages, this.frame);
		this.cols.clear();
		this.store.close();
	}

	/** Deletes what's stored and starts over. */
	void clearAll() {
        synchronized(this.publicationLock) {
            this.cacheEpoch++;
            this.details.clear();this.refined.clear();this.detailAllowed=false;this.detailFailed=false;
        }
		synchronized (this.genCache) {
			this.genCache.clear();
		}
		this.latestIngest.clear(this.store::clear);
		this.remeshAll();
	}

	static boolean exists(Path p) {
		return Files.exists(p);
	}
}
