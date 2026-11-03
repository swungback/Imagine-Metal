package mcopt.metal.mixin.chunk;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * D2/D5 (-Dmcopt.chunk.chunkGet=true): every block or fluid read and write of a chunk (LevelChunk/ProtoChunk getBlockState,
 * getFluidState, setBlockState...) maps the block Y to a section index through getSectionIndex -> getMinSectionY -> getMinY ->
 * the height accessor (a Level: dimensionType() -> Holder.value() -> minY). That chain is constant for a chunk (only ChunkAccess
 * defines getMinY, from its final levelHeightAccessor), so it is evaluated once when the chunk is constructed. Calls made during
 * construction take the original chain.
 */
@Mixin(ChunkAccess.class)
abstract class ChunkAccessSectionMixin implements LevelHeightAccessor {
	@Shadow @Final protected LevelHeightAccessor levelHeightAccessor;
	@Unique private int mcopt$minSectionY;
	@Unique private boolean mcopt$minSet;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void mcopt$cacheMinSection(CallbackInfo ci) {
		this.mcopt$minSectionY = SectionPos.blockToSectionCoord(this.levelHeightAccessor.getMinY());
		this.mcopt$minSet = true;
	}

	@Override
	public int getSectionIndex(int blockY) {
		return SectionPos.blockToSectionCoord(blockY) - (this.mcopt$minSet ? this.mcopt$minSectionY : this.getMinSectionY());
	}
}
