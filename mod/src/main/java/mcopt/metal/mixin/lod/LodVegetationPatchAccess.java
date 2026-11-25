package mcopt.metal.mixin.lod;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.VegetationPatchFeature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VegetationPatchFeature.class)
public interface LodVegetationPatchAccess {
    @Accessor("groundState")
    Holder<BlockStateProvider> mcopt$groundState();

    @Accessor("replaceable")
    HolderSet<Block> mcopt$replaceable();
}
