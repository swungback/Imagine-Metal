package mcopt.metal.mixin.compat;

import mcopt.metal.compat.ChunkWaitGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Polymer's predictive mining casts a ray from every player's eyes on every server tick. Over a chunk the server has not made
 * yet, that ray loaded the chunk synchronously: the integrated server's thread waited for its generation and the whole server
 * stood still (the 18 Nov cold-teleport profile: ticks 34 s behind, chunks already generated not sent for ~9 s). There is
 * nothing to mine in a chunk that doesn't exist yet: the ray misses until the chunk is there. Optional: Polymer without it is
 * left as is.
 */
@Mixin(targets = "eu.pb4.polymer.core.impl.PolymerImplUtils", remap = false)
abstract class PolymerPredictMixin {
	@Inject(method = "clientCorrectedBlockClip", at = @At("HEAD"), cancellable = true, require = 0)
	private static void mcopt$noChunkWait(ServerPlayer player, boolean flag, CallbackInfoReturnable<BlockHitResult> cir) {
		BlockHitResult miss = ChunkWaitGuard.missIfUnloaded(player);
		if (miss != null) cir.setReturnValue(miss);
	}
}
