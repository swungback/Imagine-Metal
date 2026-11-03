package imaginemetal.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import imaginemetal.Bloom;
import imaginemetal.Hdr;
import imaginemetal.ImagineFrame;
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
		Hdr.begin();
	}

	/** (In case the level's passes threw before the resolve: the main target gets its own colour back.) */
	@Inject(method = "render", at = @At("RETURN"))
	private void imaginemetal$hdrEnd(CallbackInfo ci) {
		Hdr.end();
	}

	/** The shadow map, after the frame's values and before the level's passes (the frame graph) draw anything. */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
	private void imaginemetal$shadows(CallbackInfo ci) {
		Shadows.render();
	}

	/** Bloom and the HDR resolve, once the level's passes have drawn the world (the hand and the HUD come after, so they don't glow). */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V", shift = At.Shift.AFTER))
	private void imaginemetal$bloom(CallbackInfo ci) {
		Bloom.render();
	}
}
