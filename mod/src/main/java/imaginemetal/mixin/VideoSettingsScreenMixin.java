package imaginemetal.mixin;

import imaginemetal.SettingsScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An "Imagine Metal..." button at the end of Video Settings, opening our settings. */
@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
	private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At("TAIL"))
	private void imaginemetal$button(CallbackInfo ci) {
		Screen self = this;
		this.list.addHeader(Component.literal("Imagine Metal"));
		this.list.addBig(Button.builder(Component.literal("Imagine Metal settings..."), b -> this.minecraft.gui.setScreen(new SettingsScreen(self))).build());
	}
}
