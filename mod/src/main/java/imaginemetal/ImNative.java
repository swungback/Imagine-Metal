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
	private static final MethodHandle BLOOM_NEW = fn("im_bloom_new", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT), false);
	private static final MethodHandle BLOOM_RENDER = fn("im_bloom_render", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_LONG, JAVA_INT), false);
	private static final MethodHandle BLOOM_SHAFTS = fn("im_bloom_shafts", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT,
		JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT), false);
	private static final MethodHandle BLOOM_FOG = fn("im_bloom_fog", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT), false);
	private static final MethodHandle BLOOM_TAA = fn("im_bloom_taa", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT), false);
	private static final MethodHandle BLOOM_AO = fn("im_bloom_ao", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_FLOAT, JAVA_LONG), false);
	private static final MethodHandle LIGHT_NEW = fn("im_light_new", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT,
		JAVA_LONG, JAVA_INT), false);
	private static final MethodHandle LIGHT_UPLOAD = fn("im_light_upload", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
		JAVA_INT, JAVA_INT, JAVA_LONG), false);
	private static final MethodHandle LIGHT_STEP = fn("im_light_step", FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT,
		JAVA_INT), false);
	private static final MethodHandle SKY_NEW = fn("im_sky_new", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT), false);
	private static final MethodHandle SKY_DRAW = fn("im_sky_draw", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT,
		JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT), false);
	private static final MethodHandle SKY_DRAW_END = fn("im_sky_draw_end", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT,
		JAVA_LONG, JAVA_LONG, JAVA_INT), false);
	private static final MethodHandle GPU_MS = fn("im_gpu_ms", FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_DOUBLE), false);
	private static final MethodHandle SKY_TEXTURES = fn("im_sky_textures", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT,
		JAVA_LONG, JAVA_INT, JAVA_INT), false);

	private static final MethodHandle SSR_NEW = fn("im_ssr_new", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG), false);
	private static final MethodHandle SSR_CAPTURE = fn("im_ssr_capture", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_INT), false);

	private static final MethodHandle UNDER_CAPTURE = fn("im_under_capture", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_LONG, JAVA_INT), false);

	static int underCapture(long enc, long state, long color, long depth, long err, int errCap) {
		try {
			return (int) UNDER_CAPTURE.invokeExact(enc, state, color, depth, err, errCap);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

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

	static long bloomNew(long ctx, long source, long err, int errCap) {
		try {
			return (long) BLOOM_NEW.invokeExact(ctx, source, err, errCap);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static int bloomRender(long enc, long state, long src, long dst, float strength, float threshold, float knee, float haze, float toe, long err,
		int errCap) {
		try {
			return (int) BLOOM_RENDER.invokeExact(enc, state, src, dst, strength, threshold, knee, haze, toe, err, errCap);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static void bloomShafts(long state, float sx, float sy, float strength, float r, float g, float b, float reach) {
		try {
			BLOOM_SHAFTS.invokeExact(state, sx, sy, strength, r, g, b, reach);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static void bloomFog(long state, float strength, float water, float rainbow) {
		try {
			BLOOM_FOG.invokeExact(state, strength, water, rainbow);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static void bloomTaa(long state, float jx, float jy, float on) {
		try {
			BLOOM_TAA.invokeExact(state, jx, jy, on);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static void bloomAo(long state, float strength, long clips) {
		try {
			BLOOM_AO.invokeExact(state, strength, clips);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static long lightNew(long ctx, long source, int sx, int sy, int sz, long err, int errCap) {
		try {
			return (long) LIGHT_NEW.invokeExact(ctx, source, sx, sy, sz, err, errCap);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static void lightUpload(long state, int x, int y, int z, int w, int h, int d, long bytes) {
		try {
			LIGHT_UPLOAD.invokeExact(state, x, y, z, w, h, d, bytes);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static void lightStep(long enc, long state, int dx, int dy, int dz, boolean reset) {
		try {
			LIGHT_STEP.invokeExact(enc, state, dx, dy, dz, reset ? 1 : 0);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static long ssrNew(long ctx) {
		try {
			return (long) SSR_NEW.invokeExact(ctx);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static int ssrCapture(long enc, long state, long color, long depth, long err, int errCap) {
		try {
			return (int) SSR_CAPTURE.invokeExact(enc, state, color, depth, err, errCap);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static long skyNew(long ctx, long source, long err, int errCap) {
		try {
			return (long) SKY_NEW.invokeExact(ctx, source, err, errCap);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static int skyTextures(long state, long clouds, int w, int h, long noise, int nw, int nh) {
		try {
			return (int) SKY_TEXTURES.invokeExact(state, clouds, w, h, noise, nw, nh);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static int skyDraw(long enc, long state, long target, long frame, int frameBytes, long rot, int mode, long err, int errCap) {
		try {
			return (int) SKY_DRAW.invokeExact(enc, state, target, frame, frameBytes, rot, mode, err, errCap);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	static double gpuMs() {
		try {
			return (double) GPU_MS.invokeExact();
		} catch (Throwable t) {
			return 0.0;
		}
	}

	static int skyDrawEnd(long enc, long state, long target, long frame, int frameBytes, long rot, long err, int errCap) {
		try {
			return (int) SKY_DRAW_END.invokeExact(enc, state, target, frame, frameBytes, rot, err, errCap);
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
