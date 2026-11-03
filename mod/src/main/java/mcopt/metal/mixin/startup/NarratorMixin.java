package mcopt.metal.mixin.startup;

import com.mojang.text2speech.Narrator;
import mcopt.metal.LazyNarrator;
import net.minecraft.client.GameNarrator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** -Dmcopt.startup.narrator=lazy: GameNarrator gets a narrator that is created on its first use. */
@Mixin(GameNarrator.class)
abstract class NarratorMixin {
	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/text2speech/Narrator;getNarrator()Lcom/mojang/text2speech/Narrator;"))
	private static Narrator mcopt$lazy() {
		return new LazyNarrator();
	}
}
