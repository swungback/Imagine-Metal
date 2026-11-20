package imaginemetal.metalods;

import net.minecraft.tags.BlockTags;

/** (fix13) Whether a voxel is part of a tree (logs, stems, leaves), cached per block id: the ground under a tree is the block under it. */
public final class VoxelLogs {
	private VoxelLogs() {
	}

	/** 0 not known yet, 1 not a log, 2 a log. */
	private static final byte[] KNOWN = new byte[MetaPalette.MAX_BLOCKS];

	public static boolean isTreePart(int voxel) {
		int id = Voxel.id(voxel);
		if (id == 0) return false;
		byte k = KNOWN[id];
		if (k == 0) {
			boolean log;
			try {
				var state = MetaPalette.state(id);
				// (by name too: before the data packs' tags are bound, and modded trunks outside the tag)
				String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
				log = state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || path.endsWith("_leaves") || path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_wood") || path.endsWith("_hyphae");
			} catch (RuntimeException e) {
				log = false;
			}
			KNOWN[id] = k = (byte) (log ? 2 : 1);
		}
		return k == 2;
	}
}
