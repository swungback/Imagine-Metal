package imaginemetal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mcopt.metal.MetalBridge;

/**
 * Entities, block entities, dropped items and particles lit as the terrain is. Minecraft draws them with its own shaders
 * (entity, item, particle), which mcopt translates to Metal (SPIRV-Cross) when a pipeline is made; here that Metal source is
 * patched: the vertex stage stops baking in Minecraft's light (its two fixed lights on the normal, its lightmap) and passes
 * the position, normal and light levels on; the fragment stage, after Minecraft's own colour (texture, tint, the red hurt
 * overlay, fog), lights it with lighting.metal imShadeEntity: our sun and shadow maps, sky light, coloured block light.
 *
 * <p>Only in the level's own pass (the HDR one): the same pipelines draw the inventory's items and the hand, and there a flag
 * (buffer 25) keeps Minecraft's light. A shader that doesn't look as expected is left as it is (a line in the log says which).
 */
public final class EntityShade {
	private static MethodHandle bind;
	private static boolean bindFailed;
	private static String lighting;

	private EntityShade() {
	}

	/** The patched {vertex, fragment} sources, or null to keep both as they are. */
	public static String[] patch(String name, String vs, String fs) {
		if (!Settings.entityLight || !Engine.TERRAIN) return null;
		try {
			boolean lm = vs.contains("out.lightMapColor");
			boolean particle = !lm && vs.contains("sample_lightmap(") && vs.contains("out.vertexColor");
			if (!lm && !particle) return null;
			if (!fs.contains("out.fragColor") || !vs.contains("in.Position")) return null;
			String v = patchVs(vs, particle);
			String f = v == null ? null : patchFs(fs);
			if (v == null || f == null) {
				System.out.println("[Imagine Metal] entity light: " + name + " left as Minecraft's (its shader isn't the shape expected)");
				return null;
			}
			System.out.println("[Imagine Metal] entity light: " + name);
			return new String[] {v, f};
		} catch (Throwable t) {
			System.out.println("[Imagine Metal] entity light: " + name + " left as Minecraft's: " + t);
			return null;
		}
	}

	private static final Pattern VS_MAIN = Pattern.compile("vertex\\s+main0_out\\s+main0\\s*\\(\\s*main0_in\\s+in\\s*\\[\\[stage_in\\]\\]");
	private static final Pattern FS_MAIN = Pattern.compile("fragment\\s+main0_out\\s+main0\\s*\\(\\s*main0_in\\s+in\\s*\\[\\[stage_in\\]\\]");

	private static String addMembers(String src, String struct, String members) {
		int s = src.indexOf("struct " + struct);
		if (s < 0) return null;
		int b = src.indexOf('{', s);
		if (b < 0) return null;
		return src.substring(0, b + 1) + "\n" + members + src.substring(b + 1);
	}

	private static String beforeLastReturn(String src, String code) {
		int r = src.lastIndexOf("return out;");
		if (r < 0) return null;
		return src.substring(0, r) + code + "\n    " + src.substring(r);
	}

	private static String patchVs(String vs, boolean particle) {
		Matcher m = VS_MAIN.matcher(vs);
		if (!m.find()) return null;
		String v = vs.substring(0, m.end()) + ", constant float4& imOn [[buffer(25)]]" + vs.substring(m.end());
		v = addMembers(v, "main0_out", "    float3 imPos [[user(locn20)]];\n    float3 imNrm [[user(locn21)]];\n    float2 imLm [[user(locn22)]];");
		if (v == null) return null;
		boolean normal = v.contains("in.Normal"), uv2 = v.contains("in.UV2"), color = v.contains("in.Color");
		StringBuilder c = new StringBuilder();
		c.append("    out.imPos = in.Position;\n");
		c.append("    out.imNrm = ").append(normal && !particle ? "in.Normal" : "float3(0.0)").append(";\n");
		c.append("    out.imLm = ").append(uv2 ? "float2(in.UV2) / 240.0" : "float2(1.0)").append(";\n");
		c.append("    if (imOn.x > 0.5) {\n");
		if (color) {
			if (v.contains("out.vertexPerFaceColorBack")) c.append("        out.vertexPerFaceColorBack = in.Color;\n        out.vertexPerFaceColorFront = in.Color;\n");
			if (v.contains("out.vertexColor")) c.append("        out.vertexColor = in.Color;\n");
		} else if (particle) return null;
		if (v.contains("out.lightMapColor")) c.append("        out.lightMapColor = float4(1.0);\n");
		c.append("    }");
		return beforeLastReturn(v, c.toString());
	}

	private static String patchFs(String fs) {
		Matcher m = FS_MAIN.matcher(fs);
		if (!m.find()) return null;
		String f = fs.substring(0, m.end()) + ", constant float4& imOn [[buffer(25)]], constant ImFrame& imf [[buffer(30)]]"
			+ ", depth2d_array<float> imShadowMap [[texture(20)]], sampler imShadowCmp [[sampler(13)]]"
			+ ", texture3d<float> imLightVol [[texture(21)]], sampler imLightSampler [[sampler(12)]]" + fs.substring(m.end());
		f = addMembers(f, "main0_in", "    float3 imPos [[user(locn20)]];\n    float3 imNrm [[user(locn21)]];\n    float2 imLm [[user(locn22)]];");
		if (f == null) return null;
		f = beforeLastReturn(f, "    if (imOn.x > 0.5) out.fragColor.xyz = imShadeEntity(out.fragColor.xyz, in.imPos, in.imNrm, in.imLm, imf, imShadowMap,"
			+ " imShadowCmp, imLightVol, imLightSampler);");
		if (f == null) return null;
		if (lighting == null) lighting = Engine.resource("/imaginemetal/shaders/lighting.metal");
		return lighting + "\n// ---- Minecraft's shader (SPIRV-Cross), patched ----\n" + f;
	}

	/** Right after a patched pipeline is set on a pass: its extra resources (mcown.m mc_im_entity_bind). */
	public static void bind(long enc, boolean level) {
		if (bindFailed) return;
		try {
			if (bind == null) {
				var symbol = MetalBridge.library().find("mc_im_entity_bind").orElseThrow();
				bind = Linker.nativeLinker().downcallHandle(symbol, FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT), Linker.Option.critical(false));
			}
			bind.invokeExact(enc, level ? 1 : 0);
		} catch (Throwable t) {
			bindFailed = true;
			System.out.println("[Imagine Metal] entity light off: " + t);
		}
	}
}
