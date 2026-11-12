package imaginemetal.mixin;

import imaginemetal.metalods.MetaLods;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MetaLODS: chunks the client loads, and unloads with the player's changes, become LODs with their real blocks. */
@Mixin(ClientLevel.class)
abstract class MetaLodsClientLevelMixin {
	@Inject(method = "onChunkLoaded", at = @At("TAIL"))
	private void imaginemetal$metaLodsLoaded(ChunkPos pos, CallbackInfo ci) {
		MetaLods.chunkLoaded((ClientLevel) (Object) this, pos);
	}

	@Inject(method = "unload", at = @At("HEAD"))
	private void imaginemetal$metaLodsUnloaded(LevelChunk chunk, CallbackInfo ci) {
		MetaLods.chunkUnloaded((ClientLevel) (Object) this, chunk);
	}
}
