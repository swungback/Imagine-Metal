package imaginemetal;

/**
 * The size of the game's framebuffer (WindowMixin): what the hand, the HUD and the menus are drawn at, and what the world is
 * scaled up to (the world itself is drawn smaller, Settings.renderScale, and brought up to this size by the TAA: Hdr, Bloom).
 * imaginemetal.retina (false): true draws every Retina pixel. Otherwise imaginemetal.output (1080) is the framebuffer's height
 * in lines, the width following the window's shape (1728x1080 for a 1440x900 window), never more than the Retina size; 0 = the
 * window's own size in points (1440x900). The Mac scales the picture to the window.
 */
public final class Resolution {
	public static final boolean RETINA;
	public static final int OUTPUT;

	static {
		Config.load();
		RETINA = Engine.flag("imaginemetal.retina", false);
		OUTPUT = (int) Config.number("imaginemetal.output", 1080, 0, 4320);
	}

	/**
	 * The framebuffer for a window of sw x sh points whose full (Retina) framebuffer is fw x fh; null: leave it as it is. Given its
	 * own result as the full size it returns null, so applying it twice changes nothing.
	 */
	public static int[] target(int sw, int sh, int fw, int fh) {
		if (RETINA || sw <= 0 || sh <= 0 || fw <= 0 || fh <= 0) return null;
		int th = Math.min(OUTPUT > 0 ? OUTPUT : sh, fh);
		th = Math.max(2, th & ~1);
		int tw = Math.max(2, Math.round((float) fw * th / fh / 2f) * 2);
		tw = Math.min(tw, fw);
		if (tw >= fw && th >= fh) return null;
		return new int[] {tw, th};
	}

	private Resolution() {
	}
}
