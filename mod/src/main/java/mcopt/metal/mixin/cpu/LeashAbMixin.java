package mcopt.metal.mixin.cpu;

import mcopt.metal.cpu.Ab;
import mcopt.metal.cpu.Cpu;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dmcopt.cpu.leash=ab: times the frame's entity pass with the leash shortcut alternately off and on (per frame). */
@Mixin(LevelExtractor.class)
abstract class LeashAbMixin {
	@Unique private static final Ab mcopt$ab = new Ab("leash.extractVisibleEntities");

	@Inject(method = "extractVisibleEntities", at = @At("HEAD"))
	private void mcopt$begin(CallbackInfo ci) {
		if (Cpu.LEASH_AB) Cpu.leashActive = mcopt$ab.begin();
	}

	@Inject(method = "extractVisibleEntities", at = @At("RETURN"))
	private void mcopt$end(CallbackInfo ci) {
		if (Cpu.LEASH_AB) mcopt$ab.end();
	}
}
