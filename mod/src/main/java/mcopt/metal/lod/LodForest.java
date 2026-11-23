package mcopt.metal.lod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * The game's own trees on terrain that was never generated: for a chunk, the vegetal decoration's tree features run exactly
 * as ChunkGenerator.applyBiomeDecoration runs them (the same decoration and feature seeds, the same placement modifiers and
 * tree shapes), against a stand-in level whose ground is the far terrain's own block-exact surface (LodNoise at level 0) and
 * which records every block the trees set. So a far forest has the trees the real chunks will have, where they will be.
 *
 * Each feature has a seed of its own (decoration seed, its index in the step, the step), so running only the tree features
 * leaves their randomness exactly the game's; within a feature the trees are placed in the game's order, which matters (each
 * consumes randomness). What differs from the real chunk: the ground ignores caves breaking the surface and earlier features
 * (lakes, rocks), and trees of neighboring chunks don't see each other. Per chunk the result is cached (tiles share border
 * chunks); a tile asks for its own chunks and the ring around them (trees reach across chunk edges).
 */
final class LodForest {
	/** A chunk's tree blocks: x, y, z (absolute) and the block state id, four ints each. */
	record Trees(int[] blocks) {
	}

	/** A chunk's ground, block exact: top face y, wet (sea over it), the surface block, the biome per column. */
	record Ground(short[] height, boolean[] wet, BlockState[] state) {
	}

	private static final int VEG = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
	/** (fix21) The disks (gravel, sand, clay, mud around water: the step before the trees) run too: their ground is the real one. */
	private static final int ORES = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
	static final boolean DISKS = !"false".equals(System.getProperty("metalods.disks"));
	/** In a chunk's block list: the block was set by a disk feature (the ground's own material). */
	static final int FROM_GROUND = 1 << 29;
	private final FeatureSorter.@Nullable StepFeatureData oreStep;
	private final boolean[] disk;
	/**
	 * (fix21) Surface structures from the seed (villages, outposts, pyramids, igloos, ruined portals, the mods' own), as SeedGen
	 * shows them: each chunk's starts made as the game's createStructures makes them (the same structure sets, placement and
	 * weighted choice, Structure.generate with its own seeds), their pieces placed into the stand-in level for the chunks of a
	 * near tile (StructureStart.placeInChunk, the decoration step's own seed). What they set stands on the ground as the trees do:
	 * a column per block column, its walls' block up to its top block. -Dmetalods.structures=false: off.
	 */
	static final boolean STRUCTURES = !"false".equals(System.getProperty("metalods.structures"));
    static final boolean EXACT_STRUCTURES = STRUCTURES && Boolean.getBoolean("metalods.exactStructures");
	/** In a chunk's block list: the block was set by a structure piece. */
	static final int FROM_STRUCT = 1 << 28;
	/** How far (chunks) a structure's start may lie from a chunk its pieces reach (jigsaw: up to 128 blocks from its centre). */
	static final int STRUCT_REACH = 8;
	private static final int SURFACE = GenerationStep.Decoration.SURFACE_STRUCTURES.ordinal();
	private final ConcurrentHashMap<Long, List<StructureStart>> starts = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, CompletableFuture<List<StructureStart>>> pendingStarts = new ConcurrentHashMap<>();
	private final java.util.Map<net.minecraft.world.level.levelgen.structure.Structure, Integer> surfaceIndex = new java.util.IdentityHashMap<>();
	private final ThreadLocal<net.minecraft.world.level.biome.Climate.Sampler> climate;
	final java.util.concurrent.atomic.AtomicLong structuresMade = new java.util.concurrent.atomic.AtomicLong(), structureNanos = new java.util.concurrent.atomic.AtomicLong();
	private volatile boolean structuresFailed;
	private static final int CACHE_MAX = 40000;
	private final LodNoise noise;
	private final ServerLevel level;
	private final ChunkGenerator generator;
	private final long seed;
	private final long biomeSeed;
	private final FeatureSorter.@Nullable StepFeatureData step;
	/** Per global feature index of the vegetal step: whether it grows trees (big plants seen from afar). */
	private final boolean[] tree;
	/** Per global feature index: whether it grows plants (grass, ferns, flowers, bushes, cane, cacti, pumpkins). */
	private final boolean[] plants;
	/** In a chunk's block list: the block was set by a tree feature (else by a plant feature). */
	static final int FROM_TREE = 1 << 30;
	private final ConcurrentHashMap<Long, Ground> grounds = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, Trees> trees = new ConcurrentHashMap<>();
	private final ThreadLocal<Stand> stands;
	final java.util.concurrent.atomic.AtomicLong runs = new java.util.concurrent.atomic.AtomicLong(), runNanos = new java.util.concurrent.atomic.AtomicLong(),
		failures = new java.util.concurrent.atomic.AtomicLong();

	LodForest(LodNoise noise) {
		this(noise, LodConfig.PLANTS);
	}

