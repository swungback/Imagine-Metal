package imaginemetal.mixin;

import imaginemetal.PbrAtlas;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** When the block atlas is (re)built: the resource packs' PBR maps laid out the same way (PbrAtlas). */
@Mixin(TextureAtlas.class)
abstract class TextureAtlasMixin {
	@Inject(method = "upload", at = @At("TAIL"))
	private void imaginemetal$pbr(SpriteLoader.Preparations prep, CallbackInfo ci) {
		TextureAtlas self = (TextureAtlas) (Object) this;
		if (TextureAtlas.LOCATION_BLOCKS.equals(self.location())) PbrAtlas.build(prep.width(), prep.height(), prep.regions());
	}
}
