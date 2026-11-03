package mcopt.metal;

/**
 * -Dmcopt.metal.latency=true (with -Dmcopt.metal.gpuTimes=true): the time of the input poll that starts each frame
 * (RenderSystem.pollEvents: keyboard, mouse buttons and the mouse movement the frame's camera turn uses), stored with that frame's
 * presenting command buffer, so a report can give input-sample -> commit -> GPU done -> presented per shown frame.
 */
public final class Latency {
	public static final boolean ON = Boolean.getBoolean("mcopt.metal.latency");
	public static volatile long lastPollNs;

	private Latency() {
	}
}
