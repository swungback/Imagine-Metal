package mcopt.metal.mixin.compat;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Each compat mixin applies only when the mod it is for is loaded (its target classes are that mod's). */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".DhRenderApiMixin")) return FabricLoader.getInstance().isModLoaded("distanthorizons");
		if (mixinClassName.endsWith(".PolymerPredictMixin")) return FabricLoader.getInstance().isModLoaded("polymer-core");
		if (mixinClassName.endsWith(".C2mePriorityMixin")) return FabricLoader.getInstance().isModLoaded("c2me-base") && !"off".equalsIgnoreCase(System.getProperty("mcopt.gen.worldgenQos", "default").trim());
		return false;
	}

	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
