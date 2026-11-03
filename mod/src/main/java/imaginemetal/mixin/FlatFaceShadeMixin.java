package imaginemetal.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import imaginemetal.Engine;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chunks mesh without vanilla's directional face shade (sides 0.6-0.8, bottoms 0.5 baked into every vertex): our lighting shades
 * faces by their direction itself, and with both the sides and undersides of blocks were darkened twice.
 */
@Mixin(RenderSectionRegion.class)
abstract class FlatFaceShadeMixin {
	@Unique
	private static volatile CardinalLighting imaginemetal$flat;

	@ModifyReturnValue(method = "cardinalLighting", at = @At("RETURN"))
	private CardinalLighting imaginemetal$flatten(CardinalLighting lighting) {
		if (!Engine.TERRAIN) return lighting;
		float up = lighting.up();
		CardinalLighting flat = imaginemetal$flat;
		if (flat == null || flat.up() != up) imaginemetal$flat = flat = new CardinalLighting(up, up, up, up, up, up);
		return flat;
	}
}
