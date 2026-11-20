package mcopt.metal.lod;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Thread QoS classes for the contention experiments (far-terrain workers vs the server's chunk generation on the same cores):
 * -Dmcopt.lod.qos=CLASS for the far terrain's generation workers (each sets its own class on its first tile, LodNoise),
 * -Dmcopt.gen.worldgenQos=CLASS for the integrated server's chunk generation threads (each on its first chunk fill, mixin
 * GenWorldgenQosMixin). CLASS: background (E-cores only on Apple silicon), utility, default, initiated, interactive. A thread
 * may only set its own class (libSystem's pthread_set_qos_class_self_np). Unset: nothing changes.
 */
public final class LodGenQos {
	private LodGenQos() {
	}

	static final int LOD = parse(System.getProperty("mcopt.lod.qos"));
	public static final int WORLDGEN = parse(System.getProperty("mcopt.gen.worldgenQos"));
	private static MethodHandle setSelf;
	private static final ThreadLocal<Boolean> DONE = ThreadLocal.withInitial(() -> Boolean.FALSE);

	static int parse(String name) {
		if (name == null) return 0;
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "background" -> 0x09;
			case "utility" -> 0x11;
			case "default" -> 0x15;
			case "initiated", "user-initiated" -> 0x19;
			case "interactive", "user-interactive" -> 0x21;
			default -> 0;
		};
	}

	private static final ThreadLocal<int[]> CURRENT = ThreadLocal.withInitial(() -> new int[1]);

	/**
	 * (fix14) The calling thread into class cls whenever it differs from the class it last set (0: nothing). MetaLODS workers
	 * switch between default (a view to fill) and utility (little left) as the work comes and goes.
	 */
	public static void set(int cls) {
		int[] cur = CURRENT.get();
		if (cls == 0 || cur[0] == cls) return;
		cur[0] = cls;
		try {
			synchronized (LodGenQos.class) {
				if (setSelf == null) {
					Linker linker = Linker.nativeLinker();
					setSelf = linker.downcallHandle(linker.defaultLookup().find("pthread_set_qos_class_self_np").orElseThrow(),
						FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
				}
			}
			int rc = (int) setSelf.invokeExact(cls, 0);
			if (rc != 0) System.out.println("mcopt-lod: QoS 0x" + Integer.toHexString(cls) + " for " + Thread.currentThread().getName() + ": " + rc);
		} catch (Throwable t) {
			// (not macOS: no QoS classes; stop trying on this thread)
			cur[0] = Integer.MIN_VALUE;
		}
	}

	private static MethodHandle getSelf;

	/** The calling thread's QoS class now (libSystem's qos_class_self), -1 if it can't be read. */
	static int current() {
		try {
			synchronized (LodGenQos.class) {
				if (getSelf == null) {
					Linker linker = Linker.nativeLinker();
					getSelf = linker.downcallHandle(linker.defaultLookup().find("qos_class_self").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
				}
			}
			return (int) getSelf.invokeExact();
		} catch (Throwable t) {
			return -1;
		}
	}

	/** The calling thread into class cls once (0: nothing). */
	public static void once(int cls, String role) {
		if (cls == 0 || DONE.get()) return;
		DONE.set(Boolean.TRUE);
		try {
			synchronized (LodGenQos.class) {
				if (setSelf == null) {
					Linker linker = Linker.nativeLinker();
					setSelf = linker.downcallHandle(linker.defaultLookup().find("pthread_set_qos_class_self_np").orElseThrow(),
						FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
				}
			}
			int was = current();
			int rc = (int) setSelf.invokeExact(cls, 0);
			System.out.println("mcopt-lod: QoS 0x" + Integer.toHexString(cls) + " for " + role + " (" + Thread.currentThread().getName() + ", was 0x" + Integer.toHexString(was) + "): " + rc);
		} catch (Throwable t) {
			System.out.println("mcopt-lod: QoS for " + role + ": " + t);
		}
	}
}
