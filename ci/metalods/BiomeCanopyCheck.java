package mcopt.metal.lod;
public class BiomeCanopyCheck {
 public static void main(String[] args) {
  net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
  var regs=net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
  var biome=regs.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.OLD_GROWTH_SPRUCE_TAIGA);
  var unknown=net.minecraft.core.Holder.direct(biome.value());
  LodTrees.configure(unknown,biome.value().getGenerationSettings());
  if(LodTrees.leaves(unknown).getBlock()!=net.minecraft.world.level.block.Blocks.SPRUCE_LEAVES||LodTrees.profile(unknown).cover()<=0)
   throw new AssertionError("unknown biome did not use actual spruce features");
  int trunks=0;
  for(int z=-50;z<50;z++)for(int x=-50;x<50;x++)if(CanopyShape.trunkCell(x,z,1))trunks++;
  if(trunks<300||trunks>500)throw new AssertionError("trunks are not sparse tree centres: "+trunks);
  for(int g=0;g<32;g++)for(int h=2;h<30;h++) {
   int crown=CanopyShape.crownBase(g,g+h,1);
   if(crown<=g+1 || crown>g+h)throw new AssertionError("leaves reach ground or missing crown");
  }
  System.out.println("PASS unknown-biome spruce feature profile, sparse negative/positive-coordinate trunks and raised crowns");
 }
}
