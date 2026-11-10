package mcopt.metal.mixin.own;

import mcopt.metal.own.OwnSolid;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Solid-section occlusion: every section vanilla marks dirty is checked again (OwnSolid; nothing without -Dmcopt.own.solidOcc). */
@Mixin(LevelExtractor.class)
abstract class OwnSolidExtractorMixin {
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void mcopt$solidDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
		OwnSolid.dirty(sectionX, sectionY, sectionZ);
	}

	/**
	 * Leaving the level (setLevel(null)): vanilla only flags its reset for the next level's first frame, so our sections (and their
	 * arena pages) stayed resident on the title screen. They go now: every section released, the arena renewed (OwnTerrain.disposeAll;
	 * the reset at the next level then has nothing left to do).
	 */
	@Inject(method = "setLevel", at = @At("RETURN"))
	private void mcopt$ownLevelGone(net.minecraft.client.multiplayer.ClientLevel level, CallbackInfo ci) {
		// (Imagine Metal) and on a change of dimension too: the last dimension's sections stayed drawn where the new one had nothing
		// yet, floating pieces of the Overworld over the End's islands. The new level's sections are all compiled anew anyway.
		if (level != null && level == mcopt$lastLevel) return;
		mcopt$lastLevel = level;
		mcopt.metal.own.OwnTerrain own = mcopt.metal.own.OwnTerrain.get();
		if (own != null) own.disposeAll();
	}

	@org.spongepowered.asm.mixin.Unique
	private static net.minecraft.client.multiplayer.ClientLevel mcopt$lastLevel;
}
