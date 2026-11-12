# MetaLODS (Imagine Metal's far terrain): design, status, test plan

Jack asked on 2026-11-13 00:00 for a Voxy-like LOD system built into the shader, Metal-native, as performant as possible, with its
own settings menu next to Imagine Metal's (not under it), named MetaLODS. Unexplored terrain should come from the seed the way
SeedGen does it (he pointed at SeedGen, "works with Terralith"). Default distance 128 chunks (his pick).

Licences: Voxy and both SeedGens are All Rights Reserved. Only their methods were studied, nothing was copied. mcopt (Apache-2.0)
supplies the seed generator (mcopt.metal.lod.LodNoise / LodForest / LodTrees / LodColors).

## How it works
- **Columns** (`imaginemetal.metalods`): level L = 32 x 32 cells of 2^L blocks, the dimension's full height in voxels 2^L
  tall, levels 0-5. Each cell is kept as runs (value, end) along y (`Rle`). A voxel is an int: block id 16 bits
  (`MetaPalette`), biome 8, sky light 4, block light 4 (light only on air).
- **Choice per frame** (`MetaWorld.visit`): top down from level 5 (2D, Voxy-style). A column closer than `detail` x its width
  splits into its 4 children once all 4 are meshed, otherwise it stays drawn. Columns fully inside the real terrain's chunks
  are skipped. Default detail 6: level 0 to ~384 blocks, 1 to ~768, 2 to ~1536, 3 beyond.
- **Data**: generated column (`MetaGen`: mcopt's LodNoise tile of 64 x 64 cells -> 4 columns; exact trees from LodForest on
  level 0, the biome's impostor canopy above; with "Overhangs" (default on) the terrain density is also sampled in 3D on
  LodNoise's grid and carves overhangs/arches under the surface and adds floating ground above it) + every real chunk seen there laid over it (`MetaStore.RealChunk`: levels 0-4,
  mipped Voxy's way: the most solid of 8 children wins, upper first; all-air keeps the brightest light).
- **Real chunks** (`MetaChunks`): captured 2 s after the client loads them (light in) and again at unload (player edits).
  Air with sky light 0 under a column's top block is filled (caves, house insides: invisible from afar, else thousands of
  hidden faces). Skipped where the column's light isn't known yet.
- **Storage** (`MetaStore`): `saves/<world>/metalods/<dim>/gen5/L<l>/<cx>.<cz>.bin` (generated) and `real/r.<rx>.<rz>.bin`
  (32x32 chunks), deflate, palette by block/biome names per file. Multiplayer: `.minecraft/metalods/servers/<ip>/<dim>`.
- **Meshing** (`MetaMesher`): a face wherever non-air touches air, greedy rectangles up to 32 x 32 per face direction. Quad =
  2 uints (x5 z5 y9 face3 w5 h5 | block16 biome8 sky4 block4).
- **GPU** (`MetaArena`, `native/metalods.m`, `shaders/metalods.metal`): pages of 256 quads in one 128 MB shared buffer, a
  16-byte header per page; per frame the CPU lists the pages of visible columns whose face group faces the camera (ring of 4)
  and one instanced draw (instance = page, 1024 indices) runs in the **G-buffer pass** (hooked in `Deferred.end`, before
  `im_defer_fs`), writing the same 4 attachments as `own_fs_g`. So the LODs get Imagine Metal's lighting, haze, sky light,
  fog and TAA (the shader adds ImFrame.taa's sub-pixel offset like own_vs). Fragments inside chunks the real terrain draws
  are discarded (chunk mask). Textures: the block's top/side sprite (LodColors), tiled per block, fading to the sprite's
  average past mip 2.5-4.5; biome tints from a 256-entry table.
- Far plane (`MetaLodsCameraMixin`), vanilla fog (`MetaLodsFogMixin`) and Imagine Metal's border fog (`ImagineFrame`
  fogParams.x) reach out to the LOD distance while MetaLODS is active.
- Settings: `config/metalods.properties`, screen `MetaLodsScreen` (Video Settings: button beside "Imagine Metal settings...";
  pause menu: "MetaLODS" button beside "Imagine Metal"). Stats line in latest.log every 10 s: `[MetaLODS] stats: ...`.

## Limits of this first build (known)
- Needs Imagine Metal's deferred lighting (off with a PBR pack, a debug view, F8): then nothing is drawn (logged once).
- No LODs in dimensions with a ceiling (the Nether). Generation only in singleplayer (a server's seed isn't known).
- Generated 3D is from the terrain shape before caves (sloped_cheese): no caves, and islands get stone inside, the surface
  block on top. Whether Terralith's skylands are in that density is unverified.
- LODs cast no shadows. Small cracks can show where two levels meet. Grass, flowers, torches are left out (no collision).
- Water is an opaque surface with a sheen (material POLISHED), not see-through.
- Nothing was run: CI compiles the shader, the native code and the jar; the data path (runs, mips, meshing, file round trip)
  was tested offline against the 26.3 jar (`/home/claude/test`, not in the repo).

## Test plan for Jack
1. Deploy the branch jar (see HANDOFF). Open a singleplayer world (Terralith). Look for `[MetaLODS]` lines in the log:
   "ready", the dimension line, "stats" every 10 s (tiles generated, ms each, meshes, real chunks).
2. Look out over the land: LODs past the render distance? Seams with the real terrain? Colours vs the near terrain?
3. Fly around for a minute: does far land fill in, fps vs MetaLODS off (pause menu > MetaLODS > off)?
4. Distance 64 / 128 / 256 and Detail 4 / 6 / 10: fps and look.
5. Anything wrong: F2 + a chat note as usual.

## Also on this branch
- C3 (merged from branch `underwater-window`): under water the window up through the surface is wide (Debug toggle "Wide
  window up from under water", env.w bit 32), for Jack's "can only see out through a circle above me".
- Debug: "Show levels" tints each level (0 red ... 5 blue), "Remake all LODs".

## Next steps (not done)
- LOD shadows, LODs in the forward path (deferred off),
  translucent far water, import of existing saves (Voxy's world import), GPU-side culling (HiZ) if the draw costs too much.
