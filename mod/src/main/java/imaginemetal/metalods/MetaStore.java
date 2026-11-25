package imaginemetal.metalods;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.RegistryAccess;
import org.jspecify.annotations.Nullable;

/**
 * MetaLODS on disk, per world and dimension (saves/WORLD/metalods/DIMENSION, or metalods/SERVER/DIMENSION for a server):
 * - gen9/L{level}/{cx}.{cz}.bin (gen9h: without overhangs): a column generated from the seed (regenerated if the generator's version changes);
 * - real/r.{rx}.{rz}.bin: the real chunks seen there (a region of 32 x 32 chunks), each with its LODs at levels 0 to 4.
 * Files name their blocks and biomes (a palette per file), so numbers never need to match between sessions.
 * Everything compressed (deflate). Writes go to one background thread.
 */
final class MetaStore {
	/** (fix24: 10: the structures are the game's own (LodStructurePreviews), not guessed sites: gen9 tiles hold houses where the
	 * world has none; levels 4 and up have no structures and are read from gen9 or gen8) */
	static final int GEN_VERSION = 10;
	/** (s1121) as mcopt.metal.lod.LodStructurePreviews.VOXELS (that class isn't loaded with the store) */
	static final boolean STRUCTURE_VOXELS = !"false".equals(System.getProperty("metalods.structureVoxels"));
	/**
	 * The real chunks' folder. real2 (fix9): only still water counts (a waterfall's flowing water is air to the LODs) and a
	 * coarser voxel is water only when at least half of its eight is: the old files hold waterfalls grown into huge water
	 * pillars under floating islands (Jack 22.30.05), so they are left behind.
	 */
	static final String REAL = "real2";
	/**
	 * (fix9b) The old folder: still read (a chunk not yet in real2), its levels 1-4 made again from its level 0 by the new rule,
	 * then saved into real2. Without it every place visited before fix9 was generated from the seed again (Jack 00:46 "making
	 * new chunks again now??", "lods are slow").
	 */
	static final String OLD_REAL = "real";
	private static final int MAGIC = 0x4D4C4F44;  // "MLOD"
	final Path dir;
	private final RegistryAccess access;
	private final int cliffBiome, mesaBiome;
	private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "MetaLODS disk");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	/** Regions with real chunks that exist on disk or in memory. */
	private final Set<Long> knownRegions = ConcurrentHashMap.newKeySet();
	private final Map<Long, Region> regions = new LinkedHashMap<>(16, 0.75f, true);
	private volatile boolean closed;
    private record GeneratedKey(int level,int cx,int cz) {}
    /** kind: 0 full (the whole router, or levels 2+), 1 a quick surface preview, 2 levels 0-1 from the cave-free far terrain. */
    private record PendingGen(Rle data,int kind) {}
    private final Map<GeneratedKey,PendingGen> pendingGenerated=new ConcurrentHashMap<>();
    private final Set<GeneratedKey> surfaceColumns=ConcurrentHashMap.newKeySet(),farColumns=ConcurrentHashMap.newKeySet();
    boolean isSurface(int level,int cx,int cz) { return this.surfaceColumns.contains(new GeneratedKey(level,cx,cz)); }
    /** A level 0-1 column made from the far terrain (no caves), to be made again from the whole router once the player is near. */
    boolean isFar(int level,int cx,int cz) { return this.farColumns.contains(new GeneratedKey(level,cx,cz)); }
    /**
     * Level 0's folders carry "-t2" (fix11): level-0 columns made before Terralith's tree features were recognised (fix10) have
     * bare ground where its forests are; they are left on disk but not read, and made again where they are seen.
     */
    private String dirFor(int level) {
        // (-t3, fix21: level 0's soil by the rules' own depth and the ground's disks: made again)
        // (-s1, fix21: levels 1-3 with the surface structures from the seed; level 2+'s density every second voxel)
        // (-s3, s1121: levels 1-3's structures as voxels, mcopt.metal.lod.LodStructurePreviews: made again; -s2 still read with
        // -Dmetalods.structureVoxels=false. -t6, s1121: level 0's structures on the shore the game raises (raiseShore): made again)
        return level==0?this.genDir+(STRUCTURE_VOXELS?"-t6":"-t5"):level<=3?this.genDir+(STRUCTURE_VOXELS?"-s3":"-s2"):this.genDir;
    }
    private Path surfacePath(int level,int cx,int cz) {
        return this.dir.resolve(this.generationFolder(level)+"-surface").resolve("L"+level).resolve(cx+"."+cz+".bin");
    }
    private Path farPath(int level,int cx,int cz) {
        // (-far2, fix12: fix11's far columns were interpolated, with blocky ledges; made again block-exact)
        // (-far3, fix16: fix13-15 stored far level-0 columns with estimated trees; made again with the game's trees)
        return this.dir.resolve(this.generationFolder(level)+"-far3").resolve("L"+level).resolve(cx+"."+cz+".bin");
    }
    final java.util.concurrent.atomic.AtomicLong cacheHits = new java.util.concurrent.atomic.AtomicLong(), cacheMisses = new java.util.concurrent.atomic.AtomicLong(), cacheErrors = new java.util.concurrent.atomic.AtomicLong();

	/** A real chunk's LODs: levels 0 (16 x 16 cells) to 4 (1 x 1). */
	record RealChunk(Rle[] levels) {
	}

	static final class Region {
		final AtomicReferenceArray<RealChunk> chunks = new AtomicReferenceArray<>(1024);
		volatile boolean dirty;
	}

	/** The generated columns' folder: the generator's version, and h for heightfield ones (3D off). */
	private final String genDir;

	MetaStore(Path dir, RegistryAccess access, boolean overhangs, int cliffBiome, int mesaBiome) {
		this.genDir = "gen" + GEN_VERSION + (overhangs ? "" : "h");
		this.dir = dir;
		this.access = access;
		this.cliffBiome = cliffBiome;
		this.mesaBiome = mesaBiome;
		try {
			Files.createDirectories(dir.resolve(REAL));
			// (fix9b: the regions of the old real/ folder are known too: read from it, re-mipped by the new rule (readRegion))
			for (String folder : new String[] {REAL, OLD_REAL}) {
			if (!Files.isDirectory(dir.resolve(folder))) continue;
			try (var s = Files.list(dir.resolve(folder))) {
				s.forEach(p -> {
					String n = p.getFileName().toString();
					if (!n.startsWith("r.") || !n.endsWith(".bin")) return;
					String[] parts = n.substring(2, n.length() - 4).split("\\.");
					if (parts.length != 2) return;
					try {
						this.knownRegions.add(key(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
					} catch (NumberFormatException ignored) {
					}
				});
			}
			}
		} catch (IOException e) {
			System.out.println("[MetaLODS] can't use " + dir + ": " + e);
		}
	}

	static long key(int x, int z) {
		return (long) x << 32 | (z & 0xFFFFFFFFL);
	}

	// ---- generated columns ----

	private Path genPath(int level, int cx, int cz) {
		return this.dir.resolve(this.generationFolder(level)).resolve("L" + level).resolve(cx + "." + cz + ".bin");
	}

	private String cliffFolder(int level) {
		return this.dirFor(level) + (level == 0 && this.cliffBiome != 0 ? "-cliff1" : "");
	}

	private String generationFolder(int level) {
		return this.cliffFolder(level) + (this.mesaBiome == 0 ? "" : "-mesa1");
	}

	private record CachedSeed(Path path, int kind) {}

	private @Nullable CachedSeed findSeed(String folder, int level, Path file) {
		Path path = this.dir.resolve(folder).resolve(file);
		if (Files.exists(path)) return new CachedSeed(path, 0);
		if (level <= 1) {
			path = this.dir.resolve(folder + "-far3").resolve(file);
			if (Files.exists(path)) return new CachedSeed(path, 2);
		}
		path = this.dir.resolve(folder + "-surface").resolve(file);
		return Files.exists(path) ? new CachedSeed(path, 1) : null;
	}

	@Nullable Rle loadGen(int level, int cx, int cz) {
		GeneratedKey key = new GeneratedKey(level, cx, cz);
		PendingGen pending = this.pendingGenerated.get(key);
		if (pending != null) return pending.data();
		Path file = Path.of("L" + level, cx + "." + cz + ".bin");
		CachedSeed seed = this.findSeed(this.generationFolder(level), level, file);
		boolean oldMesa = seed == null && this.mesaBiome != 0;
		if (oldMesa) seed = this.findSeed(this.cliffFolder(level), level, file);
		boolean oldCliff = seed == null && level == 0 && this.cliffBiome != 0;
		if (oldCliff) seed = this.findSeed(this.dirFor(level), level, file);
		// Coarse terrain was unchanged in gen9 and gen10. Older unaffected biome columns can still be reused.
		if (seed == null && level >= 4) {
			for (String version : new String[] {"gen9", "gen8"}) {
				seed = this.findSeed(this.genDir.replace("gen" + GEN_VERSION, version), level, file);
				if (seed != null) break;
			}
		}
		if (seed == null && level <= 3 && !MetaSettings.generate) {
			String[] folders = level == 0 ? new String[]{"-t5", "-t4", "-t3"} : new String[]{"-s2", "-s1"};
			for (String folder : folders) {
				seed = this.findSeed(this.genDir + folder, level, file);
				if (seed != null) break;
			}
		}
		if (seed == null) {
			this.cacheMisses.incrementAndGet();
			return null;
		}
		Path path = seed.path();
		try (DataInputStream in = open(path)) {
			if (in.readInt() != MAGIC) throw new IOException("bad generated cache header");
			Rle r = this.readRle(in);
			if (MetaSettings.generate && (oldMesa || oldCliff)
			    && this.containsOldSurface(r, oldMesa ? this.mesaBiome : 0, oldCliff ? this.cliffBiome : 0)) {
				this.cacheMisses.incrementAndGet();
				return null;
			}
			this.cacheHits.incrementAndGet();
			this.mark(key, seed.kind());
			return r;
		} catch (IOException | RuntimeException e) {
			if (this.cacheErrors.incrementAndGet() <= 3) System.out.println("[MetaLODS] can't read cached column " + path + ": " + e);
			return null;
		}
	}

	private boolean containsOldSurface(Rle column, int first, int second) {
		for (int i = 0; i < column.runs.length; i += 2) {
			int biome = Voxel.biome(column.runs[i]);
			if (biome != 0 && (biome == first || biome == second)) return true;
		}
		return false;
	}

    private void mark(GeneratedKey key,int kind) {
        if(kind==1)this.surfaceColumns.add(key);else this.surfaceColumns.remove(key);
        if(kind==2)this.farColumns.add(key);else this.farColumns.remove(key);
    }
    void saveGen(int level,int cx,int cz,Rle r) { this.saveGenerated(level,cx,cz,r,0); }
    void saveSurface(int level,int cx,int cz,Rle r) { this.saveGenerated(level,cx,cz,r,1); }
    void saveFar(int level,int cx,int cz,Rle r) { this.saveGenerated(level,cx,cz,r,level<=1?2:0); }
    private synchronized void saveGenerated(int level,int cx,int cz,Rle r,int kind) {
        if(this.closed)return;
        GeneratedKey key=new GeneratedKey(level,cx,cz);
        PendingGen existing=this.pendingGenerated.get(key);
        // (never downgrade: a preview or a far column doesn't replace a full one, a preview doesn't replace a far one)
        if(kind!=0 && (Files.exists(this.genPath(level,cx,cz)) || existing!=null && existing.kind()==0))return;
        if(kind==1 && (level<=1 && Files.exists(this.farPath(level,cx,cz)) || existing!=null && existing.kind()==2))return;
        PendingGen pending=new PendingGen(r,kind);
        this.pendingGenerated.put(key,pending);
        this.mark(key,kind);
        this.io.execute(() -> {
            Path p=kind==1?this.surfacePath(level,cx,cz):kind==2?this.farPath(level,cx,cz):this.genPath(level,cx,cz);
            try {
                this.write(p,out -> {out.writeInt(MAGIC);this.writeRle(out,r);});
            } catch(IOException e) {
                if(this.cacheErrors.incrementAndGet()<=3)System.out.println("[MetaLODS] can't save cached column "+p+": "+e);
            } finally {this.pendingGenerated.remove(key,pending);}
        });
    }

	// ---- real chunks ----

	boolean hasRegion(int rx, int rz) {
		return this.knownRegions.contains(key(rx, rz));
	}

	/** The region, loaded from disk if needed, or null if no real chunk was ever seen in it. */
	@Nullable Region region(int rx, int rz, boolean create) {
		long k = key(rx, rz);
		synchronized (this.regions) {
			Region r = this.regions.get(k);
			if (r != null) return r;
		}
		if (!this.knownRegions.contains(k) && !create) return null;
		Region r = new Region();
		Path p = this.dir.resolve(REAL).resolve("r." + rx + "." + rz + ".bin");
		Path old = this.dir.resolve(OLD_REAL).resolve("r." + rx + "." + rz + ".bin");
		if (Files.exists(old) && this.readRegion(old, r, true) > 0) r.dirty = true;  // (then real2's own chunks over them)
		if (Files.exists(p)) this.readRegion(p, r, false);
		synchronized (this.regions) {
			Region had = this.regions.get(k);
			if (had != null) return had;
			this.regions.put(k, r);
			this.knownRegions.add(k);
			// (keep up to 48 regions in memory: the least recently used clean ones go)
			if (this.regions.size() > 48) {
				var it = this.regions.entrySet().iterator();
				while (it.hasNext() && this.regions.size() > 48) {
					var e = it.next();
					if (!e.getValue().dirty && e.getKey() != k) it.remove();
				}
			}
		}
		return r;
	}

	/** Reads a region file into r; remip: levels 1-4 made again from level 0 (an old real/ file). Returns the chunks read. */
	private int readRegion(Path p, Region r, boolean remip) {
		int n = 0;
		try (DataInputStream in = open(p)) {
			if (in.readInt() == MAGIC) {
				int count = in.readInt();
				for (int i = 0; i < count; i++) {
					int idx = in.readUnsignedShort();
					Rle[] lv = new Rle[5];
					for (int l = 0; l < 5; l++) lv[l] = this.readRle(in);
					if (remip) for (int l = 1; l < 5; l++) lv[l] = lv[l - 1].mip();
					if (idx < 1024) {
						r.chunks.set(idx, new RealChunk(lv));
						n++;
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[MetaLODS] couldn't read " + p + ": " + e);
		}
		return n;
	}

	@Nullable RealChunk real(int chunkX, int chunkZ) {
		Region r = this.region(chunkX >> 5, chunkZ >> 5, false);
		if (r == null) return null;
		return r.chunks.get((chunkX & 31) + (chunkZ & 31) * 32);
	}

	void putReal(int chunkX, int chunkZ, RealChunk c) {
		Region r = this.region(chunkX >> 5, chunkZ >> 5, true);
		if (r == null) return;
		r.chunks.set((chunkX & 31) + (chunkZ & 31) * 32, c);
		r.dirty = true;
	}

	boolean putRealIfAbsent(int chunkX, int chunkZ, RealChunk chunk) {
		Region r = this.region(chunkX >> 5, chunkZ >> 5, true);
		if (r == null || !r.chunks.compareAndSet((chunkX & 31) + (chunkZ & 31) * 32, null, chunk)) return false;
		r.dirty = true;
		return true;
	}

	/** Writes the regions changed since the last call (in the background). */
	void flush() {
		Map<Long, Region> dirty = new HashMap<>();
		synchronized (this.regions) {
			for (var e : this.regions.entrySet()) {
				if (e.getValue().dirty) {
					e.getValue().dirty = false;
					dirty.put(e.getKey(), e.getValue());
				}
			}
		}
		for (var e : dirty.entrySet()) {
			int rx = (int) (e.getKey() >> 32), rz = (int) (long) e.getKey();
            Region r = e.getValue();
            // Count and write one immutable snapshot. A capture arriving between
            // two passes must not produce a truncated/miscounted region file.
            RealChunk[] snapshot = new RealChunk[1024];
            for (int i = 0; i < 1024; i++) snapshot[i] = r.chunks.get(i);
			this.io.execute(() -> {
				Path p = this.dir.resolve(REAL).resolve("r." + rx + "." + rz + ".bin");
				try {
					this.write(p, out -> {
						out.writeInt(MAGIC);
						int count = 0;
						for (int i = 0; i < 1024; i++) if (snapshot[i] != null) count++;
						out.writeInt(count);
						for (int i = 0; i < 1024; i++) {
							RealChunk c = snapshot[i];
							if (c == null) continue;
							out.writeShort(i);
							for (int l = 0; l < 5; l++) this.writeRle(out, c.levels()[l]);
						}
					});
				} catch (IOException ex) {
					r.dirty = true;
					System.out.println("[MetaLODS] couldn't save " + p + ": " + ex);
				}
			});
		}
	}

	void close() {
		this.flush();
		this.closed = true;
		this.io.shutdown();
		try {
			this.io.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}

	/** Deletes everything stored for this dimension (the generated columns and the real chunks). */
	void clear() {
        this.pendingGenerated.clear();this.surfaceColumns.clear();this.farColumns.clear();
		synchronized (this.regions) {
			this.regions.clear();
		}
		this.knownRegions.clear();
		this.io.execute(() -> {
			try (var s = Files.walk(this.dir)) {
				s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
					try {
						Files.deleteIfExists(p);
					} catch (IOException ignored) {
					}
				});
			} catch (IOException ignored) {
			}
		});
	}

	// ---- format ----

	private interface Body {
		void write(DataOutputStream out) throws IOException;
	}

	private void write(Path p, Body body) throws IOException {
		Files.createDirectories(p.getParent());
		Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
		try (OutputStream fo = Files.newOutputStream(tmp);
			DeflaterOutputStream dz = new DeflaterOutputStream(new BufferedOutputStream(fo, 65536), new Deflater(Deflater.BEST_SPEED), 65536);
			DataOutputStream out = new DataOutputStream(dz)) {
			body.write(out);
		}
		Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static DataInputStream open(Path p) throws IOException {
		InputStream fi = Files.newInputStream(p);
		return new DataInputStream(new BufferedInputStream(new InflaterInputStream(fi), 65536));
	}

	/** n, h; the palette (block names, biome names); per cell its run count; the runs (value with local numbers, end). */
	private void writeRle(DataOutputStream out, Rle r) throws IOException {
		Map<Integer, Integer> blocks = new HashMap<>(), biomes = new HashMap<>();
		java.util.List<Integer> bl = new java.util.ArrayList<>(), bi = new java.util.ArrayList<>();
		blocks.put(0, 0);
		bl.add(0);
		biomes.put(0, 0);
		bi.add(0);
		int[] runs = r.runs;
		int pairs = runs.length / 2;
		int[] local = new int[pairs];
		for (int i = 0; i < pairs; i++) {
			int v = runs[2 * i];
			int b = Voxel.id(v), g = Voxel.biome(v);
			Integer lb = blocks.get(b);
			if (lb == null) {
				lb = bl.size();
				blocks.put(b, lb);
				bl.add(b);
			}
			Integer lg = biomes.get(g);
			if (lg == null) {
				lg = bi.size();
				biomes.put(g, lg);
				bi.add(g);
			}
			local[i] = (v & 0xFF000000) | lg << 16 | lb;
		}
		out.writeShort(r.n);
		out.writeShort(r.h);
		out.writeShort(bl.size() - 1);
		for (int i = 1; i < bl.size(); i++) out.writeUTF(MetaPalette.blockKey(bl.get(i)));
		out.writeShort(bi.size() - 1);
		for (int i = 1; i < bi.size(); i++) out.writeUTF(MetaPalette.biomeKey(bi.get(i)));
		int cells = r.n * r.n;
		for (int c = 0; c < cells; c++) out.writeShort(r.offsets[c + 1] - r.offsets[c]);
		for (int i = 0; i < pairs; i++) {
			out.writeInt(local[i]);
			out.writeShort(runs[2 * i + 1]);
		}
	}

	private Rle readRle(DataInputStream in) throws IOException {
		int n = in.readUnsignedShort(), h = in.readUnsignedShort();
		if (n == 0 || n > 64 || h == 0 || h > 4096) throw new IOException("bad column " + n + "x" + h);
		int nb = in.readUnsignedShort();
		int[] blocks = new int[nb + 1];
		for (int i = 1; i <= nb; i++) blocks[i] = MetaPalette.blockFromKey(in.readUTF());
		int ng = in.readUnsignedShort();
		int[] biomes = new int[ng + 1];
		for (int i = 1; i <= ng; i++) biomes[i] = MetaPalette.biomeFromKey(in.readUTF(), this.access);
		int cells = n * n;
		int[] off = new int[cells + 1];
		for (int c = 0; c < cells; c++) off[c + 1] = off[c] + in.readUnsignedShort();
		int pairs = off[cells];
		int[] runs = new int[2 * pairs];
		for (int i = 0; i < pairs; i++) {
			int v = in.readInt();
			int lb = v & 0xFFFF, lg = (v >>> 16) & 0xFF;
			int b = lb < blocks.length ? blocks[lb] : 0, g = lg < biomes.length ? biomes[lg] : 0;
			runs[2 * i] = (v & 0xFF000000) | g << 16 | b;
			runs[2 * i + 1] = in.readUnsignedShort();
		}
		return new Rle(n, h, off, runs);
	}
}
