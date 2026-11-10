package imaginemetal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import mcopt.metal.own.OwnTerrain;

/**
 * Which of the terrain's shading features are on (lighting.metal's function constants 40-53): the fragment stages are specialized
 * with them (mcown.m mco_im_features), so a feature switched off is not compiled in at all and costs nothing, instead of a branch the
 * shader still carries (registers, texture reads). Read from the settings every frame (ImagineFrame); a change makes the stages
 * again (the first time a set is used its pipelines compile: a short hitch; they're kept after that).
 */
final class ShadeFeatures {
	private static int current = -1;
	private static boolean failed;
	private static final MemorySegment ERR = Arena.global().allocate(1024);

	private ShadeFeatures() {
	}

	/** The set the settings ask for now (bit i = function constant 40 + i; the order of lighting.metal's imFc*). */
	static int wanted() {
		int m = 0;
		if (Settings.shadows) m |= 1;
		if (Settings.pbr) m |= 1 << 1;
		if (Settings.normals > 0f) m |= 1 << 2;
		if (Settings.pbrPack) m |= 1 << 3;
		if (Settings.parallax) m |= 1 << 4;
		if (Settings.coated) m |= 1 << 5;
		if (Settings.mobShadows) m |= 1 << 6;
		if (Settings.glassSun) m |= 1 << 7;
		if (Settings.coloredLight) m |= 1 << 8;
		if (Settings.heldLight) m |= 1 << 9;
		if (Settings.sky) m |= 1 << 10;
		if (Settings.wet) m |= 1 << 11;
		if (Settings.glassReflections) m |= 1 << 12;
		if (Settings.portals) m |= 1 << 13;
		return m;
	}

	/** Once a frame, before the terrain draws. */
	static void update() {
		if (failed) return;
		int m = wanted();
		if (m == current) return;
		try {
			OwnTerrain own = OwnTerrain.get();
			int rc = ImNative.shadeFeatures(own != null ? own.ownHandle() : 0L, m, ERR.address(), 1024);
			if (rc < 0) {
				failed = true;
				System.out.println("[Imagine Metal] shading features stay all on: " + ERR.getString(0));
				return;
			}
			if (current != -1 || rc > 0) System.out.println("[Imagine Metal] shading features: " + describe(m));
			current = m;
		} catch (Throwable t) {
			failed = true;
			System.out.println("[Imagine Metal] shading features stay all on: " + t);
		}
	}

	private static final String[] NAMES = {"shadows", "pbr", "normals", "pbr pack", "parallax", "coated", "mob shadows", "glass sun",
		"coloured light", "held light", "sky/air", "wet", "glass reflections", "portals"};

	static String describe(int m) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < NAMES.length; i++) if ((m >> i & 1) == 0) b.append(b.length() == 0 ? "compiled out: " : ", ").append(NAMES[i]);
		return b.length() == 0 ? "all compiled in" : b.toString();
	}
}
