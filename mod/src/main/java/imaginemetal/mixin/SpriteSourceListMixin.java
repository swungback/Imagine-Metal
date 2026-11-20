package imaginemetal.mixin;

import java.util.List;
import mcopt.metal.own.BushyLeafSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** (fix9) The blocks atlas gets the bushy leaf sprites (BushyLeafSource, Way Better Leaves' plus leaves) after the packs' sources. */
@Mixin(SpriteSourceList.class)
abstract class SpriteSourceListMixin {
	@Shadow
	@Final
	private List<SpriteSource> sources;

	@Inject(method = "load", at = @At("RETURN"))
	private static void imaginemetal$bushyLeaves(ResourceManager resourceManager, Identifier atlasId, CallbackInfoReturnable<SpriteSourceList> cir) {
		if (!Identifier.withDefaultNamespace("blocks").equals(atlasId)) return;
		try {
			((SpriteSourceListMixin) (Object) cir.getReturnValue()).sources.add(BushyLeafSource.INSTANCE);
		} catch (Throwable t) {
			System.out.println("[Imagine Metal] bushy leaves: atlas source not added (" + t + ")");
		}
	}
}
