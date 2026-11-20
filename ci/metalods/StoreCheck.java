package imaginemetal.metalods;
import java.nio.file.*;
import java.util.Arrays;
public class StoreCheck {
 public static void main(String[] args) throws Exception {
  Path dir=Path.of(args[0]);
  Rle.Builder b=new Rle.Builder(32,64);
  int[] col=new int[64]; Arrays.fill(col,0x0f000000);Arrays.fill(col,0,20,1|3<<16);
  for(int z=0;z<32;z++)for(int x=0;x<32;x++)b.column(x,z,col);
  Rle original=b.build();
  MetaStore s=new MetaStore(dir,null,true);
  s.saveGen(0,-2,3,original);s.saveGen(2,-2,3,original);
  Rle[] levels=new Rle[5];Arrays.fill(levels,original);
  MetaStore target=s;
  Thread writer=new Thread(()->{for(int i=0;i<128;i++)target.putReal(i%32,i/32,new MetaStore.RealChunk(levels));});
  writer.start();for(int i=0;i<10;i++)s.flush();writer.join();s.close();
  s=new MetaStore(dir,null,true);
  if(!original.sameData(s.loadGen(0,-2,3))||!original.sameData(s.loadGen(2,-2,3)))throw new AssertionError("reopen lost generated data");
  for(int i=0;i<128;i++)if(s.real(i%32,i/32)==null)throw new AssertionError("reopen lost captured chunk");
  s.close();
  Files.createDirectories(dir.resolve("gen8/L2"));Files.move(dir.resolve("gen9/L2/-2.3.bin"),dir.resolve("gen8/L2/-2.3.bin"));
  Files.createDirectories(dir.resolve("gen8/L0"));Files.move(dir.resolve("gen9/L0/-2.3.bin"),dir.resolve("gen8/L0/-2.3.bin"));
  s=new MetaStore(dir,null,true);
  if(!original.sameData(s.loadGen(2,-2,3)))throw new AssertionError("compatible gen8 far cache discarded");
  if(s.loadGen(0,-2,3)!=null)throw new AssertionError("inaccurate gen8 near cache reused");
  if(s.cacheHits.get()!=1||s.cacheMisses.get()!=1||s.cacheErrors.get()!=0)throw new AssertionError("cache diagnostics");
  s.saveSurface(0,9,-9,original);
  if(!s.isSurface(0,9,-9)||!original.sameData(s.loadGen(0,9,-9)))throw new AssertionError("pending preview lost");
  s.close();s=new MetaStore(dir,null,true);
  if(!original.sameData(s.loadGen(0,9,-9))||!s.isSurface(0,9,-9))throw new AssertionError("preview reopen lost quality marker");
  Rle full=Rle.empty(32,64);s.saveGen(0,9,-9,full);
  s.saveSurface(0,9,-9,original);
  if(s.isSurface(0,9,-9)||!full.sameData(s.loadGen(0,9,-9)))throw new AssertionError("preview downgraded pending full detail");
  s.close();s=new MetaStore(dir,null,true);
  if(!full.sameData(s.loadGen(0,9,-9))||s.isSurface(0,9,-9))throw new AssertionError("full cache did not take precedence on reopen");
  s.close();System.out.println("PASS generated/captured cache reopen, concurrent region snapshots, compatible far-cache fallback and near-cache isolation");
 }
}
