package imaginemetal.mixin;

import imaginemetal.Sky;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Our sky in place of Minecraft's flat disc, sunset fan and dark lower disc; its sun, moon and stars stay. */
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void imaginemetal$sky(com.mojang.renderpearl.api.buffers.GpuBufferSlice fog, SkyRenderState state, CallbackInfo ci) {
		Sky.draw(state);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void imaginemetal$clouds(com.mojang.renderpearl.api.buffers.GpuBufferSlice fog, SkyRenderState state, CallbackInfo ci) {
		Sky.drawAfter();
	}

	@Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
	private void imaginemetal$noDisc(CallbackInfo ci) {
		if (Sky.active) ci.cancel();
	}

	@Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
	private void imaginemetal$noFan(CallbackInfo ci) {
		if (Sky.active) ci.cancel();
	}

	@Inject(method = "renderEndSky", at = @At("HEAD"), cancellable = true)
	private void imaginemetal$noEndSky(CallbackInfo ci) {
		if (Sky.active) ci.cancel();  // (ours: Reimagined's End sky and ender stars)
	}

	@Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
	private void imaginemetal$noStars(CallbackInfo ci) {
		if (Sky.active && imaginemetal.Settings.stars == 1) ci.cancel();  // (ours, twinkling, in the sky pass)
	}

	@Inject(method = "renderDarkDisc", at = @At("HEAD"), cancellable = true)
	private void imaginemetal$noDarkDisc(CallbackInfo ci) {
		if (Sky.active) ci.cancel();
	}
}
