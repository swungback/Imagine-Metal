package mcopt.metal.lod;

import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction;

/** Block-exact near LODs consume only solid/air decisions. For min(a,b),
 * evaluate the terrain first and only ask the cave branch over rows that can
 * contain solid terrain. Never interpolate this signed mask as a density. */
record LodSolidSampler(DensitySampler.Bound terrain, DensitySampler.Bound caves) implements DensitySampler {
    static DensitySampler.Bound create(DensityFunction function, DensitySamplerSet set) {
        DensityFunction f = function;
        for(int guard=0;guard<64;guard++) {
            if(f instanceof DensityFunctions.HolderHolder h) { f=h.function().value(); continue; }
            if(f instanceof BinaryFunction b && b.type()==BinaryFunction.Type.ADD) {
                if(beard(b.left())) { f=b.right(); continue; }
                if(beard(b.right())) { f=b.left(); continue; }
            }
            if(f instanceof BinaryFunction b && b.type()==BinaryFunction.Type.MIN) {
                var first=create(b.left(),set); var second=create(b.right(),set);
                return new DensitySampler.Bound(new LodSolidSampler(first,second),first.context());
            }
            return set.get(f);
        }
        return set.get(function);
    }
    private static boolean beard(DensityFunction f) {
        if(f instanceof DensityFunctions.HolderHolder h) return beard(h.function().value());
        return String.valueOf(f).equals("BEARDIFIER") || f.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("beardifier");
    }
    @Override public float sampleValue(SamplerContext ctx,int x,int y,int z) {
        return terrain.sampleValue(x,y,z)>0 && caves.sampleValue(x,y,z)>0 ? 1f : -1f;
    }
    @Override public void sampleVolume(SamplerContext ctx,DensityBuffer out,DensityVolume v) {
        LodDensityBatch.sample(terrain,out,v);
        int low=v.sizeY(), high=-1;
        for(int z=0;z<v.sizeZ();z++) for(int x=0;x<v.sizeX();x++) for(int y=0;y<v.sizeY();y++)
            if(out.get(v.indexUnchecked(x,y,z))>0) {low=Math.min(low,y);high=Math.max(high,y);}
        // Align crop boundaries to the original vertical lattice to preserve
        // Minecraft's repeated-add interpolation rounding for every retained row.
        if(high<0) {
            for(int i=0;i<v.size();i++)out.set(i,-1f);
            return;
        }
        if(v.stepBlockY()!=1 || Math.floorMod(v.minBlockY(),8)!=0) {low=0;high=v.sizeY()-1;}
        else { low=low/8*8; high=Math.min(v.sizeY()-1,(high/8+1)*8-1); }
        DensityVolume part=new DensityVolume(v.sizeX(),high-low+1,v.sizeZ(),v.minBlockX(),v.minBlockY()+low*v.stepBlockY(),v.minBlockZ(),v.stepBlockX(),v.stepBlockY(),v.stepBlockZ());
        try(var other=LodBuffers.acquire(part.size())){
        LodDensityBatch.sample(caves,other,part);
        for(int z=0;z<v.sizeZ();z++)for(int x=0;x<v.sizeX();x++)for(int y=0;y<v.sizeY();y++) {
            int at=v.indexUnchecked(x,y,z);
            boolean solid=out.get(at)>0 && y>=low && y<=high && other.get(part.indexUnchecked(x,y-low,z))>0;
            out.set(at,solid?1f:-1f);
        }
        }
    }
    /** Exact heights without evaluating caves below an already found surface. */
    void sampleSurface(SamplerContext ctx,DensityBuffer out,DensityVolume v) {
        if(v.stepBlockY()!=1 || Math.floorMod(v.minBlockY(),8)!=0) {sampleVolume(ctx,out,v);return;}
        LodDensityBatch.sample(terrain,out,v);
        int count=v.sizeX()*v.sizeZ(),ny=v.sizeY(),remaining=0;
        int[] next=new int[count],found=new int[count];java.util.Arrays.fill(found,-1);
        boolean[] done=new boolean[count];
        for(int i=0;i<count;i++) {
            int y=ny-1;while(y>=0 && !(out.get(i*ny+y)>0))y--;
            next[i]=y;done[i]=y<0;if(y>=0)remaining++;
        }
        while(remaining>0) {
            int low=ny,high=-1;
            for(int i=0;i<count;i++)if(!done[i]) {low=Math.min(low,Math.max(0,next[i]-15));high=Math.max(high,next[i]);}
            low=low/8*8;high=Math.min(ny-1,(high/8+1)*8-1);
            DensityVolume band=new DensityVolume(v.sizeX(),high-low+1,v.sizeZ(),v.minBlockX(),v.minBlockY()+low,v.minBlockZ(),v.stepBlockX(),1,v.stepBlockZ());
            try(var values=LodBuffers.acquire(band.size())){LodDensityBatch.sample(caves,values,band);
            for(int i=0;i<count;i++)if(!done[i]) {
                int y=next[i];
                while(y>=low && !(out.get(i*ny+y)>0 && values.get((i*(high-low+1))+y-low)>0))y--;
                if(y>=low || low==0) {found[i]=y;done[i]=true;remaining--;}
                else next[i]=low-1;
            }
            }
        }
        for(int i=0;i<count;i++)for(int y=0;y<ny;y++)out.set(i*ny+y,y==found[i]?1f:-1f);
    }

}
