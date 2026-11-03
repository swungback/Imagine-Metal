package imaginemetal;

import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
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

	public SettingsScreen(Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.literal("Imagine Metal"));
	}

	@Override
	protected void addOptions() {
		this.list.addHeader(Component.literal("Shadows"));
		this.list.addSmall(toggle("Shadows", Settings.shadows, v -> Settings.shadows = v, "Sun and moon shadows."),
			slider("Shadow distance", 64, 256, 16, Settings.shadowRange, v -> Settings.shadowRange = v, v -> Math.round(v) + " blocks",
				"How far around you shadows reach. Less is faster."));

		this.list.addHeader(Component.literal("Sky"));
		this.list.addSmall(toggle("Sky and haze", Settings.sky, v -> Settings.sky = v,
			"Our sky (Reimagined's colours, the sun's glow, sunsets, a blue night) and the haze that fades distant land into it. Off: Minecraft's."),
			slider("Air", 0, 3, 0.05f, Settings.haze, v -> Settings.haze = v, v -> Math.round(v * 100) + "%",
				"How thick the air is: distant land turning blue, mist in the valleys at dawn and dusk, each biome's own air. 100% is Photon's."));

		this.list.addHeader(Component.literal("Surfaces"));
		this.list.addSmall(toggle("PBR", Settings.pbr, v -> Settings.pbr = v,
			"Sun highlights on every block by its texture, and reflections traced on screen: metal, gems and polished stone clearly, rough blocks at low angles."),
			slider("Generated normals", 0, 4, 0.25f, Settings.normals, v -> Settings.normals = v, v -> v == 0 ? "Off" : Math.round(v * 100) + "%",
				"The edges between a texture's light and dark pixels bevel, so blocks look like raised pixel tiles. 100% is Reimagined's strength."));

		this.list.addHeader(Component.literal("Light"));
		this.list.addBig(toggle("Coloured light", Settings.coloredLight, v -> Settings.coloredLight = v,
			"Light sources light the world in their own colours: soul fire blue, redstone red, lava orange."));

		this.list.addHeader(Component.literal("Glow"));
		this.list.addSmall(toggle("Bloom", Settings.bloom, v -> Settings.bloom = v, "The soft glow around bright things."),
			slider("Bloom strength", 0, 2, 0.05f, Settings.bloomStrength, v -> Settings.bloomStrength = v, v -> String.format(java.util.Locale.ROOT, "%.2f", v),
				"How strong the glow is."));

		this.list.addHeader(Component.literal("Needs a restart"));
		this.list.addSmall(toggle("Retina resolution*", Settings.retina, v -> Settings.retina = v,
			"Draw every Retina pixel: sharper, much slower. " + RESTART),
			CycleButton.<Integer>builder(v -> Component.literal(String.valueOf(v)), Settings.shadowRes)
				.withValues(1024, 2048, 4096)
				.withTooltip(v -> Tooltip.create(Component.literal("Shadow map size: sharper leaf shadows with more. " + RESTART)))
				.create(0, 0, 150, 20, Component.literal("Shadow detail*"), (b, v) -> Settings.shadowRes = v));
		this.list.addBig(toggle("Imagine Metal lighting*", Settings.terrain, v -> Settings.terrain = v,
			"Off: vanilla's lighting on the terrain, for comparing. " + RESTART));

		this.list.addHeader(Component.literal("Debug"));
		String[] views = {"Off", "Light levels", "Normals", "Bloom glow only", "Coloured light grid"};
		this.list.addBig(CycleButton.<Integer>builder(v -> Component.literal(views[v]), Settings.debug)
			.withValues(0, 1, 2, 3, 4)
			.withTooltip(v -> Tooltip.create(Component.literal("Shows one part of the picture on its own, to check it.")))
			.create(0, 0, 310, 20, Component.literal("Debug view"), (b, v) -> Settings.debug = v));
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
}
