package imaginemetal.metalods;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import mcopt.metal.MetalBridge;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.storage.LevelResource;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * MetaLODS: far terrain as voxel LODs, in the spirit of Voxy (whose levels of 2^L-block voxels, mipped by the most solid
 * child, and coarse-to-fine column choice it follows), natively on Metal and lit by Imagine Metal's own deferred lighting.
 * Terrain never visited comes from the world seed (mcopt's generator, as SeedGen does it for Voxy and Distant Horizons);
 * every real chunk seen replaces it.
 *
 * Hooks: OwnTerrain.drawOpaque gives the frame's camera (frame); Deferred.end, while the G-buffer's pass is still open,
 * draws (drawInG); the camera's far plane and the fog reach out to the LODs (reach, adjustFog); chunks loaded and unloaded
 * by the client (chunkLoaded, chunkUnloaded).
 */
public final class MetaLods {
	/** (fix21) The width (blocks) of the band where the real terrain dissolves into the LODs (MetaWorld.EDGE_BAND). */
	public static final float EDGE_BAND = Math.clamp(Integer.getInteger("metalods.edgeBand", 48), 16, 64);

	/**
	 * (fix19) Imagine Metal's Benchmark steps for the far terrain's GPU cost (Benchmark.start, put back by its restore): 0 as
	 * always, 1 the LODs' shading skipped (same pixels, plain colour: what ml_fs's body costs), 2 vertex work only (nothing
	 * rasterized: what the quads cost before any pixel), 3 not drawn at all (what MetaLODS costs the frame).
	 */
	public static volatile int probe;

	private static final int ARENA_PAGES = 98304;  // 216 MB of quads (25 million; 65536 filled up at 512 chunks, detail 10)
	private static boolean failed, saidNoG, saidDrawn;
	private static long lib;
	private static @Nullable MetaArena arena;
	private static @Nullable MetaWorld world;
	private static @Nullable Object worldLevel;
	private static final Matrix4f clip = new Matrix4f();
	private static double camX, camY, camZ;
	private static long atlas;
	private static int renderDistance = 8;
    private static int preparedPages = -1;
	private static boolean haveFrame;
	private static final ArrayDeque<long[]> pendingLoads = new ArrayDeque<>();
	// Coalesce edits per chunk; snapshot after lighting settles, with bounded work each frame.
	private static final LinkedHashMap<Long, Long> pendingEdits = new LinkedHashMap<>();

	private MetaLods() {
	}

	public static void init() {
		MetaSettings.load();
		System.out.println("[MetaLODS] " + (MetaSettings.enabled ? "on" : "off") + ", " + MetaSettings.distance + " chunks, detail " + MetaSettings.detail + ", far detail " + MetaSettings.farDetail
			+ (MetaSettings.generate ? ", unexplored terrain from the seed" : ""));
	}

	/** Whether MetaLODS draws in the current level (the far plane and fog reach out only then). */
	public static boolean active() {
		return MetaSettings.enabled && !failed && world != null && worldLevel == Minecraft.getInstance().level;
	}

	/** The reach in blocks while MetaLODS draws, else 0. */
	public static float reach() {
		return active() ? MetaSettings.distance * 16f : 0f;
	}

	/** (OwnTerrain.drawOpaque) this frame's camera-relative clip, camera and block atlas. */
	/** (fix19) the render thread's time in frame() (prepare, integrate, uploads), summed and the longest, for Imagine's Perf line. */
	public static long frameNanos, frameMaxNanos;

	/** (ColdTp) whether the real terrain draws this chunk whole this frame (the hand-over mask), render thread. */
	public static boolean realDrawn(int cx, int cz) {
		MetaWorld w = world;
		return w != null && w.drawnChunk(cx, cz);
	}

	public static void frame(Matrix4fc terrainClip, double x, double y, double z, long atlasHandle, int rd) {
		long fT0 = System.nanoTime();
		try {
			frame0(terrainClip, x, y, z, atlasHandle, rd);
		} finally {
			long fd = System.nanoTime() - fT0;
			frameNanos += fd;
			if (fd > frameMaxNanos) frameMaxNanos = fd;
		}
	}

	private static void frame0(Matrix4fc terrainClip, double x, double y, double z, long atlasHandle, int rd) {
		clip.set(terrainClip);
		camX = x;
		camY = y;
		camZ = z;
		atlas = atlasHandle;
		renderDistance = rd;
        haveFrame = true;
        preparedPages = -1;
        if (arena != null) MetaNative.chunkFade(0);
        imaginemetal.ImagineFrame.lodTransition(0);
        if (arena != null && active() && imaginemetal.Deferred.wanted() && atlas != 0) {
            try {
                MetaWorld w = ensureWorld();
                if (w != null) {
                    pumpChunks(w);
                    preparedPages = w.prepare(clip, camX, camY, camZ, renderDistance);
                    imaginemetal.ImagineFrame.lodTransition(w.transitionEnd);
                    MetaNative.chunkFade(w.chunkFadeBuffer());
                    if (imaginemetal.ColdTp.on()) imaginemetal.ColdTp.frame();
                }
            } catch (Throwable t) {
                preparedPages = -1;
                imaginemetal.ImagineFrame.lodTransition(0);
                System.out.println("[MetaLODS] early LOD preparation failed: " + t);
            }
        }
	}

	/** (Deferred.end, before the G-buffer is lit) the LODs into the G-buffer. */
	public static void drawInG(long enc) {
		if (!haveFrame || failed || !MetaSettings.enabled) return;
		haveFrame = false;
		try {
			if (!MetaNative.gOpen(enc)) {
				if (!saidNoG) {
					saidNoG = true;
					System.out.println("[MetaLODS] no G-buffer pass this frame (deferred lighting off?): the LODs need Imagine Metal's deferred lighting");
				}
				return;
			}
			if (lib == 0) {
				lib = MetaNative.create(enc, source());
				arena = new MetaArena(enc, ARENA_PAGES);
				System.out.println("[MetaLODS] ready (" + ARENA_PAGES + " pages of quads)");
			}
			MetaWorld w = ensureWorld();
			if (w == null) return;
			if (preparedPages < 0) pumpChunks(w);
			MetaArena a = arena;
			int pages = preparedPages >= 0 ? preparedPages : w.prepare(clip, camX, camY, camZ, renderDistance);
            if (preparedPages < 0) {
                // Near terrain already drew without fading on this first frame.
                w.transitionEnd = 0;
                org.lwjgl.system.MemoryUtil.memPutFloat(a.frame + 92, 0f);
            }
			MetaNative.heightmap(a.heightBuf, a.frame + 160);  // (fix7: far shadows, this frame's lighting draw)
			if (pages > 0 && atlas != 0) {
				int slot = w.slot();
				int drawn = 0;
				boolean waterPhase = false;
				try {
					for (int group = 0; group < MetaWorld.DRAW_GROUPS; group++) {
						int count = w.drawCount[group];
						if (count == 0) continue;
						boolean water = group >= MetaWorld.WATER_DRAW;
						if (water && !waterPhase) {
							MetaNative.waterPhase(enc, true);
							waterPhase = true;
						}
						boolean masked = water ? group == MetaWorld.WATER_DRAW : group < MetaArena.LAND_BUCKETS;
						drawn |= MetaNative.draw(lib, enc, a.frame, MetaArena.FRAME_BYTES, a.visBuf[slot], 4L * w.drawOffset[group],
							count, MetaWorld.quadCapacity(group), a.infoBuf, a.quadBuf, a.modelBuf, a.biomeBuf, a.mask,
							MetaArena.MASK_BYTES, atlas, masked, water);
					}
				} finally {
					if (waterPhase) MetaNative.waterPhase(enc, false);
				}
				if (drawn == 1 && !saidDrawn) {
					saidDrawn = true;
					System.out.println("[MetaLODS] first LODs drawn (" + pages + " pages)");
				}
			}
		} catch (Throwable t) {
			failed = true;
			System.out.println("[MetaLODS] stopped: " + t);
			t.printStackTrace(System.out);
		}
	}

	private static String source() throws IOException {
		try (InputStream in = MetaLods.class.getResourceAsStream("/imaginemetal/shaders/metalods.metal")) {
			if (in == null) throw new IOException("metalods.metal missing from the jar");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** Called at level detach, including the title screen where no terrain pass will run. */
	public static void levelChanged(@Nullable ClientLevel level) {
		if (level == worldLevel) return;
		if (world != null) world.close();
		world = null;
		worldLevel = null;
		haveFrame = false;
		synchronized (pendingLoads) { pendingLoads.clear(); }
		synchronized (pendingEdits) { pendingEdits.clear(); }
		if (arena != null) arena.resetHeights();
	}

	private static @Nullable MetaWorld ensureWorld() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level != worldLevel) {
			if (world != null) world.close();
			world = null;
			worldLevel = level;
			synchronized (pendingEdits) { pendingEdits.clear(); }
			if (level == null || arena == null) return null;
			if (level.dimensionType().hasCeiling()) {
				System.out.println("[MetaLODS] " + level.dimension().identifier() + " has a ceiling: no LODs there");
				return null;
			}
			String dim = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
			Path dir;
			ServerLevel server = null;
			var sp = mc.getSingleplayerServer();
			if (sp != null) {
				dir = sp.getWorldPath(LevelResource.ROOT).resolve("metalods").resolve(dim);
				server = sp.getLevel(level.dimension());
			} else {
				var sd = mc.getCurrentServer();
				String name = sd != null ? sd.ip : "unknown";
				dir = FabricLoader.getInstance().getGameDir().resolve("metalods").resolve("servers").resolve(name.replaceAll("[^A-Za-z0-9._-]", "_")).resolve(dim);
			}
			world = new MetaWorld(level, dir, arena, server);
		}
		return world;
	}

	// ---- chunks ----

	/** (ClientLevel.onChunkLoaded) taken a moment later, when its light is in. */
	public static void chunkLoaded(ClientLevel level, ChunkPos pos) {
		if (!MetaSettings.enabled || failed) return;
		synchronized (pendingLoads) {
			pendingLoads.add(new long[] {System.currentTimeMillis() + 2000L, pos.x(), pos.z()});
			if (pendingLoads.size() > 20000) pendingLoads.pollFirst();
		}
	}

	/** (ClientLevel.unload) the chunk with the player's changes. */
	public static void chunkUnloaded(ClientLevel level, LevelChunk chunk) {
		MetaWorld w = world;
		if (w == null || level != worldLevel || failed) return;
		try {
			MetaChunks.Snapshot s = MetaChunks.capture(level, chunk);
			if (s != null) w.ingest(s);
		} catch (Throwable t) {
			System.out.println("[MetaLODS] chunk capture: " + t);
		}
	}

	/** Client block updates must reach LODs even while the chunk stays loaded. */
	public static void blockChanged(ClientLevel level, net.minecraft.core.BlockPos pos) {
		if (!MetaSettings.enabled || failed || level != worldLevel) return;
		long key = MetaStore.key(pos.getX() >> 4, pos.getZ() >> 4);
		synchronized (pendingEdits) {
			// Move renewed edits to the end so the first entry is the next deadline.
			pendingEdits.remove(key);
			pendingEdits.put(key, System.nanoTime() + 150_000_000L);
		}
	}

	private static void pumpEdits(MetaWorld w, long deadline) {
		for (int i = 0; i < 4; i++) {
			long now = System.nanoTime();
			if (now >= deadline) return;
			long key;
			synchronized (pendingEdits) {
				var edits = pendingEdits.entrySet().iterator();
				if (!edits.hasNext()) return;
				var edit = edits.next();
				if (edit.getValue() > now) return;
				key = edit.getKey();
				edits.remove();
			}
			LevelChunk c = w.level.getChunkSource().getChunk((int) (key >> 32), (int) key, false);
			if (c == null) continue; // unloading already captures it
			MetaChunks.Snapshot s = MetaChunks.capture(w.level, c);
			if (s != null) w.ingest(s);
		}
	}

	/** Bound render-thread snapshot work and keep postponed requests queued. */
	private static void pumpChunks(MetaWorld w) {
		long deadline = System.nanoTime() + 1_000_000L;
		pumpEdits(w, deadline);
		long now = System.currentTimeMillis();
		ClientLevel level = w.level;
		for (int i = 0; i < 24 && System.nanoTime() < deadline; i++) {
			long[] e;
			synchronized (pendingLoads) {
				e = pendingLoads.peekFirst();
				if (e == null || e[0] > now) return;
				pendingLoads.pollFirst();
			}
			LevelChunk c = level.getChunkSource().getChunk((int) e[1], (int) e[2], false);
			if (c == null) continue;
			MetaChunks.Snapshot s = MetaChunks.capture(level, c);
			if (s != null) w.ingest(s);
		}
	}

	// ---- on screen ----

	/** (Hud.extractRenderState, MetaLodsHudMixin) while LODs are being made: chunks ready, left and the speed, top left. */
	public static void hud(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		if (!MetaSettings.progress || !active()) return;
		MetaWorld w = world;
		String line = w == null ? null : w.progressLine();
		if (line == null) return;
		Minecraft mc = Minecraft.getInstance();
		var font = mc.font;
		int width = font.width(line);
		// (top left; with the F3 screen up, top centre so it doesn't sit on its lines)
		int x = mc.getDebugOverlay().showDebugScreen() ? (g.guiWidth() - width) / 2 : 4, y = 4;
		g.fill(x - 2, y - 2, x + width + 2, y + 10, 0x90000000);
		g.text(font, line, x, y, 0xFFFFFFFF, false);
	}

	// ---- fog ----

	/** The fog when the LODs draw: as the game would set it for a render distance reaching their edge (mcopt's LOD does the same). */
	/**
	 * (the air pass, Reflections) MetaLODS' far fog this frame into out[0..1]: where it starts (blocks from the camera: the real
	 * terrain's edge) and its density (0: none).
	 */
	public static void farFog(float[] out) {
		out[0] = 0f;
		out[1] = 0f;
		if (!active() || MetaSettings.farFog <= 0f || reach() <= 0) return;
		out[0] = renderDistance * 16f;
		out[1] = MetaSettings.farFog;  // (fix5: the haze's strength; air.metal starts it at 24 blocks, over near land too)
	}

	public static void adjustFog(FogData fog, FogType type) {
		float reach = reach();
		if (reach <= 0 || type != FogType.ATMOSPHERIC) return;
		fog.renderDistanceStart = Math.max(fog.renderDistanceStart, reach - Math.clamp(reach / 10f, 4f, 64f));
		fog.renderDistanceEnd = Math.max(fog.renderDistanceEnd, reach);
		if (fog.environmentalEnd >= 512) {
			float scale = Math.max(1f, reach / 1024f);
			fog.environmentalStart *= scale;
			fog.environmentalEnd *= scale;
		}
		fog.skyEnd = Math.max(fog.skyEnd, reach);
	}

	// ---- from the settings screen ----

	static void settingsChanged(boolean remesh) {
		MetaWorld w = world;
		if (w != null && remesh) w.remeshAll();
		if (MetaSettings.enabled && failed) {
			failed = false;
			System.out.println("[MetaLODS] trying again");
		}
	}

	static void clearWorld() {
		MetaWorld w = world;
		if (w != null) w.clearAll();
	}

	static String status() {
		MetaWorld w = world;
		if (failed) return "stopped (see the log)";
		if (!MetaSettings.enabled) return "off";
		if (w == null) return "waiting for a world";
		return "on in " + w.level.dimension().identifier();
	}
}
