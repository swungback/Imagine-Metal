package imaginemetal.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import imaginemetal.Bloom;
import imaginemetal.Reflections;
import imaginemetal.Hdr;
import imaginemetal.ImagineFrame;
import imaginemetal.LightVolume;
import imaginemetal.PassTimes;
import imaginemetal.Shadows;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The engine's per-frame values, the shadow map before the level's passes, bloom after them. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void imaginemetal$frame(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState,
		GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
		ImagineFrame.update(cameraState);
		imaginemetal.Benchmark.frame();
		imaginemetal.Live.poll();  // (F8: shaders off/on without a restart)
		// (the Nether: the picture is cleared to its haze, so the void past the land matches the fogged land at its edge)
		ImagineFrame.netherClear(fogColor);
		Hdr.begin();
	}

	/** (In case the level's passes threw before the resolve: the main target gets its own colour back.) */
	@Inject(method = "render", at = @At("RETURN"))
	private void imaginemetal$hdrEnd(CallbackInfo ci) {
		Hdr.end();
		PassTimes.tag(PassTimes.HUD);  // (the hand and the GUI come after the level)
	}

	// ---- the per-pass GPU timing (PassTimes): which of the level's steps the game's passes draw (require = 0: timing only) ----

	@Inject(method = "lambda$addSkyPass$0", at = @At("HEAD"), require = 0)
	private void imaginemetal$tagSky(CallbackInfo ci) {
		PassTimes.tag(PassTimes.SKY);
	}

	@Inject(method = "executeSolid", at = @At("HEAD"), require = 0)
	private void imaginemetal$tagTerrain(CallbackInfo ci) {
		PassTimes.tag(PassTimes.TERRAIN);
	}

	@Inject(method = "executeSolid", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"),
		require = 0)
	private void imaginemetal$tagEntities(CallbackInfo ci) {
		PassTimes.tag(PassTimes.ENTITIES);
	}

	@Inject(method = "executeClassicTransparency", at = @At("HEAD"), require = 0)
	private void imaginemetal$tagTranslucent(CallbackInfo ci) {
		PassTimes.tag(PassTimes.TRANSLUCENT);
	}

	@Inject(method = "executeOit", at = @At("HEAD"), require = 0)
	private void imaginemetal$tagOit(CallbackInfo ci) {
		PassTimes.tag(PassTimes.TRANSLUCENT);
	}

	@Inject(method = "executeOutline", at = @At("HEAD"), require = 0)
	private void imaginemetal$tagOverlay(CallbackInfo ci) {
		PassTimes.tag(PassTimes.OVERLAY);
	}

	/** After the main pass: the frame graph's other passes (clouds, weather, the world border). */
	@Inject(method = "lambda$addMainPass$0", at = @At("RETURN"), require = 0)
	private void imaginemetal$tagAfterMain(CallbackInfo ci) {
		PassTimes.tag(PassTimes.WEATHER);
	}

	/** The shadow map, after the frame's values and before the level's passes (the frame graph) draw anything. */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
	private void imaginemetal$shadows(CallbackInfo ci) {
		Shadows.render();
		LightVolume.render();
	}

	/** Bloom and the HDR resolve, once the level's passes have drawn the world (the hand and the HUD come after, so they don't glow). */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V", shift = At.Shift.AFTER))
	private void imaginemetal$bloom(CallbackInfo ci) {
		imaginemetal.Sky.lateEnd();  // (Build C1: the clouds drawn after the opaque world: did that happen this frame?)
		Reflections.capture();
		imaginemetal.Sky.drawEndLate();
		Bloom.render();
	}
}
