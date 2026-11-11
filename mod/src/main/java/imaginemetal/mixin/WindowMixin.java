package imaginemetal.mixin;

import com.mojang.blaze3d.platform.Window;
import imaginemetal.Resolution;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * imaginemetal.retina=false: the game's framebuffer is smaller than the window's Retina pixels (Resolution.target: 1080 lines by
 * default, or the window's size in points) and the Mac scales the image up. Both ways the size reaches the game: the query
 * (startup, fullscreen changes) and the resize event. Applying the target twice changes nothing.
 */
@Mixin(Window.class)
abstract class WindowMixin {
	@Inject(method = "queryFramebufferSize", at = @At("RETURN"), cancellable = true)
	private void imaginemetal$size(CallbackInfoReturnable<Window.FramebufferSize> cir) {
		if (Resolution.RETINA) return;
		Window w = (Window) (Object) this;
		Window.FramebufferSize s = cir.getReturnValue();
		int[] t = Resolution.target(w.getScreenWidth(), w.getScreenHeight(), s.width(), s.height());
		if (t != null) cir.setReturnValue(new Window.FramebufferSize(t[0], t[1]));
	}

	@Inject(method = "onFramebufferResize", at = @At("HEAD"), cancellable = true)
	private void imaginemetal$resize(int newWidth, int newHeight, CallbackInfo ci) {
		if (Resolution.RETINA) return;
		Window w = (Window) (Object) this;
		int[] t = Resolution.target(w.getScreenWidth(), w.getScreenHeight(), newWidth, newHeight);
		if (t == null) return;
		ci.cancel();
		((WindowAccessor) (Object) this).imaginemetal$onFramebufferResize(t[0], t[1]);
	}
}
