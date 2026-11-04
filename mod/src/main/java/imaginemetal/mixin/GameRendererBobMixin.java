package imaginemetal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import imaginemetal.Sky;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The view bobbing and hurt tilt exactly as Minecraft builds them this frame: the pose they leave on the stack Minecraft then
 * multiplies into the level's projection. Our sky takes the same (Sky.skyRot), so it moves with the land.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererBobMixin {
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void imaginemetal$bobReset(CallbackInfo ci) {
		Sky.noteBob(null);
	}

	@Inject(method = "bobHurt", at = @At("TAIL"))
	private void imaginemetal$hurt(CameraRenderState camera, PoseStack pose, CallbackInfo ci) {
		Sky.noteBob(pose.last().pose());
	}

	@Inject(method = "bobView", at = @At("TAIL"))
	private void imaginemetal$bob(CameraRenderState camera, PoseStack pose, CallbackInfo ci) {
		Sky.noteBob(pose.last().pose());
	}
}
