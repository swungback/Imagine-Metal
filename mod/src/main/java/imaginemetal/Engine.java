package imaginemetal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Imagine Metal's switches and the shader sources it adds to the backend's libraries. */
public final class Engine {
	/** imaginemetal.terrain (true): the terrain is lit by our lighting model (lighting.metal) instead of vanilla's lightmap. */
	public static final boolean TERRAIN = flag("imaginemetal.terrain", true);

	private static String lighting;

	private Engine() {
	}

	static boolean flag(String key, boolean def) {
		String v = System.getProperty(key);
		return v == null ? def : !"false".equalsIgnoreCase(v.trim());
	}

	/** Appended to mcopt's terrain library (OwnTerrain): the shadow map's casters, which use the terrain's own vertex fetch. */
	public static String terrainEpilogue() {
		if (!TERRAIN) return "";
		return "\n" + resource("/imaginemetal/shaders/shadow.metal");
	}

	/** Prepended to mcopt's terrain library (OwnTerrain): our lighting with IM_SHADE defined, or nothing with the engine off. */
	public static String terrainPrelude() {
		if (!TERRAIN) return "";
		if (lighting == null) lighting = resource("/imaginemetal/shaders/lighting.metal");
		System.out.println("[Imagine Metal] terrain lighting on");
		return "#define IM_SHADE 1\n" + lighting + "\n";
	}

	static String resource(String path) {
		try (InputStream in = Engine.class.getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("missing resource " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("can't read " + path, e);
		}
	}
}
