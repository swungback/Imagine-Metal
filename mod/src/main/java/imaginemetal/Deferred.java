package imaginemetal;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Build B2: deferred lighting in tile memory. The opaque terrain writes what the lighting needs into four attachments that live
 * only in the GPU's tile memory (terrain.metal own_fs_g), and one draw in the same pass lights each visible pixel once
 * (im_defer_fs), instead of every fragment of every small triangle lighting itself. Apple GPUs only, which is all this mod runs
 * on. The native side (mcown.m) switches the pass over at the first opaque terrain draw and back when the terrain is done.
 *
 * <p>Off (the terrain lights its own fragments, as before Build B2): imaginemetal.deferred=false, a debug view, the shaders
 * switched off live, a PBR resource pack in use (its maps are read per fragment), no HDR image, or the lighting pipeline could
 * not be made.
 */
public final class Deferred {
	private static boolean failed, saidOn, saidOff;
	private static int frames;

	private Deferred() {
	}

	static boolean wanted() {
		return Settings.deferred && Engine.TERRAIN && !failed && Settings.debug == 0 && !Live.isOff() && !(PbrAtlas.on && Settings.pbrPack) && Hdr.active();
	}

	/** (OwnTerrain.drawOpaque, before its draws) own: the terrain's native handle; clip: this frame's camera-relative world to clip. */
	public static void frame(long own, Matrix4fc clip) {
		if (!Engine.TERRAIN || own == 0) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			boolean on = wanted() && Math.abs(clip.determinant()) > 1e-12f;
			long m = stack.ncalloc(4, 16, 4);
			if (on) new Matrix4f(clip).invert().get(MemoryUtil.memFloatBuffer(m, 16));
			if (ImNative.defer(own, on ? 1 : 0, m) != 0 && !saidOff) {
				saidOff = true;
				failed = true;
				System.out.println("[Imagine Metal] deferred lighting off: its pipeline could not be made (forward shading as before)");
			}
			if (on && !saidOn && ++frames == 20) {
				long st = stack.ncalloc(8, 3, 8);
				ImNative.deferStats(st);
				if (MemoryUtil.memGetLong(st) > 0 && MemoryUtil.memGetLong(st + 16) == 0) {
					saidOn = true;
					System.out.println("[Imagine Metal] deferred lighting on (the opaque terrain lit once per pixel, in tile memory)");
				} else {
					frames = 0;
				}
			}
		} catch (Throwable t) {
			failed = true;
			System.out.println("[Imagine Metal] deferred lighting off: " + t);
		}
	}

	/** (OwnTerrain.drawOpaque's end) true: the pass was switched back, the caller binds the game's pass state again. */
	public static boolean end(long enc) {
		if (!Engine.TERRAIN || enc == 0) return false;
		// (MetaLODS: the far terrain into the G-buffer while its pass is still open, lit with the rest)
		imaginemetal.metalods.MetaLods.drawInG(enc);
		try {
			return ImNative.deferEnd(enc) == 1;
		} catch (Throwable t) {
			return false;
		}
	}
}
