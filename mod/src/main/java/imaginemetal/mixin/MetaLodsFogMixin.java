package imaginemetal.mixin;

import imaginemetal.metalods.MetaLods;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** MetaLODS: the fog as the game would set it for a render distance reaching the LODs' edge (in air only). */
@Mixin(value = FogRenderer.class, priority = 500)
abstract class MetaLodsFogMixin {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void imaginemetal$metaLodsFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level,
		CallbackInfoReturnable<FogData> cir) {
		FogType fluid = camera.getFluidInCamera();
		if (fluid == FogType.NONE) MetaLods.adjustFog(cir.getReturnValue(), FogType.ATMOSPHERIC);
	}
}
