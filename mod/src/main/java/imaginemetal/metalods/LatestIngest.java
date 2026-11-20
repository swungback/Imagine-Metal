package imaginemetal.metalods;

import java.util.HashMap;

/** Per-chunk publication gate. Conversion stays parallel; obsolete results never reach the store. */
final class LatestIngest {
	private final HashMap<Long, Object> latest = new HashMap<>();
	private boolean closed;

	synchronized Object submit(long key) {
		Object ticket = new Object();
		if (!this.closed) this.latest.put(key, ticket);
		return ticket;
	}

	synchronized boolean current(long key, Object ticket) {
		return !this.closed && this.latest.get(key) == ticket;
	}

	synchronized boolean publish(long key, Object ticket, Runnable write) {
		if (!this.current(key, ticket)) return false;
		// Keep the check and write atomic with submit, clear and close.
		try {
			write.run();
			return true;
		} finally {
			this.latest.remove(key, ticket);
		}
	}

	synchronized void finish(long key, Object ticket) {
		this.latest.remove(key, ticket);
	}

	synchronized void clear(Runnable clearStore) {
		this.latest.clear();
		clearStore.run();
	}

	synchronized void close() {
		this.closed = true;
		this.latest.clear();
	}
}
