package mcopt.metal.lod;

import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;

/** fix17: LodShellSampler against LodSurfaceSampler (block-exact) on the cave-free far terrain: every height and every block's
 *  solid/air must be equal. args: Terralith pack dir (or "vanilla"), seed, tiles per level. */
public class ShellCheck {
 public static void main(String[] args) throws Exception {
  SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
  var loc = new PackLocationInfo("fixture", Component.literal("fixture"), PackSource.DEFAULT, Optional.empty());
  var vanilla = new VanillaPackResourcesBuilder().pushJarResources().exposeNamespace("minecraft").build(loc);
  boolean terralith = !args[0].equals("vanilla");
  var packs = terralith ? List.of(vanilla.fullResources(), new VanillaPackResourcesBuilder().pushUniversalPath(Path.of(args[0])).exposeNamespace("minecraft", "terralith", "c").build(loc).fullResources()) : List.of(vanilla.fullResources());
  var keys = RegistryDataLoader.WORLD_REGISTRIES.stream().map(RegistryDataLoader.RegistryData::key).toList();
  var context = VanillaRegistries.createWorldLookup().listRegistries().filter(r -> !keys.contains(r.key())).toList();
  long seed=Long.parseLong(args[1]); int n=Integer.parseInt(args[2]);
  try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs)) {
   var regs = RegistryDataLoader.load(resources,context,RegistryDataLoader.WORLD_REGISTRIES,Runnable::run).join();
   var settings=regs.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
   var random=RandomState.create(regs.lookupOrThrow(Registries.NOISE),seed,settings);
   int[] count={0};
   DensityFunction far=LodFarTerrain.withoutCaves(settings.noiseRouter().finalDensity(),count);
   Random rnd=new Random(seed);
   boolean fail=false;
   for(int l=0;l<=1;l++){
    int c=1<<l; long hDiff=0,bDiff=0,blocks=0; double tOld=0,tNew=0;
    for(int i=0;i<n;i++){
     int tx=rnd.nextInt(800)-400, tz=rnd.nextInt(800)-400; int x0=tx*64*c, z0=tz*64*c;
     for(boolean keep:new boolean[]{true,false}){
      var set=LodNativeNoise.wrap(random.samplersWithContext(SamplerContext.builder().enableCaches().build()),random);
      var solids=LodSolidSampler.create(far,set); var terrain=set.get(far);
      short[] ha=new short[4096], hb=new short[4096];
      long t0=System.nanoTime(); float[] a=LodSurfaceSampler.sample(solids,ha,64,c,x0,z0,-64,319,keep);
      long t1=System.nanoTime(); float[] b=LodShellSampler.sample(terrain,solids,hb,64,c,x0,z0,-64,319,keep);
      long t2=System.nanoTime(); if(keep){tOld+=(t1-t0)/1e6; tNew+=(t2-t1)/1e6;}
      for(int j=0;j<4096;j++) if(ha[j]!=hb[j]) hDiff++;
      if(keep) for(int j=0;j<a.length;j++){ blocks++; if((a[j]>0)!=(b[j]>0)) bDiff++; }
     }
    }
    System.out.printf(Locale.ROOT,"%s L%d %d tiles (x/z within %d blocks): heights differing %d, blocks solid/air differing %d of %d; block-exact %.1f ms -> shell %.1f ms per tile (with JIT warm-up)%n",
      terralith?"Terralith":"vanilla",l,n,400*64*c,hDiff,bDiff,blocks,tOld/n,tNew/n);
    if(hDiff>0||bDiff>0) fail=true;
   }
   System.out.println(fail?"ShellCheck FAIL":"ShellCheck PASS");
   if(fail) System.exit(1);
  }
 }
}
