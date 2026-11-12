package imaginemetal;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Imagine Metal: Complementary Reimagined style shaders with PBR and coloured lighting, written for Apple Metal.
 *
 * <p>The game draws through mcopt's Metal backend (package {@code mcopt}, Apache-2.0, see META-INF/mcopt). Everything that
 * makes the image look the way it does lives in this package: the deferred pipeline, lighting, materials and effects.
 */
public final class ImagineMetal implements ClientModInitializer {
	public static final String ID = "imaginemetal";

	public static String version() {
		return FabricLoader.getInstance().getModContainer(ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}

	@Override
	public void onInitializeClient() {
		System.out.println("[Imagine Metal] " + version() + " on mcopt's Metal backend");
		imaginemetal.metalods.MetaLods.init();
	}
}
