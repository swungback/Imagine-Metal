package imaginemetal.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** imaginemetal.Hdr swaps the main target's colour texture for the HDR one (and, with a render scale, its depth) while the level draws. */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
	@Accessor("colorTexture")
	void imaginemetal$setColorTexture(GpuTexture texture);

	@Accessor("colorTextureView")
	void imaginemetal$setColorTextureView(GpuTextureView view);

	// (the world's render scale: the depth swapped as well, for one of the HDR image's size)
	@Accessor("depthTexture")
	void imaginemetal$setDepthTexture(GpuTexture texture);

	@Accessor("depthTextureView")
	void imaginemetal$setDepthTextureView(GpuTextureView view);
}
