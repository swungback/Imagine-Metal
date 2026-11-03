package imaginemetal;

/** imaginemetal.retina (false): draw at the window's size in points, or at every Retina pixel (WindowMixin). */
public final class Resolution {
	public static final boolean RETINA;

	static {
		Config.load();
		RETINA = Engine.flag("imaginemetal.retina", false);
	}

	private Resolution() {
	}
}
