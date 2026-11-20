package mcopt.metal.lod;
import java.util.Arrays;
public class StreamsCheck {
 public static void main(String[] args) {
  int cases=0;
  for(int cell:new int[]{1,2,4,8,16})for(int minY:new int[]{-64,0}) {
   int[] col=new int[64];Arrays.fill(col,1|7<<16);col[2]=0;
   int b=20,w=24;
   LodStreams.channel(col,minY,cell,minY+b*cell,minY+w*cell,2,3,1,15);
   if(col[b]!=(3|7<<16))throw new AssertionError("bed");
   for(int y=b+1;y<=w;y++)if(col[y]!=(2|7<<16))throw new AssertionError("water");
   for(int y=w+1;y<64;y++)if(col[y]!=0x0f000000)throw new AssertionError("channel cover not cleared");
   if(col[2]!=0)throw new AssertionError("underground cave changed");
   cases++;
  }
  System.out.println("PASS "+cases+" Streams channel transforms: bed/water/sky, biome, negative origin, caves, levels0..4");
 }
}
