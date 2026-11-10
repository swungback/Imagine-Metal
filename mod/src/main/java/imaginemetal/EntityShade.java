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
		if (!Engine.TERRAIN) return null;
		// (only Minecraft's entity, item and particle pipelines: not the terrain's (ours draws it), text, rain, leads, beams, the
		// wireframe, nor the order-independent transparency's own passes, whose blending and outputs are their own)
		String n = name.toLowerCase(java.util.Locale.ROOT);
		if (n.contains("glint")) return glint(name, vs, fs);
		// (the selection outline: Minecraft's lines, and in 26.x the block outline's own pipelines too)
		if (n.endsWith("pipeline/lines") || n.endsWith("pipeline/lines_translucent") || n.contains("block_outline")) return outline(name, vs, fs);
		if (Settings.portals && (n.contains("end_portal") || n.contains("end_gateway"))) return endPortal(name, vs, fs);
		for (String skip : SKIP) if (n.contains(skip)) return null;
		boolean wanted = false;
		for (String want : WANT) if (n.contains(want)) wanted = true;
		if (!wanted) return null;
		try {
			boolean lm = vs.contains("out.lightMapColor");
			boolean particle = !lm && vs.contains("sample_lightmap(") && vs.contains("out.vertexColor");
			if (!lm && !particle) return null;
			if (!fs.contains("out.fragColor") || !vs.contains("in.Position")) return null;
			String v = patchVs(vs, particle);
			String f = v == null ? null : patchFs(fs, n.contains("armor") ? "2.0" : n.contains("item") ? "1.0" : "0.0");
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

	/**
	 * The enchantment glint: brighter, and in the level's pass (HDR) past white, so the bloom gives enchanted items a faint glow.
	 */
	private static String[] glint(String name, String vs, String fs) {
		try {
			Matcher m = FS_MAIN.matcher(fs);
			if (!m.find() || !fs.contains("out.fragColor")) return null;
			String f = fs.substring(0, m.end()) + ", constant float4& imOn [[buffer(25)]]" + fs.substring(m.end());
			// (a touch: at 2.6 the glint's purple took over the whole item, a netherite sword in the hand looked purple)
			f = beforeLastReturn(f, "    out.fragColor.xyz *= imOn.x > 0.5 && imOn.x < 1.5 ? 1.25 : 1.0;");
			if (f == null) return null;
			System.out.println("[Imagine Metal] glint glow: " + name);
			return new String[] {vs, f};
		} catch (Throwable t) {
			return null;
		}
	}

	/** The block outline (Minecraft's lines): its colour by the "Selection outline" setting (imOn.y: 0 Minecraft's, 1 white, 2 rainbow). */
	private static String[] outline(String name, String vs, String fs) {
		try {
			Matcher m = FS_MAIN.matcher(fs);
			if (!m.find() || !fs.contains("out.fragColor")) return null;
			// (only the flag buffer, nothing of ours compiled in with it: with our lighting code and the frame's buffer added the
			// outline no longer drew at all)
			String f = fs.substring(0, m.end()) + ", constant float4& imOn [[buffer(25)]]" + fs.substring(m.end());
			f = beforeLastReturn(f, "    if (imOn.y > 1.5) out.fragColor = float4(1.6, 1.6, 1.6, max(out.fragColor.w, 0.85));\n"
				+ "    else if (imOn.y > 0.5) out.fragColor = float4(1.0, 1.0, 1.0, max(out.fragColor.w, 0.7));");
			if (f == null) return null;
			System.out.println("[Imagine Metal] outline: " + name);
			return new String[] {vs, f};
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * The End portal and gateway (Euphoria Patches' look): Minecraft's own layered star field, turned a glowing cyan and, in the
	 * level's pass (HDR), past white so the bloom lifts a haze of light off it.
	 */
	private static String[] endPortal(String name, String vs, String fs) {
		// (Minecraft's own End portal surface again: ours, a cyan over it, looked worse than vanilla's; the rays stay, bloom.metal)
		if (fs != null) return null;
		try {
			Matcher m = FS_MAIN.matcher(fs);
			if (!m.find() || !fs.contains("out.fragColor")) return null;
			String f = fs.substring(0, m.end()) + ", constant float4& imOn [[buffer(25)]]" + fs.substring(m.end());
			f = beforeLastReturn(f, "    { float3 ec = out.fragColor.xyz; float el = max(max(ec.x, ec.y), ec.z);\n"
				+ "      ec = mix(float3(el) * float3(0.35, 1.0, 0.95), ec, 0.35) + float3(0.0, 0.035, 0.04);\n"
				+ "      out.fragColor.xyz = ec * (imOn.x > 0.5 && imOn.x < 1.5 ? 2.2 : 1.2); }");
			if (f == null) return null;
			System.out.println("[Imagine Metal] end portal glow: " + name);
			return new String[] {vs, f};
		} catch (Throwable t) {
			return null;
		}
	}

	private static final String[] WANT = {"pipeline/entity", "pipeline/armor", "pipeline/item", "particle", "banner_pattern", "pipeline/solid_block",
		"pipeline/cutout_block", "pipeline/translucent_block"};
	private static final String[] SKIP = {"oit_", "terrain", "text", "wireframe", "weather", "leash", "beam", "breeze", "lines", "gui", "eyes"};

	private static final Pattern PROJ = Pattern.compile("constant\\s+Projection&\\s+(\\w+)");
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
		v = addMembers(v, "main0_out", "    float3 imPos [[user(locn20)]];\n    float3 imNrm [[user(locn21)]];\n    float2 imLm [[user(locn22)]];\n    float imHand [[user(locn23)]];");
		if (v == null) return null;
		boolean normal = v.contains("in.Normal"), uv2 = v.contains("in.UV2"), color = v.contains("in.Color");
		StringBuilder c = new StringBuilder();
		c.append("    out.imPos = in.Position;\n");
		c.append("    out.imNrm = ").append(normal && !particle ? "in.Normal" : "float3(0.0)").append(";\n");
		c.append("    out.imLm = ").append(uv2 ? "float2(in.UV2) / 240.0" : "float2(1.0)").append(";\n");
		// (outside the level's pass (imOn 2): only the hand, told from the inventory's flat items by its perspective projection)
		Matcher pm = PROJ.matcher(v);
		String persp = pm.find() ? pm.group(1) + ".ProjMat[2][3] != 0.0" : "false";
		c.append("    out.imHand = imOn.x > 1.5 && ").append(persp).append(" ? 1.0 : 0.0;\n");
		c.append("    if (imOn.x > 0.5 && (imOn.x < 1.5 || out.imHand > 0.5)) {\n");
		if (color) {
			if (v.contains("out.vertexPerFaceColorBack")) c.append("        out.vertexPerFaceColorBack = in.Color;\n        out.vertexPerFaceColorFront = in.Color;\n");
			if (v.contains("out.vertexColor")) c.append("        out.vertexColor = in.Color;\n");
		} else if (particle) return null;
		if (v.contains("out.lightMapColor")) c.append("        out.lightMapColor = float4(1.0);\n");
		c.append("    }");
		return beforeLastReturn(v, c.toString());
	}

	private static String patchFs(String fs, String kind) {
		Matcher m = FS_MAIN.matcher(fs);
		if (!m.find()) return null;
		String f = fs.substring(0, m.end()) + ", constant float4& imOn [[buffer(25)]], constant ImFrame& imf [[buffer(30)]]"
			+ ", depth2d_array<float> imShadowMap [[texture(20)]], sampler imShadowCmp [[sampler(13)]]"
			+ ", texture3d<float> imLightVol [[texture(21)]], sampler imLightSampler [[sampler(12)]]" + fs.substring(m.end());
		f = addMembers(f, "main0_in", "    float3 imPos [[user(locn20)]];\n    float3 imNrm [[user(locn21)]];\n    float2 imLm [[user(locn22)]];\n    float imHand [[user(locn23)]];");
		if (f == null) return null;
		// (the hand: lit at the eye, as a thing held there; its torch's flame glowing as a dropped one's does)
		f = beforeLastReturn(f, "    if (imOn.x > 0.5 && imOn.x < 1.5) out.fragColor.xyz = imShadeEntity(out.fragColor.xyz, in.imPos, in.imNrm, in.imLm, imf,"
			+ " imShadowMap, imShadowCmp, imLightVol, imLightSampler, " + kind + ");\n"
			+ "    else if (imOn.x > 1.5 && in.imHand > 0.5) out.fragColor.xyz = imShadeEntity(out.fragColor.xyz, float3(0.0, -0.4, 0.001),"
			+ " float3(0.0), in.imLm, imf, imShadowMap, imShadowCmp, imLightVol, imLightSampler, 3.0);");
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
			bind.invokeExact(enc, (!Settings.entityLight ? 0 : level ? 1 : 2) | Settings.outline << 4);
		} catch (Throwable t) {
			bindFailed = true;
			System.out.println("[Imagine Metal] entity light off: " + t);
		}
	}
}
