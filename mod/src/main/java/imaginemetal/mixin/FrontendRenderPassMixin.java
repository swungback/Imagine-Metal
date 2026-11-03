package imaginemetal.mixin;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import imaginemetal.Hdr;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * setPipeline checks that every colour attachment's format is the pipeline's declared one. The HDR image (imaginemetal.Hdr) stands
 * in for the 8-bit main image while the level draws, and the backend draws with each pipeline's 16-bit twin there, so to this check
 * it is the format it replaces.
 */
@Mixin(FrontendRenderPass.class)
abstract class FrontendRenderPassMixin {
	@Redirect(method = "setPipeline", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/textures/GpuTexture;getFormat()Lcom/mojang/renderpearl/api/GpuFormat;"))
	private GpuFormat imaginemetal$hdrFormat(GpuTexture texture) {
		GpuFormat format = texture.getFormat();
		return format == GpuFormat.RGBA16_FLOAT && Hdr.isHdrImage(texture) ? GpuFormat.RGBA8_UNORM : format;
	}
}
