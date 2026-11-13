package imaginemetal.metalods;

import java.util.ArrayDeque;
import org.lwjgl.system.MemoryUtil;

/**
 * The GPU side's memory: quads in pages of 256 (8 bytes each) in one shared buffer, a 16-byte header per page (PageInfo in
 * metalods.metal), the per-frame list of pages to draw (a ring: frames in flight keep theirs), the block and biome tables,
 * and the real terrain's chunk mask. Pages are handed out and taken back on the render thread only; a page given up is
 * reused RING frames later, when no frame in flight can still be reading it.
 */
final class MetaArena {
	static final int QUADS_PER_PAGE = 256, PAGE_BYTES = QUADS_PER_PAGE * 8, INFO_BYTES = 16, RING = 4;
	static final int FRAME_BYTES = 144, MASK_MAX = 128, MASK_BYTES = MASK_MAX * MASK_MAX / 8;
	final int pages;
	final long quadBuf, infoBuf, modelBuf, biomeBuf;
	final long quads, infos;
	final long[] visBuf = new long[RING], vis = new long[RING];
	final long frame = MemoryUtil.nmemCalloc(1, 256), mask = MemoryUtil.nmemCalloc(1, MASK_BYTES);
	private final int[] free;
	private int freeCount;
	private final ArrayDeque<long[]> held = new ArrayDeque<>();

	MetaArena(long enc, int pages) {
		this.pages = pages;
		this.quadBuf = MetaNative.buffer(enc, (long) pages * PAGE_BYTES);
		this.infoBuf = MetaNative.buffer(enc, (long) pages * INFO_BYTES);
		this.modelBuf = MetaNative.buffer(enc, (long) MetaPalette.MAX_BLOCKS * MetaPalette.MODEL_BYTES);
		this.biomeBuf = MetaNative.buffer(enc, (long) MetaPalette.MAX_BIOMES * MetaPalette.BIOME_BYTES);
		this.quads = MetaNative.contents(this.quadBuf);
		this.infos = MetaNative.contents(this.infoBuf);
		MemoryUtil.memSet(this.infos, 0, (long) pages * INFO_BYTES);
		for (int i = 0; i < RING; i++) {
			this.visBuf[i] = MetaNative.buffer(enc, (long) pages * 4);
			this.vis[i] = MetaNative.contents(this.visBuf[i]);
		}
		this.free = new int[pages];
		for (int i = 0; i < pages; i++) this.free[i] = pages - 1 - i;
		this.freeCount = pages;
		MetaPalette.bind(MetaNative.contents(this.modelBuf), MetaNative.contents(this.biomeBuf));
	}

	int freePages() {
		return this.freeCount;
	}

	/** A page, or -1 if none is left. */
	int alloc() {
		return this.freeCount == 0 ? -1 : this.free[--this.freeCount];
	}

	/** The arena's own frame count (reclaim): a world's frame count starts over at 0 when another world opens. */
	private long tick;

	/**
	 * Pages to give back once the current frame can't be in flight any more. (By the arena's own count: with the world's, a
	 * world closed at frame 90,000 held its pages until the next world reached frame 90,000, so opening another world kept
	 * nearly all of the old one's pages: Jack's log 2026-11-13 14:05, 50,000 -> 11,000 pages free in a minute.)
	 */
	void release(int[] pages, long frame) {
		if (pages == null || pages.length == 0) return;
		long[] e = new long[pages.length + 1];
		e[0] = this.tick;
		for (int i = 0; i < pages.length; i++) e[i + 1] = pages[i];
		this.held.add(e);
	}

	/** Pages held long enough go back to the free list (at the top of each frame). */
	void reclaim(long frame) {
		long now = ++this.tick;
		while (!this.held.isEmpty() && now - this.held.peekFirst()[0] >= RING + 1) {
			long[] e = this.held.pollFirst();
			for (int i = 1; i < e.length; i++) {
				int p = (int) e[i];
				MemoryUtil.memPutInt(this.infos + (long) p * INFO_BYTES + 12, 0);
				this.free[this.freeCount++] = p;
			}
		}
	}

	/** Writes `count` quads (pairs of ints from src[from]) into page p and its header. */
	void fill(int p, int[] src, int from, int count, int ox, int oy, int oz, int level, int face) {
		long at = this.quads + (long) p * PAGE_BYTES;
		MemoryUtil.memIntBuffer(at, 2 * count).put(src, 2 * from, 2 * count);
		long h = this.infos + (long) p * INFO_BYTES;
		MemoryUtil.memPutInt(h, ox);
		MemoryUtil.memPutInt(h + 4, oy);
		MemoryUtil.memPutInt(h + 8, oz);
		MemoryUtil.memPutInt(h + 12, (level & 15) | (face & 7) << 4 | (count & 511) << 8);
	}

	void free() {
		MetaNative.release(this.quadBuf);
		MetaNative.release(this.infoBuf);
		for (long b : this.visBuf) MetaNative.release(b);
		// (the block and biome tables stay: MetaPalette keeps writing into them for the session)
	}
}
