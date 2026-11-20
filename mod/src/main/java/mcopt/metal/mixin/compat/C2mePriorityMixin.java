package mcopt.metal.mixin.compat;

import mcopt.metal.lod.LodGenQos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * C2ME puts its worker threads at a fixed Mach priority on macOS (client default preset LOWER: priority 23, below every
 * thread in the default QoS class at 31). A fixed priority also takes a thread out of the QoS system for good: setting a class
 * afterwards fails (EPERM). In the 18 Nov cold-teleport profile the six generation workers stood runnable yet got under one core
 * between them while MetaLODS, Streams, the Metal driver and other apps ran above them, and the chunks under the player came
 * 34-36 s after the teleport. On macOS the thread goes into the worldgen QoS class instead (-Dmcopt.gen.worldgenQos, default
 * default: 23 s); with -Dmcopt.gen.worldgenQos=off C2ME's own setting applies as before.
 */
@Mixin(targets = "com.ishland.c2me.base.common.threadpriority.bindings.MacOsPthread", remap = false)
abstract class C2mePriorityMixin {
	@Inject(method = "setCurrentThreadPriority", at = @At("HEAD"), cancellable = true, require = 0)
	private static void mcopt$qosInstead(int priority, CallbackInfo ci) {
		if (LodGenQos.WORLDGEN == 0) return;
		LodGenQos.once(LodGenQos.WORLDGEN, "chunk generation (C2ME, its priority " + priority + " not applied)");
		ci.cancel();
	}
}
