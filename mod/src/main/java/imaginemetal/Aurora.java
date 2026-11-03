package imaginemetal;

/**
 * The aurora's colour schemes: below (where the curtains end) and above (their glowing height), on Complementary Reimagined's
 * scale (its own is the first). "Shifting" drifts slowly through every hue.
 */
public final class Aurora {
	public static final String[] NAMES = {"Reimagined (pink, green)", "Northern (green, crimson)", "Sakura (pink, white)", "Glacier (cyan, blue)",
		"Galaxy (violet, teal)", "Inferno (red, gold)", "Toxic (lime, purple)", "Shifting"};
	private static final float[][] LOW = {{7f, 2.2f, 12f}, {14f, 1.5f, 5f}, {15f, 3f, 9f}, {2.5f, 6f, 16f}, {11f, 2f, 16f}, {16f, 2f, 1f},
		{9f, 2f, 15f}};
	private static final float[][] HIGH = {{6f, 16f, 12f}, {2f, 17f, 7f}, {13f, 11f, 15f}, {2f, 16f, 15f}, {2f, 14f, 14f}, {16f, 11f, 2f},
		{8f, 18f, 2f}};

	private Aurora() {
	}

	static float[] low(int scheme) {
		return scheme < LOW.length ? LOW[scheme] : hue(0.0);
	}

	static float[] high(int scheme) {
		return scheme < HIGH.length ? HIGH[scheme] : hue(0.33);
	}

	/** a saturated colour on the Reimagined scale, its hue drifting once around in about two minutes */
	private static float[] hue(double offset) {
		double h = (System.nanoTime() / 1e9 / 120.0 + offset) % 1.0;
		float[] c = new float[3];
		for (int i = 0; i < 3; i++) {
			double x = Math.abs(((h * 6.0 + 4.0 - i * 2.0) % 6.0) - 3.0) - 1.0;
			c[i] = (float) (2.0 + 14.0 * Math.clamp(x, 0.0, 1.0));
		}
		return c;
	}
}
