package mcopt.metal.lod;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction;

/**
 * The far terrain (MetaLODS levels 2 and up): the world's own final density with its underground caves left out. Every
 * reference to an underground cave function (minecraft:overworld/caves/..., the noodles, spaghetti, pillars) becomes a constant
 * that never carves; the cave entrances, which open the surface, are kept, and the range choice on sloped_cheese that switches deep ground over to the cave branch keeps its surface branch
 * everywhere. What is left is the land's shape as the datapack makes it, Terralith's extra terrain (arches, spikes, floating
 * islands) and its cliff carving included, at a fraction of the cost: the caves are most of the final density's noise, and a
 * far surface with cave mouths punched in it only flickers. Levels 0-1 (next to the real terrain) keep the whole router.
 */
final class LodFarTerrain {
	/** What a left-out cave function becomes: well above any density the land reaches (min(land, it) = land). */
	private static final DensityFunction NO_CAVE = new ConstantFunction(64.0F);

	private LodFarTerrain() {
	}

	/** The function without its caves; count[0]: how many parts were left out (0: nothing cave-like found). */
	static DensityFunction withoutCaves(DensityFunction f, int[] count) {
		Map<DensityFunction, DensityFunction> memo = new IdentityHashMap<>();
		DfRewriteRule[] rule = new DfRewriteRule[1];
		rule[0] = g -> rewrite(g, rule[0], memo, count);
		return rule[0].rewrite(f);
	}

	private static DensityFunction rewrite(DensityFunction f, DfRewriteRule self, Map<DensityFunction, DensityFunction> memo, int[] count) {
		DensityFunction done = memo.get(f);
		if (done != null) return done;
		DensityFunction out;
		// (fix11b: cave entrances stay: they are the openings and ravine-like cuts you see in the surface, Jack 2026-11-17)
		if (f instanceof DensityFunctions.HolderHolder h && path(h).contains("caves/") && !path(h).endsWith("entrances")) {
			out = NO_CAVE;
			count[0]++;
		} else if (f instanceof RangeChoiceFunction rc && slopedCheese(rc.input())) {
			out = self.rewrite(rc.whenInRange());
			count[0]++;
		} else {
			out = f.rewriteChildren(self);
			// min(x, no cave) is x (and the game warns when compiling a min of two ranges that never meet)
			if (out instanceof BinaryFunction b && b.type() == BinaryFunction.Type.MIN) {
				if (b.right() == NO_CAVE) out = b.left();
				else if (b.left() == NO_CAVE) out = b.right();
			}
		}
		memo.put(f, out);
		return out;
	}

	private static boolean slopedCheese(DensityFunction f) {
		return f instanceof DensityFunctions.HolderHolder h && path(h).endsWith("sloped_cheese");
	}

	private static String path(DensityFunctions.HolderHolder h) {
		try {
			return h.function().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
		} catch (RuntimeException e) {
			return "";
		}
	}
}
