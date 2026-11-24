package imaginemetal.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import imaginemetal.WorldLabels;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DisplayRenderer.TextDisplayRenderer.class)
abstract class TextDisplayRendererMixin {
	@WrapMethod(method = "submitInner(Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V")
	private void imaginemetal$trackFloatingText(TextDisplayEntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
		int light, float yaw, Operation<Void> original) {
		boolean previous = WorldLabels.submittingDisplay();
		WorldLabels.submittingDisplay(true);
		try {
			original.call(state, pose, collector, light, yaw);
		} finally {
			WorldLabels.submittingDisplay(previous);
		}
	}
}
