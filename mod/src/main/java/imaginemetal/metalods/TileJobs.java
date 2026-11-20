package imaginemetal.metalods;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Keep distant coverage and completed siblings moving through a large fine-detail backlog.
 * Six slots in eight for near detail, one for far coverage, one for deferred sibling jobs.
 * Empty lanes lend their slots to the others; priorities within each lane stay intact. */
final class TileJobs<T extends Comparable<? super T>> {
    private final PriorityBlockingQueue<T> near = new PriorityBlockingQueue<>();
    private final PriorityBlockingQueue<T> far = new PriorityBlockingQueue<>();
    private final ConcurrentLinkedQueue<T> deferred = new ConcurrentLinkedQueue<>();
    private final AtomicInteger turn = new AtomicInteger();

    void add(T job, boolean distant) { (distant ? far : near).add(job); }
    void defer(T job) { deferred.add(job); }
    T poll() {
        // (fix12: six turns in eight for the near lane, nearest first; one each for far coverage and deferred siblings)
        int lane = turn.getAndIncrement() & 7;
        T job = lane == 0 ? deferred.poll() : lane == 1 ? far.poll() : near.poll();
        if (job == null) job = near.poll();
        if (job == null) job = far.poll();
        if (job == null) job = deferred.poll();
        return job;
    }
    int size() { return near.size() + far.size() + deferred.size(); }
    void clear() { near.clear(); far.clear(); deferred.clear(); }
}
