package imaginemetal;

/**
 * The aurora's colour schemes: below (where the curtains end, low in the sky) and above (their glowing height), as
 * Complementary Reimagined writes them (light, 0..16-ish: the noise is faint). The first is Reimagined's own.
 */
public final class Aurora {
	public static final String[] NAMES = {"Reimagined (pink, green)", "Classic green", "Pink and violet", "Blue and violet", "Red and gold"};
	static final float[][] LOW = {{7f, 2.2f, 12f}, {2f, 9f, 6f}, {12f, 2f, 9f}, {6f, 3f, 14f}, {14f, 2f, 3f}};
	static final float[][] HIGH = {{6f, 16f, 12f}, {4f, 18f, 9f}, {9f, 4f, 16f}, {3f, 12f, 18f}, {16f, 10f, 3f}};

	private Aurora() {
	}
}
