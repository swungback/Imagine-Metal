package imaginemetal.mixin;

import imaginemetal.metalods.MetaLods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** A seed LOD is only a drawing. Keep gravity from carrying the player through missing real chunks. */
@Mixin(LocalPlayer.class)
abstract class MetaLodsLocalPlayerMixin {
	@ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
	private Vec3 imaginemetal$waitForCollisionChunk(Vec3 movement) {
		if (movement.y >= 0 || !MetaLods.active()) return movement;
		Minecraft mc = Minecraft.getInstance();
		if (!mc.hasSingleplayerServer() || mc.level == null) return movement;
		LocalPlayer player = (LocalPlayer) (Object) this;
		if (player.isSpectator() || player.getAbilities().flying) return movement;

		int x = SectionPos.blockToSectionCoord(player.getX() + movement.x);
		int z = SectionPos.blockToSectionCoord(player.getZ() + movement.z);
		ClientLevel level = mc.level;
		if (level.getChunkSource().getChunk(x, z, false) != null) return movement;

		Vec3 velocity = player.getDeltaMovement();
		if (velocity.y < 0) player.setDeltaMovement(velocity.x, 0, velocity.z);
		return new Vec3(movement.x, 0, movement.z);
	}
}
