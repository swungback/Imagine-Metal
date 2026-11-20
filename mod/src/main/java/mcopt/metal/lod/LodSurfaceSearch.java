package mcopt.metal.lod;

import net.minecraft.world.level.levelgen.densityfunction.*;

/** Batched heightfield search. The same 32->16->8 brackets as coarseSurface,
 * with midpoint planes evaluated together rather than thousands of point calls. */
final class LodSurfaceSearch {
    static void sample(DensitySampler.Bound sampler,short[] heights,int n,int cell,int x0,int z0,int minY,int maxY) {
        sample(sampler,heights,n,cell,x0,z0,minY,maxY,true);
    }
    static void sample(DensitySampler.Bound sampler,short[] heights,int n,int cell,int x0,int z0,int minY,int maxY,boolean refine) {
        int y0=Math.floorDiv(minY,32)*32,ny=(maxY-y0)/32+1;
        DensityVolume v=new DensityVolume(n,ny,n,x0,y0,z0,cell,32,cell);
        DensityBuffer d=DensityBuffer.createUnpooled(v.size());
        LodDensityBatch.sample(sampler,d,v);
        int count=n*n;
        int[] lo=new int[count],hi=new int[count];float[] vl=new float[count],vh=new float[count];
        boolean[] active=new boolean[count];
        for(int i=0;i<count;i++) {
            int k=ny-1;
            while(k>=0 && d.get(i*ny+k)<=0)k--;
            while(k>=1 && d.get(i*ny+k-1)<=0) {k--;while(k>=0 && d.get(i*ny+k)<=0)k--;}
            if(k<0)heights[i]=(short)minY;
            else if(k==ny-1)heights[i]=(short)(y0+k*32+1);
            else {active[i]=true;lo[i]=y0+k*32;hi[i]=lo[i]+32;vl[i]=d.get(i*ny+k);vh[i]=d.get(i*ny+k+1);}
        }
        int bandLow=Integer.MAX_VALUE,bandHigh=Integer.MIN_VALUE;
        for(int i=0;i<count;i++)if(active[i]) {bandLow=Math.min(bandLow,lo[i]+8);bandHigh=Math.max(bandHigh,hi[i]-8);}
        if(refine && bandHigh>=bandLow) {
            int bandNy=(bandHigh-bandLow)/8+1;
            DensityVolume band=new DensityVolume(n,bandNy,n,x0,bandLow,z0,cell,8,cell);
            DensityBuffer values=DensityBuffer.createUnpooled(band.size());
            LodDensityBatch.sample(sampler,values,band);
            for(int step=16;step>=8;step/=2)for(int i=0;i<count;i++)if(active[i]) {
                int mid=lo[i]+step;
                float value=values.get(band.indexUnchecked(i%n,(mid-bandLow)/8,i/n));
                if(value>0){lo[i]=mid;vl[i]=value;}else{hi[i]=mid;vh[i]=value;}
            }
        }
        for(int i=0;i<count;i++)if(active[i])heights[i]=(short)((int)Math.floor(lo[i]+vl[i]/(vl[i]-vh[i])*(hi[i]-lo[i]))+1);
    }
}
