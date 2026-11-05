package imaginemetal;

import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Imagine Metal's settings, from Video Settings (VideoSettingsScreenMixin). Most apply at once while the game keeps drawing
 * behind the screen; the ones marked * apply after a restart. Saved to config/imaginemetal.properties on close.
 */
public final class SettingsScreen extends OptionsSubScreen {
	private static final String RESTART = "Applies after a restart.";

	/** The pages: the main one lists the others. */
	public enum Page {
		MAIN("Imagine Metal"), LIGHT("Light and shadows"), SKY("Sky and weather"), WORLDS("Nether and End"), AURORA("Aurora"), SURFACES("Surfaces"),
		CAMERA("Camera and glow"), ADVANCED("Advanced");

		final String title;

		Page(String title) {
			this.title = title;
		}
	}

	private final Page page;

	public SettingsScreen(Screen lastScreen) {
		this(lastScreen, Page.MAIN);
	}

	public SettingsScreen(Screen lastScreen, Page page) {
		super(lastScreen, Minecraft.getInstance().options, Component.literal(page == Page.MAIN ? "Imagine Metal" : "Imagine Metal: " + page.title));
		this.page = page;
	}

	private AbstractWidget open(Page p) {
		return Button.builder(Component.literal(p.title + "..."), b -> Minecraft.getInstance().gui.setScreen(new SettingsScreen(this, p))).bounds(0, 0, 150, 20)
			.build();
	}

	private static String pct(float v) {
		return Math.round(v * 100) + "%";
	}

