package imaginemetal.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import imaginemetal.WorldLabels;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SubmitNodeCollection.class)
abstract class WorldTextSubmitMixin {
	@ModifyReturnValue(method = "nameTag", at = @At("RETURN"))
	private static TextFeatureRenderer.Submit imaginemetal$markNameTag(TextFeatureRenderer.Submit text) {
		WorldLabels.mark(text);
		return text;
	}

	@WrapOperation(method = "submitTextPart", at = @At(value = "NEW",
		target = "(Lorg/joml/Matrix4fc;Lnet/minecraft/client/gui/Font$DisplayMode;ILnet/minecraft/client/renderer/feature/TextFeatureRenderer$Content;)Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Submit;"))
	private TextFeatureRenderer.Submit imaginemetal$markDisplay(Matrix4fc pose, Font.DisplayMode mode, int light, TextFeatureRenderer.Content content,
		Operation<TextFeatureRenderer.Submit> original) {
		var text = original.call(pose, mode, light, content);
		if (WorldLabels.submittingDisplay()) WorldLabels.mark(text);
		return text;
	}
}
