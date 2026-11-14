package mcopt.metal.own;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * (fix9b) The region as the fluid renderer sees it for a fluid block on its section's side: a neighbour in a chunk the client
 * hasn't loaded is the same fluid, not air. Vanilla's air there lowered the water's edge corners and gave it a side face down
 * to the bed along the edge of the loaded land, the thin dark line and dark notches where the real sea meets the LOD sea
 * (Jack's 22.30.23).
 */
final class OwnFluidEdge implements BlockAndTintGetter {
	private BlockAndTintGetter region;
	private @Nullable ClientLevel client;
	private BlockState fill;
	private FluidState fillFluid;
	private int cx, cz;

	OwnFluidEdge set(BlockAndTintGetter region, BlockPos pos, BlockState state, FluidState fluid) {
		this.region = region;
		this.client = Minecraft.getInstance().level;
		this.fillFluid = fluid.getType().defaultFluidState();
		this.fill = this.fillFluid.createLegacyBlock();
		this.cx = pos.getX() >> 4;
		this.cz = pos.getZ() >> 4;
		return this;
	}

	private boolean missing(BlockPos p) {
		int x = p.getX() >> 4, z = p.getZ() >> 4;
		if (x == this.cx && z == this.cz) return false;
		ClientLevel c = this.client;
		return c != null && !c.getChunkSource().hasChunk(x, z);
	}

	@Override
	public BlockState getBlockState(BlockPos p) {
		return this.missing(p) ? this.fill : this.region.getBlockState(p);
	}

	@Override
	public FluidState getFluidState(BlockPos p) {
		return this.missing(p) ? this.fillFluid : this.region.getFluidState(p);
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos p) {
		return this.region.getBlockEntity(p);
	}

	@Override
	public CardinalLighting cardinalLighting() {
		return this.region.cardinalLighting();
	}

	@Override
	public int getBlockTint(BlockPos p, ColorResolver r) {
		return this.region.getBlockTint(p, r);
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return this.region.getLightEngine();
	}

	@Override
	public int getMinY() {
		return this.region.getMinY();
	}

	@Override
	public int getHeight() {
		return this.region.getHeight();
	}
}
