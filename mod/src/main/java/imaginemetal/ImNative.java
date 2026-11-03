package imaginemetal;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import mcopt.metal.MetalBridge;

/** Our entry points in the backend's native library (libmcmetal.dylib). Pointers cross as longs, as in mcopt.metal.Native. */
final class ImNative {
	private static final MethodHandle FRAME = fn("mc_im_frame", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT), true);
	private static final MethodHandle LIBRARY = fn("mco_library", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG), false);
	private static final MethodHandle SHADOW_NEW = fn("im_shadow_new", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT), false);
	private static final MethodHandle SHADOW_PIPELINES = fn("im_shadow_pipelines",
		FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT), false);
	private static final MethodHandle SHADOW_RENDER = fn("im_shadow_render", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_FLOAT, JAVA_INT), false);

	private static MethodHandle fn(String name, FunctionDescriptor d, boolean critical) {
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return critical ? Linker.nativeLinker().downcallHandle(symbol, d, Linker.Option.critical(false)) : Linker.nativeLinker().downcallHandle(symbol, d);
	}

	private ImNative() {
	}

	static long terrainLibrary(long own) {
		try {
			return (long) LIBRARY.invokeExact(own);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static long shadowNew(long ctx, int resolution, long err, int errCap) {
		try {
			return (long) SHADOW_NEW.invokeExact(ctx, resolution, err, errCap);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static int shadowPipelines(long state, long library, boolean compact, boolean fat, long err, int errCap) {
		try {
			return (int) SHADOW_PIPELINES.invokeExact(state, library, compact ? 1 : 0, fat ? 1 : 0, err, errCap);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static void shadowRender(long enc, long state, long frame, int frameBytes, long arena, long sections, long recs, long lists, long args,
		int listCap, long atlas, float slopeScale, int cascade) {
		try {
			SHADOW_RENDER.invokeExact(enc, state, frame, frameBytes, arena, sections, recs, lists, args, listCap, atlas, slopeScale, cascade);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	/** This frame's ImFrame (length bytes at address) for the terrain's fragment stage. */
	static void frame(long address, int length) {
		try {
			FRAME.invokeExact(address, length);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}
}
