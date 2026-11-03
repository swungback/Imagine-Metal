package mcopt.metal.mixin.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import mcopt.metal.chunk.LightSnapshots;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** C3 (light=true|verify): the sky-light map (its per-column top-section map is still cloned, as vanilla)'s copy() builds a base+delta snapshot instead of cloning (LightSnapshots). */
@Mixin(targets = "net.minecraft.world.level.lighting.SkyLightSectionStorage$SkyDataLayerStorageMap")
abstract class SkyLightMapMixin {
	@Redirect(method = "copy", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;clone()Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;"))
	private Long2ObjectOpenHashMap<DataLayer> mcopt$noClone(Long2ObjectOpenHashMap<DataLayer> map) {
		return new mcopt.metal.chunk.LayeredLightMap();
	}

	@Inject(method = "copy", at = @At("RETURN"))
	private void mcopt$snapshot(CallbackInfoReturnable<Object> cir) {
		((LightSnapshots) this).mcopt$snapshotInto(cir.getReturnValue());
	}
}
