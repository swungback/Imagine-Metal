package mcopt.metal.mixin.gen;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * The generation experiments' mixins apply only with their flags: -Dmcopt.gen.worldgenQos (GenWorldgenQosMixin),
 * -Dmcopt.gen.nativeNoise (GenNoiseChunkMixin).
 * Without them the game is byte for byte what it is without these mixins. Reads the properties itself, so nothing of
 * mcopt.metal.lod loads before the game does.
 */
public final class GenMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply(); // before any flag is read
	}

	/**
	 * (18 Nov cold teleport) The integrated server's chunk generation threads now go into the default QoS class by default
	 * (C2ME's workers included, see compat.C2mePriorityMixin): C2ME had put them at a fixed priority below every default-class
	 * thread, and on a busy Mac (MetaLODS, Streams, the Metal driver, other apps) the six generation workers stood runnable
	 * yet got under one core between them while the chunks under the player were missing. User-initiated (the server thread's
	 * class) was tried: no faster, and 1.2-2.4 s frame stalls while the chunks came in. -Dmcopt.gen.worldgenQos=off: as before
	 * (the threads' class untouched); =CLASS: that class.
	 */
	private static final boolean WORLDGEN_QOS;

	static {
		String v = System.getProperty("mcopt.gen.worldgenQos");
		if (v == null) System.setProperty("mcopt.gen.worldgenQos", v = "default");
		WORLDGEN_QOS = !"off".equalsIgnoreCase(v.trim());
	}
	private static final boolean NATIVE_NOISE = !System.getProperty("mcopt.gen.nativeNoise", "").isBlank();

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".GenWorldgenQosMixin")) return WORLDGEN_QOS;
		if (mixinClassName.endsWith(".GenNoiseChunkMixin")) return NATIVE_NOISE;
		if (mixinClassName.endsWith(".GenBaseHeightCacheMixin")) return mcopt.metal.lod.LodBaseHeights.ENABLED;
		return false;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
