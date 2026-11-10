package imaginemetal;

/** imaginemetal.retina (false): draw at the window's size in points, or at every Retina pixel (WindowMixin). */
public final class Resolution {
	public static final boolean RETINA;
	/**
	 * imaginemetal.renderScale (1): with retina off, the game's framebuffer is this share of the window's size in points on each
	 * axis (0.75: 1080x675 for a 1440x900 window) and the Mac scales it up. An experiment to see what shading fewer pixels buys:
	 * the menus and the HUD are drawn at that size too, so they get soft.
	 */
	public static final float SCALE;

	static {
		Config.load();
		RETINA = Engine.flag("imaginemetal.retina", false);
		SCALE = Config.number("imaginemetal.renderScale", 1f, 0.5f, 1f);
	}

	/** A window side in points -> the framebuffer's (even, so half-size passes line up). */
	public static int scaled(int points) {
		if (SCALE >= 0.999f) return points;
		return Math.max(2, Math.round(points * SCALE / 2f) * 2);
	}

	private Resolution() {
	}
}
