package imaginemetal.mixin;

import imaginemetal.metalods.MetaLods;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MetaLODS' progress line over the HUD while LODs are being made (not with the HUD hidden, F1). */
@Mixin(Hud.class)
abstract class MetaLodsHudMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void imaginemetal$metaLodsProgress(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (((Hud) (Object) this).isHidden()) return;
		try {
			MetaLods.hud(graphics);
		} catch (Throwable t) {
			// (never the HUD's problem)
		}
	}
}
