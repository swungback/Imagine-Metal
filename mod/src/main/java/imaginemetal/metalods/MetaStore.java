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
 * - gen6/L{level}/{cx}.{cz}.bin (gen6h: without overhangs): a column generated from the seed (regenerated if the generator's version changes);
 * - real/r.{rx}.{rz}.bin: the real chunks seen there (a region of 32 x 32 chunks), each with its LODs at levels 0 to 4.
 * Files name their blocks and biomes (a palette per file), so numbers never need to match between sessions.
 * Everything compressed (deflate). Writes go to one background thread.
 */
final class MetaStore {
	static final int GEN_VERSION = 6;
	private static final int MAGIC = 0x4D4C4F44;  // "MLOD"
	final Path dir;
	private final RegistryAccess access;
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

	/** A real chunk's LODs: levels 0 (16 x 16 cells) to 4 (1 x 1). */
	record RealChunk(Rle[] levels) {
	}

	static final class Region {
		final AtomicReferenceArray<RealChunk> chunks = new AtomicReferenceArray<>(1024);
		volatile boolean dirty;
	}

	/** The generated columns' folder: the generator's version, and h for heightfield ones (3D off). */
	private final String genDir;

	MetaStore(Path dir, RegistryAccess access, boolean overhangs) {
		this.genDir = "gen" + GEN_VERSION + (overhangs ? "" : "h");
		this.dir = dir;
		this.access = access;
		try {
			Files.createDirectories(dir.resolve("real"));
			try (var s = Files.list(dir.resolve("real"))) {
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
		} catch (IOException e) {
			System.out.println("[MetaLODS] can't use " + dir + ": " + e);
		}
	}

	static long key(int x, int z) {
		return (long) x << 32 | (z & 0xFFFFFFFFL);
	}

	// ---- generated columns ----

	private Path genPath(int level, int cx, int cz) {
		return this.dir.resolve(this.genDir).resolve("L" + level).resolve(cx + "." + cz + ".bin");
	}

	@Nullable Rle loadGen(int level, int cx, int cz) {
		Path p = this.genPath(level, cx, cz);
		if (!Files.exists(p)) return null;
		try (DataInputStream in = open(p)) {
			if (in.readInt() != MAGIC) return null;
			return this.readRle(in);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	void saveGen(int level, int cx, int cz, Rle r) {
		if (this.closed) return;
		this.io.execute(() -> {
			Path p = this.genPath(level, cx, cz);
			try {
				this.write(p, out -> {
					out.writeInt(MAGIC);
					this.writeRle(out, r);
				});
			} catch (IOException e) {
				// (a cache: regenerated next time)
			}
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
		Path p = this.dir.resolve("real").resolve("r." + rx + "." + rz + ".bin");
		if (Files.exists(p)) {
			try (DataInputStream in = open(p)) {
				if (in.readInt() == MAGIC) {
					int count = in.readInt();
					for (int i = 0; i < count; i++) {
						int idx = in.readUnsignedShort();
						Rle[] lv = new Rle[5];
						for (int l = 0; l < 5; l++) lv[l] = this.readRle(in);
						if (idx < 1024) r.chunks.set(idx, new RealChunk(lv));
					}
				}
			} catch (IOException | RuntimeException e) {
				System.out.println("[MetaLODS] couldn't read " + p + ": " + e);
			}
		}
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
			this.io.execute(() -> {
				Path p = this.dir.resolve("real").resolve("r." + rx + "." + rz + ".bin");
				try {
					this.write(p, out -> {
						out.writeInt(MAGIC);
						int count = 0;
						for (int i = 0; i < 1024; i++) if (r.chunks.get(i) != null) count++;
						out.writeInt(count);
						for (int i = 0; i < 1024; i++) {
							RealChunk c = r.chunks.get(i);
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
