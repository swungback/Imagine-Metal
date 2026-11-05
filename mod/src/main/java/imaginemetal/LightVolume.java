package imaginemetal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import mcopt.metal.MetalBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Coloured block light: a grid of SX x SY x SZ voxels (one a block) around the player holding the light sources and the blocks
 * that stop light (seeds), spread on the GPU one block a frame the way Minecraft spreads light (light.metal, imlight.m). The terrain takes block light's hue from it
 * (lighting.metal imBlockLightColor); its brightness stays Minecraft's own light level.
 *
 * <p>The seeds come from the section compiles, which visit every block anyway (mcopt's OwnMaterials hooks): per section a bit for
 * every block that stops light and a list of its light sources, kept here by section. The grid follows the player a section at a
 * time; then it's refilled from those, and each recompiled section inside it is rewritten in place.
 */
public final class LightVolume {
	/** The grid's size in voxels: 8 x 4 x 8 sections. */
	public static final int SX = 128, SY = 64, SZ = 128;

	/** One section's light data: blocks that stop light (a bit each, index y << 8 | z << 4 | x) and its light sources. */
	private record SectionLight(long[] opaque, int[] sources) {
	}

	// ---- compile threads ----

	private static final class Collector {
		final long[] opaque = new long[64];
		final int[] sources = new int[4096 * 2];
		int count;
		boolean active;
		int sx, sy, sz;
		boolean placed;
		final java.util.ArrayList<int[]> portals = new java.util.ArrayList<>();
	}

	/** End portal blocks by section (world block coordinates), for the End portal's rays (portalCentre). */
	private static final ConcurrentHashMap<Long, int[][]> PORTALS = new ConcurrentHashMap<>();

	/**
	 * The middle of the end portal nearest the camera (its blocks within 2 of the nearest one), camera-relative, or null when none
	 * is within 40 blocks.
	 */
	static float[] portalCentre(double cx, double cy, double cz) {
		int[] best = null;
		double bd = 40.0 * 40.0;
		for (int[][] list : PORTALS.values())
			for (int[] b : list) {
				double dx = b[0] + 0.5 - cx, dy = b[1] + 0.5 - cy, dz = b[2] + 0.5 - cz, d = dx * dx + dy * dy + dz * dz;
				if (d < bd) {
					bd = d;
					best = b;
				}
			}
		if (best == null) return null;
		double sx = 0, sy = 0, sz = 0;
		int n = 0;
		for (int[][] list : PORTALS.values())
			for (int[] b : list)
				if (Math.abs(b[0] - best[0]) <= 2 && Math.abs(b[2] - best[2]) <= 2 && b[1] == best[1]) {
					sx += b[0];
					sy += b[1];
					sz += b[2];
					n++;
				}
		return new float[] {(float) (sx / n + 0.5 - cx), (float) (sy / n + 0.5 - cy), (float) (sz / n + 0.5 - cz)};
	}

	private static final ThreadLocal<Collector> COLLECTOR = ThreadLocal.withInitial(Collector::new);
	private static final ConcurrentHashMap<Long, SectionLight> SECTIONS = new ConcurrentHashMap<>();
	private static final ConcurrentLinkedQueue<Long> DIRTY = new ConcurrentLinkedQueue<>();

	private LightVolume() {
	}

	private static long key(int sx, int sy, int sz) {
		return ((long) sx & 0x3FFFFF) << 42 | ((long) sy & 0xFFFFF) << 22 | (long) sz & 0x3FFFFF;
	}

	/** A section compile starts (true) or ends (false) on this thread. */
	public static void compiling(boolean on) {
		Collector c = COLLECTOR.get();
		if (on) {
			java.util.Arrays.fill(c.opaque, 0L);
			c.count = 0;
			c.placed = false;
			c.active = true;
			c.portals.clear();
			return;
		}
		if (!c.active) return;
		c.active = false;
		if (!c.placed) return;
		long k = key(c.sx, c.sy, c.sz);
		if (c.portals.isEmpty()) PORTALS.remove(k);
		else PORTALS.put(k, c.portals.toArray(new int[0][]));
		SECTIONS.put(k, new SectionLight(c.opaque.clone(), java.util.Arrays.copyOf(c.sources, c.count)));
		DIRTY.add(k);
	}

	/** The compile visits the block at world (x, y, z). */
	public static void block(int x, int y, int z, BlockState state) {
		Collector c = COLLECTOR.get();
		if (!c.active) return;
		if (!c.placed) {
			c.sx = x >> 4;
			c.sy = y >> 4;
			c.sz = z >> 4;
			c.placed = true;
		}
		if (state.isAir()) return;
		if (state.getBlock() == net.minecraft.world.level.block.Blocks.END_PORTAL) c.portals.add(new int[] {x, y, z});
		int i = (y & 15) << 8 | (z & 15) << 4 | x & 15;
		if (state.isSolidRender()) c.opaque[i >> 6] |= 1L << (i & 63);
		int light = state.getLightEmission(), glass;
		if (light > 0 && c.count + 2 <= c.sources.length) {
			float[] col = LightColors.of(state);
			int r = Math.round(col[0] * 255f), g = Math.round(col[1] * 255f), b = Math.round(col[2] * 255f);
			c.sources[c.count++] = i;
			// 4 levels stronger than Minecraft's: coloured lights read clearly (a redstone torch lights like an 11)
			c.sources[c.count++] = r | g << 8 | b << 16 | Math.min(light + 4, 15) << 24;
		} else if (light == 0 && c.count + 2 <= c.sources.length && (glass = LightColors.glass(state)) != 0) {
			// (stained glass: light passing through takes its colour)
			c.sources[c.count++] = i;
			c.sources[c.count++] = glass | 64 << 24;
		} else if (light == 0 && c.count + 2 <= c.sources.length) {
			// (Reimagined's extra lights: glowing ores, the redstone and lapis blocks)
			float[] e = LightColors.extra(state);
			if (e != null) {
				c.sources[c.count++] = i;
				c.sources[c.count++] = Math.round(e[0] * 255f) | Math.round(e[1] * 255f) << 8 | Math.round(e[2] * 255f) << 16 | (int) e[3] << 24;
			}
		}
	}

	// ---- render thread ----

	private static long state;
	private static boolean failed, have;
	private static int ox, oy, oz, lastX, lastY, lastZ;
	private static Object level;
	private static ByteBuffer seeds, section;

	/** The grid's low corner (world blocks) and whether it holds anything this frame, as LightVolume.prepare decided. */
	static int originX() {
		return ox;
	}

	static int originY() {
		return oy;
	}

	static int originZ() {
		return oz;
	}

	static boolean valid() {
		return Settings.coloredLight && Engine.TERRAIN && have && !failed;
	}

	/** At the frame's start (ImagineFrame): where the grid sits this frame (the player's section in its middle). */
	static void prepare() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != level) {
			level = mc.level;
			SECTIONS.clear();
			DIRTY.clear();
			have = false;
		}
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		ox = (((int) Math.floor(cam.x)) >> 4) * 16 - SX / 2;
		oy = (((int) Math.floor(cam.y)) >> 4) * 16 - SY / 2;
		oz = (((int) Math.floor(cam.z)) >> 4) * 16 - SZ / 2;
	}

	/** Before the level's passes: the seeds brought up to date and one spread step on the GPU. */
	public static void render() {
		if (!Settings.coloredLight || !Engine.TERRAIN || failed) return;
		long t0 = Perf.start();
		try {
			renderTimed();
		} finally {
			Perf.end(Perf.LIGHT, t0);
		}
	}

	private static void renderTimed() {
		try {
			Object encoder = MetalBridge.encoder(((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend());
			if (encoder == null) return;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long err = stack.ncalloc(1, 1024, 1);
				if (state == 0) {
					var source = MemoryUtil.memUTF8(Engine.resource("/imaginemetal/shaders/light.metal"));
					try {
						state = ImNative.lightNew(MetalBridge.ctx(encoder), MemoryUtil.memAddress(source), SX, SY, SZ, err, 1024);
					} finally {
						MemoryUtil.memFree(source);
					}
					if (state == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
					seeds = MemoryUtil.memAlloc(SX * SY * SZ * 4);
					section = MemoryUtil.memAlloc(16 * 16 * 16 * 4);
					System.out.println("[Imagine Metal] coloured light on (" + SX + "x" + SY + "x" + SZ + ")");
				}
			}
			int dx = ox - lastX, dy = oy - lastY, dz = oz - lastZ;
			boolean moved = !have || dx != 0 || dy != 0 || dz != 0;
			if (moved) {
				fillAll();
				ImNative.lightUpload(state, 0, 0, 0, SX, SY, SZ, MemoryUtil.memAddress(seeds));
				DIRTY.clear();
			} else {
				Long k;
				int budget = 64;  // sections rewritten a frame (a burst of recompiles spreads over a few frames)
				while (budget-- > 0 && (k = DIRTY.poll()) != null) rewrite(k);
			}
			boolean reset = !have || Math.abs(dx) >= SX || Math.abs(dy) >= SY || Math.abs(dz) >= SZ;
			ImNative.lightStep(MetalBridge.enc(encoder), state, dx, dy, dz, reset);
			lastX = ox;
			lastY = oy;
			lastZ = oz;
			have = true;
		} catch (Throwable t) {
			failed = true;
			System.out.println("[Imagine Metal] coloured light off: " + t);
		}
	}

	private static void fillAll() {
		MemoryUtil.memSet(MemoryUtil.memAddress(seeds), 0, (long) SX * SY * SZ * 4);
		long base = MemoryUtil.memAddress(seeds);
		for (int cz = 0; cz < SZ / 16; cz++) {
			for (int cy = 0; cy < SY / 16; cy++) {
				for (int cx = 0; cx < SX / 16; cx++) {
					SectionLight s = SECTIONS.get(key((ox >> 4) + cx, (oy >> 4) + cy, (oz >> 4) + cz));
					if (s == null) continue;
					write(s, base, cx * 16, cy * 16, cz * 16, SX, SY);
				}
			}
		}
		// (sections far behind are dropped, so the map doesn't grow without end)
		if (SECTIONS.size() > 24000) {  // (generous: a section dropped here only comes back when it recompiles)
			int mx = ox >> 4, my = oy >> 4, mz = oz >> 4;
			SECTIONS.keySet().removeIf(k -> {
				int sx = (int) (k << 0 >> 42), sy = (int) (k << 22 >> 44), sz = (int) (k << 42 >> 42);
				return Math.abs(sx - mx) > 24 || Math.abs(sy - my) > 12 || Math.abs(sz - mz) > 24;
			});
		}
	}

	/** One recompiled section, if it's inside the grid: its 16^3 voxels rewritten. */
	private static void rewrite(long k) {
		int sx = (int) (k >> 42), sy = (int) (k << 22 >> 44), sz = (int) (k << 42 >> 42);
		int cx = sx - (ox >> 4), cy = sy - (oy >> 4), cz = sz - (oz >> 4);
		if (cx < 0 || cy < 0 || cz < 0 || cx >= SX / 16 || cy >= SY / 16 || cz >= SZ / 16) return;
		SectionLight s = SECTIONS.get(k);
		if (s == null) return;
		long base = MemoryUtil.memAddress(section);
		MemoryUtil.memSet(base, 0, 16 * 16 * 16 * 4);
		write(s, base, 0, 0, 0, 16, 16);
		ImNative.lightUpload(state, cx * 16, cy * 16, cz * 16, 16, 16, 16, base);
		// (and the full copy, so a later full refill agrees)
		long full = MemoryUtil.memAddress(seeds);
		for (int z = 0; z < 16; z++) {
			for (int y = 0; y < 16; y++) {
				MemoryUtil.memCopy(base + ((long) (z * 16 + y) * 16) * 4, full + ((long) ((cz * 16 + z) * SY + cy * 16 + y) * SX + cx * 16) * 4, 16 * 4);
			}
		}
	}

	/** s's voxels into the box at base (w x h x ..., 4 bytes a voxel) with the section's corner at (x0, y0, z0). */
	private static void write(SectionLight s, long base, int x0, int y0, int z0, int w, int h) {
		long[] op = s.opaque();
		for (int word = 0; word < 64; word++) {
			long bits = op[word];
			while (bits != 0) {
				int b = Long.numberOfTrailingZeros(bits);
				bits &= bits - 1;
				int i = word << 6 | b;
				int x = i & 15, z = i >> 4 & 15, y = i >> 8;
				MemoryUtil.memPutByte(base + ((long) ((z0 + z) * h + y0 + y) * w + x0 + x) * 4 + 3, (byte) 128);
			}
		}
		int[] src = s.sources();
		for (int n = 0; n + 1 < src.length; n += 2) {
			int i = src[n], rgb = src[n + 1];
			int x = i & 15, z = i >> 4 & 15, y = i >> 8;
			long a = base + ((long) ((z0 + z) * h + y0 + y) * w + x0 + x) * 4;
			MemoryUtil.memPutByte(a, (byte) (rgb & 255));
			MemoryUtil.memPutByte(a + 1, (byte) (rgb >> 8 & 255));
			MemoryUtil.memPutByte(a + 2, (byte) (rgb >> 16 & 255));
			// alpha: bit 7 stops light (kept from the opacity pass), bits 0-3 the light level
			MemoryUtil.memPutByte(a + 3, (byte) (MemoryUtil.memGetByte(a + 3) & 128 | rgb >>> 24 & 79));  // (79: bit 6 and the level)
		}
	}
}
