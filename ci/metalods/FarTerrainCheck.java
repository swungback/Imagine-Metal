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

/** Terralith's real router vs LodFarTerrain's cave-free one: far surface heights and cost. */
public class FarTerrainCheck {
 public static void main(String[] args) throws Exception {
  SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
  var loc = new PackLocationInfo("fixture", Component.literal("fixture"), PackSource.DEFAULT, Optional.empty());
  var vanilla = new VanillaPackResourcesBuilder().pushJarResources().exposeNamespace("minecraft").build(loc);
  boolean terralith = args.length > 0 && !args[0].equals("vanilla");
  var packs = terralith ? List.of(vanilla.fullResources(), new VanillaPackResourcesBuilder().pushUniversalPath(Path.of(args[0])).exposeNamespace("minecraft", "terralith", "c").build(loc).fullResources()) : List.of(vanilla.fullResources());
  var keys = RegistryDataLoader.WORLD_REGISTRIES.stream().map(RegistryDataLoader.RegistryData::key).toList();
  var context = VanillaRegistries.createWorldLookup().listRegistries().filter(r -> !keys.contains(r.key())).toList();
  try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs)) {
   var regs = RegistryDataLoader.load(resources,context,RegistryDataLoader.WORLD_REGISTRIES,Runnable::run).join();
   var settings=regs.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
   var random=RandomState.create(regs.lookupOrThrow(Registries.NOISE),923478761L,settings);
   DensityFunction full=settings.noiseRouter().finalDensity();
   int[] count={0};
   DensityFunction far=LodFarTerrain.withoutCaves(full,count);
   System.out.println((terralith?"Terralith":"vanilla")+": parts left out "+count[0]+"; far = "+LodNoise.describe(far,6));
   for(int cell:new int[]{1,2}) {
    double[] tf=new double[3],tr=new double[3]; int same=0,tot=0;
    for(int run=0;run<3;run++){
     var fs=LodNativeNoise.wrap(random.samplersWithContext(SamplerContext.builder().enableCaches().build()),random);
     var rs=LodNativeNoise.wrap(random.samplersWithContext(SamplerContext.builder().enableCaches().build()),random);
     var full1=LodSolidSampler.create(full,fs); var far1=LodSolidSampler.create(far,rs);
     short[] hf=new short[4096],hr=new short[4096]; int x0=-64+run*3000,z0=64-run*1700;
     long t0=System.nanoTime(); LodSurfaceSampler.sample(full1,hf,64,cell,x0,z0,-64,319,true);
     long t1=System.nanoTime(); LodSurfaceSampler.sample(far1,hr,64,cell,x0,z0,-64,319,true);
     long t2=System.nanoTime(); tf[run]=(t1-t0)/1e6; tr[run]=(t2-t1)/1e6;
     for(int i=0;i<4096;i++){tot++; if(Math.abs(hf[i]-hr[i])<=1)same++;}
    }
    java.util.Arrays.sort(tf);java.util.Arrays.sort(tr);
    System.out.printf(Locale.ROOT,"BLOCK-EXACT cell %d 64x64 surface+3D: whole router %.1f ms, far %.1f ms; heights within 1 block %.1f%%%n",cell,tf[1],tr[1],100.0*same/tot);
   }
   {
    var a=random.samplersWithContext(SamplerContext.builder().enableCaches().build()).get(full);
    var b=random.samplersWithContext(SamplerContext.builder().enableCaches().build()).get(far);
    int n=0,same=0,within2=0,hole=0; long sum=0; int worst=0;
    for(int r=0;r<4;r++) for(int i=0;i<24;i++) for(int j=0;j<24;j++) {
     int x=-3000+r*2600+i*11, z=1500-r*1900+j*11;
     int ta=-65,tb=-65;
     for(int y=319;y>=-64&&ta==-65;y--) if(a.sampleValue(x,y,z)>0) ta=y;
     for(int y=319;y>=-64&&tb==-65;y--) if(b.sampleValue(x,y,z)>0) tb=y;
     int d=Math.abs(ta-tb); n++; if(d==0)same++; if(d<=2)within2++; sum+=d; worst=Math.max(worst,d); if(tb-ta>2)hole++;
    }
    System.out.printf(Locale.ROOT,"TRUE top solid block (point scan, %d columns): equal %.1f%%, within 2 %.1f%%, mean %.2f, worst %d, opening in the full router (far higher by >2) %.1f%%%n",n,100.0*same/n,100.0*within2/n,(double)sum/n,worst,100.0*hole/n);
   }
   for(int cell:new int[]{4,8,16}) {
    int n=64; long same=0,within2=0,total=0,fullNs=0,farNs=0; int worst=0; long sumAbs=0; int caveDrops=0;
    for(int r=0;r<4;r++) {
     int x0=-3000+r*2600, z0=1500-r*1900;
     var a=LodNativeNoise.wrap(random.samplersWithContext(SamplerContext.builder().enableCaches().build()),random).get(full);
     var b=LodNativeNoise.wrap(random.samplersWithContext(SamplerContext.builder().enableCaches().build()),random).get(far);
     short[] ha=new short[n*n], hb=new short[n*n];
     long t0=System.nanoTime(); LodSurfaceSearch.sample(a,ha,n,cell,x0,z0,-64,319,true);
     long t1=System.nanoTime(); LodSurfaceSearch.sample(b,hb,n,cell,x0,z0,-64,319,true);
     long t2=System.nanoTime();
     if(r>0){fullNs+=t1-t0; farNs+=t2-t1;}
     for(int i=0;i<n*n;i++){int d=Math.abs(ha[i]-hb[i]); total++; if(d==0)same++; if(d<=2)within2++; sumAbs+=d; worst=Math.max(worst,d); if(hb[i]-ha[i]>2)caveDrops++;}
    }
    System.out.printf(Locale.ROOT,"cell %2d: heights equal %.1f%%, within 2 blocks %.1f%%, mean |diff| %.2f, worst %d, far higher by >2 (cave mouths/caves under the surface) %.1f%%; surface search full %.1f ms -> far %.1f ms per 64x64 tile (%.1fx)%n",
      cell,100.0*same/total,100.0*within2/total,(double)sumAbs/total,worst,100.0*caveDrops/total,fullNs/3e6,farNs/3e6,(double)fullNs/Math.max(1,farNs));
   }
   {
    var lookup=regs.lookupOrThrow(Registries.DENSITY_FUNCTION);
    java.util.function.Function<String,DensityFunction> ref=id->new DensityFunctions.HolderHolder(lookup.getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DENSITY_FUNCTION,net.minecraft.resources.Identifier.parse(id))));
    Map<String,DensityFunction> parts=new LinkedHashMap<>();
    parts.put("sloped_cheese",ref.apply("minecraft:overworld/sloped_cheese"));
    if(terralith){parts.put("extra_terrain_sum",ref.apply("terralith:overworld/extra_terrain_sum"));parts.put("subtract_terrain_sum",ref.apply("terralith:overworld/subtract_terrain_sum"));}
    parts.put("caves/entrances",ref.apply("minecraft:overworld/caves/entrances"));
    parts.put("far",far);parts.put("full",full);
    for(var e:parts.entrySet()){
     var a=LodNativeNoise.wrap(random.samplersWithContext(SamplerContext.builder().enableCaches().build()),random).get(e.getValue());
     var v=new DensityVolume(65,49,65,-2048,-64,1696,8,8,8);var d=DensityBuffer.createUnpooled(v.size());LodDensityBatch.sample(a,d,v);
     v=new DensityVolume(65,49,65,3000,-64,-1500,8,8,8);long t0=System.nanoTime();LodDensityBatch.sample(a,d,v);
     System.out.printf(Locale.ROOT,"part %-22s 65x49x65 volume %.1f ms%n",e.getKey(),(System.nanoTime()-t0)/1e6);
    }
   }
   for(int cell:new int[]{4,8,16}) {
    int x=-2048,z=1696;
    var a=LodNativeNoise.wrap(random.samplersWithContext(SamplerContext.builder().enableCaches().build()),random).get(full);
    var b=LodNativeNoise.wrap(random.samplersWithContext(SamplerContext.builder().enableCaches().build()),random).get(far);
    var v=new DensityVolume(65,49,65,x,-64,z,cell,8,cell);
    var da=DensityBuffer.createUnpooled(v.size()); var db=DensityBuffer.createUnpooled(v.size());
    LodDensityBatch.sample(a,da,v); LodDensityBatch.sample(b,db,v);
    v=new DensityVolume(65,49,65,x+5000,-64,z-3000,cell,8,cell);
    long t0=System.nanoTime(); LodDensityBatch.sample(a,da,v); long t1=System.nanoTime(); LodDensityBatch.sample(b,db,v); long t2=System.nanoTime();
    System.out.printf(Locale.ROOT,"cell %2d: 3D volume 65x49x65 (overhangs) full %.1f ms -> far %.1f ms (%.1fx)%n",cell,(t1-t0)/1e6,(t2-t1)/1e6,(double)(t1-t0)/(t2-t1));
   }
  }
 }
}
