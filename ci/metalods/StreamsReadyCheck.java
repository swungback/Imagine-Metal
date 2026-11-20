package mcopt.metal.lod;
import imaginemetal.metalods.Rle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
public class StreamsReadyCheck {
 public static class Engine {
  static final Engine INSTANCE=new Engine();static boolean ready;static int plans,generated;
  public static Engine engineIfPresent(ServerLevel w){return INSTANCE;}
  public Object[] platesTouchingBox(int x,int z,int xx,int zz){return new Object[]{"plate"};}
  public boolean platesReady(Object[] p){return ready;}
  public java.util.concurrent.CompletableFuture<Void> whenPlatesReady(Object[] p){generated++;throw new AssertionError("LOD requested Streams generation");}
  public Plan plan(int x,int z){plans++;if(!ready)throw new AssertionError("plan requested in cold region");return new Plan();}
 }
 public static class Plan {
  public boolean inChannel(){return false;}public int bedY(){return 0;}public int waterSurfaceY(){return 1;}
 }
 public static void main(String[] args)throws Exception {
  net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
  var adapter=new LodStreams(null,Engine.class,Engine.class,Plan.class);
  Rle base=Rle.empty(32,64);
  var ids=new MetaGen.Ids(){public int block(BlockState state){return 1;}public int biome(Holder<Biome> b){return 1;}};
  for(int i=0;i<32;i++)if(adapter.apply(base,0,-1,2,-64,15,ids)!=base)throw new AssertionError("cold cache modified");
  if(Engine.plans!=0||Engine.generated!=0)throw new AssertionError("cold Streams generation triggered");
  Engine.ready=true;adapter.pollReady();if(adapter.revision()!=1)throw new AssertionError("ready region did not request remesh");
  adapter.apply(base,0,-1,2,-64,15,ids);
  if(Engine.plans!=1024||Engine.generated!=0)throw new AssertionError("ready-only lookup failed");
  adapter.apply(base,0,-1,2,-64,15,ids);
  if(Engine.plans!=1024)throw new AssertionError("unchanged column queried repeatedly");
  adapter.close();adapter.apply(base,0,-1,2,-64,15,ids);
  if(Engine.plans!=1024)throw new AssertionError("closed world planned streams");
  System.out.println("PASS 32 cold-region attempts: zero plan/generation calls; ready region remesh+1024 lookups; closed-world guard");
 }
}