	@Override
	protected void addOptions() {
		switch (this.page) {
			case MAIN -> {
				this.list.addSmall(open(Page.LIGHT), open(Page.SKY));
				this.list.addSmall(open(Page.SURFACES), open(Page.CAMERA));
				this.list.addSmall(open(Page.WORLDS), open(Page.AURORA));
				this.list.addBig(open(Page.ADVANCED));
			}
			case LIGHT -> {
				this.list.addBig(toggle("Intense deep dark", Settings.intenseDeepDark, v -> Settings.intenseDeepDark = v,
					"Euphoria Patches' deep dark: pitch black where nothing glows, so sculk and torches are all you see."));
				this.list.addHeader(Component.literal("Shadows"));
				this.list.addSmall(toggle("Shadows", Settings.shadows, v -> Settings.shadows = v, "Sun and moon shadows."),
					slider("Shadow distance", 64, 256, 16, Settings.shadowRange, v -> Settings.shadowRange = v, v -> Math.round(v) + " blocks",
						"How far around you shadows reach. Less is faster."));
				this.list.addBig(toggle("Light fog", Settings.lightFog, v -> Settings.lightFog = v,
					"The air near lights glows faintly in their colour (Reimagined's coloured light fog). Needs coloured light."));
				this.list.addBig(toggle("Sunlight through stained glass", Settings.glassSun, v -> Settings.glassSun = v,
					"Sunlight coming through stained glass takes its colour, throwing coloured light on the floor. Needs coloured light."));
				this.list.addBig(toggle("Mob shadows", Settings.mobShadows, v -> Settings.mobShadows = v,
					"Mobs, players and items cast short shadows on the ground beside them in the sun."));
				this.list.addHeader(Component.literal("Light"));
				this.list.addSmall(toggle("Coloured light", Settings.coloredLight, v -> Settings.coloredLight = v,
					"Light sources light the world in their own colours: soul fire blue, redstone red, lava orange."),
					toggle("Held light", Settings.heldLight, v -> Settings.heldLight = v,
						"A torch, lantern or any other light in your hand lights up what's around you."));
				this.list.addSmall(toggle("Mobs and items lit", Settings.entityLight, v -> Settings.entityLight = v,
					"Mobs, dropped items and particles lit by the sun, the shadows and coloured light like the blocks around them. Off: Minecraft's own lighting on them."),
					slider("Night brightness", 0.25f, 2f, 0.05f, Settings.nightBrightness, v -> Settings.nightBrightness = v, SettingsScreen::pct,
					"How bright moonlit nights are. 100% is ours."));
			}
			case SKY -> {
				this.list.addSmall(toggle("Sky and haze", Settings.sky, v -> Settings.sky = v,
					"Our sky (Reimagined's colours, the sun's glow, sunsets, a blue night) and the air that fades distant land into it. Off: Minecraft's."),
					slider("Air", 0, 3, 0.05f, Settings.haze, v -> Settings.haze = v, SettingsScreen::pct,
						"How thick the air is: distant land turning blue, mist in the valleys at dawn and dusk, each biome's own air. 100% is Photon's."));
				this.list.addSmall(toggle("Clouds", Settings.clouds, v -> Settings.clouds = v,
					"Our clouds: Minecraft's cloud shapes as soft, rounded blocks, lit by the sun, coloured by the sky. Off: Minecraft's. (Minecraft's Clouds video setting must be on.)"),
					toggle("Rainbows", Settings.rainbows, v -> Settings.rainbows = v, "A rainbow opposite the sun as the rain clears."));
				this.list.addSmall(toggle("Light shafts", Settings.shafts, v -> Settings.shafts = v,
					"Beams of sunlight around leaves, mountains and cave mouths when you look toward the sun; in the End, its violet beams rising out of the void."),
					slider("Shaft strength", 0, 3, 0.05f, Settings.shaftStrength, v -> Settings.shaftStrength = v, SettingsScreen::pct,
						"How bright the light shafts are."));
				this.list.addSmall(cycle("Stars", new String[] {"Minecraft's", "Reimagined, twinkling"}, Settings.stars, v -> Settings.stars = v,
					"Reimagined's star field, each star twinkling on its own."),
					toggle("Shooting stars", Settings.shootingStars, v -> Settings.shootingStars = v, "Now and then a shooting star on a clear night."));
				this.list.addBig(toggle("Night nebula", Settings.nebula, v -> Settings.nebula = v,
					"A faint band of violet and blue cloud across the night sky, under the stars."));
			}
			case WORLDS -> {
				this.list.addSmall(cycle("End sky", new String[] {"Reimagined", "Black hole"}, Settings.endStyle, v -> Settings.endStyle = v,
					"Reimagined: a violet sky with beams of light rising from the void. Black hole: a black sky full of stars and a blazing black hole low over the islands, lighting them white and throwing long shadows."),
					toggle("Portal effects", Settings.portals, v -> Settings.portals = v,
						"The nether portal's glowing swirls with depth behind them, the End portal's cyan glow."));
				this.list.addSmall(toggle("Nether haze", Settings.netherFog, v -> Settings.netherFog = v,
					"Reimagined's Nether: each biome its own glowing haze, orange in the wastes, teal in soul sand valleys, ash grey in the deltas, red in the crimson forest, violet in the warped one."),
					toggle("Nether storm", Settings.netherStorm, v -> Settings.netherStorm = v,
						"Slow drifting clouds of the Nether's haze through its caverns (Reimagined's Nether storm)."));
			}
			case CAMERA -> {
				this.list.addSmall(toggle("Bloom", Settings.bloom, v -> Settings.bloom = v, "The soft glow around bright things."),
					slider("Bloom strength", 0, 2, 0.05f, Settings.bloomStrength, v -> Settings.bloomStrength = v,
						v -> String.format(java.util.Locale.ROOT, "%.2f", v), "How strong the glow is."));
				this.list.addBig(toggle("Bloom fog", Settings.bloomFog, v -> Settings.bloomFog = v,
					"Reimagined's soft depth: in rain, at night, in caves and under water, distant things dissolve into a gentle glow."));
				this.list.addBig(toggle("TAA (anti-aliasing)", Settings.taa, v -> Settings.taa = v,
					"Smooth block edges and less grain in the light shafts, shadows and reflections, averaged over a few frames."));
				this.list.addSmall(toggle("Ambient occlusion", Settings.ssao, v -> Settings.ssao = v,
					"Corners, the ground under ledges and the gaps between blocks a little darker, so shapes read better."),
					slider("AO strength", 0, 1, 0.05f, Settings.ssaoStrength, v -> Settings.ssaoStrength = v, SettingsScreen::pct,
						"How dark the ambient occlusion gets."));
			}
			case AURORA -> {
				this.list.addSmall(toggle("Aurora", Settings.aurora, v -> Settings.aurora = v, "Northern lights at night."),
					cycle("When", new String[] {"Every night", "Cold biomes", "Full moon", "Cold biomes or full moon"}, Settings.auroraWhen,
						v -> Settings.auroraWhen = v, "Which nights the aurora shows on."));
				this.list.addSmall(cycle("Style", new String[] {"Smooth (Reimagined)", "Pixel (Reimagined)"}, Settings.auroraStyle,
					v -> Settings.auroraStyle = v, "Reimagined's two aurora styles: soft curtains, or curtains in blocky steps."),
					cycle("Colours", Aurora.NAMES, Settings.auroraColors, v -> Settings.auroraColors = v,
						"The aurora's colours: below, where the curtains end, and above."));
				this.list.addSmall(slider("Brightness", 0.25f, 3f, 0.05f, Settings.auroraBrightness, v -> Settings.auroraBrightness = v, SettingsScreen::pct,
					"How bright the aurora is."),
					slider("Influence", 0, 3, 0.05f, Settings.auroraInfluence, v -> Settings.auroraInfluence = v, v -> v == 0 ? "Off" : pct(v),
						"How strongly the aurora's light tints the world below it (the snow turns its colour)."));
			}
			case SURFACES -> {
				this.list.addBig(toggle("Waving plants", Settings.waving, v -> {
					Settings.waving = v;
					Materials.clearCache();
					rebuildChunks();
				}, "Grass, flowers, crops and leaves sway in the wind."));
				this.list.addSmall(cycle("Water", new String[] {"Vanilla", "Reimagined", "Reimagined + waves"}, Settings.waterStyle,
					v -> Settings.waterStyle = v, "Reimagined's water: its colour, clearer up close, reflections of the sky and the world. Waves add moving ripples."),
					toggle("Glass reflections", Settings.glassReflections, v -> Settings.glassReflections = v,
						"Stained glass, ice and other see-through blocks reflect the sky and the world."));
				this.list.addBig(toggle("Water refraction and foam", Settings.waterFx, v -> Settings.waterFx = v,
					"What's under the water wavers with its surface, and foam gathers where it meets the shore. Needs Reimagined water."));
				this.list.addBig(toggle("Coated textures", Settings.coated, v -> Settings.coated = v,
					"Reimagined's coated textures: each texture pixel a touch lighter or darker, so flat colours read as a material."));
				this.list.addBig(toggle("Wet in the rain", Settings.wet, v -> Settings.wet = v,
					"In the rain everything under the sky gets darker and glossy, with puddles on top of blocks. Needs PBR."));
				this.list.addSmall(toggle("PBR", Settings.pbr, v -> Settings.pbr = v,
					"Sun highlights on every block by its texture, and reflections traced on screen: metal, gems and polished stone clearly, rough blocks at low angles."),
					slider("Generated normals", 0, 4, 0.25f, Settings.normals, v -> Settings.normals = v, v -> v == 0 ? "Off" : pct(v),
						"The edges between a texture's light and dark pixels bevel, so blocks look like raised pixel tiles. 100% is Reimagined's strength."));
			}
			case ADVANCED -> {
				this.list.addHeader(Component.literal("Needs a restart"));
				this.list.addSmall(toggle("Retina resolution*", Settings.retina, v -> Settings.retina = v,
					"Draw every Retina pixel: sharper, much slower. " + RESTART),
					CycleButton.<Integer>builder(v -> Component.literal(String.valueOf(v)), Settings.shadowRes)
						.withValues(1024, 2048, 4096)
						.withTooltip(v -> Tooltip.create(Component.literal("Shadow map size: sharper leaf shadows with more. " + RESTART)))
						.create(0, 0, 150, 20, Component.literal("Shadow detail*"), (b, v) -> Settings.shadowRes = v));
				this.list.addBig(toggle("Imagine Metal lighting*", Settings.terrain, v -> Settings.terrain = v,
					"Off: vanilla's lighting on the terrain, for comparing. " + RESTART));
				this.list.addHeader(Component.literal("Speed"));
				this.list.addBig(Button.builder(Component.literal("Run benchmark (stand still, ~1 minute)"), b -> {
					Minecraft.getInstance().gui.setScreen(null);
					Benchmark.start();
				}).bounds(0, 0, 310, 20).tooltip(Tooltip.create(Component.literal(
					"Turns each feature off in turn for a few seconds and measures the frame rate, then puts everything back. The results go to latest.log.")))
					.build());
				this.list.addHeader(Component.literal("Debug"));
				String[] views = {"Off", "Light levels", "Normals", "Bloom glow only", "Coloured light grid"};
				this.list.addBig(CycleButton.<Integer>builder(v -> Component.literal(views[v]), Settings.debug)
					.withValues(0, 1, 2, 3, 4)
					.withTooltip(v -> Tooltip.create(Component.literal("Shows one part of the picture on its own, to check it.")))
					.create(0, 0, 310, 20, Component.literal("Debug view"), (b, v) -> Settings.debug = v));
			}
		}
	}

