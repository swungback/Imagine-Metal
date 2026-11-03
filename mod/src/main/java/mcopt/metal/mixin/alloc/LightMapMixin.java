package mcopt.metal.mixin.alloc;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import mcopt.metal.alloc.CowLong2ObjectMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -Dmcopt.alloc.lightMap=true: the light engine publishes a read-only snapshot of its section map (DataLayerStorageMap.copy ->
 * map.clone()) after every batch of light changes; with tens of thousands of sections that clone was the single largest allocation
 * in a flight (Neo: ~74 MB/s on the server's light workers, ~11 MB/s on the client). With the map copy-on-write by bucket
 * (CowLong2ObjectMap) a publish costs O(256) and a later write copies only the bucket it touches; snapshots stay immutable.
 */
@Mixin(DataLayerStorageMap.class)
abstract class LightMapMixin {
	@Shadow @Final @Mutable protected Long2ObjectOpenHashMap<DataLayer> map;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void mcopt$cow(Long2ObjectOpenHashMap<DataLayer> given, CallbackInfo ci) {
		if (!(map instanceof CowLong2ObjectMap)) map = CowLong2ObjectMap.of(map);
	}
}
