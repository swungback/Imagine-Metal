package mcopt.metal.mixin.cpu;

import mcopt.metal.cpu.Ab;
import mcopt.metal.cpu.EntityBox;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dmcopt.cpu.entityBox=ab: times the frame's entity pass with the box reuse alternately off and on (see Ab). */
@Mixin(LevelExtractor.class)
abstract class EntityBoxAbMixin {
	@Unique private static final Ab mcopt$ab = new Ab("entityBox.extractVisibleEntities");

	@Inject(method = "extractVisibleEntities", at = @At("HEAD"))
	private void mcopt$begin(CallbackInfo ci) {
		if (EntityBox.AB) EntityBox.active = mcopt$ab.begin();
	}

	@Inject(method = "extractVisibleEntities", at = @At("RETURN"))
	private void mcopt$end(CallbackInfo ci) {
		if (EntityBox.AB) mcopt$ab.end();
	}
}
