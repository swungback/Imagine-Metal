package mcopt.metal.lod;

import net.minecraft.world.level.levelgen.densityfunction.*;

/** Uses the game's real interpolator, including the non-block-step expansion that failed in latest.log. */
public final class DensityBatchCheck {
    static long sampled, largest;
    public static void main(String[] args) throws Exception {
        DensitySampler input = new DensitySampler() {
            public float sampleValue(SamplerContext c, int x, int y, int z) {
                return (float)(Math.sin(x * .0123) + Math.cos(z * .0187) + y * .03125);
            }
            public void sampleVolume(SamplerContext c, DensityBuffer out, DensityVolume v) {
                largest = Math.max(largest, v.size()); sampled += v.size();
                for(int z=0;z<v.sizeZ();z++) for(int x=0;x<v.sizeX();x++) for(int y=0;y<v.sizeY();y++)
                    out.set(v.indexUnchecked(x,y,z), sampleValue(c,v.minBlockX()+x*v.stepBlockX(),v.minBlockY()+y*v.stepBlockY(),v.minBlockZ()+z*v.stepBlockZ()));
            }
        };
        var ctor = Class.forName("net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction$Sampler")
            .getDeclaredConstructor(DensitySampler.class,int.class,int.class,float.class,float.class);
        ctor.setAccessible(true);
        DensitySampler sampler = (DensitySampler)ctor.newInstance(input,4,8,.25f,.125f);
        var bound = sampler.bind(SamplerContext.builder().enableCaches().build());
        DensitySamplerSet set = f -> bound;
        var optimized = LodNativeNoise.wrap(set, new Object()).get(null);
        int cases=0;
        for(int sy:new int[]{1,2,4,8}) for(int s:new int[]{1,2,4,8,16,32}) for(int origin:new int[]{-2051,-16,0,17}) {
            DensityVolume v=new DensityVolume(19,9,17,origin,-67,origin-11,s,sy,s);
            DensityBuffer a=DensityBuffer.createUnpooled(v.size()),b=DensityBuffer.createUnpooled(v.size());
            bound.sampleVolume(a,v); LodDensityBatch.sample(bound,b,v);
            for(int i=0;i<v.size();i++) if(Float.floatToIntBits(a.get(i))!=Float.floatToIntBits(b.get(i)))
                throw new AssertionError("density changed at step "+s+", origin "+origin+", index "+i+": "+a.get(i)+" vs "+b.get(i));
            cases++;
            LodDensityBatch.sample(optimized,b,v);
            for(int i=0;i<v.size();i++) if(Float.floatToIntBits(a.get(i))!=Float.floatToIntBits(b.get(i)))
                throw new AssertionError("optimized density changed at step "+s+", origin "+origin+", index "+i);
        }
        largest=0; sampled=0;
        for(int s:new int[]{4,8,16,32}) {
            DensityVolume v=new DensityVolume(65,49,65,-2051,-64,1703,s,8,s);
            DensityBuffer b=DensityBuffer.createUnpooled(v.size());
            LodDensityBatch.sample(bound,b,v);
            for(int z=0;z<v.sizeZ();z++) for(int x=0;x<v.sizeX();x++) for(int y=0;y<v.sizeY();y++) {
                float expected=bound.sampleValue(v.minBlockX()+x*s,-64+y*8,v.minBlockZ()+z*s);
                if(Math.abs(b.get(v.indexUnchecked(x,y,z))-expected)>1e-5f) throw new AssertionError("coarse mismatch");
            }
        }
        if(largest>16384) throw new AssertionError("unbounded interpolator expansion: "+largest);
        System.out.println("PASS: "+cases+" bit-exact whole/batched volumes; four full coarse tiles vs point sampling; max inner volume "+largest+" floats under 128 MiB heap");
        // The aligned path is the actual LOD tile layout. Compare to the bounded
        // Minecraft reference without allocating the original multi-gigabyte block volume.
        for(int step:new int[]{4,8,16,32}) {
            DensityVolume v=new DensityVolume(65,49,65,-2048,-64,1696,step,8,step);
            DensityBuffer a=DensityBuffer.createUnpooled(v.size()),b=DensityBuffer.createUnpooled(v.size());
            sampled=0;
            long t0=System.nanoTime(); LodDensityBatch.sample(bound,a,v); long t1=System.nanoTime();
            long reference=sampled; sampled=0;
            LodDensityBatch.sample(optimized,b,v); long t2=System.nanoTime();
            for(int i=0;i<v.size();i++) if(Float.floatToIntBits(a.get(i))!=Float.floatToIntBits(b.get(i)))
                throw new AssertionError("aligned density changed at step "+step+", index "+i);
            if(sampled!=v.size()) throw new AssertionError("lattice samples expanded: "+sampled+" vs "+v.size());
            System.out.printf("PASS aligned step %d: %d -> %d inner samples; fixture %.1f -> %.1f ms%n",
                step,reference,sampled,(t1-t0)/1e6,(t2-t1)/1e6);
        }
    }
}