	/** plants: also the small plant features (grass, flowers, bushes); off for the far terrain (a block at 200+ blocks away). */
	LodForest(LodNoise noise, boolean plants) {
		this.noise = noise;
		this.level = noise.level;
		this.generator = this.level.getChunkSource().getGenerator();
		this.seed = this.level.getSeed();
		this.biomeSeed = BiomeManager.obfuscateSeed(this.seed);
		List<FeatureSorter.StepFeatureData> all = stepsData(this.generator);
		this.step = all != null && VEG < all.size() ? all.get(VEG) : null;
		this.oreStep = DISKS && all != null && ORES < all.size() ? all.get(ORES) : null;
		boolean[] dk = new boolean[0];
		if (this.oreStep != null) {
			var registry = this.level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
			dk = new boolean[this.oreStep.features().size()];
			List<String> ids = new ArrayList<>();
			for (int i = 0; i < dk.length; i++) {
				dk[i] = isDisk(this.oreStep.features().get(i).feature(), 0);
				Identifier id = registry.getKey(this.oreStep.features().get(i));
				if (dk[i]) ids.add(id != null ? id.getPath() : "?");
			}
			System.out.println("mcopt-lod: ground disks from " + ids.size() + " features: " + ids);
		}
		this.disk = dk;
		{
			int j = 0;
			for (var st : this.level.registryAccess().lookupOrThrow(Registries.STRUCTURE)) if (st.step().ordinal() == SURFACE) this.surfaceIndex.put(st, j++);
		}
		this.climate = ThreadLocal.withInitial(() -> this.level.getChunkSource().randomState()
			.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.builder().enableCaches().build()));
		boolean[] t = new boolean[0], p = new boolean[0];
		if (this.step != null) {
			var registry = this.level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
			t = new boolean[this.step.features().size()];
			p = new boolean[t.length];
			int count = 0;
			List<String> plantIds = new ArrayList<>();
			for (int i = 0; i < t.length; i++) {
				Identifier id = registry.getKey(this.step.features().get(i));
				// (by name as before, or by what it grows: Terralith's tree features have their own names, terralith:dark/trees,
				// highlands/temperate/dark_oak, flower/blue/trees..., and were left out or run as small plants: its forests were
				// empty grass on the near LODs until the real chunks came, Jack's 16.17.14)
				t[i] = id != null && (isTree(id.getPath()) || growsTrees(this.step.features().get(i)));
				p[i] = plants && id != null && !t[i] && isPlants(id.getPath());
				if (t[i]) count++;
				if (p[i]) plantIds.add(id.getPath());
			}
			System.out.println("mcopt-lod: trees from " + count + " of the game's " + t.length + " vegetal features, plants from " + plantIds.size() + ": " + plantIds);
		}
		this.tree = t;
		this.plants = p;
		this.stands = ThreadLocal.withInitial(Stand::new);
	}

	private static boolean isTree(String path) {
		return path.startsWith("trees_") || path.equals("birch_tall") || path.equals("dark_forest_vegetation") || path.equals("pale_garden_vegetation")
			|| path.equals("mushroom_island_vegetation") || path.equals("bamboo_vegetation");
	}

	/** Whether a placed feature grows trees or huge mushrooms, anywhere in its (random selector) tree of features. */
	static boolean growsTrees(PlacedFeature placed) {
		try {
			return grows(placed.feature(), 0);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static boolean grows(Holder<net.minecraft.world.level.levelgen.feature.Feature> h, int depth) {
		if (depth > 8 || !h.isBound()) return false;
		var f = h.value();
		if (f instanceof net.minecraft.world.level.levelgen.feature.TreeFeature
			|| f instanceof net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature) return true;
		if (f instanceof net.minecraft.world.level.levelgen.feature.FallenTreeFeature
			|| f instanceof net.minecraft.world.level.levelgen.feature.CoralTreeFeature) return false;
		return f.getSubFeatures().limit(64).anyMatch(sub -> grows(sub, depth + 1));
	}

	/** Features that grow small plants on the surface (not under water, not in caves, not on trees). */
	private static boolean isPlants(String path) {
		for (String no : new String[] {"sea", "kelp", "lily", "mushroom", "vine", "lichen", "spring", "bamboo", "cave", "dripleaf", "spore", "moss", "lush"}) {
			if (path.contains(no)) return false;
		}
		for (String yes : new String[] {"patch_", "flower", "grass", "fern", "bush", "cane", "cactus", "pumpkin", "melon", "berr", "leaf_litter", "petal"}) {
			if (path.contains(yes)) return true;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private static @Nullable List<FeatureSorter.StepFeatureData> stepsData(ChunkGenerator generator) {
		try {
			java.lang.reflect.Field f = ChunkGenerator.class.getDeclaredField("featuresPerStep");
			f.setAccessible(true);
			return ((Supplier<List<FeatureSorter.StepFeatureData>>) f.get(generator)).get();
		} catch (ReflectiveOperationException | RuntimeException e) {
			System.out.println("mcopt-lod: no feature list (" + e + "): far trees are impostors");
			return null;
		}
	}

	/** A disk feature (replaces the ground's blocks around a point: gravel, sand, clay, mud), anywhere in its tree of features. */
	private static boolean isDisk(Holder<net.minecraft.world.level.levelgen.feature.Feature> h, int depth) {
		try {
			if (depth > 8 || !h.isBound()) return false;
			var f = h.value();
			if (f instanceof net.minecraft.world.level.levelgen.feature.DiskFeature) return true;
			return f.getSubFeatures().limit(64).anyMatch(sub -> isDisk(sub, depth + 1));
		} catch (RuntimeException e) {
			return false;
		}
	}

	boolean available() {
		return this.step != null;
	}

	/** Grounds being made right now (fix13: two workers asking for one chunk's ground made it twice). */
	private final ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<Ground>> making = new ConcurrentHashMap<>();
	final java.util.concurrent.atomic.AtomicLong groundsMade = new java.util.concurrent.atomic.AtomicLong(), groundsRefused = new java.util.concurrent.atomic.AtomicLong();

	/** The chunk's ground at block resolution (cached). */
	Ground ground(int chunkX, int chunkZ) {
		long key = chunkKey(chunkX, chunkZ);
		Ground g = this.grounds.get(key);
		if (g != null) return g;
		java.util.concurrent.CompletableFuture<Ground> mine = new java.util.concurrent.CompletableFuture<>();
		java.util.concurrent.CompletableFuture<Ground> other = this.making.putIfAbsent(key, mine);
		if (other != null) {
			try {
				return other.join();
			} catch (RuntimeException e) {
				// (the other worker failed: make it here)
				return this.makeGround(chunkX, chunkZ, key);
			}
		}
		try {
			g = this.grounds.get(key);
			if (g == null) g = this.makeGround(chunkX, chunkZ, key);
			mine.complete(g);
			return g;
		} catch (RuntimeException e) {
			mine.completeExceptionally(e);
			throw e;
		} finally {
			this.making.remove(key, mine);
		}
	}

	private Ground makeGround(int chunkX, int chunkZ, long key) {
		LodTile t = new LodTile(0, chunkX * 16, chunkZ * 16, 16);
		t.impostorTrees = false;
		this.noise.generate(t);
		Ground g = groundOf(t, 0, 0, 16);
		if (this.grounds.size() > CACHE_MAX) this.grounds.clear();
		this.grounds.put(key, g);
		this.groundsMade.incrementAndGet();
		return g;
	}

	/**
	 * The chunk's ground if it is known or the chunk is one the tile being planted asked for (its own and the ring around
	 * them); else null (fix13: a tree at a ring chunk's far edge looking into the chunk beyond made that chunk's whole ground,
	 * ~17 grounds per level-0 tile in game, two thirds of its trees' time). Beyond: open air, nothing to stand on.
	 */
	@Nullable Ground groundNear(int chunkX, int chunkZ, Stand s) {
		Ground g = this.grounds.get(chunkKey(chunkX, chunkZ));
		if (g != null) return g;
		if (s.bounded && (chunkX < s.minCx || chunkX > s.maxCx || chunkZ < s.minCz || chunkZ > s.maxCz)) {
			this.groundsRefused.incrementAndGet();
			return null;
		}
		return this.ground(chunkX, chunkZ);
	}

	/** A level-0 tile's chunks go into the ground cache as it is generated (tile cells from (cx, cz), 16 x 16). */
	void offer(LodTile t) {
		if (t.level != 0) return;
		for (int cz = 0; cz < t.size / 16; cz++) {
			for (int cx = 0; cx < t.size / 16; cx++) {
				long key = chunkKey((t.x0 >> 4) + cx, (t.z0 >> 4) + cz);
				if (!this.grounds.containsKey(key)) this.grounds.put(key, groundOf(t, cx * 16, cz * 16, t.size));
			}
		}
	}

	private Ground groundOf(LodTile t, int ox, int oz, int stride) {
		short[] h = new short[256];
		boolean[] wet = new boolean[256];
		BlockState[] st = new BlockState[256];
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = (oz + z) * stride + ox + x;
				h[z * 16 + x] = t.ground[i];
				wet[z * 16 + x] = t.water[i] != LodTile.DRY && t.water[i] > t.ground[i];
				st[z * 16 + x] = t.state[i] != null ? t.state[i] : Blocks.STONE.defaultBlockState();
			}
		}
		return new Ground(h, wet, st);
	}

	static long chunkKey(int x, int z) {
		return (long) x << 32 | (z & 0xFFFFFFFFL);
	}

	/**
	 * (fix21) The surface structures whose start is chunk (cx, cz), as the game's ChunkGenerator.createStructures picks them:
	 * per structure set whose placement chooses this chunk, its single structure or a weighted pick (the large-feature seed,
	 * trying the next while one fails), Structure.generate with the world's seed and the set's biome check. Cached per chunk.
	 */
	List<StructureStart> starts(int cx, int cz) {
		long key = chunkKey(cx, cz);
		var cached = this.starts.get(key);
		if (cached != null) return cached;

		var claim = new CompletableFuture<List<StructureStart>>();
		var pending = this.pendingStarts.putIfAbsent(key, claim);
		if (pending != null) return pending.join();
		try {
			cached = this.starts.get(key);
			if (cached == null) {
				cached = this.generateStarts(cx, cz);
				if (this.starts.size() > 400_000) this.starts.clear();
				this.starts.put(key, cached);
			}
			claim.complete(cached);
			return cached;
		} catch (RuntimeException | Error failure) {
			claim.completeExceptionally(failure);
			throw failure;
		} finally {
			this.pendingStarts.remove(key, claim);
		}
	}

	private List<StructureStart> generateStarts(int cx, int cz) {
		List<StructureStart> out = List.of();
		LodBaseHeights.enter();  // (s1119: the ground-height walks kept, see LodBaseHeights)
		try {
			var state = this.level.getChunkSource().getGeneratorState();
			net.minecraft.world.level.ChunkPos pos = null;
			for (Holder<net.minecraft.world.level.levelgen.structure.StructureSet> set : state.possibleStructureSets()) {
				var placement = set.value().placement();
				if (placement instanceof net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement) continue;
				if (!placement.isStructureChunk(state, cx, cz)) continue;
				var list = set.value().structures();
				boolean any = false;
				for (var e : list) any |= e.structure().value().step().ordinal() == SURFACE;
				if (!any) continue;
				if (pos == null) pos = new net.minecraft.world.level.ChunkPos(cx, cz);
				StructureStart made = null;
				if (list.size() == 1) made = this.tryStart(list.get(0), state, pos);
				else {
					List<net.minecraft.world.level.levelgen.structure.StructureSet.StructureSelectionEntry> left = new ArrayList<>(list);
					WorldgenRandom r = new WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(0L));
					r.setLargeFeatureSeed(state.getLevelSeed(), cx, cz);
					int total = 0;
					for (var e : left) total += e.weight();
					while (!left.isEmpty()) {
						int pick = r.nextInt(total), j = 0;
						for (var e : left) {
							pick -= e.weight();
							if (pick < 0) break;
							j++;
						}
						var e = left.get(Math.min(j, left.size() - 1));
						made = this.tryStart(e, state, pos);
						if (made != null) break;
						left.remove(e);
						total -= e.weight();
					}
				}
				if (made != null && made.getStructure().step().ordinal() == SURFACE) {
					if (out.isEmpty()) out = new ArrayList<>(2);
					out.add(made);
					this.structuresMade.incrementAndGet();
				}
			}
		} catch (RuntimeException | StackOverflowError | LinkageError e) {
			if (!this.structuresFailed) System.out.println("mcopt-lod: structures from the seed stopped (" + e + "): LODs without structures");
			this.structuresFailed = true;
		} finally {
			LodBaseHeights.exit();
		}
		return out;
	}

	private net.minecraft.world.level.levelgen.structure.@Nullable StructureStart tryStart(net.minecraft.world.level.levelgen.structure.StructureSet.StructureSelectionEntry e,
		net.minecraft.world.level.chunk.ChunkGeneratorStructureState state, net.minecraft.world.level.ChunkPos pos) {
		var holder = e.structure();
		var st = holder.value();
		var start = st.generate(holder, this.level.dimension(), this.level.registryAccess(), this.generator, this.generator.getBiomeSource(), this.climate.get(),
			state.randomState(), this.level.getStructureTemplateManager(), state.getLevelSeed(), pos, 0, this.level, st.biomes()::contains);
		return start.isValid() ? start : null;
	}

	/**
	 * (fix21) Levels 1-3 (no trees there): the surface structures reaching tile (x0, z0, span blocks) placed into the stand-in
	 * level, folded into the tile's columns as the trees are at level 0 (a column of the walls' block up to its top block).
	 */
	void structures(LodTile t) {
		if (!EXACT_STRUCTURES || this.structuresFailed || t.level < 1) return;
		long t0 = System.nanoTime();
		int L = t.level, span = t.size << L, x0 = t.x0, z0 = t.z0;
		net.minecraft.world.level.levelgen.structure.BoundingBox tile = new net.minecraft.world.level.levelgen.structure.BoundingBox(x0, this.level.getMinY(), z0,
			x0 + span - 1, this.level.getMaxY(), z0 + span - 1);
		List<net.minecraft.world.level.levelgen.structure.StructureStart> hits = new ArrayList<>();
		int c0x = (x0 >> 4) - STRUCT_REACH, c0z = (z0 >> 4) - STRUCT_REACH, c1x = ((x0 + span - 1) >> 4) + STRUCT_REACH, c1z = ((z0 + span - 1) >> 4) + STRUCT_REACH;
		for (int cz = c0z; cz <= c1z; cz++) for (int cx = c0x; cx <= c1x; cx++) {
			for (var st : this.starts(cx, cz)) if (st.getBoundingBox().intersects(tile)) hits.add(st);
		}
		if (hits.isEmpty()) {
			this.structureNanos.addAndGet(System.nanoTime() - t0);
			return;
		}
		Stand s = this.stands.get();
		s.reset();
		s.structFeature = true;
		s.treeFeature = false;
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
		try {
			for (var st : hits) {
				var bb = st.getBoundingBox();
				Integer j = this.surfaceIndex.get(st.getStructure());
				int bx0 = Math.max(bb.minX(), x0) >> 4, bz0 = Math.max(bb.minZ(), z0) >> 4, bx1 = Math.min(bb.maxX(), x0 + span - 1) >> 4, bz1 = Math.min(bb.maxZ(), z0 + span - 1) >> 4;
				for (int cz = bz0; cz <= bz1; cz++) for (int cx = bx0; cx <= bx1; cx++) {
					net.minecraft.world.level.levelgen.structure.BoundingBox box = new net.minecraft.world.level.levelgen.structure.BoundingBox(cx * 16,
						this.level.getMinY(), cz * 16, cx * 16 + 15, this.level.getMaxY(), cz * 16 + 15);
					long decoration = random.setDecorationSeed(this.seed, cx * 16, cz * 16);
					random.setFeatureSeed(decoration, j != null ? j : 0, SURFACE);
					try {
						st.placeInChunk(s.proxy, this.level.structureManager(), this.generator, random, box, new net.minecraft.world.level.ChunkPos(cx, cz));
					} catch (RuntimeException | StackOverflowError | LinkageError e) {
						if (this.failures.incrementAndGet() <= 5) System.out.println("mcopt-lod: structure piece failed in chunk " + cx + "," + cz + ": " + e);
					}
				}
			}
		} finally {
			s.structFeature = false;
		}
		int n = t.cells();
		int[] lo = new int[n], hi = new int[n];
		java.util.Arrays.fill(lo, Integer.MAX_VALUE);
		java.util.Arrays.fill(hi, Integer.MIN_VALUE);
		BlockState[] top = new BlockState[n], base = new BlockState[n];
		for (var e : s.placed.entrySet()) {
			long p = e.getKey();
			if (!s.fromStruct.contains(p)) continue;
			BlockState st = e.getValue();
			int bx = BlockPos.getX(p) - x0, y = BlockPos.getY(p), bz = BlockPos.getZ(p) - z0;
			if (bx < 0 || bz < 0 || bx >= span || bz >= span || st.isAir() || !st.getFluidState().isEmpty()) continue;
			var shape = st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			if (shape.isEmpty() || shape.max(net.minecraft.core.Direction.Axis.Y) < 0.5) continue;
			int i = (bz >> L) * t.size + (bx >> L);
			if (y < t.ground[i]) continue;
			if (y < lo[i]) {
				lo[i] = y;
				base[i] = st;
			}
			if (y > hi[i]) {
				hi[i] = y;
				top[i] = st;
			}
		}
		for (int i = 0; i < n; i++) {
			if (hi[i] == Integer.MIN_VALUE || t.water[i] != LodTile.DRY && t.water[i] > t.ground[i]) continue;
			t.canopyLo[i] = (short) t.ground[i];
			t.canopyHi[i] = (short) hi[i];
			t.canopyState[i] = top[i];
			t.trunk[i] = base[i];
			t.standing[i] = true;
		}
		this.structureNanos.addAndGet(System.nanoTime() - t0);
	}

	/** The chunk's trees (cached): every block the game's tree features set when decorating it. */
	Trees trees(int chunkX, int chunkZ) {
		long key = chunkKey(chunkX, chunkZ);
		Trees t = this.trees.get(key);
		if (t != null) return t;
		t = this.run(chunkX, chunkZ);
		if (this.trees.size() > CACHE_MAX) this.trees.clear();
		this.trees.put(key, t);
		return t;
	}

	private Trees run(int chunkX, int chunkZ) {
		FeatureSorter.StepFeatureData step = this.step;
		if (step == null) return new Trees(new int[0]);
		long start = System.nanoTime();
		Stand s = this.stands.get();
		s.reset();
		// the features of the biomes at the chunk's surface (a feature whose biome isn't there places nothing: its biome check fails)
		Ground g = this.ground(chunkX, chunkZ);
		java.util.BitSet wanted = new java.util.BitSet();
		java.util.Set<Holder<Biome>> seen = new java.util.HashSet<>();
		// a 6 x 6 grid over the chunk and 4 blocks around it (the game's biome lookup jitters between neighboring 4-block cells)
		for (int gz = 0; gz < 6; gz++) {
			for (int gx = 0; gx < 6; gx++) {
				int lx = -4 + gx * 24 / 5, lz = -4 + gz * 24 / 5;
				int i = Math.clamp(lz, 0, 15) * 16 + Math.clamp(lx, 0, 15);
				Holder<Biome> b = s.biomes.getBiome(chunkX * 16 + lx, g.height()[i], chunkZ * 16 + lz);
				if (!seen.add(b)) continue;
				List<net.minecraft.core.HolderSet<PlacedFeature>> steps = this.generator.getBiomeGenerationSettings(b).features();
				if (steps.size() <= VEG) continue;
				for (Holder<PlacedFeature> pf : steps.get(VEG)) {
					int idx = step.indexMapping().applyAsInt(pf.value());
					if (idx >= 0 && idx < this.tree.length && (this.tree[idx] || this.plants[idx])) wanted.set(idx);
				}
			}
		}
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
		BlockPos origin = new BlockPos(chunkX * 16, this.level.getMinY(), chunkZ * 16);
		long decoration = random.setDecorationSeed(this.seed, origin.getX(), origin.getZ());
		FeaturePlacer placer = new FeaturePlacer(s.proxy, this.generator);
		if (EXACT_STRUCTURES && !this.structuresFailed) {
			// (fix21) the surface structures first (their step comes before the disks' and the trees')
			long t0 = System.nanoTime();
			net.minecraft.world.level.levelgen.structure.BoundingBox box = new net.minecraft.world.level.levelgen.structure.BoundingBox(chunkX * 16,
				this.level.getMinY(), chunkZ * 16, chunkX * 16 + 15, this.level.getMaxY(), chunkZ * 16 + 15);
			net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(chunkX, chunkZ);
			s.structFeature = true;
			s.treeFeature = false;
			try {
				for (int dz = -STRUCT_REACH; dz <= STRUCT_REACH; dz++) {
					for (int dx = -STRUCT_REACH; dx <= STRUCT_REACH; dx++) {
						for (var st : this.starts(chunkX + dx, chunkZ + dz)) {
							if (!st.getBoundingBox().intersects(box)) continue;
							Integer j = this.surfaceIndex.get(st.getStructure());
							random.setFeatureSeed(decoration, j != null ? j : 0, SURFACE);
							try {
								st.placeInChunk(s.proxy, this.level.structureManager(), this.generator, random, box, pos);
							} catch (RuntimeException | StackOverflowError | LinkageError e) {
								if (this.failures.incrementAndGet() <= 5) System.out.println("mcopt-lod: structure piece failed in chunk " + chunkX + "," + chunkZ + ": " + e);
							}
						}
					}
				}
			} finally {
				s.structFeature = false;
			}
			this.structureNanos.addAndGet(System.nanoTime() - t0);
		}
		if (this.oreStep != null) {
			// (fix21) the disks first, as the game decorates (their step comes before the trees'): gravel, sand, clay and mud
			// around water, which the trees then stand on
			java.util.BitSet disks = new java.util.BitSet();
			for (Holder<Biome> b : seen) {
				List<net.minecraft.core.HolderSet<PlacedFeature>> steps = this.generator.getBiomeGenerationSettings(b).features();
				if (steps.size() <= ORES) continue;
				for (Holder<PlacedFeature> pf : steps.get(ORES)) {
					int idx = this.oreStep.indexMapping().applyAsInt(pf.value());
					if (idx >= 0 && idx < this.disk.length && this.disk[idx]) disks.set(idx);
				}
			}
			s.groundFeature = true;
			s.treeFeature = false;
			for (int idx = disks.nextSetBit(0); idx >= 0; idx = disks.nextSetBit(idx + 1)) {
				random.setFeatureSeed(decoration, idx, ORES);
				try {
					placer.placeWithBiomeCheck(this.oreStep.features().get(idx), random, origin);
				} catch (RuntimeException e) {
					if (this.failures.incrementAndGet() <= 5) System.out.println("mcopt-lod: disk feature failed in chunk " + chunkX + "," + chunkZ + ": " + e);
				}
			}
			s.groundFeature = false;
		}
		for (int idx = wanted.nextSetBit(0); idx >= 0; idx = wanted.nextSetBit(idx + 1)) {
			random.setFeatureSeed(decoration, idx, VEG);
			s.treeFeature = this.tree[idx];
			try {
				placer.placeWithBiomeCheck(step.features().get(idx), random, origin);
			} catch (RuntimeException e) {
				if (this.failures.incrementAndGet() <= 5) {
					var id = this.level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getKey(step.features().get(idx));
					System.out.println("mcopt-lod: tree feature " + id + " failed in chunk " + chunkX + "," + chunkZ + ": " + e);
				}
			}
		}
		int[] out = new int[s.placed.size() * 4];
		int n = 0;
		for (var e : s.placed.entrySet()) {
			long p = e.getKey();
			out[n++] = BlockPos.getX(p);
			out[n++] = BlockPos.getY(p);
			out[n++] = BlockPos.getZ(p);
			out[n++] = Block.getId(e.getValue()) | (s.fromTree.contains(p) ? FROM_TREE : 0) | (s.fromGround.contains(p) ? FROM_GROUND : 0)
				| (s.fromStruct.contains(p) ? FROM_STRUCT : 0);
		}
		this.runs.incrementAndGet();
		this.runNanos.addAndGet(System.nanoTime() - start);
		return new Trees(out);
	}

	/**
	 * Adds the trees of the tile's chunks and of the ring of chunks around them to a tile of level 0 or 1 (-Dmcopt.lod.treeLevels):
	 * per column (per cell: the blocks of its 2 x 2 columns at level 1, where at least two of them must hold a tree), a trunk
	 * or low growth standing on the ground raises the column; a crown over air is a floating canopy (canopyLo..canopyHi).
	 * The ground a tree changed (podzol, dirt under trunks) becomes the column's surface at level 0.
	 */
	void plant(LodTile t) {
		if (t.level >= LodConfig.TREE_LEVELS || t.level > 1 || this.step == null) return;
		int L = t.level, span = t.size << L;
		int c0x = (t.x0 >> 4) - 1, c0z = (t.z0 >> 4) - 1, c1x = ((t.x0 + span) >> 4), c1z = ((t.z0 + span) >> 4);
		Stand bounds = this.stands.get();
		bounds.bounded = true;
		bounds.minCx = c0x;
		bounds.minCz = c0z;
		bounds.maxCx = c1x;
		bounds.maxCz = c1z;
		try {
			this.plant0(t, L, span, c0x, c0z, c1x, c1z);
		} finally {
			bounds.bounded = false;
		}
	}

	private void plant0(LodTile t, int L, int span, int c0x, int c0z, int c1x, int c1z) {
		int n = t.cells();
		if (L == 0) t.treeBlocks = new BlockState[n][];
		int[] lo = new int[n], hi = new int[n], cols = new int[n];
		// the blocks a tree fills, up from the ground (bit k: ground + k), for the crown's runs (a spruce's tiers)
		long[] occ = new long[n];
		BlockState[] topState = new BlockState[n];
		boolean[] standing = new boolean[n];
		// level 0: plants standing on the ground, up to 3 blocks (k: ground + k)
		BlockState[] plantAt = L == 0 && LodConfig.PLANTS ? new BlockState[n * 3] : null;
		// level 1: which of a cell's 4 columns hold a tree block (bit per column)
		java.util.Arrays.fill(lo, Integer.MAX_VALUE);
		java.util.Arrays.fill(hi, Integer.MIN_VALUE);
		for (int cz = c0z; cz <= c1z; cz++) {
			for (int cx = c0x; cx <= c1x; cx++) {
				int[] b = this.trees(cx, cz).blocks();
				for (int k = 0; k < b.length; k += 4) {
					int bx = b[k] - t.x0, y = b[k + 1], bz = b[k + 2] - t.z0;
					if (bx < 0 || bz < 0 || bx >= span || bz >= span) continue;
					int i = (bz >> L) * t.size + (bx >> L);
					BlockState st = Block.stateById(b[k + 3] & ~(FROM_TREE | FROM_GROUND | FROM_STRUCT));
					boolean fromTree = (b[k + 3] & FROM_TREE) != 0, fromGround = (b[k + 3] & FROM_GROUND) != 0, fromStruct = (b[k + 3] & FROM_STRUCT) != 0;
					if (st.isAir()) continue;
					int g = t.ground[i];
					if (fromGround) {
						// (fix21) a disk's block: the surface or the soil under it takes its material
						if (L == 0 && y == g - 1) t.state[i] = st;
						else if (L == 0 && y == g - 2) t.under[i] = st;
						continue;
					}
					if (L == 0 && y < g) {
						// the ground itself changed (podzol, rooted dirt, mud under mangroves; a village's paths and floors)
						if (y == g - 1 && (fromTree || fromStruct && st.isSolidRender())) t.state[i] = st;
						continue;
					}
					if (fromStruct && y >= g) {
						// (fix21) a structure's block: solid ones stand as the column's walls (the lowest one seen) up to its top block
						var shape = st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
						if (!st.getFluidState().isEmpty() || shape.isEmpty() || shape.max(net.minecraft.core.Direction.Axis.Y) < 0.5) continue;
						if (y < lo[i] || t.trunk[i] == null) t.trunk[i] = st;
					}
					if (y < g) continue;
					if (L == 0 && fromTree && y <= this.level.getMaxY()) {
						int offset = y - g;
						BlockState[] blocks = t.treeBlocks[i];
						if (blocks == null) blocks = new BlockState[Math.max(16, offset + 1)];
						else if (offset >= blocks.length) blocks = java.util.Arrays.copyOf(blocks, Math.max(blocks.length * 2, offset + 1));
						blocks[offset] = st;
						t.treeBlocks[i] = blocks;
					}
					if (!fromTree) {
						// a plant (crossed quads) on the ground; solid growth (cacti, pumpkins, melons) stands like a tree; flat
						// growth (petals, leaf litter) isn't seen from afar
						if (LodColors.look(st).cross() && st.getFluidState().isEmpty()) {
							if (plantAt != null && y - g < 3) plantAt[i * 3 + (y - g)] = st;
							continue;
						}
						var shape = st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
						if (shape.isEmpty() || shape.max(net.minecraft.core.Direction.Axis.Y) < 0.5) continue;
					}
					cols[i] |= 1 << (((bz & L) << 1) | (bx & L));
					if (y - g < 64) occ[i] |= 1L << (y - g);
					if (st.is(BlockTags.LOGS)) t.trunk[i] = st;
					if (y == g) standing[i] = true;
					if (y < lo[i]) lo[i] = y;
					if (y > hi[i]) {
						hi[i] = y;
						topState[i] = st;
					}
				}
			}
		}
		if (plantAt != null) {
			for (int i = 0; i < n; i++) {
				if (plantAt[i * 3] == null || standing[i] || t.water[i] != LodTile.DRY && t.water[i] > t.ground[i]) continue;
				int blocks = 1;
				while (blocks < 3 && plantAt[i * 3 + blocks] != null) blocks++;
				t.plantLower[i] = plantAt[i * 3];
				t.plantUpper[i] = blocks > 1 ? plantAt[i * 3 + blocks - 1] : null;
				t.plantBlocks[i] = (byte) blocks;
			}
		}
		for (int i = 0; i < n; i++) {
			if (hi[i] == Integer.MIN_VALUE || L == 1 && Integer.bitCount(cols[i]) < 2) continue;
			t.canopyLo[i] = (short) (standing[i] ? t.ground[i] : lo[i]);
			t.canopyHi[i] = (short) hi[i];
			t.canopyState[i] = topState[i];
			t.standing[i] = standing[i] || lo[i] <= t.ground[i];
			if (!t.standing[i] && occ[i] != 0) t.crownRuns[i] = LodTile.runs(occ[i] >>> Long.numberOfTrailingZeros(occ[i]));
		}
	}

	/** The stand-in level a chunk's trees grow in: its ground from the far terrain, what the trees set in a map. */
	private final class Stand implements InvocationHandler {
		final HashMap<Long, BlockState> placed = new HashMap<>();
		/** (fix13) The chunks whose ground may be made while planting a tile (else only known grounds are used). */
		boolean bounded;
		int minCx, minCz, maxCx, maxCz;
		/** Blocks the tree features set (the rest: plant features). */
		final java.util.HashSet<Long> fromTree = new java.util.HashSet<>();
		boolean treeFeature;
		/** (fix21) Blocks the disk features set. */
		final java.util.HashSet<Long> fromGround = new java.util.HashSet<>();
		boolean groundFeature;
		/** (fix21) Blocks the structures' pieces set. */
		final java.util.HashSet<Long> fromStruct = new java.util.HashSet<>();
		boolean structFeature;
		/** Per column (x, z packed), per heightmap type: the highest block set that the heightmap counts (heightmaps rise with them). */
		final HashMap<Long, int[]> tops = new HashMap<>();
		final WorldGenLevel proxy = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, this);
		final BiomeManager biomes = new BiomeManager(LodForest.this.noise.resolver(), LodForest.this.biomeSeed);
		final RandomSource random = RandomSource.create(0L);
		private final java.util.Set<String> unknown = new java.util.HashSet<>();

		void reset() {
			this.placed.clear();
			this.fromTree.clear();
			this.fromGround.clear();
			this.fromStruct.clear();
			this.tops.clear();
			this.scratch = null;
		}

		private net.minecraft.world.level.chunk.@Nullable ProtoChunk scratch;

		net.minecraft.world.level.chunk.ProtoChunk scratch() {
			if (this.scratch == null) {
				this.scratch = new net.minecraft.world.level.chunk.ProtoChunk(net.minecraft.world.level.ChunkPos.ZERO, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
					LodForest.this.level, LodForest.this.level.palettedContainerFactory(), null);
			}
			return this.scratch;
		}

		BlockState state(int x, int y, int z) {
			BlockState p = this.placed.get(BlockPos.asLong(x, y, z));
			if (p != null) return p;
			Ground g = LodForest.this.groundNear(x >> 4, z >> 4, this);
			if (g == null) return Blocks.AIR.defaultBlockState();
			int i = (z & 15) * 16 + (x & 15), h = g.height()[i];
			if (y < h) {
				if (y == h - 1) return g.state()[i];
				if (y >= h - 4) {
					BlockState s = g.state()[i];
					return s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK) ? Blocks.DIRT.defaultBlockState() : s;
				}
				return Blocks.STONE.defaultBlockState();
			}
			if (g.wet()[i] && y < LodForest.this.noise.seaLevel) return Blocks.WATER.defaultBlockState();
			return Blocks.AIR.defaultBlockState();
		}

		int height(Heightmap.Types type, int x, int z) {
			Ground g = LodForest.this.groundNear(x >> 4, z >> 4, this);
			// (unknown ground: nothing to stand on there, below the world)
			if (g == null) return LodForest.this.level.getMinY();
			int i = (z & 15) * 16 + (x & 15), h = g.height()[i];
			int[] top = this.tops.get(BlockPos.asLong(x, 0, z));
			int placedTop = top == null || top[type.ordinal()] == Integer.MIN_VALUE ? Integer.MIN_VALUE : top[type.ordinal()] + 1;
			int water = g.wet()[i] ? Math.max(h, LodForest.this.noise.seaLevel) : h;
			return switch (type) {
				case OCEAN_FLOOR, OCEAN_FLOOR_WG -> Math.max(h, placedTop);
				default -> Math.max(water, placedTop);
			};
		}

		@Override
		public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
			String name = m.getName();
			int n = a == null ? 0 : a.length;
			switch (name) {
				case "getBlockState":
					if (n == 1) {
						BlockPos p = (BlockPos) a[0];
						return this.state(p.getX(), p.getY(), p.getZ());
					}
					break;
				case "getFluidState":
					if (n == 1) {
						BlockPos p = (BlockPos) a[0];
						return this.state(p.getX(), p.getY(), p.getZ()).getFluidState();
					}
					break;
				case "isStateAtPosition":
					if (n == 2) {
						BlockPos p = (BlockPos) a[0];
						@SuppressWarnings("unchecked")
						Predicate<BlockState> pred = (Predicate<BlockState>) a[1];
						return pred.test(this.state(p.getX(), p.getY(), p.getZ()));
					}
					break;
				case "isFluidAtPosition":
					if (n == 2) {
						BlockPos p = (BlockPos) a[0];
						@SuppressWarnings("unchecked")
						Predicate<FluidState> pred = (Predicate<FluidState>) a[1];
						return pred.test(this.state(p.getX(), p.getY(), p.getZ()).getFluidState());
					}
					break;
				case "setBlock":
					if (n >= 2) {
						BlockPos p = (BlockPos) a[0];
						BlockState st = (BlockState) a[1];
						this.placed.put(p.asLong(), st);
						if (this.treeFeature) this.fromTree.add(p.asLong());
						else this.fromTree.remove(p.asLong());
						if (this.groundFeature) this.fromGround.add(p.asLong());
						else this.fromGround.remove(p.asLong());
						if (this.structFeature) this.fromStruct.add(p.asLong());
						else this.fromStruct.remove(p.asLong());
						if (!st.isAir()) {
							int[] top = this.tops.computeIfAbsent(BlockPos.asLong(p.getX(), 0, p.getZ()), k -> {
								int[] none = new int[Heightmap.Types.values().length];
								java.util.Arrays.fill(none, Integer.MIN_VALUE);
								return none;
							});
							for (Heightmap.Types t : Heightmap.Types.values()) if (t.isOpaque().test(st)) top[t.ordinal()] = Math.max(top[t.ordinal()], p.getY());
						}
						return true;
					}
					break;
				case "removeBlock":
				case "destroyBlock":
					if (n >= 1) {
						BlockPos p = (BlockPos) a[0];
						this.placed.put(p.asLong(), Blocks.AIR.defaultBlockState());
						return true;
					}
					break;
				case "getHeight":
					if (n == 3) return this.height((Heightmap.Types) a[0], (Integer) a[1], (Integer) a[2]);
					if (n == 0) return LodForest.this.level.getHeight();
					break;
				case "getMinY":
					return LodForest.this.level.getMinY();
				case "getBiome":
					if (n == 1) return this.biomes.getBiome((BlockPos) a[0]);
					break;
				case "getSeed":
					return LodForest.this.seed;
				case "getLevel":
					return LodForest.this.level;
				case "registryAccess":
					return LodForest.this.level.registryAccess();
				case "getRandom":
					return this.random;
				case "getBlockEntity":
					if (n == 1) return null;
					break;
				case "getChunk":
					// a feature marking blocks for post-processing (fallen trees, multiface growth): a scratch chunk takes the marks
					return this.scratch();
				case "getLightEngine":
					return net.minecraft.world.level.lighting.LevelLightEngine.EMPTY;
				case "ensureCanWrite":
				case "hasChunk":
				case "isAreaLoaded":
					return true;
				case "setCurrentlyGenerating":
				case "scheduleTick":
				case "blockUpdated":
				case "updateNeighborsAt":
				case "playSound":
				case "levelEvent":
				case "gameEvent":
				case "addParticle":
					return null;
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == a[0];
				case "toString":
					return "LodForest.Stand";
				default:
					break;
			}
			if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, a);
			if (this.unknown.add(name + "/" + n)) System.out.println("mcopt-lod: tree stand-in level asked " + name + "/" + n + " (answering a default)");
			Class<?> r = m.getReturnType();
			if (r == boolean.class) return false;
			if (r == int.class) return 0;
			if (r == long.class) return 0L;
			if (r == float.class) return 0.0F;
			if (r == double.class) return 0.0;
			if (r == java.util.Optional.class) return java.util.Optional.empty();
			if (r == List.class) return new ArrayList<>();
			return null;
		}
	}
}
