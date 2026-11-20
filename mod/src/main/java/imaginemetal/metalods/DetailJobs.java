package imaginemetal.metalods;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;

/** Deduplicated detail work, with a small owner budget so coverage workers stay available.
 * A clear/close invalidates old leases without letting their completion erase a
 * newer request for the same tile. No lock is held while generating terrain. */
final class DetailJobs<T extends Comparable<? super T>> {
    record Lease<T>(long key,long epoch,T value) {}
    private record Entry<T extends Comparable<? super T>>(long key,long epoch,T value) implements Comparable<Entry<T>> {
        public int compareTo(Entry<T> other) { return value.compareTo(other.value); }
    }
    private final PriorityBlockingQueue<Entry<T>> pending=new PriorityBlockingQueue<>();
    private final Map<Long,Long> keys=new HashMap<>();
    private final java.util.Set<Lease<T>> active=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private long epoch;
    private boolean closed;

    synchronized boolean offer(long key,T value) {
        if(closed || keys.containsKey(key))return false;
        keys.put(key,epoch);pending.add(new Entry<>(key,epoch,value));return true;
    }
    synchronized Lease<T> take(boolean allowed) { return take(allowed,1); }
    synchronized Lease<T> take(boolean allowed,int limit) {
        if(!allowed || closed || active.size()>=Math.max(1,limit))return null;
        Entry<T> e=pending.poll();
        if(e==null)return null;
        Lease<T> lease=new Lease<>(e.key,e.epoch,e.value);active.add(lease);return lease;
    }
    synchronized Lease<T> takeNearest(boolean allowed,int limit,java.util.function.Predicate<T> eligible,java.util.function.ToDoubleFunction<T> score) {
        if(!allowed || closed || active.size()>=Math.max(1,limit))return null;
        Entry<T> best=null;double value=Double.POSITIVE_INFINITY;
        for(Entry<T> e:pending)if(eligible.test(e.value)){double s=score.applyAsDouble(e.value);if(s<value){value=s;best=e;}}
        if(best==null)return null;
        pending.remove(best);Lease<T> lease=new Lease<>(best.key,best.epoch,best.value);active.add(lease);return lease;
    }
    synchronized void complete(Lease<T> lease) {
        if(!active.remove(lease))return;
        keys.remove(lease.key,lease.epoch);
    }
    synchronized void retry(Lease<T> lease) {
        if(!active.remove(lease))return;
        if(!closed && lease.epoch==epoch)pending.add(new Entry<>(lease.key,lease.epoch,lease.value));
        else keys.remove(lease.key,lease.epoch);
    }
    synchronized void clear() {epoch++;pending.clear();keys.clear();}
    synchronized void close() {closed=true;clear();}
    synchronized int size() {return pending.size();}
    synchronized int activeCount() { return active.size(); }
    synchronized boolean active() {return !active.isEmpty();}
}
