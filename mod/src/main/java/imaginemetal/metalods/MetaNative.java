package imaginemetal.metalods;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import mcopt.metal.MetalBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Downcalls into metalods.m. Pointers cross as longs, as everywhere in the backend. */
final class MetaNative {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle NEW = fn("mml_new", JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle BUFFER = fn("mml_buffer", JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle CONTENTS = fn("mml_contents", JAVA_LONG, JAVA_LONG);
	private static final MethodHandle RELEASE = fn("mml_release", null, JAVA_LONG);
	private static final MethodHandle G_OPEN = fn("mml_g_open", JAVA_INT, JAVA_LONG);
	private static final MethodHandle DRAW = fn("mml_draw", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);  // (..., atlas, masked, err, cap)

	private MetaNative() {
	}

	private static MethodHandle fn(String name, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return LINKER.downcallHandle(symbol, d);
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}

	static long create(long enc, String source) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.ncalloc(1, 8192, 1);
			long src = MemoryUtil.memAddress(MemoryUtil.memUTF8(source));
			long m;
			try {
				m = (long) NEW.invokeExact(enc, src, err, 8192);
			} finally {
				MemoryUtil.nmemFree(src);
			}
			if (m == 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
			return m;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long buffer(long enc, long size) {
		try {
			long b = (long) BUFFER.invokeExact(enc, size);
			if (b == 0) throw new IllegalStateException("MetaLODS: no buffer of " + size + " bytes");
			return b;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static long contents(long buffer) {
		try {
			return (long) CONTENTS.invokeExact(buffer);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void release(long obj) {
		try {
			RELEASE.invokeExact(obj);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean gOpen(long enc) {
		try {
			return (int) G_OPEN.invokeExact(enc) == 1;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** 1 drawn, 0 nothing to draw into; throws with the native error on -1. */
	static int draw(long m, long enc, long frame, int frameLength, long visible, long visOffset, int count, long pages, long quads, long models,
		long biomes, long mask, int maskLength, long atlas, boolean masked) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.ncalloc(1, 2048, 1);
			int r = (int) DRAW.invokeExact(m, enc, frame, frameLength, visible, visOffset, count, pages, quads, models, biomes, mask, maskLength, atlas,
				masked ? 1 : 0, err, 2048);
			if (r < 0) throw new IllegalStateException(MemoryUtil.memUTF8(err));
			return r;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}
}
