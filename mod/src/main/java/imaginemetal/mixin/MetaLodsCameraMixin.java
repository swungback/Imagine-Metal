package imaginemetal.mixin;

import imaginemetal.metalods.MetaLods;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MetaLODS: the far plane reaches past the LODs (reversed Z on a float depth buffer: precision doesn't suffer). */
@Mixin(Camera.class)
abstract class MetaLodsCameraMixin {
	@Shadow private float depthFar;

	@Inject(method = "update", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;depthFar:F", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void imaginemetal$metaLodsFar(DeltaTracker deltaTracker, CallbackInfo ci) {
		float reach = MetaLods.reach();
		if (reach > 0) this.depthFar = Math.max(this.depthFar, reach * 1.5F + 1024.0F);
	}
}
