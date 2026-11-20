package imaginemetal.metalods;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class DetailCheck {
 public static void main(String[] args)throws Exception {
  DetailJobs<Integer> q=new DetailJobs<>();q.offer(1,10);q.offer(2,1);
  if(q.take(false)!=null)throw new AssertionError("detail ran before coverage");
  var first=q.take(true);if(first.value()!=1||q.take(true)!=null||q.offer(2,99))throw new AssertionError("priority/owner/dedup");
  q.retry(first);first=q.take(true);if(first.key()!=2)throw new AssertionError("busy tile lost");
  q.clear();q.offer(2,7);q.complete(first);
  if(q.offer(2,8))throw new AssertionError("stale completion erased new lease key");
  var next=q.take(true);if(next.value()!=7)throw new AssertionError("clear retained old work");q.complete(next);
  for(int i=0;i<100;i++)q.offer(i,i);
  AtomicInteger active=new AtomicInteger(),max=new AtomicInteger(),done=new AtomicInteger();
  var pool=Executors.newFixedThreadPool(6);
  for(int t=0;t<6;t++)pool.submit(()->{while(done.get()<100){var l=q.take(true);if(l==null){Thread.yield();continue;}int a=active.incrementAndGet();max.accumulateAndGet(a,Math::max);Thread.yield();active.decrementAndGet();q.complete(l);done.incrementAndGet();}});
  pool.shutdown();if(!pool.awaitTermination(5,TimeUnit.SECONDS)||max.get()!=1||done.get()!=100)throw new AssertionError("coverage workers occupied by concurrent detail");
  q.offer(800,1);q.offer(801,2);q.offer(802,3);q.offer(803,4);
  var a=q.take(true,3);var b=q.take(true,3);var c=q.take(true,3);
  if(a==null||b==null||c==null||q.take(true,3)!=null||q.take(true,1)!=null)throw new AssertionError("idle expansion/backpressure");
  q.complete(a);q.complete(b);q.complete(c);q.clear();
  q.offer(100,1);q.offer(101,50);q.offer(102,99);
  var urgent=q.takeNearest(true,1,v->v>=40,v->Math.abs(v-60));
  if(urgent==null||urgent.value()!=50)throw new AssertionError("current-camera priority ignored");
  q.complete(urgent);q.clear();
  q.offer(900,1);var old=q.take(true);q.close();q.complete(old);
  if(q.take(true)!=null||q.offer(901,2))throw new AssertionError("closed-world work survived");
  System.out.println("PASS staged detail priorities, ownership/retry, stale-clear safety, 100 jobs/6 workers max1 detail owner, close");
 }
}
