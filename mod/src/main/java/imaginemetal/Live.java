package imaginemetal;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Switching the shaders off and on without a restart (the pause menu's "Shaders: on/off" button, or F8 in game): off, every
 * feature that can change while the game runs is switched off (our sky, shadows, reflections, bloom, fog, coloured light...)
 * and the terrain and mobs are lit plainly, close to vanilla (ImFrame flags.z 0); on, everything is put back as it was. The
 * settings file isn't touched while off. Also the settings snapshot the benchmark uses.
 */
public final class Live {
	private static volatile boolean off;
	private static Map<Field, Object> saved;
	private static boolean keyWas;
	private static java.lang.reflect.Method handleMethod;
	private static boolean handleFailed;

	/** Fields that only take effect after a restart (or a chunk rebuild): never switched live. */
	private static final java.util.Set<String> FIXED = java.util.Set.of("retina", "terrain", "shadowRes", "waving", "debug");

	private Live() {
	}

	public static boolean isOff() {
		return off;
	}

	/** Every live setting's current value (Settings' static fields, by reflection). */
	static Map<Field, Object> snapshot() {
		Map<Field, Object> m = new LinkedHashMap<>();
		for (Field f : Settings.class.getDeclaredFields()) {
			int mod = f.getModifiers();
			if (!Modifier.isStatic(mod) || Modifier.isFinal(mod) || FIXED.contains(f.getName())) continue;
			Class<?> t = f.getType();
			if (t != boolean.class && t != int.class && t != float.class) continue;
			try {
				f.setAccessible(true);
				m.put(f, f.get(null));
			} catch (Throwable ignored) {
			}
		}
		return m;
	}

	static void restore(Map<Field, Object> m) {
		if (m == null) return;
		for (var e : m.entrySet()) {
			try {
				e.getKey().set(null, e.getValue());
			} catch (Throwable ignored) {
			}
		}
	}

	/** Shaders off: the snapshot kept, every live feature off. */
	public static void setOff(boolean wantOff) {
		if (wantOff == off) return;
		if (wantOff) {
			saved = snapshot();
			Settings.sky = Settings.shadows = Settings.pbr = Settings.clouds = Settings.aurora = Settings.heldLight = false;
			Settings.wet = Settings.taa = Settings.rainbows = Settings.netherFog = Settings.entityLight = Settings.portals = false;
			Settings.glassSun = Settings.nebula = Settings.coated = Settings.lightFog = Settings.mobShadows = Settings.waterFx = false;
			Settings.pbrPack = Settings.parallax = Settings.netherStorm = Settings.bloomFog = Settings.ssao = Settings.shafts = false;
			Settings.glassReflections = Settings.bloom = Settings.coloredLight = Settings.shootingStars = false;
			Settings.waterStyle = 0;
			Settings.stars = 0;
			Settings.normals = 0f;
			off = true;
		} else {
			off = false;
			restore(saved);
			saved = null;
		}
		System.out.println("[Imagine Metal] shaders " + (off ? "off" : "on") + " (live)");
		chat("Imagine Metal: shaders " + (off ? "off (F8 or the pause menu to turn back on)" : "on"));
	}

	/** A line in the player's chat (reflection: the method's name has moved between versions); the log either way. */
	static void chat(String text) {
		var p = Minecraft.getInstance().player;
		if (p == null) return;
		Component c = Component.literal(text);
		for (var m : p.getClass().getMethods()) {
			try {
				if (m.getName().equals("displayClientMessage") && m.getParameterCount() == 2) {
					m.invoke(p, c, false);
					return;
				}
			} catch (Throwable ignored) {
			}
		}
		for (var m : p.getClass().getMethods()) {
			try {
				if (m.getName().equals("sendSystemMessage") && m.getParameterCount() == 1) {
					m.invoke(p, c);
					return;
				}
			} catch (Throwable ignored) {
			}
		}
	}

	public static void toggle() {
		setOff(!off);
	}

	/** Every frame: F8 (in game, no screen open) toggles. */
	static void poll() {
		Minecraft mc = Minecraft.getInstance();
		boolean down = false;
		if (mc.gui.screen() == null && !handleFailed) {
			try {
				Object w = mc.getWindow();
				if (handleMethod == null) {
					for (String n : new String[] {"handle", "getWindow", "getHandle"}) {
						try {
							var m = w.getClass().getMethod(n);
							if (m.getReturnType() == long.class) {
								handleMethod = m;
								break;
							}
						} catch (NoSuchMethodException ignored) {
						}
					}
					if (handleMethod == null) {
						handleFailed = true;
						System.out.println("[Imagine Metal] F8 toggle unavailable (no window handle); use the pause menu button");
						return;
					}
				}
				long h = (long) handleMethod.invoke(w);
				down = org.lwjgl.glfw.GLFW.glfwGetKey(h, org.lwjgl.glfw.GLFW.GLFW_KEY_F8) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
			} catch (Throwable t) {
				handleFailed = true;
				System.out.println("[Imagine Metal] F8 toggle unavailable: " + t);
			}
		}
		if (down && !keyWas) toggle();
		keyWas = down;
	}
}
