package imaginemetal.metalods;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
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
	private final MetaArena arena;
	private final @Nullable ServerLevel server;
	private volatile @Nullable MetaGen gen;
	private volatile boolean genTried, genFailed;
	private final Object genLock = new Object();
	private volatile int saidLevel;

	// ---- render thread only ----
	private final HashMap<Long, Col> cols = new HashMap<>();
	private long frame;
	private int lastDrawPages, lastDrawCols, lastQuads;
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
	private final PriorityBlockingQueue<Job> jobs = new PriorityBlockingQueue<>();
	private final LinkedBlockingQueue<Runnable> ingest = new LinkedBlockingQueue<>();
	private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
	private final List<Thread> workers = new ArrayList<>();
	private volatile boolean closed;
	private final AtomicLong seq = new AtomicLong();
	static volatile long frameNow;
	private final Map<Long, Rle> genCache = new LinkedHashMap<>(256, 0.75f, true);
	private final ConcurrentHashMap<Long, Object> tileLocks = new ConcurrentHashMap<>();
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
		int minYb, maxYb;
		/** 0 nothing, 1 asked, 2 meshed */
		int state;
		boolean dirty;
		int version;
		int edges;
		volatile long wanted;

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

	MetaWorld(ClientLevel level, Path dir, MetaArena arena, @Nullable ServerLevel server) {
		this.level = level;
		this.minY = level.getMinY();
		this.height = Math.clamp(level.getHeight() / 32 * 32, 32, 512);
		this.skyLight = level.dimensionType().hasSkyLight();
		this.arena = arena;
		this.server = server;
		this.overhangs = MetaSettings.overhangs;
		this.store = new MetaStore(dir, level.registryAccess(), this.overhangs);
		int n = MetaSettings.workerThreads();
		for (int i = 0; i < n; i++) {
			Thread t = new Thread(this::work, "MetaLODS worker " + i);
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY + 1);
			t.start();
			this.workers.add(t);
		}
		System.out.println("[MetaLODS] " + level.dimension().identifier() + ": y " + this.minY + " to " + (this.minY + this.height) + ", " + n + " workers, "
			+ (server != null ? "generation from the seed available" : "no generation (not singleplayer)") + ", stored in " + dir);
	}

	static long key(int level, int cx, int cz) {
		return (long) level << 58 | (long) (cx & 0x1FFFFFFF) << 29 | (cz & 0x1FFFFFFF);
	}

	int airSky() {
		return this.skyLight ? 15 : 0;
	}

	// ======================================================= workers

	private void work() {
		// (Apple silicon: the utility QoS class, so the game's own threads always come first; background would keep the
		// workers on the efficiency cores only, too slow to fill the view)
		mcopt.metal.lod.LodGenQos.once(0x11, "MetaLODS worker");
		MetaMesher mesher = new MetaMesher();
		int turn = 0;
		while (!this.closed) {
			try {
				// Real chunks and columns in turn (real chunks first once many wait: each holds a copy of its chunk). Real chunks
				// alone going first starved the generation: joining a world queues ~1000 of them, a flight a steady stream.
				Runnable r = (turn++ & 1) == 0 || this.ingest.size() > 192 ? this.ingest.poll() : null;
				if (r != null) {
					r.run();
					continue;
				}
				Job j = this.jobs.poll(this.ingest.isEmpty() ? 100 : 0, TimeUnit.MILLISECONDS);
				if (j == null) continue;
				Col c = j.col;
				if (frameNow - c.wanted > 240) {
					this.results.add(new Result(c, j.version, null, 0, null, Long.MIN_VALUE));
					continue;
				}
				try {
					this.job(c, j, mesher);
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

	private void job(Col c, Job j, MetaMesher mesher) {
		List<Long> generated = new ArrayList<>(4);
		Rle center = this.derive(c.level, c.cx, c.cz, true, generated);
		int[][] edges = new int[4][];
		int known = 0;
		int[][] nb = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
		for (int s = 0; s < 4; s++) {
			edges[s] = this.edge(c.level, c.cx + nb[s][0], c.cz + nb[s][1], s, center.h);
			if (edges[s] != null) known |= 1 << s;
		}
		long t1 = System.nanoTime();
		MetaMesher.Mesh m = mesher.mesh(center, edges);
		this.arena.heights(c.cx * (32 << c.level), c.cz * (32 << c.level), c.level, mesher.tops(), this.minY);  // (fix7: far shadows)
		this.meshNanos.addAndGet(System.nanoTime() - t1);
		this.meshed.incrementAndGet();
		long[] gk = generated.isEmpty() ? null : generated.stream().mapToLong(Long::longValue).toArray();
		this.results.add(new Result(c, j.version, m, known, gk, Long.MIN_VALUE));
	}

	private @Nullable MetaGen generator() {
		if (this.gen != null || this.genFailed || this.server == null) return this.gen;
		synchronized (this.genLock) {
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
			Rle r = this.genCache.get(k);
			if (r != null) return r;
		}
		Rle r = this.store.loadGen(level, cx, cz);
		if (r != null && r.n == 32 && r.h == Math.max(1, this.height >> level)) {
			this.cache(k, r);
			return r;
		}
		return null;
	}

	private void cache(long k, Rle r) {
		synchronized (this.genCache) {
			this.genCache.put(k, r);
			if (this.genCache.size() > 1024) {
				Iterator<Long> it = this.genCache.keySet().iterator();
				while (this.genCache.size() > 900 && it.hasNext()) {
					it.next();
					it.remove();
				}
			}
		}
	}

	/** The seed's column, from memory, disk, or generated now (with its tile's three others) if allowed. */
	private @Nullable Rle generated(int level, int cx, int cz, boolean generate, List<Long> made) {
		Rle r = this.cachedGen(level, cx, cz);
		if (r != null || !generate || !MetaSettings.generate) return r;
		MetaGen g = this.generator();
		if (g == null) return null;
		int tx = Math.floorDiv(cx, 2), tz = Math.floorDiv(cz, 2);
		Object lock = this.tileLocks.computeIfAbsent(key(level, tx, tz), k -> new Object());
		synchronized (lock) {
			r = this.cachedGen(level, cx, cz);
			if (r != null) return r;
			long t0 = System.nanoTime();
			Rle[] four;
			try {
				four = g.generate(level, tx, tz, this.minY, this.height, MetaSettings.exactTrees, this.airSky(), this.overhangs, new MetaGen.Ids() {
					@Override
					public int block(BlockState state) {
						return MetaPalette.id(state);
					}

					@Override
					public int biome(Holder<Biome> biome) {
						return MetaPalette.biome(biome);
					}
				});
			} catch (Throwable t) {
				if (!this.genFailed) {
					this.genFailed = true;
					this.gen = null;
					System.out.println("[MetaLODS] generation from the seed stopped (" + t + "): only real chunks become LODs");
					t.printStackTrace(System.out);
				}
				return null;
			} finally {
				this.tileLocks.remove(key(level, tx, tz));
			}
			long t1 = System.nanoTime(), ns = t1 - t0;
			this.genNanos.addAndGet(ns);
			if (this.genFirstNs == 0) this.genFirstNs = t0;
			this.genLastNs = t1;
			this.generatedChunks.addAndGet(16L << (2 * level));
			if (this.generatedTiles.incrementAndGet() <= 3 || (this.saidLevel & (1 << level)) == 0) {
				this.saidLevel |= 1 << level;
				System.out.println(String.format(java.util.Locale.ROOT, "[MetaLODS] generated a level-%d tile at %d, %d in %.1f ms", level, tx, tz, ns / 1e6));
			}
			for (int q = 0; q < 4; q++) {
				int qx = 2 * tx + (q & 1), qz = 2 * tz + (q >> 1);
				long k = key(level, qx, qz);
				this.cache(k, four[q]);
				this.store.saveGen(level, qx, qz, four[q]);
				made.add(k);
				if (qx == cx && qz == cz) r = four[q];
			}
			return r;
		}
	}

	/** Chunks per column side at this level, and cells per chunk side. */
	private static int chunksPer(int level) {
		return 2 << level;
	}

	/** The column as drawn: the seed's (or air) with every real chunk seen there over it (levels 0 to 4). */
	Rle derive(int level, int cx, int cz, boolean generate, List<Long> made) {
		int h = Math.max(1, this.height >> level);
		Rle base = this.generated(level, cx, cz, generate, made);
		if (base == null) base = Rle.empty(32, h);
		if (level > 4) return base;
		int cpc = 16 >> level, cps = chunksPer(level);
		int c0x = cx * cps, c0z = cz * cps;
		MetaStore.RealChunk[] real = null;
		for (int j = 0; j < cps; j++) {
			for (int i = 0; i < cps; i++) {
				MetaStore.RealChunk rc = this.store.hasRegion((c0x + i) >> 5, (c0z + j) >> 5) ? this.store.real(c0x + i, c0z + j) : null;
				if (rc == null) continue;
				if (real == null) real = new MetaStore.RealChunk[cps * cps];
				real[i + j * cps] = rc;
			}
		}
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
		this.ingest.add(() -> {
			try {
				MetaStore.RealChunk rc = MetaChunks.build(s, this.minY, this.height, this.skyLight);
				if (rc == null) return;
				this.store.putReal(s.chunkX(), s.chunkZ(), rc);
				this.ingested.incrementAndGet();
				this.results.add(new Result(null, 0, null, 0, null, MetaStore.key(s.chunkX(), s.chunkZ())));
			} catch (Throwable t) {
				System.out.println("[MetaLODS] chunk " + s.chunkX() + ", " + s.chunkZ() + ": " + t);
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
		c.pagesValid = c.state == 2;
		c.state = 1;
		c.dirty = false;
		c.version++;
		this.jobs.add(new Job(c, c.version, prio, this.seq.incrementAndGet()));
	}

	/** Applies what the workers finished, for at most `budgetNs`. */
	private void integrate(long budgetNs) {
		long end = System.nanoTime() + budgetNs;
		Result r;
		while (System.nanoTime() < end && (r = this.results.poll()) != null) {
			if (r.realChunk != Long.MIN_VALUE) {
				int chx = (int) (r.realChunk >> 32), chz = (int) r.realChunk;
				for (int l = 0; l <= 4; l++) {
					int cps = chunksPer(l);
					Col c = this.cols.get(key(l, Math.floorDiv(chx, cps), Math.floorDiv(chz, cps)));
					if (c != null) c.dirty = true;
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
		for (int f = 0; f < 6; f++) need += (m.counts[f] + MetaArena.QUADS_PER_PAGE - 1) / MetaArena.QUADS_PER_PAGE;
		if (need > this.arena.freePages()) {
			if (!this.arenaFull) {
				this.arenaFull = true;
				System.out.println("[MetaLODS] the GPU arena is full (" + this.arena.pages + " pages): lower the distance or detail");
			}
			c.state = c.pagesValid ? 2 : 0;
			return;
		}
		int[] pages = new int[need];
		int n = 0;
		int ox = c.cx * (32 << c.level), oz = c.cz * (32 << c.level);
		for (int f = 0; f < 6; f++) {
			c.faceFirst[f] = n;
			for (int q = 0; q < m.counts[f]; q += MetaArena.QUADS_PER_PAGE) {
				int p = this.arena.alloc();
				this.arena.fill(p, m.quads[f], m.ao[f], q, Math.min(MetaArena.QUADS_PER_PAGE, m.counts[f] - q), ox, this.minY, oz, c.level, f);
				pages[n++] = p;
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
					System.out.println("[MetaLODS] the drawn-chunk mask failed (" + e + "): loaded chunks instead");
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

	/** Pages at the start of this frame's list that need the mask (the rest don't). */
	int maskedPages;

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
		int c0x = x0 >> 4, c0z = z0 >> 4, n = Math.max(1, w >> 4);
		if (n > this.maskSize) return false;
		for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) if (!this.masked(c0x + i, c0z + j)) return false;
		return true;
	}

	private final List<Col> draw = new ArrayList<>();
	/** This frame's view in chunks' worth of area: all the columns it wants, and those of them with a mesh (the on-screen progress). */
	private long areaAll, areaReady;
	private final long[] progT = new long[8], progA = new long[8];
	private int progN;
	private long progDoneSince, progNext, progBigSince;
	private boolean progDone;
	private double progRate;
	private double camX, camY, camZ;
	private float reach;

	/** The current level's sea level (the y above its topmost sea water block), 0 if there's no level. */
	private static float seaLevel() {
		ClientLevel l = Minecraft.getInstance().level;
		return l == null ? 0f : (float) l.getSeaLevel();
	}
	private int detail;
	private @Nullable FrustumIntersection frustum;

	/**
	 * This frame: finished work in, the columns chosen, the pages to draw listed into the arena's ring slot. Returns the
	 * number of pages listed (and fills the frame constants).
	 */
	int prepare(Matrix4fc clip, double camX, double camY, double camZ, int renderDistance) {
		this.frame++;
		frameNow = this.frame;
		this.arena.reclaim(this.frame);
		this.integrate(3_000_000L);
		this.camX = camX;
		this.camY = camY;
		this.camZ = camZ;
		this.reach = MetaSettings.distance * 16f;
		this.detail = MetaSettings.detail;
		this.frustum = new FrustumIntersection(clip, false);
		this.updateMask(camX, camY, camZ, renderDistance);
		this.draw.clear();
		this.areaAll = 0;
		this.areaReady = 0;
		int wTop = 32 << TOP;
		int x0 = (int) Math.floor((camX - this.reach) / wTop), x1 = (int) Math.floor((camX + this.reach) / wTop);
		int z0 = (int) Math.floor((camZ - this.reach) / wTop), z1 = (int) Math.floor((camZ + this.reach) / wTop);
		for (int cz = z0; cz <= z1; cz++) for (int cx = x0; cx <= x1; cx++) this.visit(TOP, cx, cz);
		// the pages, face groups turned toward the camera only
		int slot = (int) (this.frame % MetaArena.RING);
		long vis = this.arena.vis[slot];
		int n = 0, quadsDrawn = 0, colsDrawn = 0;
		// (two passes: first the columns the real terrain's chunks cut into (drawn with the mask), then the rest (without: no
		// discard, so the GPU's hidden-surface removal stays fully on for them))
		for (int pass = 0; pass < 2; pass++) {
		if (pass == 1) this.maskedPages = n;
		for (Col c : this.draw) {
			int w = 32 << c.level;
			int bx = c.cx * w, bz = c.cz * w;
			// (fix8: a block wider, so the columns along the real terrain's edge are drawn with the mask too: their water's sides that
			// face into the real terrain's chunks are hidden there (ml_fs), Jack's 18.31.26-18.35.11 blue strips seen through the water)
			boolean cut = MetaSettings.hideUnderReal && this.anyMasked(bx - 1, bz - 1, w + 2);
			if (cut != (pass == 0)) continue;
			float rx = (float) (bx - camX), rz = (float) (bz - camZ);
			if (!this.frustum.testAab(rx, (float) (c.minYb - camY), rz, rx + w, (float) (c.maxYb - camY), rz + w)) continue;
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
				for (int i = c.faceFirst[f]; i < c.faceFirst[f + 1] && n < this.arena.pages; i++) {
					MemoryUtil.memPutInt(vis + 4L * n++, c.pages[i]);
					quadsDrawn += MetaArena.QUADS_PER_PAGE;
				}
			}
		}
		}
		this.lastDrawPages = n;
		this.lastDrawCols = colsDrawn;
		this.lastQuads = quadsDrawn;
		// frame constants (MLFrame)
		long fr = this.arena.frame;
		clip.getToAddress(fr);
		int bx = (int) Math.floor(camX), by = (int) Math.floor(camY), bz = (int) Math.floor(camZ);
		MemoryUtil.memPutInt(fr + 64, bx);
		MemoryUtil.memPutInt(fr + 68, by);
		MemoryUtil.memPutInt(fr + 72, bz);
		MemoryUtil.memPutInt(fr + 76, 0);
		MemoryUtil.memPutFloat(fr + 80, (float) (camX - bx));
		MemoryUtil.memPutFloat(fr + 84, (float) (camY - by));
		MemoryUtil.memPutFloat(fr + 88, (float) (camZ - bz));
		MemoryUtil.memPutFloat(fr + 92, 0f);
		MemoryUtil.memSet(fr + 96, 0, 16);  // (the TAA's offset: filled in natively from ImFrame)
		MemoryUtil.memPutInt(fr + 112, this.maskX);
		MemoryUtil.memPutInt(fr + 116, this.maskZ);
		MemoryUtil.memPutInt(fr + 120, this.maskSize);
		MemoryUtil.memPutInt(fr + 124, this.maskWords);
		MemoryUtil.memPutFloat(fr + 128, MetaSettings.hideUnderReal ? 1f : 0f);
		MemoryUtil.memPutFloat(fr + 132, this.reach);
		MemoryUtil.memPutFloat(fr + 136, MetaSettings.showLevels ? 1f : 0f);
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
		MemoryUtil.memPutFloat(hp + 40, 0f);
		MemoryUtil.memPutFloat(hp + 44, 0f);
		for (int i = 0; i < this.maskSize * this.maskWords; i++) MemoryUtil.memPutInt(this.arena.mask + 4L * i, this.maskBits[i]);
		this.housekeeping();
		return n;
	}

	int slot() {
		return (int) (this.frame % MetaArena.RING);
	}

	private void visit(int level, int cx, int cz) {
		int w = 32 << level;
		int x0 = cx * w, z0 = cz * w;
		double dx = Math.max(0, Math.max(x0 - this.camX, this.camX - (x0 + w)));
		double dz = Math.max(0, Math.max(z0 - this.camZ, this.camZ - (z0 + w)));
		double d = Math.sqrt(dx * dx + dz * dz);
		if (d > this.reach) return;
		if (w <= 256 && this.allMasked(x0, z0, w)) return;
		Col c = this.col(level, cx, cz);
		c.wanted = this.frame;
		float ry = (float) (this.minY - this.camY);
		boolean inView = this.frustum == null || this.frustum.testAab((float) (x0 - this.camX), ry, (float) (z0 - this.camZ), (float) (x0 + w - this.camX),
			ry + this.height, (float) (z0 + w - this.camZ));
		double prio = (d + 1.0) / w * (inView ? 1.0 : 4.0) + (level == 0 ? 0.5 : 0.0);
		boolean refine = level > 0 && d < this.detail * w;
		if (!refine) {
			this.count(level, c.ready());
			if (c.state != 2 || c.dirty) this.request(c, prio);
			if (c.ready()) this.draw.add(c);
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
			if (cw <= 256 && this.allMasked(ax, az, cw)) continue;
			Col child = this.cols.get(key(cl, kx, kz));
			if (child == null || !child.ready()) all = false;
		}
		if (all || !c.ready()) {
			for (int k = 0; k < 4; k++) this.visit(cl, 2 * cx + (k & 1), 2 * cz + (k >> 1));
			// (a coarser level only as a stand-in while its children are made: off by default (Jack 2026-11-13, DH + SeedGen
			// "doesn't load in huge blocks"): land appears at its own level, nearest first, and no tiles are made only to be
			// replaced. A column made earlier, at a time it was the right level, still stands in (see below))
			if (COARSE_FIRST && !all && !c.ready()) this.request(c, prio);
			return;
		}
		// not all children yet: this column stays, the children are asked for
		if (c.dirty) this.request(c, prio);
		this.draw.add(c);
		for (int k = 0; k < 4; k++) {
			int kx = 2 * cx + (k & 1), kz = 2 * cz + (k >> 1);
			int ax = kx * cw, az = kz * cw;
			double ex = Math.max(0, Math.max(ax - this.camX, this.camX - (ax + cw)));
			double ez = Math.max(0, Math.max(az - this.camZ, this.camZ - (az + cw)));
			double cd = Math.sqrt(ex * ex + ez * ez);
			if (cd > this.reach || cw <= 256 && this.allMasked(ax, az, cw)) continue;
			Col child = this.col(cl, kx, kz);
			this.count(cl, child.ready());
			if (child.state != 2) this.request(child, (cd + 1.0) / cw * (inView ? 1.0 : 4.0));
		}
	}

	/** A column the view wants at this level: its area in chunks into the progress. */
	private void count(int level, boolean ready) {
		long a = 4L << (2 * level);  // ((32 << level) / 16)^2
		this.areaAll += a;
		if (ready) this.areaReady += a;
	}

	/**
	 * The on-screen progress line while the view isn't made yet, else null (also for 2 s after it is done, then gone until a
	 * tenth of the view or more is missing again). The speed: chunks' worth of area made ready per second over the last ~3 s.
	 */
	@Nullable String progressLine() {
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
		return String.format(java.util.Locale.ROOT, "MetaLODS: %,d chunks ready, %,d left, %,.0f chunks/s", this.areaReady, left, this.progRate);
	}

	private void housekeeping() {
		long now = System.currentTimeMillis();
		if (now >= this.nextEvict) {
			this.nextEvict = now + 2000L;
			// columns not wanted for ~10 seconds give their pages back
			Iterator<Col> it = this.cols.values().iterator();
			while (it.hasNext()) {
				Col c = it.next();
				if (this.frame - c.wanted > 600 && c.state != 1) {
					this.arena.release(c.pages, this.frame);
					c.pages = new int[0];
					it.remove();
				}
			}
			if (this.arenaFull && this.arena.freePages() > this.arena.pages / 8) this.arenaFull = false;
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
			long tiles = this.generatedTiles.get(), meshes = this.meshed.get();
			System.out.println(String.format(java.util.Locale.ROOT,
				"[MetaLODS] stats: %d columns meshed, %d asked, %d jobs queued, %d drawn (%d pages, <= %d quads), arena %d of %d pages free; "
					+ "tiles generated %d (%.1f ms each), meshes %d (%.1f ms each), real chunks %d",
				ready, asked, this.jobs.size(), this.lastDrawCols, this.lastDrawPages, this.lastQuads, this.arena.freePages(), this.arena.pages, tiles,
				tiles > 0 ? this.genNanos.get() / 1e6 / tiles : 0.0, meshes, meshes > 0 ? this.meshNanos.get() / 1e6 / meshes : 0.0, this.ingested.get()));
			if (tiles > this.statsTiles) {
				// (only while tiles are being made) where the time went and the rate: tiles per level x chunks per tile, over the time
				// from the first tile's start to the last one's end
				this.statsTiles = tiles;
				double span = Math.max(1e-3, (this.genLastNs - this.genFirstNs) / 1e9);
				System.out.println(String.format(java.util.Locale.ROOT, "[MetaLODS] generation: %d tiles in %.1f s with %d workers = %.1f tiles/s, %.0f chunks/s (%d chunks' worth, levels summed); %s",
					tiles, span, this.workers.size(), tiles / span, this.generatedChunks.get() / span, this.generatedChunks.get(), mcopt.metal.lod.MetaGen.timings()));
			}
		}
	}

	/** Everything meshed made again (a setting that changes what the columns hold). */
	void remeshAll() {
		for (Col c : this.cols.values()) c.dirty = true;
	}

	void close() {
		this.closed = true;
		for (Thread t : this.workers) t.interrupt();
		for (Col c : this.cols.values()) this.arena.release(c.pages, this.frame);
		this.cols.clear();
		this.store.close();
	}

	/** Deletes what's stored and starts over. */
	void clearAll() {
		synchronized (this.genCache) {
			this.genCache.clear();
		}
		this.store.clear();
		this.remeshAll();
	}

	static boolean exists(Path p) {
		return Files.exists(p);
	}
}
