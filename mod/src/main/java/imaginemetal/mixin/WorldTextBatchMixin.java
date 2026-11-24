package imaginemetal.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import imaginemetal.WorldLabels;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(RenderTypeFeatureRenderer.class)
abstract class WorldTextBatchMixin<Submit extends SubmitNode> {
	@WrapMethod(method = "prepareGroup")
	@SuppressWarnings("unchecked")
	private void imaginemetal$splitWorldLabels(FeatureFrameContext context, List<Submit> submits, boolean ordered, Operation<Void> original) {
		if (WorldLabels.splitting(this)) {
			WorldLabels.prepare(context, (List<TextFeatureRenderer.Submit>) (List<?>) submits, ordered,
				world -> original.call(context, world, ordered));
		} else {
			original.call(context, submits, ordered);
		}
	}
}
