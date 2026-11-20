package mcopt.metal.lod;

import java.nio.file.*;
import java.util.*;

/**
 * fix16: Streams Reflowing's corridor in the LODs against the real world. Inputs (from a session): the plan dump (claude-diag
 * streams-plan-X_Z.csv), the seed's ground (GorgeCheck -Ddump), the real ground (region heightmaps, x,z,oceanFloor,motionBlocking).
 * Prints how many corridor columns the LOD gets within 2 blocks of the real ground, before and with the banks.
 */
public class CorridorCheck {
 public static void main(String[] a) throws Exception {
  int x0=Integer.parseInt(a[3]), z0=Integer.parseInt(a[4]), n=Integer.parseInt(a[5]);
  byte[] kind=new byte[n*n]; int[] water=new int[n*n];
  for(String l:Files.readAllLines(Path.of(a[0]))){ if(l.startsWith("x"))continue; String[] f=l.split(","); int x=Integer.parseInt(f[0])-x0,z=Integer.parseInt(f[1])-z0; if(x<0||z<0||x>=n||z>=n)continue;
   int i=z*n+x; boolean ch=f[2].equals("1"); int by=Integer.parseInt(f[4]),wy=Integer.parseInt(f[5]); kind[i]=ch&&wy>=by?LodStreams.CHANNEL:LodStreams.CORRIDOR; if(ch&&wy<by)kind[i]=LodStreams.NONE; water[i]=wy; }
  int[] seed=new int[n*n]; Arrays.fill(seed,Integer.MIN_VALUE);
  for(String l:Files.readAllLines(Path.of(a[1]))){ String[] f=l.split(","); int x=Integer.parseInt(f[0])-x0,z=Integer.parseInt(f[1])-z0; if(x<0||z<0||x>=n||z>=n)continue; seed[z*n+x]=Integer.parseInt(f[2]); }
  int[] real=new int[n*n]; boolean[] wet=new boolean[n*n]; Arrays.fill(real,Integer.MIN_VALUE);
  for(String l:Files.readAllLines(Path.of(a[2]))){ String[] f=l.split(","); int x=Integer.parseInt(f[0])-x0,z=Integer.parseInt(f[1])-z0; if(x<0||z<0||x>=n||z>=n)continue; int i=z*n+x; real[i]=Integer.parseInt(f[2]); wet[i]=Integer.parseInt(f[3])>real[i]; }
  int[] d=LodStreams.channelDistance(kind,n);
  int cols=0,before=0,after=0; long errB=0,errA=0;
  for(int i=0;i<n*n;i++){
   if(kind[i]!=LodStreams.CORRIDOR||seed[i]==Integer.MIN_VALUE||real[i]==Integer.MIN_VALUE||wet[i]||d[i]>LodStreams.CORRIDOR_REACH+1)continue;
   int lod=Math.min(seed[i],LodStreams.bankTop(water[i],d[i]));
   cols++; if(Math.abs(seed[i]-real[i])<=2)before++; if(Math.abs(lod-real[i])<=2)after++; errB+=Math.abs(seed[i]-real[i]); errA+=Math.abs(lod-real[i]);
  }
  System.out.printf(Locale.ROOT,"corridor columns %d: LOD ground within 2 blocks of the real ground %.0f%% before, %.0f%% with the banks; mean error %.1f -> %.1f blocks%n",cols,100.0*before/cols,100.0*after/cols,(double)errB/cols,(double)errA/cols);
  if(after*100<cols*60) throw new AssertionError("banks don't match the real world");
 }
}
