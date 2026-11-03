package imaginemetal.mixin;

import imaginemetal.Clouds;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft's clouds: where and how they are this frame (for ours), and not drawn while ours are (Clouds). */
@Mixin(CloudRenderer.class)
abstract class CloudRendererMixin {
	@Inject(method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V", at = @At("HEAD"))
	private void imaginemetal$note(int color, CloudStatus status, float height, int range, Vec3 camera, long gameTime, float partial,
		CallbackInfo ci) {
		Clouds.note(status != CloudStatus.OFF, height, gameTime, partial);
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void imaginemetal$noClouds(CallbackInfo ci) {
		if (Clouds.drawn()) ci.cancel();
	}

	@Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
	private void imaginemetal$noCloudsOit(CallbackInfo ci) {
		if (Clouds.drawn()) ci.cancel();
	}
}
