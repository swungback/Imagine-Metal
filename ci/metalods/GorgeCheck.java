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
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.block.state.BlockState;

/** The game's own fill (final density + aquifers) vs the final density alone: where the aquifers' barriers make ground. */
public class GorgeCheck {
 public static void main(String[] args) throws Exception {
  SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
  var loc = new PackLocationInfo("fixture", Component.literal("fixture"), PackSource.DEFAULT, Optional.empty());
  var vanilla = new VanillaPackResourcesBuilder().pushJarResources().exposeNamespace("minecraft").build(loc);
  var packs = List.of(vanilla.fullResources(), new VanillaPackResourcesBuilder().pushUniversalPath(Path.of(args[0])).exposeNamespace("minecraft", "terralith", "c").build(loc).fullResources());
  var keys = RegistryDataLoader.WORLD_REGISTRIES.stream().map(RegistryDataLoader.RegistryData::key).toList();
  var context = VanillaRegistries.createWorldLookup().listRegistries().filter(r -> !keys.contains(r.key())).toList();
  long seed = Long.parseLong(args[1]);
  int cx0=Integer.parseInt(args[2]), cz0=Integer.parseInt(args[3]), ncx=Integer.parseInt(args[4]), ncz=Integer.parseInt(args[5]);
  try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs)) {
   var regs = RegistryDataLoader.load(resources,context,RegistryDataLoader.WORLD_REGISTRIES,Runnable::run).join();
   var settings=regs.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
   var random=RandomState.create(regs.lookupOrThrow(Registries.NOISE),seed,settings);
   var m=NoiseBasedChunkGenerator.class.getDeclaredMethod("createFluidPicker", NoiseGeneratorSettings.class); m.setAccessible(true);
   var picker=(Aquifer.FluidPicker)m.invoke(null,settings);
   var ns=settings.noiseSettings();
   int W=ncx*16,H=ncz*16; int[] topD=new int[W*H], topA=new int[W*H], wat=new int[W*H];
   long t0=System.nanoTime();
   for(int a=0;a<ncx;a++) for(int b=0;b<ncz;b++){
    int x0=(cx0+a)*16,z0=(cz0+b)*16;
    var vol=new DensityVolume(16,ns.height(),16,x0,ns.minY(),z0);
    try(var nc=new NoiseChunk(random,Beardifier.EMPTY,settings,picker,Blender.empty(),vol)){
     var buf=nc.cachingSamplers().get(settings.noiseRouter().finalDensity()).sampleVolume(vol);
     var aq=nc.aquifer();
     for(int x=0;x<16;x++) for(int z=0;z<16;z++){
      int td=-999,ta=-999,tw=-999;
      for(int y=vol.sizeY()-1;y>=0;y--){
       int by=vol.blockY(y); float d=buf.get(vol.indexUnchecked(x,y,z));
       boolean solid=d>0; BlockState s=null;
       if(!solid){ s=aq.computeSubstance(x0+x,by,z0+z,d); if(s==null) solid=true; }
       if(solid){ if(td==-999 && d>0) td=by; if(ta==-999) ta=by; }
       else if(tw==-999 && ta==-999 && !s.getFluidState().isEmpty()) tw=by;
       if(td!=-999 && ta!=-999) break;
      }
      int i=(b*16+z)*W+a*16+x; topD[i]=td; topA[i]=ta; wat[i]=tw;
     }
     buf.close();
    }
   }
   System.out.printf("fill %d chunks %.0f ms%n",ncx*ncz,(System.nanoTime()-t0)/1e6);
   int diff=0,big=0;
   for(int i=0;i<W*H;i++){int d=topA[i]-topD[i]; if(d!=0)diff++; if(d>3)big++;}
   System.out.printf("columns %d: aquifer ground differs %d, higher by >3: %d%n",W*H,diff,big);
   // map every 4 blocks: char by (topA-topD)
   StringBuilder sb=new StringBuilder();
   for(int z=0;z<H;z+=4){ sb.append(String.format("%5d ",cz0*16+z)); for(int x=0;x<W;x+=2){int i=z*W+x; int d=topA[i]-topD[i]; sb.append(d<=0?(wat[i]>-999?'~':'.'):d<=3?'1':d<=10?'5':d<=30?'X':'#');} sb.append('\n'); }
   System.out.print(sb);
   if(args.length>7){ for(int z=0;z<H;z+=2){StringBuilder r=new StringBuilder(String.format("%5d ",cz0*16+z)); for(int x=0;x<W;x+=2) r.append(String.format("%3d",topD[z*W+x]%1000)); System.out.println(r);} }
   if(args.length>6){ int qx=Integer.parseInt(args[6])-cx0*16; for(int z=0;z<H;z+=4){int i=z*W+qx; System.out.printf("x=%d z=%d final %d, game %d, water %d%n",qx+cx0*16,z+cz0*16,topD[i],topA[i],wat[i]);} }
  }
 }
}
