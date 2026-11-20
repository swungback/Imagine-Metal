package mcopt.metal.lod;

import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/** An interpolated function evaluated exactly on its lattice needs no intermediate blocks.
 * Minecraft already does this for steps equal to the cell width. LOD steps are
 * multiples of that width, for which its generic path instead expands every block
 * in the footprint, only to discard all the intermediate values. */
record LodLatticeSampler(DensitySampler original, DensitySampler input, int cellXz, int cellY)
        implements DensitySampler {
    private static boolean aligned(int origin, int step, int size, int cell) {
        return Math.floorMod(origin, cell) == 0 && (size == 1 || step % cell == 0);
    }

    @Override
    public float sampleValue(SamplerContext context, int x, int y, int z) {
        return original.sampleValue(context, x, y, z);
    }

    @Override
    public void sampleVolume(SamplerContext context, DensityBuffer out, DensityVolume v) {
        if (aligned(v.minBlockX(), v.stepBlockX(), v.sizeX(), cellXz)
                && aligned(v.minBlockY(), v.stepBlockY(), v.sizeY(), cellY)
                && aligned(v.minBlockZ(), v.stepBlockZ(), v.sizeZ(), cellXz)) {
            input.sampleVolume(context, out, v);
        } else {
            sampleRequested(context, out, v);
        }
    }
    /** Match Minecraft's volume interpolation order, including its repeated
     * vertical addition, without constructing unrequested intermediate blocks. */
    private void sampleRequested(SamplerContext context, DensityBuffer out, DensityVolume v) {
        int x0 = Math.floorDiv(v.minBlockX(), cellXz) * cellXz;
        int y0 = Math.floorDiv(v.minBlockY(), cellY) * cellY;
        int z0 = Math.floorDiv(v.minBlockZ(), cellXz) * cellXz;
        int nx = Math.floorDiv(v.minBlockX() + (v.sizeX()-1)*v.stepBlockX() - x0, cellXz) + 2;
        int ny = Math.floorDiv(v.minBlockY() + (v.sizeY()-1)*v.stepBlockY() - y0, cellY) + 2;
        int nz = Math.floorDiv(v.minBlockZ() + (v.sizeZ()-1)*v.stepBlockZ() - z0, cellXz) + 2;
        if ((long)nx*ny*nz > 1_000_000 || out.size() != v.size()) {
            original.sampleVolume(context, out, v);
            return;
        }
        DensityVolume corners = new DensityVolume(nx,ny,nz,x0,y0,z0,cellXz,cellY,cellXz);
        var d = LodBuffers.acquire(corners.size());
        input.sampleVolume(context,d,corners);
        float ix = 1.0f/cellXz, iy = 1.0f/cellY;
        for(int z=0;z<v.sizeZ();z++) for(int x=0;x<v.sizeX();x++) {
            int wx=v.minBlockX()+x*v.stepBlockX(), wz=v.minBlockZ()+z*v.stepBlockZ();
            int cx=Math.floorDiv(wx-x0,cellXz), cz=Math.floorDiv(wz-z0,cellXz);
            float fx=Math.floorMod(wx,cellXz)*ix, fz=Math.floorMod(wz,cellXz)*ix;
            for(int y=0;y<v.sizeY();y++) {
                int wy=v.minBlockY()+y*v.stepBlockY(), cy=Math.floorDiv(wy-y0,cellY);
                float lo=net.minecraft.util.Mth.lerp(fx,
                    net.minecraft.util.Mth.lerp(fz,d.get(corners.indexUnchecked(cx,cy,cz)),d.get(corners.indexUnchecked(cx,cy,cz+1))),
                    net.minecraft.util.Mth.lerp(fz,d.get(corners.indexUnchecked(cx+1,cy,cz)),d.get(corners.indexUnchecked(cx+1,cy,cz+1))));
                float hi=net.minecraft.util.Mth.lerp(fx,
                    net.minecraft.util.Mth.lerp(fz,d.get(corners.indexUnchecked(cx,cy+1,cz)),d.get(corners.indexUnchecked(cx,cy+1,cz+1))),
                    net.minecraft.util.Mth.lerp(fz,d.get(corners.indexUnchecked(cx+1,cy+1,cz)),d.get(corners.indexUnchecked(cx+1,cy+1,cz+1))));
                int start=Math.max(0,v.minBlockY()-(y0+cy*cellY));
                int offset=Math.floorMod(wy,cellY);
                float delta=(hi-lo)*iy, value=lo+delta*start;
                for(int k=start;k<offset;k++) value+=delta;
                out.set(v.indexUnchecked(x,y,z),value);
            }
        }
        d.close();
    }

}