	private static AbstractWidget cycle(String name, String[] names, int value, Consumer<Integer> set, String tooltip) {
		Integer[] values = new Integer[names.length];
		for (int i = 0; i < names.length; i++) values[i] = i;
		return CycleButton.<Integer>builder(v -> Component.literal(names[v]), Math.clamp(value, 0, names.length - 1))
			.withValues(values)
			.withTooltip(v -> Tooltip.create(Component.literal(tooltip)))
			.create(0, 0, 150, 20, Component.literal(name), (b, v) -> set.accept(v));
	}

	@Override
	public void removed() {
		super.removed();
		Settings.save();
	}

	private static AbstractWidget toggle(String name, boolean value, Consumer<Boolean> set, String tooltip) {
		return CycleButton.onOffBuilder(value)
			.withTooltip(v -> Tooltip.create(Component.literal(tooltip)))
			.create(0, 0, 150, 20, Component.literal(name), (b, v) -> set.accept(v));
	}

	private static AbstractWidget slider(String name, float min, float max, float step, float value, Consumer<Float> set, Function<Float, String> format,
		String tooltip) {
		Slider s = new Slider(name, min, max, step, value, set, format);
		s.setTooltip(Tooltip.create(Component.literal(tooltip)));
		return s;
	}

	private static final class Slider extends AbstractSliderButton {
		private final String name;
		private final float min, max, step;
		private final Consumer<Float> set;
		private final Function<Float, String> format;

		Slider(String name, float min, float max, float step, float value, Consumer<Float> set, Function<Float, String> format) {
			super(0, 0, 150, 20, Component.empty(), Math.clamp((value - min) / (max - min), 0.0, 1.0));
			this.name = name;
			this.min = min;
			this.max = max;
			this.step = step;
			this.set = set;
			this.format = format;
			this.updateMessage();
		}

		private float current() {
			float v = this.min + (float) this.value * (this.max - this.min);
			return Math.clamp(Math.round(v / this.step) * this.step, this.min, this.max);
		}

		@Override
		protected void updateMessage() {
			if (this.name == null) return; // (if the parent's constructor asks before ours has run)
			this.setMessage(Component.literal(this.name + ": " + this.format.apply(this.current())));
		}

		@Override
		protected void applyValue() {
			this.set.accept(this.current());
		}
	}

	/** Rebuilds every chunk (as F3+A does): for settings that change what's in the chunk meshes. */
	static void rebuildChunks() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		try {
			mc.levelExtractor.allChanged();  // (what F3+A does)
		} catch (Throwable t) {
			System.out.println("imaginemetal: couldn't rebuild the chunks (" + t + "); press F3+A");
		}
	}
}
