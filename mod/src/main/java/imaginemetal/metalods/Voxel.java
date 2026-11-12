package imaginemetal.metalods;

/**
 * A voxel as an int: bits 0-15 the block (MetaPalette's number; 0 air), 16-23 the biome (MetaPalette's number), 24-27 sky
 * light, 28-31 block light. Light is only kept on air (what a face looking into it is lit by); a quad's second word has the
 * same layout, with the light of the air in front of the face.
 */
public final class Voxel {
	public static final int SKY_AIR = 15 << 24;
	public static final int KIND_AIR = 0, KIND_WATER = 1, KIND_LEAVES = 2, KIND_SOLID = 3;

	private Voxel() {
	}

	public static boolean isAir(int v) {
		return (v & 0xFFFF) == 0;
	}

	public static int id(int v) {
		return v & 0xFFFF;
	}

	public static int biome(int v) {
		return (v >>> 16) & 0xFF;
	}

	public static int sky(int v) {
		return (v >>> 24) & 0xF;
	}

	public static int block(int v) {
		return (v >>> 28) & 0xF;
	}

	public static int air(int sky, int block) {
		return (sky & 15) << 24 | (block & 15) << 28;
	}

	/** For the mip: air 0, water 1, leaves 2, anything solid 3. */
	public static int rank(int v) {
		int id = v & 0xFFFF;
		return id == 0 ? 0 : MetaPalette.kind(id);
	}

	/** The face's quad value: the voxel's block and biome with the light of the air in front. */
	public static int face(int v, int front) {
		return (v & 0x00FFFFFF) | (front & 0xFF000000);
	}
}
