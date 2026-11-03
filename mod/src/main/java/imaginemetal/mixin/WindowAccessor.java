package imaginemetal.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Window.class)
public interface WindowAccessor {
	@Invoker("onFramebufferResize")
	void imaginemetal$onFramebufferResize(int newWidth, int newHeight);
}
