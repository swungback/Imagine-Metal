package imaginemetal.mixin;

import com.mojang.blaze3d.platform.Window;
import imaginemetal.Resolution;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * imaginemetal.retina=false: the game's framebuffer is the window's size in points instead of its Retina pixels, so everything
 * draws at a quarter of the pixels and the Mac scales the image up. Both ways the size reaches the game: the query (startup,
 * fullscreen changes) and the resize event. The target is the window's own size, so applying it twice changes nothing.
 */
@Mixin(Window.class)
abstract class WindowMixin {
	@Inject(method = "queryFramebufferSize", at = @At("RETURN"), cancellable = true)
	private void imaginemetal$size(CallbackInfoReturnable<Window.FramebufferSize> cir) {
		if (Resolution.RETINA) return;
		Window w = (Window) (Object) this;
		Window.FramebufferSize s = cir.getReturnValue();
		int sw = w.getScreenWidth(), sh = w.getScreenHeight();
		if (sw <= 0 || sh <= 0) return;
		sw = Resolution.scaled(sw);
		sh = Resolution.scaled(sh);
		if (s.width() > sw || s.height() > sh) cir.setReturnValue(new Window.FramebufferSize(Math.min(s.width(), sw), Math.min(s.height(), sh)));
	}

	@Inject(method = "onFramebufferResize", at = @At("HEAD"), cancellable = true)
	private void imaginemetal$resize(int newWidth, int newHeight, CallbackInfo ci) {
		if (Resolution.RETINA) return;
		Window w = (Window) (Object) this;
		int sw = w.getScreenWidth(), sh = w.getScreenHeight();
		if (sw <= 0 || sh <= 0) return;
		sw = Resolution.scaled(sw);
		sh = Resolution.scaled(sh);
		if (newWidth <= sw && newHeight <= sh) return;
		ci.cancel();
		((WindowAccessor) (Object) this).imaginemetal$onFramebufferResize(Math.min(newWidth, sw), Math.min(newHeight, sh));
	}
}
