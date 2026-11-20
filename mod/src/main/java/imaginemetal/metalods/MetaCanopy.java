package imaginemetal.metalods;

import mcopt.metal.lod.CanopyShape;
import mcopt.metal.lod.LodTrees;
import net.minecraft.tags.BlockTags;

/** Repair estimated vegetation when deriving seed columns, including old caches.
 * Actual captured chunks and exact level-zero feature trees bypass this path. */
final class MetaCanopy {
    private static final Object SAME=new Object();
    private final java.util.Map<Rle,Object> cache=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static boolean isLog(int voxel) {
        var state=MetaPalette.state(Voxel.id(voxel));
        if(state.is(BlockTags.LOGS))return true;
        // Estimated trunks also work before data-pack tags have been bound.
        var biome=MetaPalette.biomeHolder(Voxel.biome(voxel));
        return biome!=null&&state.getBlock()==LodTrees.trunk(biome).getBlock();
    }
    Rle apply(Rle base,int level,int cx,int cz,int minY,int sky) {
        Object known=cache.get(base);if(known!=null)return known instanceof Rle r?r:base;
        int cell=1<<level,x0=cx*32*cell,z0=cz*32*cell;
        int[] col=new int[base.h],old=new int[base.h];
        Rle.Builder builder=new Rle.Builder(32,base.h);boolean changed=false;
        for(int z=0;z<32;z++)for(int x=0;x<32;x++) {
            base.expand(x,z,col,0);System.arraycopy(col,0,old,0,col.length);
            int ground=-1,water=-1;
            int column=x+z*32;
            for(int r=base.offsets[column+1]-1;r>=base.offsets[column];r--) {
                int voxel=base.runs[2*r],kind=MetaPalette.kind(Voxel.id(voxel));
                int top=base.runs[2*r+1]-1;
                if(kind==Voxel.KIND_WATER){water=Math.max(water,top);continue;}
                if(kind==Voxel.KIND_AIR||kind==Voxel.KIND_LEAVES||isLog(voxel))continue;
                ground=top;break;
            }
            if(ground>=0) {
                var biome=MetaPalette.biomeHolder(Voxel.biome(col[ground]));
                if(biome!=null) {
                    for(int y=ground+1;y<col.length;y++) {
                        int id=Voxel.id(col[y]);if(id==0)continue;
                        int kind=MetaPalette.kind(id);
                        if(kind==Voxel.KIND_LEAVES||kind==Voxel.KIND_SOLID&&isLog(col[y]))col[y]=(sky&15)<<24;
                    }
                    int bx=x0+x*cell+cell/2,bz=z0+z*cell+cell/2;
                    var groundState = MetaPalette.state(Voxel.id(col[ground]));
                    var leaves = LodTrees.leaves(biome);
                    int biomeBits = col[ground] & 0x00ff0000;
                    if (water <= ground && cell <= 4 && leaves.is(net.minecraft.world.level.block.Blocks.SPRUCE_LEAVES)) {
                        var spruce = LodTrees.spruce(biome, groundState, bx, bz, cell);
                        if (spruce.trunkVoxels() > 0) {
                            int trunk = biomeBits | MetaPalette.id(LodTrees.trunk(biome));
                            java.util.Arrays.fill(col, ground + 1, Math.min(col.length, ground + 1 + spruce.trunkVoxels()), trunk);
                        }
                        if (spruce.layers() != 0) {
                            int leaf = biomeBits | MetaPalette.id(leaves);
                            for (long bits = spruce.layers(); bits != 0; bits &= bits - 1) {
                                int y = ground + 1 + Long.numberOfTrailingZeros(bits);
                                if (y < col.length) col[y] = leaf;
                            }
                        }
                    } else if (water <= ground) {
                        int height = LodTrees.canopy(biome, groundState, bx, bz, cell);
                        int top = Math.min(col.length - 1, ground + (height + cell - 1) / cell);
                        if (height > 0 && top > ground) {
                            int crown = CanopyShape.crownBase(ground, top, cell);
                            if (CanopyShape.trunkCell(bx, bz, cell)) {
                                java.util.Arrays.fill(col, ground + 1, crown, biomeBits | MetaPalette.id(LodTrees.trunk(biome)));
                            }
                            java.util.Arrays.fill(col, crown, top + 1, biomeBits | MetaPalette.id(leaves));
                        }
                    }
                }
            }
            changed|=!java.util.Arrays.equals(col,old);builder.column(x,z,col);
        }
        Rle result=changed?builder.build():base;cache.put(base,changed?result:SAME);return result;
    }
}
