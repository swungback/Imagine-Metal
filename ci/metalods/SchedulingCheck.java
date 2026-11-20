package imaginemetal.metalods;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class SchedulingCheck {
    public static void main(String[] args) throws Exception {
        TileGate gate = new TileGate();
        // These distinct tiles all collided in Long.hashCode(packedKey) & 63.
        for (int x = 0; x < 8; x++) {
            long key = (long) x << 29;
            if (!gate.enter(key)) throw new AssertionError("independent tile blocked");
            if (gate.enter(key)) throw new AssertionError("duplicate tile admitted");
        }
        for (int x = 0; x < 8; x++) gate.leave((long) x << 29);
        AtomicInteger entered = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1), attempting = new CountDownLatch(24);
        ExecutorService pool = Executors.newFixedThreadPool(24);
        var tasks = new java.util.ArrayList<Future<?>>();
        for (int i = 0; i < 24; i++) tasks.add(pool.submit(() -> {
            try {
                start.await();
                boolean owner = gate.enter(123);
                if (owner) entered.incrementAndGet();
                attempting.countDown();
                if (owner) {
                    if (!attempting.await(10, TimeUnit.SECONDS)) throw new AssertionError("tile admission blocked");
                    gate.leave(123);
                }
            } catch (InterruptedException e) { throw new AssertionError(e); }
        }));
        start.countDown();
        for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        pool.shutdown();
        if (entered.get() != 1 || !gate.enter(123)) throw new AssertionError("tile ownership lifecycle");
        gate.leave(123);

        TileJobs<Integer> jobs = new TileJobs<>();
        for (int i = 999; i >= 0; i--) jobs.add(i, false);
        jobs.add(10000, true); jobs.defer(20000);
        boolean far = false, sibling = false;
        for (int i = 0; i < 4; i++) {
            int job = jobs.poll(); far |= job == 10000; sibling |= job == 20000;
        }
        if (!far || !sibling) throw new AssertionError("fine backlog starved distant/sibling work");
        int previous = 1;
        while (jobs.size() > 0) {
            int next = jobs.poll();
            if (next <= previous) throw new AssertionError("near priority order");
            previous = next;
        }
        if (jobs.poll() != null) throw new AssertionError("queue not drained");
        jobs.add(3, true); jobs.add(2, true); jobs.add(1, true);
        for (int i = 1; i <= 3; i++) if (jobs.poll() != i) throw new AssertionError("empty lanes not lending slots");
        jobs.defer(4); jobs.add(5, false); jobs.add(6, true); jobs.clear();
        if (jobs.size() != 0 || jobs.poll() != null) throw new AssertionError("close did not clear all lanes");
        System.out.println("PASS: independent tiles, 24 concurrent duplicate claims, publication/release, distant/sibling fairness, priorities and shutdown");
    }
}
