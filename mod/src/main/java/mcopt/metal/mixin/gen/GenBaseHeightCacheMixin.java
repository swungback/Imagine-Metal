package mcopt.metal.mixin.gen;

import java.util.function.Supplier;
import mcopt.metal.lod.LodBaseHeights;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * (s1119) LodBaseHeights: a column's ground height kept while a LOD worker works out structure starts. (s1120) A miss fills the
 * whole noise cell's columns at once (LodBaseHeights.fill); verify mode lets the game's own walk run and compares.
 */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class GenBaseHeightCacheMixin {
	@Shadow @Final private Holder<NoiseGeneratorSettings> settings;
	@Shadow @Final private Supplier<Aquifer.FluidPicker> globalFluidPicker;

	@Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
	private void mcopt$cachedHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random, CallbackInfoReturnable<Integer> cir) {
		LodBaseHeights c = LodBaseHeights.active();
		if (c == null) return;
		int v = c.get(x, z, type.ordinal(), random, level.getMinY(), level.getHeight());
		if (v != Integer.MIN_VALUE) {
			cir.setReturnValue(v);
			return;
		}
		if (LodBaseHeights.BATCH <= 1) {
			c.walkStart = System.nanoTime();
			return;
		}
		int b = c.fill(this.settings, this.globalFluidPicker, x, z, type, level, random);
		if (c.verifyNext()) {
			c.expected = b;
			c.walkStart = System.nanoTime();
			return;
		}
		cir.setReturnValue(b);
	}

	@Inject(method = "getBaseHeight", at = @At("RETURN"))
	private void mcopt$keepHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random, CallbackInfoReturnable<Integer> cir) {
		LodBaseHeights c = LodBaseHeights.active();
		if (c == null) return;
		c.verifyDone(x, z, cir.getReturnValueI());
		c.put(x, z, type.ordinal(), cir.getReturnValueI());
	}
}
