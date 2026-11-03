package imaginemetal;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The light in the player's hands (Reimagined's held lighting): any block item that gives off light when placed (torches,
 * lanterns, glowstone, modded lights too) lights the world around the player with its level and colour; the brighter hand wins.
 */
public final class HeldLight {
	private static final float[] NONE = {0f, 0f, 0f, 0f};

	private HeldLight() {
	}

	static float[] get() {
		if (!Settings.heldLight) return NONE;
		var player = Minecraft.getInstance().player;
		if (player == null) return NONE;
		float[] best = NONE;
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = player.getItemInHand(hand);
			if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) continue;
			BlockState state = bi.getBlock().defaultBlockState();
			int light = state.getLightEmission();
			if (light <= 0 || light / 15f <= best[3]) continue;
			float[] c = LightColors.of(state);
			best = new float[] {c[0], c[1], c[2], light / 15f};
		}
		return best;
	}
}
