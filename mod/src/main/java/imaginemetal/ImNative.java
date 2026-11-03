package imaginemetal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import mcopt.metal.MetalBridge;

/** Our entry points in the backend's native library (libmcmetal.dylib). Pointers cross as longs, as in mcopt.metal.Native. */
final class ImNative {
	private static final MethodHandle FRAME = Linker.nativeLinker().downcallHandle(
		MetalBridge.library().find("mc_im_frame").orElseThrow(() -> new IllegalStateException("missing native symbol mc_im_frame")),
		FunctionDescriptor.ofVoid(JAVA_LONG, JAVA_INT), Linker.Option.critical(false));

	private ImNative() {
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
