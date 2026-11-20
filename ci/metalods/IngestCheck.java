package imaginemetal.metalods;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class IngestCheck {
	private static void check(boolean ok, String message) {
		if (!ok) throw new AssertionError(message);
	}
	public static void main(String[] args) throws Exception {
		LatestIngest gate = new LatestIngest();
		AtomicInteger stored = new AtomicInteger();
		long key = -16L;
		Object old = gate.submit(key);
		CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
		Thread slow = new Thread(() -> {
			started.countDown();
			try { release.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
			gate.publish(key, old, () -> stored.set(1));
			gate.finish(key, old);
		});
		slow.start();
		started.await();
		Object newer = gate.submit(key);
		check(gate.publish(key, newer, () -> stored.set(2)), "new snapshot published");
		release.countDown();
		slow.join();
		check(stored.get() == 2, "slow old snapshot cannot overwrite new snapshot");
		Object a = gate.submit(key), b = gate.submit(key), other = gate.submit(16);
		gate.finish(key, a);
		check(gate.current(key, b), "obsolete cleanup cannot remove current ticket");
		check(!gate.publish(key, a, () -> stored.set(3)), "superseded pending work rejected");
		check(gate.publish(16, other, () -> stored.set(4)), "independent chunks progress");
		gate.clear(() -> stored.set(0));
		check(!gate.publish(key, b, () -> stored.set(5)), "clear invalidates in-flight writes");
		Object c = gate.submit(key);
		check(gate.publish(key, c, () -> stored.set(6)), "new work after clear succeeds");
		Object d = gate.submit(key);
		gate.close();
		check(!gate.publish(key, d, () -> stored.set(7)), "close rejects in-flight writes");
		check(!gate.current(key, gate.submit(key)), "close rejects future submissions");
		check(stored.get() == 6, "no writes after close");
		System.out.println("PASS: reversed ingest completion, stale cleanup, independent chunks, clear and close");
	}
}
