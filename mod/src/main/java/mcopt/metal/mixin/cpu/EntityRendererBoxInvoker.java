package mcopt.metal.mixin.cpu;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntityRenderer.class)
public interface EntityRendererBoxInvoker {
	@Invoker("getBoundingBoxForCulling")
	AABB mcopt$box(Entity entity, float partialTicks);
}
