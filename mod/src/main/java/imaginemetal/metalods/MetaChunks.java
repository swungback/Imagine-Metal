package imaginemetal.metalods;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Real chunks into LODs (Voxy's ingest). On the render thread only copies are taken (the sections' block palettes and the
 * light, a few microseconds a chunk); a worker turns them into the chunk's voxels and their LODs at levels 0 to 4.
 *
 * Captured air stays air, including zero-skylight air. Skylight is not an enclosure test: a wide floating island can
 * shadow open air all the way to the sea, and filling from below grew water/stone curtains under those islands.
 */
final class MetaChunks {
	/** What the render thread copies out of a chunk. */
	record Snapshot(int chunkX, int chunkZ, int minSectionY, @Nullable PalettedContainer<BlockState>[] sections, @Nullable DataLayer[] sky,
		@Nullable DataLayer[] block, int[] biomes) {
	}

	private MetaChunks() {
	}

	@SuppressWarnings("unchecked")
	static @Nullable Snapshot capture(ClientLevel level, @Nullable LevelChunk chunk) {
		if (chunk == null) return null;
		LevelChunkSection[] secs = chunk.getSections();
		int n = secs.length, minSec = chunk.getMinSectionY();
		int cx = chunk.getPos().x(), cz = chunk.getPos().z();
		PalettedContainer<BlockState>[] states = new PalettedContainer[n];
		DataLayer[] sky = new DataLayer[n], blk = new DataLayer[n];
		var skyL = level.getLightEngine().getLayerListener(LightLayer.SKY);
		var blkL = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
		for (int i = 0; i < n; i++) {
			LevelChunkSection s = secs[i];
			if (s != null && !s.hasOnlyAir()) {
				states[i] = s.getStates().copy();
			}
			SectionPos sp = SectionPos.of(cx, minSec + i, cz);
			DataLayer a = skyL.getDataLayerData(sp), b = blkL.getDataLayerData(sp);
			sky[i] = a != null ? a.copy() : null;
			blk[i] = b != null ? b.copy() : null;
		}
		// Empty chunks are authoritative too: removing the last block must replace cached geometry.
		int[] biomes = new int[16];
		for (int qz = 0; qz < 4; qz++) {
			for (int qx = 0; qx < 4; qx++) {
				int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, qx * 4 + 2, qz * 4 + 2);
				try {
					biomes[qx + qz * 4] = MetaPalette.biome(chunk.getNoiseBiome((cx << 2) + qx, y >> 2, (cz << 2) + qz));
				} catch (RuntimeException e) {
					biomes[qx + qz * 4] = 0;
				}
			}
		}
		return new Snapshot(cx, cz, minSec, states, sky, blk, biomes);
	}

	/** The chunk's voxels (16 x 16 x height from minY) and their LODs at levels 0 to 4. */
	static MetaStore.@Nullable RealChunk build(Snapshot s, int minY, int height, boolean skyLight) {
		Rle.Builder b = new Rle.Builder(16, height);
		int[] col = new int[height];
		BlockState lastState = null;
		int lastId = 0;
		int airTop = skyLight ? 15 : 0;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int biome = s.biomes()[(x >> 2) + (z >> 2) * 4] << 16;
				for (int iy = 0; iy < height; iy++) {
					int y = minY + iy;
					int sec = (y >> 4) - s.minSectionY(), ly = y & 15;
					int v;
					if (sec < 0 || sec >= s.sections().length) {
						v = Voxel.air(airTop, 0);
					} else {
						PalettedContainer<BlockState> pc = s.sections()[sec];
						int id;
						if (pc == null) {
							id = 0;
						} else {
							BlockState st = pc.get(x, ly, z);
							if (st != lastState) {
								lastState = st;
								lastId = MetaPalette.id(st);
							}
							id = lastId;
						}
						if (id != 0) {
							v = id | biome;
						} else {
							DataLayer sl = s.sky()[sec], bl = s.block()[sec];
							int sky = sl != null ? sl.get(x, ly, z) : airTop;
							int blk = bl != null ? bl.get(x, ly, z) : 0;
							v = Voxel.air(sky, blk);
						}
					}
					col[iy] = v;
				}
				b.column(x, z, col);
			}
		}
		Rle[] lv = new Rle[5];
		lv[0] = b.build();
		for (int l = 1; l < 5; l++) lv[l] = lv[l - 1].mip();
		return new MetaStore.RealChunk(lv);
	}
}
