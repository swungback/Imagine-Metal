package imaginemetal.metalods;

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

/** MetaLODS' settings (next to Imagine Metal's in Video Settings and the pause menu). Everything applies at once. */
public final class MetaLodsScreen extends OptionsSubScreen {
	public MetaLodsScreen(Screen lastScreen) {
		super(lastScreen, Minecraft.getInstance().options, Component.literal("MetaLODS"));
	}

	@Override
	protected void addOptions() {
		this.list.addSmall(toggle("MetaLODS", MetaSettings.enabled, v -> {
			MetaSettings.enabled = v;
			MetaLods.settingsChanged(false);
		}, "Far terrain as voxel LODs past the render distance, lit by Imagine Metal (needs its deferred lighting)."),
			slider("LOD distance", 32, 512, 16, MetaSettings.distance, v -> MetaSettings.distance = Math.round(v), v -> Math.round(v) + " chunks",
				"How far the LODs reach. Real chunks stay at your render distance; past it, the LODs."));
		this.list.addSmall(slider("Detail", 4, 16, 1, MetaSettings.detail, v -> MetaSettings.detail = Math.round(v), v -> String.valueOf(Math.round(v)),
			"How soon a LOD is swapped for a finer one: more is sharper further out, but more to make and draw. 6 is the default."),
			toggle("Generate unexplored", MetaSettings.generate, v -> {
				MetaSettings.generate = v;
				MetaLods.settingsChanged(true);
			}, "Terrain you never visited, filled in from the world seed (singleplayer: a server's seed isn't known). Off: only chunks you have seen."));
		this.list.addSmall(toggle("Exact trees", MetaSettings.exactTrees, v -> MetaSettings.exactTrees = v,
			"The game's own trees on the nearest generated LODs, where they will really grow (slower to make). Off: the biome's estimate. Applies to new LODs."),
			toggle("Stats in the log", MetaSettings.stats, v -> MetaSettings.stats = v, "A MetaLODS line in latest.log every 10 seconds."));
		this.list.addSmall(toggle("Hide under real terrain", MetaSettings.hideUnderReal, v -> MetaSettings.hideUnderReal = v,
			"Debug, not saved. Off: the LODs are drawn inside the render distance too (overlapping the real terrain)."),
			Button.builder(Component.literal("Clear this world's LODs"), b -> {
				MetaLods.clearWorld();
				b.setMessage(Component.literal("Cleared"));
			}).tooltip(Tooltip.create(Component.literal("Deletes MetaLODS' files for this world and dimension and makes them again."))).bounds(0, 0, 150, 20).build());
		this.list.addSmall(toggle("Show levels", MetaSettings.showLevels, v -> MetaSettings.showLevels = v,
			"Debug, not saved. Each LOD level in its own colour: 0 red, 1 orange, 2 yellow, 3 green, 4 cyan, 5 blue."),
			Button.builder(Component.literal("Remake all LODs"), b -> MetaLods.settingsChanged(true)).tooltip(Tooltip.create(Component.literal(
				"Meshes every LOD again from what's stored (nothing is deleted)."))).bounds(0, 0, 150, 20).build());
		this.list.addBig(Button.builder(Component.literal("Status: " + MetaLods.status()), b -> b.setMessage(Component.literal("Status: " + MetaLods.status())))
			.bounds(0, 0, 310, 20).build());
	}

	@Override
	public void removed() {
		super.removed();
		MetaSettings.save();
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
			if (this.name == null) return;
			this.setMessage(Component.literal(this.name + ": " + this.format.apply(this.current())));
		}

		@Override
		protected void applyValue() {
			this.set.accept(this.current());
		}
	}
}
