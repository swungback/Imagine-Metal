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

/** Loads the installed Terralith data through Minecraft's registry loader; no world/server opened. */
public class PackRouterCheck {
 public static void main(String[] args) throws Exception {
  SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
  var loc = new PackLocationInfo("fixture", Component.literal("fixture"), PackSource.DEFAULT, Optional.empty());
  var vanilla = new VanillaPackResourcesBuilder().pushJarResources().exposeNamespace("minecraft").build(loc);
  var pack = new VanillaPackResourcesBuilder().pushUniversalPath(Path.of(args[0])).exposeNamespace("minecraft", "terralith", "c").build(loc);
  var keys = RegistryDataLoader.WORLD_REGISTRIES.stream().map(RegistryDataLoader.RegistryData::key).toList();
  var context = VanillaRegistries.createWorldLookup().listRegistries().filter(r -> !keys.contains(r.key())).toList();
  try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanilla.fullResources(),pack.fullResources()))) {
   var regs = RegistryDataLoader.load(resources,context,RegistryDataLoader.WORLD_REGISTRIES,Runnable::run).join();
   if(args.length>1&&args[1].equals("canopy")) {
    var biomes=regs.lookupOrThrow(Registries.BIOME);
    int[] count={0};biomes.listElements().forEach(b->{LodTrees.configure(b,b.value().getGenerationSettings());if(LodTrees.profile(b).cover()>0)count[0]++;});
    for(String name:new String[]{"terralith:valley_clearing","minecraft:old_growth_spruce_taiga","minecraft:dark_forest"}) {
     var key=net.minecraft.resources.ResourceKey.create(Registries.BIOME,net.minecraft.resources.Identifier.parse(name));
     var b=biomes.getOrThrow(key);var profile=LodTrees.profile(b);
     if(profile.cover()<=0)throw new AssertionError("missing forest profile: "+name);
     System.out.println("PASS actual pack biome "+name+" leaves="+profile.leaves()+" height="+profile.height());
    }
    System.out.println("PASS "+count[0]+" forest profiles from installed pack registries");return;
   }
   var settings=regs.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
   var random=RandomState.create(regs.lookupOrThrow(Registries.NOISE),923478761L,settings);
   var original=random.samplersWithContext(SamplerContext.builder().enableCaches().build()).get(settings.noiseRouter().finalDensity());
   var fastSet=LodNativeNoise.wrap(random.samplersWithContext(SamplerContext.builder().enableCaches().build()),random);
   var fast=fastSet.get(settings.noiseRouter().finalDensity());
   var solids=LodSolidSampler.create(settings.noiseRouter().finalDensity(),fastSet);
   for(int cell:new int[]{1,2}) {
    int size=8,x0=-24,z0=112,minY=-64,maxY=319;
    short[] heights=new short[size*size];
    float[] density=LodSurfaceSampler.sample(solids,heights,size,cell,x0,z0,minY,maxY,true);
    for(int z=0;z<size;z++) for(int x=0;x<size;x++) {
     int top=minY;
     for(int y=minY;y<=maxY;y++) {
      float d=original.sampleValue(x0+x*cell+cell/2,y,z0+z*cell+cell/2);
      float actual=density[(z*size+x)*(maxY-minY+1)+y-minY];
      if((d>0)!=(actual>0)) throw new AssertionError("Terralith solid mismatch");
      if(d>0) top=y+1;
     }
     if(top!=heights[z*size+x]) throw new AssertionError("Terralith surface mismatch");
    }
    System.out.println("PASS Terralith cell "+cell+": all nearby solid/air decisions and 64 heights vs actual point sampler");
   }
   for(int cell:new int[]{1,2}) {
    short[] full=new short[64*64], check=new short[64*64];
    double[] before=new double[3],after=new double[3];
    for(int run=0;run<3;run++) {
     long t0=System.nanoTime();
     float[] a=LodSurfaceSampler.sample(fast,full,64,cell,-64,64,-64,319,true);
     long t1=System.nanoTime();
     float[] b=LodSurfaceSampler.sample(solids,check,64,cell,-64,64,-64,319,true);
     long t2=System.nanoTime();
     if(!java.util.Arrays.equals(full,check))throw new AssertionError("optimized surface changed");
     for(int i=0;i<a.length;i++)if((a[i]>0)!=(b[i]>0))throw new AssertionError("optimized cave/terrain sign changed");
     before[run]=(t1-t0)/1e6;after[run]=(t2-t1)/1e6;
    }
    java.util.Arrays.sort(before);java.util.Arrays.sort(after);
    System.out.printf("BENCH Terralith exact 64x64 cell %d surface+3D: %.1f -> %.1f ms, all signs/heights identical in 3 runs%n",cell,before[1],after[1]);
   }
   for(int cell:new int[]{1,2}) {
    short[] preview=new short[4096],full=new short[4096];
    double[] times=new double[3];
    LodSurfaceSampler.sample(solids,full,64,cell,-64,64,-64,319,true);
    for(int run=0;run<3;run++) {
     long t0=System.nanoTime();LodSurfaceSampler.sample(solids,preview,64,cell,-64,64,-64,319,false);times[run]=(System.nanoTime()-t0)/1e6;
     if(!java.util.Arrays.equals(preview,full))throw new AssertionError("surface-only changed near heights");
    }
    java.util.Arrays.sort(times);System.out.printf("BENCH exact near surface-first cell %d: median %.1f ms, heights equal full 3D%n",cell,times[1]);
   }
   for(int cell:new int[]{4,8,16,32}) {
    int n=16,minY=-64,maxY=319,x0=-2048,z0=1696;
    short[] actual=new short[n*n];
    LodSurfaceSearch.sample(fast,actual,n,cell,x0,z0,minY,maxY);
    int y0=Math.floorDiv(minY,32)*32,ny=(maxY-y0)/32+1;
    var v=new DensityVolume(n,ny,n,x0,y0,z0,cell,32,cell);
    var d=DensityBuffer.createUnpooled(v.size());LodDensityBatch.sample(fast,d,v);
    for(int i=0;i<n*n;i++) {
     int k=ny-1;while(k>=0&&d.get(i*ny+k)<=0)k--;
     while(k>=1&&d.get(i*ny+k-1)<=0){k--;while(k>=0&&d.get(i*ny+k)<=0)k--;}
     int h;
     if(k<0)h=minY;else if(k==ny-1)h=y0+k*32+1;
     else {
      int lo=y0+k*32,hi=lo+32;float vl=d.get(i*ny+k),vh=d.get(i*ny+k+1);
      for(int step=16;step>=8;step/=2){int mid=lo+step;float value=fast.sampleValue(x0+i%n*cell,mid,z0+i/n*cell);if(value>0){lo=mid;vl=value;}else{hi=mid;vh=value;}}
      h=(int)Math.floor(lo+vl/(vl-vh)*(hi-lo))+1;
     }
     if(actual[i]!=h)throw new AssertionError("batched surface search differs from point search at cell "+cell+", index "+i+": "+actual[i]+" vs "+h);
    }
    System.out.println("PASS batched surface search cell "+cell+": 256 heights equal old point search");
   }
   for(int cell:new int[]{4,8,16,32}) {
    short[] heights=new short[64*64];double[] elapsed=new double[3];
    for(int r=0;r<3;r++){long t0=System.nanoTime();LodSurfaceSearch.sample(fast,heights,64,cell,-2048,1696,-64,319,false);elapsed[r]=(System.nanoTime()-t0)/1e6;}
    java.util.Arrays.sort(elapsed);System.out.printf("BENCH surface-first 64x64 cell %d: median %.1f ms%n",cell,elapsed[1]);
   }
   for(int cell:new int[]{4,8,16,32}) {
    double[] before=new double[3],after=new double[3];
    for(int run=0;run<3;run++) {
     int x=-2048+run*4096,z=1696-run*2048;
     var a=LodNativeNoise.wrap(random.samplersWithContext(SamplerContext.builder().enableCaches().build()),random).get(settings.noiseRouter().finalDensity());
     var b=LodNativeNoise.wrap(random.samplersWithContext(SamplerContext.builder().enableCaches().build()),random).get(settings.noiseRouter().finalDensity());
     var v=new DensityVolume(65,49,65,x,-64,z,cell,8,cell);var data=DensityBuffer.createUnpooled(v.size());
     short[] heights=new short[4096];
     long t0=System.nanoTime();LodDensityBatch.sample(a,data,v);long t1=System.nanoTime();
     LodSurfaceSearch.sample(b,heights,64,cell,x,z,-64,319,false);long t2=System.nanoTime();
     before[run]=(t1-t0)/1e6;after[run]=(t2-t1)/1e6;
    }
    java.util.Arrays.sort(before);java.util.Arrays.sort(after);
    System.out.printf("BENCH fresh contexts/positions cell %d: full density %.1f -> surface %.1f ms (%.1fx)%n",cell,before[1],after[1],before[1]/after[1]);
   }
   for(int step:new int[]{4,8,16}) {
    var v=new DensityVolume(65,49,65,-2048,-64,1696,step,8,step);
    var a=DensityBuffer.createUnpooled(v.size());var b=DensityBuffer.createUnpooled(v.size());
    var old=((LodDensityBatch.SparseRoot)fast.sampler()).input().bind(fast.context());
    for(int run=0;run<3;run++) {
     long t0=System.nanoTime(); LodDensityBatch.sample(old,a,v);long t1=System.nanoTime();LodDensityBatch.sample(fast,b,v);long t2=System.nanoTime();
     for(int i=0;i<v.size();i++) if(Float.floatToIntBits(a.get(i))!=Float.floatToIntBits(b.get(i))) throw new AssertionError("Terralith bulk mismatch");
     System.out.printf("PASS Terralith step %d run %d: old batching %.1f -> bulk %.1f ms, bit exact%n",step,run,(t1-t0)/1e6,(t2-t1)/1e6);
    }
   }
  }
 }
}
