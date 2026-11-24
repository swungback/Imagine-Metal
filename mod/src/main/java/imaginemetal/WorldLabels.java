package imaginemetal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;

/** Draws world-anchored UI after the temporal resolve, using the original Minecraft text pipelines. */
public final class WorldLabels {
	private static final TextFeatureRenderer renderer = new TextFeatureRenderer();
	private static final IdentityHashMap<TextFeatureRenderer.Submit, Boolean> labels = new IdentityHashMap<>();
	private static final List<Draw> draws = new ArrayList<>();
	private static FeatureFrameContext context;
	private static GpuTexture depth;
	private static GpuTextureView depthView;
	private static boolean active, failed, depthReady;
	private static boolean submittingDisplay;

	private WorldLabels() {
	}

	public static void begin() {
		active = Settings.nativeWorldLabels && !failed && !Live.isOff() && Hdr.scaled() && Bloom.ready();
		depthReady = false;
	}

	public static void mark(TextFeatureRenderer.Submit text) {
		if (active) labels.put(text, Boolean.TRUE);
	}

	public static boolean submittingDisplay() {
		return submittingDisplay;
	}

	public static void submittingDisplay(boolean value) {
		submittingDisplay = value;
	}

	public static void prepare(FeatureFrameContext frame, List<TextFeatureRenderer.Submit> submits, boolean ordered,
		Consumer<List<TextFeatureRenderer.Submit>> original) {
		if (!active || labels.isEmpty()) {
			original.accept(submits);
			return;
		}
		List<TextFeatureRenderer.Submit> overlay = new ArrayList<>();
		List<TextFeatureRenderer.Submit> world = new ArrayList<>();
		for (var submit : submits) {
			(labels.containsKey(submit) ? overlay : world).add(submit);
		}
		original.accept(world);
		if (overlay.isEmpty()) return;
		// See-through text uses a colour-only pass in vanilla. It cannot share a group with depth-tested text.
		boolean seeThrough = overlay.getFirst().displayMode() == Font.DisplayMode.SEE_THROUGH;
		context = frame;
		renderer.prepareGroup(frame, overlay, ordered);
		draws.add(new Draw(overlay, ordered, seeThrough));
	}

	/** Save the scene's depth before any debug overlay clears it. */
	public static void captureDepth() {
		if (failed || draws.isEmpty() || depthReady || draws.stream().allMatch(Draw::seeThrough)) return;
		try {
			GpuTexture src = Hdr.worldDepth(), output = Hdr.output();
			int width = output.getWidth(0), height = output.getHeight(0);
			var device = RenderSystem.getDevice();
			if (depth == null || depth.getWidth(0) != width || depth.getHeight(0) != height || depth.getFormat() != src.getFormat()) {
				if (depthView != null) depthView.close();
				if (depth != null) depth.close();
				depth = device.createTexture(() -> "Imagine Metal label depth", GpuTexture.USAGE_RENDER_ATTACHMENT, src.getFormat(), width, height, 1, 1);
				depthView = device.createTextureView(depth);
			}
			depthReady = Bloom.copyLabelDepth(src, depth);
			if (!depthReady) throw new IllegalStateException("depth copy unavailable");
		} catch (RuntimeException ex) {
			failed = true;
			System.out.println("[Imagine Metal] native world labels off: " + ex);
		}
	}

	public static void draw(GpuTextureView color) {
		if (draws.isEmpty() || failed) return;
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		RenderPass pass = null;
		boolean previousSeeThrough = false;
		try {
			for (int index = 0; index < draws.size(); index++) {
				Draw draw = draws.get(index);
				if (pass == null || previousSeeThrough != draw.seeThrough()) {
					if (pass != null) {
						pass.close();
						pass = null;
					}
					var descriptor = RenderPassDescriptor.builder(() -> "Imagine Metal world labels")
						.withColorAttachment(color);
					if (!draw.seeThrough()) descriptor.withDepthAttachment(depthView);
					pass = encoder.createRenderPass(descriptor.build());
					RenderSystem.bindDefaultUniforms(pass);
				}
				renderer.executeGroup(context, null, pass, index, draw.submits(), draw.ordered());
				previousSeeThrough = draw.seeThrough();
			}
		} finally {
			if (pass != null) pass.close();
		}
	}

	public static void end() {
		active = false;
		labels.clear();
		if (context != null) renderer.finishExecute(context);
		context = null;
		draws.clear();
	}

	public static boolean splitting(Object feature) {
		return active && feature instanceof TextFeatureRenderer && feature != renderer && !labels.isEmpty();
	}

	private record Draw(List<TextFeatureRenderer.Submit> submits, boolean ordered, boolean seeThrough) {
	}
}
