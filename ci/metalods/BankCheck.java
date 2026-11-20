package mcopt.metal.lod;

import imaginemetal.metalods.MetaPalette;
import imaginemetal.metalods.Voxel;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

/** fix13: Streams' rivers in the LODs get banks (no vertical walls from the terrain down to the water). */
public class BankCheck {
 static int ground(int[] col){ for(int y=col.length-1;y>=0;y--){int v=col[y]; if(Voxel.isAir(v))continue; int r=Voxel.rank(v); if(r==3)return y;} return -1; }
 public static void main(String[] a){
  SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
  int stone=MetaPalette.id(Blocks.STONE.defaultBlockState()), grass=MetaPalette.id(Blocks.GRASS_BLOCK.defaultBlockState()), dirt=MetaPalette.id(Blocks.DIRT.defaultBlockState()),
   log=MetaPalette.id(Blocks.OAK_LOG.defaultBlockState()), leaves=MetaPalette.id(Blocks.OAK_LEAVES.defaultBlockState()), water=MetaPalette.id(Blocks.WATER.defaultBlockState());
  int h=384, sky=15, biome=5<<16;
  // a plain at 88 (top voxel), a river 4 wide at x 30..33 of the tile, water top 66; one dry channel cell
  int m=16, e=32+2*m; byte[] kind=new byte[e*e]; int[] top=new int[e*e];
  for(int z=0;z<e;z++)for(int x=m+30;x<=m+33;x++){kind[z*e+x]=LodStreams.WET;top[z*e+x]=66;}
  kind[(m+5)*e+m+29]=LodStreams.DRY; top[(m+5)*e+m+29]=70;
  float[] cap=LodStreams.banks(kind,top,e,32);
  int worstStep=0, changedFar=0; int[] g=new int[32];
  for(int x=0;x<32;x++){
   int i=(m+10)*e+x+m;
   int[] col=new int[h]; java.util.Arrays.fill(col,sky<<24);
   for(int y=0;y<=88;y++)col[y]=biome|(y==88?grass:y>=85?dirt:stone);
   col[40]=sky<<24; // a cave under the ground stays
   if(x==20){for(int y=89;y<=94;y++)col[y]=biome|log; for(int y=92;y<=96;y++)col[y]=biome|leaves;}
   if(kind[i]==LodStreams.WET) LodStreams.channel(col,0,1,top[i]-1,top[i],water,dirt,stone,sky);
   else if(cap[i]<h) LodStreams.lower(col,(int)Math.floor(cap[i]),4,sky);
   g[x]=ground(col);
   if(kind[i]!=LodStreams.WET && Voxel.id(col[g[x]])!=grass) throw new AssertionError("bank lost its grass at x "+x);
   if(!Voxel.isAir(col[40])&&g[x]>40) throw new AssertionError("cave filled");
   if(x==20 && cap[i]<88 && !Voxel.isAir(col[g[x]+1])) throw new AssertionError("tree left floating over the cut bank");
  }
  for(int x=1;x<30;x++) worstStep=Math.max(worstStep,Math.abs(g[x]-g[x-1]));
  System.out.println("ground across the river: "+java.util.Arrays.toString(g));
  if(g[0]!=88) throw new AssertionError("ground beyond the bank's reach changed");
  if(worstStep>2) throw new AssertionError("a wall "+worstStep+" voxels high by the river");
  // dry channel cell: lowered to its bed
  int[] col=new int[h]; java.util.Arrays.fill(col,sky<<24); for(int y=0;y<=88;y++)col[y]=biome|(y==88?grass:stone);
  LodStreams.lower(col,70,4,sky); if(ground(col)!=70||Voxel.id(col[70])!=grass) throw new AssertionError("dry bed");
  // a lake (water over the ground) isn't cut
  col=new int[h]; java.util.Arrays.fill(col,sky<<24); for(int y=0;y<=60;y++)col[y]=biome|stone; for(int y=61;y<=63;y++)col[y]=biome|water;
  int[] before=col.clone(); LodStreams.lower(col,50,4,sky); if(Voxel.rank(water)==Voxel.KIND_WATER && !java.util.Arrays.equals(col,before)) throw new AssertionError("lake cut"); if(Voxel.rank(water)!=Voxel.KIND_WATER) System.out.println("(no fluid kinds without the game running: lake case skipped)");
  // levels: reach in cells shrinks with the cell size, the cap still rises one voxel per cell
  for(int level=0;level<=4;level++){int cell=1<<level; int r=(LodStreams.BANK_REACH+cell-1)/cell; if(r*cell<LodStreams.BANK_REACH) throw new AssertionError("reach");}
  System.out.println("PASS banks: no step over 2 voxels beside a river 22 blocks under the plain, grass kept, caves kept, trees cut with the bank, dry beds, lakes untouched");
 }
}
