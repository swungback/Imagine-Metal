package imaginemetal.metalods;
import net.minecraft.world.level.block.Blocks;
public class CanopyRepairCheck {
 public static void main(String[] args) {
  net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
  var regs=net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
  MetaPalette.BIOME=regs.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.TAIGA);
  int grass=MetaPalette.id(Blocks.GRASS_BLOCK.defaultBlockState()),leaf=MetaPalette.id(Blocks.SPRUCE_LEAVES.defaultBlockState()),water=MetaPalette.id(Blocks.WATER.defaultBlockState());
  for(int level:new int[]{0,1,2}) {
   Rle.Builder b=new Rle.Builder(32,64);int[] col=new int[64];
   for(int z=0;z<32;z++)for(int x=0;x<32;x++) {java.util.Arrays.fill(col,15<<24);java.util.Arrays.fill(col,0,9,grass|1<<16);java.util.Arrays.fill(col,9,30,leaf|1<<16);if(x==0&&z==0)java.util.Arrays.fill(col,9,16,water|1<<16);b.column(x,z,col);}
   var repair=new MetaCanopy();Rle fixed=repair.apply(b.build(),level,-2,3,-64,15);
   for(int z=0;z<32;z++)for(int x=0;x<32;x++) {fixed.expand(x,z,col,0);if(MetaPalette.kind(Voxel.id(col[9]))==Voxel.KIND_LEAVES&&MetaPalette.kind(Voxel.id(col[10]))==Voxel.KIND_LEAVES)throw new AssertionError("leaf wall still meets ground");if(x==0&&z==0&&Voxel.id(col[12])!=water)throw new AssertionError("water changed");if(Voxel.id(col[8])!=grass)throw new AssertionError("ground changed");}
   if(!fixed.sameData(repair.apply(fixed,level,-2,3,-64,15)))throw new AssertionError("cache repair not idempotent");
  }
  System.out.println("PASS old leaf-wall repair, ground preservation and idempotence at levels0..2");
 }
}
