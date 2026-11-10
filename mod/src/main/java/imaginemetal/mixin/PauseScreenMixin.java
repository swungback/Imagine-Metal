package imaginemetal.mixin;

import imaginemetal.SettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An "Imagine Metal" button in the pause menu's top-left corner: our settings one click from Escape. */
@Mixin(PauseScreen.class)
abstract class PauseScreenMixin extends Screen {
	protected PauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("RETURN"))
	private void imaginemetal$button(CallbackInfo ci) {
		if (!((PauseScreen) (Object) this).showsPauseMenu()) return;
		Screen self = this;
		this.addRenderableWidget(Button.builder(Component.literal("Imagine Metal"), b -> Minecraft.getInstance().gui.setScreen(new SettingsScreen(self)))
			.bounds(6, 6, 100, 20).build());
		// (the shaders off and on without a restart: F8 in game does the same)
		this.addRenderableWidget(Button.builder(Component.literal("Shaders: " + (imaginemetal.Live.isOff() ? "off" : "on")), b -> {
			imaginemetal.Live.toggle();
			b.setMessage(Component.literal("Shaders: " + (imaginemetal.Live.isOff() ? "off" : "on")));
		}).bounds(6, 30, 100, 20).build());
	}
}
