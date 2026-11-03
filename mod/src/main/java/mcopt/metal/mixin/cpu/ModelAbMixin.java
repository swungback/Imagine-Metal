package mcopt.metal.mixin.cpu;

import mcopt.metal.cpu.Ab;
import mcopt.metal.cpu.Cpu;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** -Dmcopt.cpu.model=ab: times FeatureRenderDispatcher.prepareFrame (where models are built) with the lever alternately off/on. */
@Mixin(FeatureRenderDispatcher.class)
abstract class ModelAbMixin {
	@Unique private static final Ab mcopt$ab = new Ab("model.prepareFrame");

	@Inject(method = "prepareFrame", at = @At("HEAD"))
	private void mcopt$begin(CallbackInfoReturnable<?> cir) {
		if (Cpu.MODEL_AB) Cpu.modelActive = mcopt$ab.begin();
	}

	@Inject(method = "prepareFrame", at = @At("RETURN"))
	private void mcopt$end(CallbackInfoReturnable<?> cir) {
		if (Cpu.MODEL_AB) mcopt$ab.end();
	}
}
