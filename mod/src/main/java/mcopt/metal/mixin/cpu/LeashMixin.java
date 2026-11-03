package mcopt.metal.mixin.cpu;

import mcopt.metal.cpu.Cpu;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * -Dmcopt.cpu.leash=true: EntityRenderer.shouldRender asks every entity that fails the frustum test for its leash holder.
 * Leashable.getLeashHolder starts with getLeashData() through the interface (a megamorphic itable dispatch across mob classes)
 * and returns null when that is null. For a Mob the same getLeashData() is asked through the class first; null gives null,
 * exactly as getLeashHolder would, anything else goes to getLeashHolder as before.
 */
@Mixin(EntityRenderer.class)
abstract class LeashMixin {
	@Redirect(method = "shouldRender", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/Leashable;getLeashHolder()Lnet/minecraft/world/entity/Entity;"))
	private Entity mcopt$leashHolder(Leashable leashable) {
		if (Cpu.leashActive && leashable instanceof Mob mob && mob.getLeashData() == null) return null;
		return leashable.getLeashHolder();
	}
}
