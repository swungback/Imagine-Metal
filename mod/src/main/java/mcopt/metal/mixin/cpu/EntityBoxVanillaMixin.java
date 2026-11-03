package mcopt.metal.mixin.cpu;

import mcopt.metal.cpu.EntityBox;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** -Dmcopt.cpu.entityBox: vanilla's frustum test reuses the box Sodium just computed (see EntityBox). */
@Mixin(EntityRenderer.class)
abstract class EntityBoxVanillaMixin {
	@Redirect(method = "shouldRender", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;getBoundingBoxForCulling(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/world/phys/AABB;"))
	private AABB mcopt$reuse(EntityRenderer<?, ?> self, Entity entity, float partialTicks) {
		AABB b = EntityBox.take(self, entity, partialTicks);
		return b != null ? b : ((EntityRendererBoxInvoker) self).mcopt$box(entity, partialTicks);
	}
}
