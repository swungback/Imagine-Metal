# 2026-11-21 — Codex s1121 renderer investigation

The working tree is on s1121, based on origin/s1121 (146d1df). The tracked source matched that revision byte-for-byte before
this work. The local handoff was one revision behind and is backed up in claude-scratch/codex-s1121/HANDOFF.local.md.
Rollback: claude-scratch/s1121/imagine-metal-s1121-final7.jar (SHA256 2fa996a159be365cf7642f41fb895ab4e6d076854b80add73dbf257b712fe1f6).

## Movement

ColdTp `steps` accepts `move=X/Z` offsets in blocks and `freezeTime=true` pauses daylight on the disposable save. Tests
use a fresh copy of IM-coldtp-f1p, (-191.5, 94, 836.5), yaw 10.7, pitch -1.4, time 5000, rd 6. After loading, ColdTp
walks four timed 8-block steps east, waits, returns, then repeats the 32-block move. See claude-scratch/codex-s1121/control.properties.
The diagnostic JVM argument `-Dmetalods.movementStats=true` logs once per second (also settable with ColdTp's
`imaginemetal.metalods.MetaWorld#movementStats=true`). Counters are cumulative per world. The line gives cold requests by level, with prefetch included. Dirty requests are
broken down by refinement, real capture, newly available neighbor, Streams, and settings. Multiple reasons may apply.
It also includes age, pressure and parent evictions, selected replacements, mesh fades, completed meshes, seed tiles,
disk hits, and real captures built and accepted.

First crossing, seconds 49–60 (full logs in claude-scratch/codex-s1121):
| | final7 + probe | warm1 |
|---|---:|---:|
| Minimum mesh readiness | 99.34% | 100% |
| New on-demand requests | 17 | 0 |
| Prefetch requests | 32 | 0 |
| Real-capture rebuild requests | 88 | 79 |
| Neighbor rebuild requests | 30 | 13 |
| Completed meshes | 167 | 92 |
| Seed tiles generated | 10 | 0 |
| Disk column hits | 80 | 35 |
| Capture summaries built / published | 93 / 34 | 101 / 34 |

The arena stayed over 82% free, with zero pressure evictions. Age evictions appeared during the later hold, after the
first crossing. The original level-1 prefetch used detail 4 (512 blocks) but the traversal splits its parent at farDetail 6
(768 blocks). It also preloaded only outward refinement, missing the coarser columns that come into view when backing
away. The fixed buffer warms 64 blocks around the actual draw boundaries and holds its center until the camera moves
more than 48 blocks. The live draw selection uses the existing thresholds. The repeat crossing completed 54 meshes
before, 9 with warm1, with zero seed tiles in both. At the start of the route the warmed build held 301 prefetched columns
vs 292, and made 100 seed tiles vs 93 during initial loading. Toggle `-Dmetalods.warmBuffer=false` for the old prefetch.

Most real captures deduplicated, and the cache comparison found 30 first captures, 14 persistent block/biome differences
and one lighting-only difference. warm2 invalidates just the changed mip levels and neighbors that read changed shared
borders. First-time captures invalidate all applicable levels. Toggle `-Dmetalods.preciseCapture=false`.
Previously a same-level replacement immediately retired its old pages, bypassing the transition noise. warm2 retains those
pages for the existing 500 ms complementary dissolve when replacing a settled on-screen mesh and the arena has room.
The shadow map still uses the new mesh. Toggle `-Dmetalods.meshFades=false` or the existing `-Dmetalods.fades=false`.

The full build was rechecked on a warmed copy. The 32-block crossing stayed at 100% ready with two mesh jobs,
zero new demand/prefetch jobs, zero generated tiles and zero evictions. This warmed replay had one accepted capture during
the crossing, so it does not isolate the invalidation change's speed. It exercised 77 mesh fades by the end of the crossing.
Before vs warm1 matched photos (fixed time and view) differed by 0.77–0.97/255 across the image and 0.17–0.75/255 in the
terrain rows. Full build vs before and rd 12 real-chunk comparisons are in
claude-scratch/codex-s1121/movement-comparison.jpg. The rd 12 reference shows the existing difference in mountain detail.
Verified at 1728 × 1080 and render scale 67%. ColdTp restored rd 6.

## Frame hitches and loading CPU

JFR control, first 35 s after teleport: 40 stop-the-world pauses, 843 ms total, 139.3 ms longest (the frame window peaked
at 170 ms). Over the recording, LOD workers allocated 7.9 GB, server 2.1 GB, render 1.7 GB and ColdTp 0.49 GB.
Settled frame pacing still varied. A 136.7 ms frame took 15.9 ms of render-thread CPU, and its perf window recorded a
120 ms maximum drawable wait. No JFR GC event coincided with that spike. There was also a separate 99.9 ms GC pause
late in the run, near a 117 ms frame. Both GC and drawable waits contribute. JVM/present settings were kept for these runs.
The second cold pass had unusually long structure-start blocking and GC and was excluded from the movement A/B.

Control JFR, first 35 s, 3,088 LOD-worker CPU samples including capture and idle workers, inclusive categories overlap:
LodNoise 39.5% (LodShellSampler 30.6% and LodLatticeSampler 11.7%), Streams 15.8%, MetaWorld.edge 17.7%,
LodForest.tryStart 5.2% including base-height calls 2.1%. JFR logged 236 structure-start monitor waits totaling 23.8 s
across the workers. The second cold pass logged 12 such waits totaling 40.3 s, so remaining structure cost is sensitive to
contention in addition to the already-batched heights. The test save already held many coarse seed tiles.
The edge optimization reads each immutable real capture once per chunk along the edge (up to 32 reads → 2 per L0 edge)
and reuses four buffers per worker (192 KiB of edge-array allocations avoided per Overworld L0 job). All lighting and
fallback data are kept. Toggle `-Dmetalods.reuseEdges=false`. The change's isolated in-game wall time was not measured.
Terrain density, structure placement and Streams still use their existing algorithms.

## End and Nether

End test at (0.5, 113, -105.5), yaw -60, pitch 7, `dimension=end` in ColdTp. The End router generated 396 seed tiles
in 16.7 s and reached 1,200/1,200 selected meshes ready at rd 6. The central island and distant floating islands render
with the void between them. The far islands look flat where the End fog saturates. `ImagineFrame` packs the real-chunk
distance into the End fog limit. The shared `imFogTail` in terrain.metal uses that same limit for deferred LOD pixels.

IterationT is the in-game Black hole option. At rd 12 the streaks across the void reproduced with MetaLODS turned off,
and the pale ribbon of far islands appeared with MetaLODS on. The shots reset the camera at matching coordinates.
The sky's ringed-planet animation uses wall time. See claude-scratch/codex-s1121/end-comparison.jpg, end.log and
endcheck.log. The End sky, fog and lighting code are untouched. Black hole and End LOD fog remain open.

Nether design proposal: preserve lava seas, caverns and downward-facing natural ceilings below the bedrock, and exclude
the bedrock roof and upward-facing exterior cap from the distant mesh. Use Nether biome fog, ambient and lava light
without skylight. The desired look still needs Jack's choice between enclosed foggy caverns and an open cutaway.

## Installed and verification

Installed: claude-scratch/codex-s1121/imagine-metal-codex-s1121-buffered-final.jar
(SHA256 3b74d024b5fb8fe98b8c5e3427432683ff3adb665ce3d9fc9eb6f07dcbbc1787).
ColdTp disabled, render distance restored to 6, original render scale 67%, original End sky Reimagined.
CI check `JAVA_HOME=<JDK 25> sh ci/metalods/check.sh` passed, including the 422,927-quad digest match.
Also checked every light-only border mutation at capture sizes 1–16 and verified 1,176 edge lookup coordinate cases.

# START HERE — 2026-11-21 16:10 Claude s1121: Jack's four (cave speckle, LOD village, LOD shadows, LOD meshing speed)

## Installed: claude-scratch/s1121/imagine-metal-s1121-final7.jar (SHA256 2fa996a159be365c...), all of s1121, tested.
Branch s1121 on origin (CI 44911680342 green) = Jack's working tree (checked identical). Built by build.sh (perf1 +
this tree's classes/shaders + s1120's CI dylib, natives unchanged). Rollback: claude-scratch/s1120/imagine-metal-s1120-final.jar.
Edited files' originals: claude-scratch/s1121/orig/. Test saves moved to claude-scratch/s1121/saves. options.txt rd 6, ColdTp off.
FIRST LOAD AFTER INSTALL: level 0 (gen10-t6) and levels 1-3 (gen10-s3) LOD tiles are made again once (old -t5/-s2 kept on
disk, read only with generate=false or -Dmetalods.structureVoxels=false). Real-chunk captures (real2) untouched.

## 1 Cave speckle: FIXED, tested
Spot -321 95.37 1226 yaw 28.7 pitch 20.7, time 5000. The speckle = the hand-over dither band (edgeDither off: gone). The
speckled surfaces were the REAL chunks; the pixels they hand to the LODs were near black: lighting.metal skipped the open-air
test for LOD pixels (s1119: no LOD casters in the maps). s1120 put LOD land in the far cascade, so LOD pixels take it again when
that map holds them: MetaLods.farMapHasLods (per cascade-1 redraw), ImFrame env.w bit 128, toggle MetaSettings.lodOpenAir.
Final build, same run on/off vs real rd 12 (c1r): diff to real 10.4 -> 3.5, luma 51.1 -> 59.1 (real 60.9), noise 1.08 -> 0.95
(real 0.93). Shots coldtp-c2-40 (on), -45 (off), coldtp-c1r-55 (real).

## 2 LOD village: FIXED, tested (level 0 and forced level 1)
Spot -505 123.63 1335 yaw 126.3 pitch 10.7 (Jack's; his shot was zoomed, the FOV varies run to run: crop 700,480-960,640).
Real reference: same line 150 blocks closer, -626 95.3 1246 (v2r real rd 12, v2l LOD). The village is a walled castle-style
village (tall in reality too). Causes:
a) MAIN: the LODs' noise puts water where the game raised the ground under the village (BEARD_THIN); structure blocks on LOD-water
   cells were dropped: the hall right of the tower lost its front wall (its floors showed: "open floors, yellow bands"), the huts
   in front and the long wall were missing. LodStructurePreviews.raiseShore: under BEARD_THIN/BEARD_BOX pieces whose lowest block
   is at the water's surface or above, the cell becomes land up to that block, and 3 blocks round (structureShoreReach) land at
   the water's surface. Only raises; only those adaptations (no shipwrecks, igloos, buried). -Dmetalods.structureShore=false.
b) Levels 1-3 (shown past ~256 blocks, and until level 0 is ready: Jack's shot) drew each 2^L cell as ONE solid column in its
   LOWEST block (grey corner pillars, a brick post, floor bands). stampVoxels: per voxel the most solid kind, then the most
   common block, ties to the upper one (topmost-wins first: banded walls); structure voxels stand on water cells too
   (MetaGen.cell's wet branch lays them with the water round them); a gap of up to 8 blocks under a piece is ground.
Shots: before coldtp-v1a-55 / b4v-55 (forced level 1: steps=0:detail=2); after coldtp-f7v-55 (level 0), a6v-55 (level 1).

## 3 LOD shadows: the pillar is NOT darker (measured), nothing changed
s4c (real rd 12) vs s4a: pillar body luma 136.1 vs 136.0. With LOD shadows the LOD ground is closer to real (+5.1 -> +3.2);
the darker spots at the pillar's foot are tree shadows real ground has too. LOD slopes a little brighter than real (no corner
shading, Jack's choice fix22). Whole frame at his spot, final vs s1120 (f1p vs s4a): mean diff 1.16 = run-to-run noise.

## 4 LOD meshing: ~2.2x faster in game, same quads
JFR (s4a): in the mesher exposed() via facesX/Y/Z 42 %, sky seeding (sideDark) 20 %, last light pass 8 %. MetaMesher fast path:
face range from where air/water/rest meet, read off the column's runs; sky seeding only below the neighbours' open-sky bottoms;
last pass stops under the open sky; grid corners filled (were stale from the worker's previous column: light depended on job
order). Offline 6315 stored columns: identical, 22.4 -> 8.3 s. In game (run m1, his spot, paths alternating per column,
MetaMesher.meshAB): 9.4 ms new vs 20.2 ms old per column (wall time on the workers). CI: ci/metalods/MeshFastCheck.
Toggles: MetaMesher.meshFast (runtime, -Dmetalods.meshFast=false), meshAB; stats line "[MetaLODS] meshing: fast N ... old N ...".
Next for LOD CPU: structure heights (biggest single part), MetaWorld.edge (~6 %), Streams.

## NEXT (Jack 21 Nov 15:26; he is away until Tuesday, Codex may pick this up)
Jack's standing rules: find the cause before changing anything (profile/measure first); no resolution drops or visible quality
loss; compare before/after shots at the same spot and time, and LOD look changes against real chunks (renderDistance 12 for
one run, then back to 6); keep this file updated as you go; keep a rollback jar.
1. LODs rebuilding when moving a couple of chunks ("pretty annoying moving two chunks and it has to regen again"). Jack wants
   DH-like behaviour: a bigger area than needed is kept ready, so it is done for a while once loaded. Facts (code, not yet
   measured): metalods.properties distance=112 chunks, detail=4 (level 0 to ~256 blocks), farDetail=6. Generated tiles are
   cached on disk in the save (metalods/), so they are not generated twice, but: MetaWorld.housekeeping drops columns not
   wanted for 600 frames (~10 s), 60 frames under arena pressure (an 1/8 or less free), and parents whose children are ready;
   the look-ahead ring (around MetaWorld ~1486) is only min(128, group) blocks past each level's inner edge; the refine rule
   (level > 0 && d < kd * w) moves level boundaries with every step; real chunks entering the render distance are captured
   (MetaLods ~255-300, 2 s after load) and their columns rebuilt. MEASURE FIRST which of these makes the "regen" (log mesh
   jobs / generated tiles / disk-cache loads / evictions while flying a few chunks: ColdTp can teleport; the stats line
   "[MetaLODS] ... meshes N, tiles generated N, disk cache ..." and "MetaLODS: current view X% ready; N mesh jobs" help),
   then fix the cause (likely: hysteresis on level boundaries, a wider kept ring, longer keep time when memory allows).
2. Hitches / unstable FPS (Jack's standing complaint, before LODs too). At his spot (s1121 final, run f1p): p50 17.5 ms,
   p99 22.5 ms once loaded, but a 394 ms hitch at 15-20 s and ~2.7 s of GC pauses in a 40 s run. Profile the spikes (JFR:
   jdk.GarbageCollection, allocation by thread, the slow-frame log lines) before changing anything.
3. LOD CPU left (s1120/s1121 JFR at his spot): structure placement heights (biggest single part), terrain noise ~13 %, Streams
   rivers ~12 %, MetaWorld.edge ~6 %. Meshing is done (s1121).
4. Nether / End LODs (Jack asked). Nether: off by design: MetaLods ~255 "has a ceiling: no LODs there". Possible (DH draws the
   Nether), needs a design: e.g. the LODs without the bedrock roof and the upper ceiling layer so the view from inside isn't a
   lid, and sky/fog/lighting without sky light (MetaWorld.skyLight = dimensionType().hasSkyLight()). Ask Jack how it should look
   before building. End: not blocked (no ceiling), never tested: try it first (floating islands, void below, no sky light),
   report what breaks.
5. Older open items: GPU terrain G-pass ~9.6 ms independent of resolution (leaf triangles); the C2 ideas (simpler leaves far away)
   change the look: ask Jack first.
Git note for Codex: renderer-work/Imagine-Metal's files equal origin/s1121 (checked 21 Nov 15:05), but git there still shows
branch screenshot-audit with the changes uncommitted. Check with `git fetch origin && git diff origin/s1121 --stat` before
switching branches; commit on s1121.

## Tools this session
ColdTp steps=SECONDS:field=value,... : a MetaSettings field, im.+Settings, or a.b.Class#field (any static): matched on/off shots
or A/B timing in one run. setrun.sh x= y= z= yaw= pitch= for other spots. Offline mesher harness: claude-scratch/s1121/store-c1c.tgz
+ a MetaPalette stub (MeshFastCheck's approach). The cloud workspace can build the jar when the Mac sleeps (libs staged from
claude-scratch/s1117d/libs.tgz).

# START HERE — 2026-11-20 22:25 Claude s1120: Jack's four fixes (structures, MetalFX passes, leaves, LOD shadows)

## Installed: imagine-metal-s1120-final.jar (claude-scratch/s1120/; SHA256 39a4e9fd8678f12b...). Rollback: s1119-perf1.jar.
Java built from this working tree (overlay on perf1) + libmcmetal.dylib from CI run 44818075425 (branch s1120 on origin =
this working tree as of tonight + the s1120 native edits; CI green). Edited files' originals: claude-scratch/s1120/orig/.
Jack allowed attaching the repo to Claude's session (21:42) for CI. options.txt back to renderDistance 6, ColdTp enabled=false,
test saves moved to claude-scratch/s1120/saves. Tools this session: ~/tools/build.sh LABEL (DYLIB=... to put a CI dylib in),
~/tools/setrun.sh LABEL JAR k=v... (jar into mods, fresh save from s1118-fix24/saves/IM-coldtp-g24, ColdTp at -192 94 836).

## Jack's 16:37-16:38 shots (read): LOD mountain without the shadows the real chunks next to it have; "looks off in caves"
(16.38.25/26, LOD lush cave from above: black speckle, teal glow); "village looks broken" (16.38.53, LOD village at -505 123
1335 facing west, pieces overlapping/floating). Cave and village are NOT done: compare with real chunks there first.

## 1 Structure placement cost: DONE, exact
Cause (JFR at his spot): ~30 % of all LOD job CPU in NoiseBasedChunkGenerator.getBaseHeight (26.3: NoiseChunk over 1 x 384 x 1,
final density sampled, walked down through the aquifer; ~80 % Perlin noise at cell corners). Structures ask neighbouring columns:
same column again 5 %, same 4x4 noise cell 38-45 %, 8x8 ~62 %.
Fix: LodBaseHeights.fill (GenBaseHeightCacheMixin on a miss): the whole 4x4 cell (same corners as one column) in 64-block slabs
from the top until both world-gen heightmaps are met; cache shared by the workers (striped). Verified in game: 3000/3000 heights
identical to the game's walk at batch 4 and at batch 8 (-Dmcopt.gen.baseHeightVerify=N). Batch 8 was slower per height (fill
2.9x a walk vs 1.2x): default 4. Result (s1a vs s1d): structure-height CPU -31 %, ~10 % of LOD job CPU.
Gotcha: GenMixinPlugin reads LodBaseHeights.ENABLED while mixins are prepared: no Minecraft class in its static init (a
Heightmap field there crashed startup: "BlockStateBase was loaded too early", Lithium).
Left: meshing 30 %, terrain noise 13 %, Streams rivers 12 %, other structure work 10 % of LOD CPU.

## 2 MetalFX extra passes: motion folded in; screen copy kept (why)
imbloom.m: MetalFX's set-up moved before the resolve; the resolve writes the motion (and the mobs' reactive mask) as attachments
1/2 (bloom.metal im_bloom_resolve_fx/_fxr, same imbMotion, same alpha test on the 16-bit value); the separate motion pass is the
fallback. Shots s2a vs s3a (same spot/time): mean difference 0.8/255, below run-to-run noise (1.1-1.3): no visible change.
Saves only ~0.1 ms (taa 1.84-2.01 -> 1.80-1.86 ms). MetalFX straight to the screen: NOT done: the screen is 8-bit and the copy
(im_bloom_fxcopy) adds the 8-bit dither; writing MetalFX's output there directly would band the sky (visible loss). MetalFX's own
encoders (~2.5 ms, "idle" in the perf line) are Apple's.

## 3 Real leaves: the 4.3 ms was mismeasured
bench split booked MetaLODS' G-buffer draws AND the deferred lighting under "terrain leaves (cutout colour)" (both run after the
leaves in Deferred.end, the last part the timing step cut). Measured with new keys at his spot (s2a): bushy planes cost 0.2 ms,
leaf depth pre-pass 0.1 ms (no benefit, but the pre-pass does run: 8-14k depth + EQUAL draws per 10 s, -Dmcopt.own.leafDrawLog),
leaf culling ~0. MetaLODS not drawn: -5.4 ms (s3a). So no leaf change was made (nothing worth changing the look for).
Added: Benchmark keys bushy_off, leafcull_off, lodshadows_off; Deferred.end tags MetaLODS / lighting for the split step (the
native cut didn't take there: still shows under leaves; mc_render_split inside the G pass, not looked into).

## 4 LOD shadows at the hand-over: DONE (native), cost ~0.2 ms
MetaLods.drawShadow (Shadows, after the far cascade's terrain casters) -> MetaWorld.shadowList (last frame's selected columns
in the cascade's orthographic box, light-facing faces, land only, columns the real terrain draws whole left out, partly drawn
masked per pixel) -> metalods.m mml_shadow (Load the slice, depth only; ml_shadow_vs/fs in metalods.metal). Toggle:
MetaSettings.lodShadows (-Dmetalods.lodShadows=false). Shots (same spot/time, 40 s): real chunks rd12 s4c / before s3a / after
s4a: LOD slopes now get the stepped self-shadow the real ones have; the LOD pillar is a little darker than the real one.

## Next
Jack to test: shadow pop-in at the hand-over, LOD look in shadow. Then his cave and village notes. LOD CPU: meshing.

---

# START HERE — 2026-11-20 15:45 Claude s1119d: hitches (GC), light grid, LOD loading profiled (structures, not trees)

## Installed: imagine-metal-s1119-perf1.jar (claude-scratch/s1119/) = light3 + the items below. Rollback: s1119-light3.jar.
Prism instance JVM arguments now: -Dmcopt.own.prepass=true -XX:MaxGCPauseMillis=20 (set in Prism's UI; instance.cfg).
Jack (20 Nov): "dont want to just lower the res ... cant u optimize what we do have"; "fps was unstable before lods so dont
just give lods all the blame". His Mac had other apps open (GeForce NOW, Chrome, Spotify) and Prism warned of memory pressure:
runs today are noisier than yesterday's; compare within a day.

1. GC hitches: G1 young pauses averaged 74 ms (r7: 35 pauses, 2.6 s), every few seconds even when idle (77 ms). With
   MaxGCPauseMillis=20: 16-18 ms each (r9 154 pauses / 2.5 s, r11 168 / 3.1 s). Frames >100 ms: r7 23 -> r9 8 (r11 noisier).
2. LightVolume: no step once the light settled (32 steps with no move, no section rewrite, no light-carrying entity;
   -Dimaginemetal.lightQuiet=0: as before). "light grid" (0.7 ms) gone from settled frames.
3. LOD generation profile (JFR ExecutionSample, r9/r10): the "trees" stage is 82 % structure starts (LodForest.tryStart ->
   Structure.generate): findValidGenerationPoint -> NoiseBasedChunkGenerator.getBaseHeight (~60 %, a full noise column per call,
   ~50 ms CPU each, plus the allocation: L2 tile 0.9-1.8 GB) and Ice and Fire's mixin giveWay -> StructureRoom.check ->
   getFirstFreeHeight (~40 %). LOD workers allocated 24.6 GB in ~2.5 min (render thread 1.6 GB).
   Added LodBaseHeights + mixin GenBaseHeightCacheMixin (mcopt-metal-gen.mixins.json, GenMixinPlugin): exact per-worker cache of
   getBaseHeight while inside LodForest.starts: works, but only 5-6 % hits (columns rarely repeat). Kept (harmless).
   -Dmcopt.gen.baseHeightCache=false: off. Next for this: a cheaper exact column height for structure placement (the game
   builds a whole NoiseChunk + aquifer per call), or reuse the LOD tile's own block-exact L0 ground where it is exact.
4. Tried, rejected: workers at utility QoS during bursts (-Dmetalods.burstQos=utility): LODs ~7x slower, frames no better.
GPU while LODs load: every pass ~20-30 % slower (taa 1.9 -> 2.5 ms) while the CPU is at 90-100 %: shared power/bandwidth.

## Next (GPU, no visual change)
MetalFX: write its output straight to the screen texture (skip "MetalFX to screen" copy) and fold the motion pass into the
resolve (native: imbloom.m, CI build). LOD faces still hidden (392k: tops 193k, sides 181k, leaves 95k). Real leaves 4.3 ms.

---

# START HERE — 2026-11-19 19:30 Claude s1119c: LOD light under overhangs (underpass) fixed in code; lake checked vs real chunks

## Installed now: imagine-metal-s1119-light3.jar (= s1119-ao + the edits below). Copies in claude-scratch/s1119/.
Rollback: imagine-metal-s1119-ao.jar, then imagine-metal-claude-fix24.jar. options.txt restored to renderDistance 6
(a copy of Jack's is s1119/options.txt.jack). instance.cfg restored (s1119/instance.cfg.orig).

## Jack (16:29): LODs "fully light under huge underpass dont make sense" (real chunks dark there). Causes and edits:
1. metalods.metal ml_fs: sky light floor `max(lq.y, 10/15)` on every LOD face -> only on leaves (its reason).
2. MetaMesher.skyLight(level): -1 a voxel -> -2^L a voxel (a level-L voxel is 2^L blocks); water and leaves -1 a block, not 2
   (the game's dampening 1); open water passes 15 straight down like air; water voxels take the computed light (generated
   water at 15 takes it, real-chunk water is raised to it): LOD lake beds lit as open sky, as the real ones are (via oa).
3. ml_fs writes g3.r's low code 0 (own_fs_g writes 1..15) = LOD pixel; im_defer_fs (terrain.metal) decodes it -> m.lod
   (ImPre.lod in lighting.metal, false elsewhere); imShade: wantOpen (C1's open-air test, imShadowX oa) is skipped for LOD
   pixels: the shadow maps have no LOD casters, so under a LOD overhang it read "open" and let the sun and sky back in.
   imFarSun got a start parameter (always hp.range.x now).
Tried and reverted: height-map shadows (imFarSun) for LOD pixels from 0 blocks, multiplied with the maps: the 16-block texels
darkened a lake with higher shores (shots/cmp-lake-real-old-new.jpg: real / old / that try).
Check: shots/cmp-lake-real-old-r7.jpg (real chunks at render distance 12 / s1119-ao LODs / light3 LODs, same spot and time):
light3 matches. The underpass itself is NOT verified yet: need a spot (ask Jack for coords).

## FPS (Jack: 30s-40s in F3 during the runs). Log r1 (s1119/latest-r1.log): while LODs generate/load after the teleport,
frames 21-26 ms with the CPU at 90-99 % of 8 cores; once done 18-20 ms. At his spot (-192 94 836): light3 55 fps (18.2 ms),
MetaLODS 5.0 ms. Output is 1728x1080: render scale 67 % = 1158x724, 50 % = 864x540.
Cost table at that spot (s1119-ao, r1): render scale 50 % 4.8 ms, MetaLODS 4.6, MetalFX 4.3 (1.9 timed + ~2.5 untimed MetalFX
encoders shown as "idle"), real leaves 4.3 of terrain 8.7, shadows 2.1, coloured light 1.2, normals 0.7, rest ~0.
Next: the CPU load while LODs generate (what drops play to 30s), more invisible LOD face cuts (392k quads: tops 193k,
sides 181k, leaves 95k), Jack's answers on render scale / MetalFX.

---

# START HERE — 2026-11-19 16:30 Claude s1119b: fix22's "no per-block AO on LODs" never took effect; fixed (-30 % LOD quads, +3 fps)

## Installed now: imagine-metal-s1119-ao.jar (SHA256 6ac1dc3c5bff084d...) = probe3 + MetaMesher.BLOCK_AO fix
Copy in claude-scratch/s1119/. Rollback: imagine-metal-claude-fix24.jar. ColdTp config left enabled=false.
Cause: MetaMesher.BLOCK_AO = !"false".equals(getProperty("metalods.blockAo")) = ON unless -Dmetalods.blockAo=false. fix22's
handoff says false; every jar since fix22 (fix22/23/24, probe3) has it ON (checked the class constants). So LOD faces still only
merged where all 4 corners shade alike (most stayed 1x1). Now "true".equals(...): off by default, -Dmetalods.blockAo=true = old.
Jack approved this on 11-18 ("DH looks alright, do that if it's much faster").
Build: repo source compiled to probe3's classes byte for byte (422/422) first; only MetaMesher*.class changed. Orig source:
claude-scratch/s1119/orig2/MetaMesher.java.

## Measured (same spot/time as below, bench=lods_off; q3 = before, q4 = after; logs s1119/latest-q3.log, latest-q4.log)
| | q3 (AO on) | q4 (AO off) |
|---|---|---|
| frame, everything on | 16.66 / 16.55 ms (60 fps) | 15.86 / 15.76 / 15.81 ms (63 fps) |
| MetaLODS cost | 7.1 ms | 5.7 / 6.2 ms |
| LOD quads | 657k (L0 208k, L1 228k, L2 200k, L3 22k; tops 370k, sides 283k; leaves 166k, water 8k) | 462k (L0 159k, L1 148k, L2 140k, L3 15k; tops 231k, sides 227k; leaves 135k, water 8k) |
Look: shots/cmp-q3-q4.jpg (q3 top, q4 bottom): same, far slopes a little flatter (no crease darkening), as DH.

## Next
Quads are spread evenly over levels 0-2 (not the far rings). Still ~460k quads: sides 227k and leaves 135k are next. Ideas
to measure: leaf faces fully inside canopies; side faces below ~1 px at L1/L2 (would change the look slightly: ask Jack).
Then Jack's steps 2-4 (below). Native LOD shadows: ASK Jack first.

---

# START HERE — 2026-11-19 16:05 Claude s1119: FPS measured (cause found: MetaLODS ~7 ms of a 17 ms frame); nothing visual changed

Jack's plan (chat 15:16): 1 FPS (measure LODs off / farShadows off / shadowRange 128 / renderScale at one spot + time, find the
cause, fix without lowering visible quality unasked), 2 LOD shadows at the hand-over (native: check CI first; his yes/ask choice
was left as template text, so: ASK before native work), 3 real vs LOD at trees/water/cliffs, 4 fix22's MetaLODS tile speed plan.
His fix24 test report was also left as template text: no fix24 play session or F2 shots exist (newest are 2026-11-18 21:17-21:19).
Stopped at 16:00 because the Mac's screen locked (computer use can't launch while locked).

## Installed now: imagine-metal-s1119-probe3.jar (SHA256 ec9b432a33d576b8...) = fix24 + inert harness/probes below
Rollback: imagine-metal-claude-fix24.jar (instance folder). ColdTp config left enabled=false. Test saves IM-coldtp-q3 in saves/
(p1, v1, q1, q2 moved to claude-scratch/s1119/). Logs per run: claude-scratch/s1119/latest-*.log, shots in s1119/shots.

## Harness added (s1119)
- ColdTp `bench=key,key,...` (+ benchWait, benchMeasure, benchRepeat, benchTimeout, benchShots=true): after the teleport run, the
  Benchmark's steps at that spot, each between two "everything on" windows, then report + quit. `bench=full` = the whole list.
  Keys: Benchmark.keyed() (lods_off, lods_vertex, lods_plain, lods_lightcheap, lods_notex, farshadows_off, shadow128, shadow96,
  shadows_off, scale50, scale100, split, taa_off, ssao_off, shafts_off, deferred_off, airpass_off, ..., fardetail4, detail3).
- New probes: MetaLods.probe 4 (LOD pixels skip im_defer_fs: ml_fs marks 0x81 at params.z 13, hp.range.z 1), probe 5 (LOD
  textures skipped, params.z 14). Shader edits: metalods.metal (2 lines), terrain.metal im_defer_fs (1 line). Inert at probe 0.
- MetaLODS stats now also log "[MetaLODS] drawn quads: N (by level ..; tops/sides/bottoms; leaves, water)" — NOT run yet.
- Build: device VM, JDK 25 in ~/jdk-25.0.5+8 (gone with the VM session), cp = s1117d/libs.tgz + Terralith + Streams; fix24 source
  compiled to the installed fix24 jar byte for byte before the changes. Overlay script: re-create from the fix24 entry.

## Measurements (spot -124 149 475, yaw 25.2 pitch 8, time 5000, render distance 6, LODs 112, detail 4 / far 6, 1080 out @ 67 %)
Frame 16.6 ms (60 fps) in run p1; 17-19 ms in later runs (the Mac was busier: memory-pressure warning at launch). GPU-bound.
| saving when off (ms) | p1 (x2) | q1 (x2) |
|---|---|---|
| MetaLODS not drawn | 7.0 / 6.9 | 7.8 / 7.7 |
| LOD pixels (vertex work kept) | 5.7 / 5.6 | 6.2 / 5.9 |
| ml_fs body (plain colour) | 2.1 / 2.1 | 2.3 / 2.2 |
| LOD pixels' lighting (im_defer_fs) | - | 2.1 / 1.7 |
| LOD textures | - | 0.4 / 0.1 |
| TAA off (= MetalFX temporal scaler) | 3.7 / 3.4 | |
| render scale 50 % | 3.6 / 3.4 | (100 %: costs 9.5) |
| deferred off | 4.2 / 4.1 (but it drops the LODs: no G pass; forward costs ~2.8 more) | |
| shadows off | 1.4 / 1.4 | |
| light shafts | 0.6 / 0.4 | |
| far shadows off | 0.3 / 0.2 | |
| shadow range 128 | -0.1 / -0.3 (nothing) | |
| ssao, air pass, leaf pre-pass, plant shadows | <= 0.2 | |
q2: far detail 4 saves 0.6, detail 3 0.2 (quads 804k -> 764k): the far rings are not where the quads are.
So MetaLODS = ~42 % of the frame: vertex ~1.5, rasterizing + G writes ~1.7, ml_fs ~2.2, lighting its pixels ~1.9.
~800k quads for ~600k LOD pixels (~1 quad per pixel). Tried: ml_fs's G-buffer reads (color(2-4)) only in the water variant
([[function_constant(ML_WATER)]]: compiles) to let HSR drop hidden LOD faces: no change (v1, 7.8 vs 7.7): reverted.

## Next
1. Launch q3 (config: world IM-coldtp-q3, label q3, bench=lods_off, enabled=true) and read "drawn quads": which levels/faces/leaves
   hold the 800k quads. Hypothesis: level-0 ring (96-256 blocks, 1-block voxels) + leaves (exactTrees) dominate.
2. Then a quality-neutral cut where the quads are (e.g. leaf/canopy faces fully hidden inside canopies, quads below a pixel);
   anything that changes the look: ask Jack. Check every look change against real chunks (same spot/time) first.
3. Step 2 (LOD shadows): CI CAN build the native code: .github/workflows/build.yml on macos-15-intel runs clang -arch arm64 for
   libmcmetal.dylib (mod/build.gradle compileNative: mcmetal.m ... metalods.m) and compiles every .metal; runs on any push
   (artifact "imagine-metal"), dev release only from main. The local repo is on branch screenshot-audit with 99 files uncommitted
   since 2026-11-14 (Codex works here too): a CI build means pushing this working tree to a new branch (from the cloud container,
   not touching Jack's local git). Plan: mml_shadow_draw (metalods.m) + ml_shadow_vs (metalods.metal: same vertex pulling,
   depth only, quads in chunks the real terrain draws collapsed), drawn into the far cascade (slice 1, kept/redrawn <= every
   200 ms) for columns between the real edge and shadowRange; ask Jack first.

---

# START HERE — 2026-11-18 22:20 Claude fix24 INSTALLED (on fix23): LOD structures are the game's own; 900 ms chunk dissolve

Jack 21:20-21:30 (screens 2026-11-18_21.17.49 to 21.19.30, New World (10)): "a structure in lod not being there" = the sandy house
lumps (21.19.12, 21.19.15); "pop in and switch is too clear" / "couldn't see the blend, looked like straight pop in"; "some things
wayy too different on lod"; fps pretty bad; asked for faked shadows/dark areas on LODs (a huge shadow appears close when a real
chunk arrives) and what DH + SeedGen do.

## Causes found
- Sandy houses: LodStructurePreviews (not ours originally) stamped a village centre + random pool houses at every structure set's
  *possible* chunk, checked only against the biome at a guessed ground; the game's own pick/terrain checks never ran. New World (10)'s
  region files hold no surface structure near -165 75 550 (scan: claude-scratch/s1118-fix24, tools in ~/tools of that VM session),
  only the Ice and Fire witch cottage at -121..-103, 68..86, 518..536, which the previews never showed.
- Pop: the per-chunk dissolve (MetaWorld CHUNK_FADE_MS) was 300 ms. The band dither ran (edge blend 100 % in his log).
- Big shadow: LODs are never in the shadow maps (MetaLods draws no shadow pass); inside 0.7 x shadowRange (179 blocks) LOD pixels
  get only real casters, the height-map far shadows start at 179. A real chunk arriving brings its casters in one frame.
- FPS (his 21:15-21:19 log): render thread waits 12-16 ms/frame on the GPU (CPU 4.5-7.6 ms), even with LOD workers idle; up to
  885k LOD quads at 112 chunks, farShadows on. Not measured further yet.
- DH + SeedGen + Reimagined (iris test instance): Reimagined sets dhShadow.enabled = false; LODs get lightmap^8 sky darkening + N.L,
  shadows fade out over the last 16 blocks of shadowDistance; DH noise texture on; ditherDhFade, vanillaFadeMode DOUBLE_PASS.

## fix24 (imagine-metal-claude-fix24.jar, SHA256 6f1f409cd019e7c6..., Java only, shaders = fix23's)
1. LodStructurePreviews rewritten: starts from LodForest.starts (now package-private; the game's createStructures logic, cached per
   chunk; Streams' keepFootprintOffRivers runs in it), pieces stamped where the game puts them (PoolElementStructurePiece: template
   at position/rotation; TemplateStructurePiece: template/placeSettings/templatePosition by reflection). TERRAIN_MATCHING pieces,
   coded pieces and processors left out. L0: exact blocks into treeBlocks (a tree in the same column dropped); L1-3: column from
   the lowest block (walls) to the top (roof). Verified: g23 vs g24 (claude-scratch/s1118-fix24/shots/g-crop.jpg, same spot, same
   time, real2 store moved out): the sandstone lumps are gone.
2. MetaStore.GEN_VERSION 9 -> 10 (gen9 tiles hold the fake houses); L4+ read from gen9 then gen8. Cost: every world regenerates
   its L0-3 seed tiles once.
3. CHUNK_FADE_MS 300 -> 900 (-Dmetalods.chunkFadeMs). Not judged in game yet.
4. ColdTp: fly=true (hover), shots=s,s,... (screenshots/coldtp-LABEL-NN.Ns.png, HUD hidden), time=N (/time set at the teleport).
Cost seen: MetaLODS workers spend time in Structure.generate (Streams river wait, StructureTemplateManager/DataFixer) at new
places (f24-far JFR hot frames); the first LODs at an unexplored spot were equally late in f23 and f24 (none by 15 s in both).

## Tried and reverted (claude-scratch/s1118-fix24/tried-shaders)
- LOD pixels marked (lean code 0 in g3.r), shadow maps skipped for them, height-map shadows from 0 blocks: the 16-block texels
  shaded whole gullies the real terrain leaves lit (shots/slope-compare.jpg). Worse match.
- Reimagined-style sky^8 darkening on LODs: LOD lakes went dark (seabed sky light drops under water) where real lakes are bright.
## Next
- Shadows at the hand-over need LODs in the shadow map near the band (native: mcown.m / MetaLods draw, needs the Mac) or a finer
  height map; ask Jack.
- FPS: GPU cost per pass (LODs off, farShadows off, shadowRange 128) at one spot, same time of day (ColdTp time=).
- Structures: only templates; jigsaw paths (TERRAIN_MATCHING) and coded structures (temples) missing.

---

# START HERE — 2026-11-18 21:05 Claude fix23 INSTALLED: real chunks after a cold teleport ~40 % sooner (C2ME priority, MetaLODS backs off, Polymer look-ahead)

Task (Jack, via a Codex chat that ran out): real chunks load slowly in unexplored land ("stuck in LOD hell"). Keep LOD 512, visuals
and worldgen; keep the LOD-to-real transition; profile first. Codex's experiments (Polymer bypass, template-cache presize, worker
priority) were NOT in the source (only a stray StructureTemplateCacheMixin.class in mod/build/libs); the installed jar matched
the repo byte for byte, so that was the baseline. Codex's /private/tmp logs weren't reachable.

## Harness (inert by default): imaginemetal.ColdTp
config/imaginemetal-coldtp.properties enabled=true + world=IM-coldtp-* (refuses any other folder; switches itself off when it
starts). Opens the save from whatever menu shows, waits `settle` s, teleports to x,y,z (3000,300,2600, yaw 0 pitch 25), flying
off (the fall guard must hold), then per chunk of the 9x9 ring: server FULL (polled on the server thread), client has it, real
terrain drawn whole (MetaLODS hand-over mask; only the 40 in view count); frame times by 5 s window; CPU by thread group;
thread states + hot stacks (server-thread chunk waits with their caller); server ticks; player y (guard); JFR (profile). Report
logs/coldtp-LABEL.txt (+.jfr), then quits. Launch: Prism > File > Launch > Launch (computer use, background). JFR reader:
claude-scratch/s1118-coldtp/Jt.java (modes threads, threadsw -Dbucket, samples, contains, block, blocktop, nsamples, -Dfrom/-Dto).
All runs: identical copies of New World (9), same spot. Saves, reports, JFRs: claude-scratch/s1118-coldtp/.

## What the profile showed (baseline A)
- C2ME puts its workers at a fixed Mach priority on macOS (client default preset LOWER = priority 23, below every default-QoS
  thread at 31) and that also takes them out of QoS for good (pthread_set_qos_class_self_np -> EPERM, qos_class_self 0x0).
  Six workers runnable 44 % of the time got 0.4-1.5 cores between them while MetaLODS (burst QoS default) took 1.2-1.9, Streams'
  sampler ~1, GC/JIT/Metal ~1.5 and other apps (ChatGPT/Codex, Claude) ~2-3: the Mac sat at 99-100 % of 8 cores.
- Server thread: Polymer's predictive-mining ray (ServerPlayerGameMode.tick -> PolymerImplUtils.clientCorrectedBlockClip ->
  getChunk) and vanilla (teleport accept -> Entity.move, Entity.isInWall) load the player's chunk synchronously: ticks froze
  20-36 s. Polymer's ray hit a chunk other than the one under the player: that chunk was FULL at 33.7 s but reached the client at 43.2.
- Diagnostic C (LOD generation off): 16.7 s, so MetaLODS contention was about half of it.
- Not ours to fix: Streams computes the new region's river network first (~5-7 s, sampler threads) and C2ME waits on it
  (RiverEngine.networkFor); first use of each structure template in a session runs the DataFixer (deep DFU stacks) inside
  StructureTemplateManager's computeIfAbsent; vanilla's own waits for the player's chunk (harmless: that chunk comes first).

## fix23 (imagine-metal-claude-fix23.jar = installed, SHA256 f0732181a87775fc...; source in the repo, compiles to it exactly)
1. compat.C2mePriorityMixin (only with c2me-base): C2ME's macOS setCurrentThreadPriority is skipped; the thread goes into the
   worldgen QoS class instead. GenMixinPlugin: -Dmcopt.gen.worldgenQos now defaults to `default` (=off: as before). User-initiated
   was tried (E, G): no faster, and 1.2-2.4 s frame stalls while the chunks came in.
2. MetaWorld: under pressure (chunks missing in the render distance) the workers stay at utility (PRESSURE_QOS), never the burst
   class; -Dmetalods.pressureQos=utility|background|off. Background was tried (D-F): frame stalls. -Dmetalods.pressureWorkers=N
   (default YIELD_WORKERS, 2); 0 was tried (F): no gain, so LODs near you keep coming during a teleport as before.
3. compat.PolymerPredictMixin + mcopt.metal.compat.ChunkWaitGuard: Polymer's ray misses when a chunk on it isn't loaded on the
   server (no synchronous load). -Dmcopt.chunkWaitGuard=false: off.
4. LodGenQos.once logs the class a thread had ("was 0x..").
ci/metalods/check.sh: all Java checks PASS (the clang++ part needs the Mac).

## Before / after (client = what you see; frames after the teleport)
| run | chunk under player: server / client | 81-chunk ring on client | under player drawn | frames >100 ms / max |
|---|---|---|---|---|
| A baseline | 33.7 / 43.2 s | 57.2 s | 47.5 s | 10 / 208 ms |
| A2 baseline | 35.9 / 36.0 s | 62.8 s | 51.1 s | 20 / 223 ms |
| C LOD gen off (diagnostic) | 16.7 / 16.7 s | 31.3 s | 24.5 s | 9 / 452 ms |
| H fix23 | 23.2 / 23.2 s | 40.0 s | 31.4 s | 16 / 183 ms |
| H2 fix23 repeat | 23.1 / 23.1 s | 37.0 s | 30.4 s | 14 / 154 ms |
Frame p50 ~20 ms in all (LOD 512 loading). Fall guard: y held at 300.0 while the chunk under the player was missing, fell
normally once it arrived and landed (y 112). Edge blend 100 % of frames at the end of every run, baseline and fixed.

## Next
- Still ~23 s: Streams' network + C2ME's own chain. Ideas: warm Streams' network / structure templates for the area ahead in the
  background before the player gets there (MetaLODS already knows where it's heading); measure a walk/flight, not only a teleport.
- Cyclops cave (Ice and Fire placed feature) has no LOD preview (Codex's finding; MetaLODS previews a limited set).
- Other apps on the Mac took 2-3 cores during every run: closing ChatGPT/Codex while playing gives the game those cores.

---

# START HERE — 2026-11-18 15:30 Claude fix22 INSTALLED (on fix21): LODs without per-block AO (merged faces), cave fill fix, band diagnostics

Jack 15:23 (two DH screens, 2026-11-14_02.26.59/02.27.04: DH dissolves the vanilla chunks into its LODs with per-pixel noise over a
wide band): "DH looks alright so do that if it's much faster" (answer to the AO question); "when are you making the fade like these";
"our shit needs to perform better and look as good or better or there's no point compared to just adding DH support"; "why was it so
much faster earlier, the only difference is water being fixed? it took seconds, now ~10 minutes and keeps loading more when I move";
"as close to DH/Voxy and SeedGen in speed and polish". He hasn't run fix21 yet (latest.log still the 14:07 ZGC session).

## fix22 (imagine-metal-claude-fix22.jar, SHA256 6f9bb16d999dd777..., MetaMesher + MetaWorld over fix21)
1. MetaMesher.BLOCK_AO false: no per-block corner shading on LOD faces (0xFF), so faces merge by block/biome/light alone.
   Offline (cols/nw12): near L0 4591 -> 3465, far L0 1854 -> 1216, L1 1708 -> 1126, L2 1216 -> 939, L3 362 -> 280 quads/column.
   The SSAO pass (bloom.metal im_bloom_ao) stops at 48 blocks, so the LODs have no AO now (as DH). -Dmetalods.blockAo=true.
2. Bug in fix21: MetaMesher.DIM defaulted to 0, not 4 (the handoff said 4). Now 4: near L0 with both 7139 -> 3018 (-58 %).
   CaveCheck updated (a pocket lit <= DIM closes, lit DIM+1 stays).
3. Stats line: "edge blend on N % of frames (off: % not possible, % a LOD missing in the band)". The band (fix21: 32 blocks)
   exists since fix17 but switches off whenever one chunk in it lacks a ready LOD (transitionCovered): Jack has likely never
   seen it while moving. Read this number first.

## Why it got slow (answer given to Jack, from the logs)
16 Nov 21:51 (512 chunks, detail 10): 710 tiles in ~1 min, 252 ms per tile (wall), meshes 6.2 ms. 18 Nov 14:11: ~1000 ms per
tile, meshes 33-37 ms, workers on the CPU 26 % of their wall time. What changed after 16 Nov evening (not the water): fix12
block-exact surfaces from the whole router at levels 0-1 (384 samples per column instead of 49; the accuracy Jack asked to keep),
fix16 the game's trees on far level-0 tiles again (~445 ms of an L0 tile), Streams queries, and 1 GB/s of garbage from the
block-exact sampling (GC eating cores; meshes 6x slower = the whole process starved, not the mesher: offline 1-6 ms).
"Keeps loading when I move": far tiles are remade with the whole router (caves) and trees when you come within ~224 blocks,
and new level-0 tiles are needed as the fine rings move.

## Next (new chat): the speed plan, measure each step in game
1. Read his fix21/fix22 session: allocation MB/s by group, GC pauses, tiles/s, CPU % of wall, edge blend %, held back %.
2. Trees: LodForest's stand-in level is a java.lang.reflect.Proxy (string switch + boxing per getBlockState/setBlock): profile
   a tree chunk offline (needs a level: build a harness from the server classes or time it in game with JFR) and replace it.
3. Level 1 (733 ms density in game): bracket the surface first (LodSurfaceSearch: 32/16/8-block planes, batched) and sample
   block by block only in the bracket (as fix17's shell does with corners at L0); ShellCheck-style equality test.
4. Band: if "a LOD missing" dominates, give the band's columns first place in the job order and keep the last drawn mesh of a
   column until its replacement is ready (no gap = band stays on).
5. Then time a full view at 112 chunks against fix20/fix21; goal: seconds, as 16 Nov.
Not possible without a Mac build: per-chunk real-terrain fade (ImFrame fixed 944 bytes in native mcown.m).

---

# START HERE — 2026-11-18 14:50 Claude fix21 INSTALLED (on fix20): allocation cut, soil/disks, cave faces, structures, yield, wider band

Jack 13:55-14:19 (see fix20 below and the project doc): lag spikes, "chunk to LOD not smooth, still pops", LOD dirt where real
chunks have stone, "make it as fast as before but DH / SeedGen fast", structures like SeedGen; 14:12 screens (14.11.57,
14.12.01, 14.12.21 "in an lod"): dirt cliffs on a near LOD, standing on LODs where real chunks hadn't loaded; 14:14 "take more
inspo from Voxy, it looks better". He ran 14:09-14:13 with -XX:+UseZGC -XX:+ZGenerational in Prism (instance.cfg JvmArgs):
p50 19-26 ms, 87 frames > 50 ms in ~2.5 min, 3.8-4.1 tiles/s (the G1 run before: 6+). No gain from ZGC.

## Measured (claude-scratch/s1118/bench/benchpkg: AllocBench, MeshAlloc, MeshWhere, PrelimCheck, cmp*.py, rle.py)
- Allocation per tile, density only, offline (Terralith, New World seed, java backend): near L0 187 MB, far L0 68, L1 106, L2-3
  61. 45 % the density functions' intermediate buffers (SamplerContext's default arena = DensityBufferArena.GLOBAL, unpooled),
  the rest our createUnpooled stripes and kept volumes. 4-7 tiles/s x that = the 0.85-1.1 GB/s F3 showed.
- Materials, New World (9) store (real2 vs gen9-t2 L0, 17.7k dry columns at equal height): LOD soil always 4 deep (top + 3),
  real 0-5 by the rules' surface depth; 89 % of top-block mismatches within 2 blocks of water (gravel/clay/mud/sand/stone):
  the disk features (UNDERGROUND_ORES step), not rules. Not the preliminary surface (PrelimCheck: all "above").
  MC 26.3 steep = gradX <= -4 || gradZ >= 4; minSurfaceLevel = floor(preliminarySurface(x,0,z)) + surfaceDepth - 8.
- Near L0 meshes (cols/nw12-near-L0): 7139 quads/column, 5185 below the top: generated air has sky 15, so closeCaves never
  saw a dark cave. Far L0 1838.

## fix21 (jar imagine-metal-claude-fix21.jar, SHA256 2adc8278fb4bc49d..., 22 classes over fix20; source in the repo)
1. LodBuffers (new): per-thread DensityBufferPool (the game's) as the workers' SamplerContext arena and for our stripes
   (LodSurface/Shell/Lattice/Solid samplers, LodDensityBatch, LodNoise grid/coarse/terrainVolume); kept volumes reuse a
   thread array (slot 0 blockVolume, slot 1 volume; used up by MetaGen in the same thread). tick() after each tile (age 6).
   Offline: 0.4-2.3 MB per tile, output bit-identical (hash). -Dmetalods.pool=false: as before.
2. Logs: generation line "(allocated surface + trees + 3D + columns MB)" per level; stats line "allocated per job (mesh), per
   real chunk", "chunks missing in the render distance, workers held back %"; frames line "GC pauses" (ZGC Cycles / G1
   Concurrent beans excluded) + "allocation MB/s: render, server, MetaLODS, game workers, C2ME, other, total; heap used".
   Trees line: "structures found N (ms of placing per tree chunk)".
3. Soil: LodNoise.surfaceMaterials at level 0 runs the rules down the column (SOIL_MAX 8): under x underN, under2 x under2N,
   stone below (LodTile.under2/underN/under2N; MetaGen.cell). -Dmetalods.soil=false.
4. Disks: LodForest runs the UNDERGROUND_ORES step's DiskFeature placements (before the trees, the step's own seeds); their
   blocks (FROM_GROUND) set the surface / the block under it. -Dmetalods.disks=false.
5. Caves: MetaMesher.skyLight(): the game's sky light spread over the mesh grid (15 down through air, -1 otherwise, leaves and
   water -1 more); air stored at 15 takes it (real chunks' own light kept). closeCaves fills enclosed air lit <= DIM (4).
   Near L0 7139 -> 4010 quads/column (-44 %), entrance walls lit 5-15 kept. -Dmetalods.skyFill=false, caveDim, caveBlocks.
6. Levels 2+: density every second voxel across (LodNoise.stride, MetaGen same): CostBench far-vol-h2, 0.30 % voxels differ,
   heights within 2 voxels, 3.7x less CPU. -Dmetalods.farHalf=false.
7. Structures from the seed (LodForest.starts/tryStart/structures): per chunk as createStructures picks them (placement
   isStructureChunk, single or weighted pick with the large-feature seed, Structure.generate with the world's seed and
   biome check; concentric rings skipped), surface step only; pieces placed with StructureStart.placeInChunk into the Stand
   (decoration seed + index in the surface step). L0: inside run() with trees (FROM_STRUCT: walls' lowest block as trunk,
   top block on top; paths/floors at ground-1 set the surface). L1-3: LodForest.structures(t) folds them per cell. Starts
   cached (cleared at 400k chunks). Untested in game (no ServerLevel offline). -Dmetalods.structures=false.
8. Yield: while > 2 chunks within render distance - 1 are missing on the client, only 2 workers make LODs (nearest first),
   the rest only ingest real chunks (MetaWorld.pressure, every 10 frames). -Dmetalods.yieldWorkers=N (0 off).
9. Edge band 16 -> 32 blocks (MetaLods.EDGE_BAND: MLFrame camBlock.w and ImFrame lodFog.z via lodTransition; no shader change).
10. Store: level 0 dir -t3, levels 1-3 -s1 (regenerated: soil, disks, structures, h2); gen8 fallback only for L4+.

## Not done / why
- AO-preserving greedy merge: faces merge only with equal per-voxel AO bytes and the shader lays one byte over every voxel of a
  quad; merging different AO needs another encoding (per-vertex AO or screen-space AO for LODs). Asked Jack (look change).
- Per-chunk real-terrain fade when a chunk arrives: needs a per-chunk table in the lighting pass; ImFrame is a fixed 944-byte
  buffer in native mcown.m (imFrame[944]), can't grow without a Mac build.
- Streams banks (gravel/stone by rivers above sea level): the Stand only has water below sea level; Streams is proprietary.
- "Voxy look": asked Jack what he means (textures, no DH noise/desaturation, lighting?).

## Next
Read his fix21 session: allocation MB/s by group, GC pauses, tiles/s, structures found, missing chunks/held back %, frames.
Recommend to Jack: remove -XX:+UseZGC -XX:+ZGenerational from Prism's JVM arguments (keep -Dmcopt.own.prepass=true).
Rollback: imagine-metal-claude-fix20.jar in the instance folder.

---

# START HERE — 2026-11-18 14:15 Claude fix20 INSTALLED: far detail independent of detail; detail set to 4 (see the project doc
"claude/HANDOFF - START HERE.md" for the full measurements: LOD cost is geometry, lag spikes are G1 GC pauses at ~1 GB/s
allocation; next steps: ZGC via Prism JVM args, MetaLODS allocation pooling, near-tile cave faces, AO-preserving merge)

---

# START HERE — 2026-11-18 14:00 Claude fix19 INSTALLED: frame-time diagnostics + far-terrain benchmark (on fix18b crossfades)

Jack 13:36: plays with MetaLODS ~100 chunks (512 only to pre-load); the cherry-blossom 20s were on Friday (13 Nov) in a random
cherry biome; performance is mid and unstable even before LODs; find the issues and fix them, don't assume it's all LODs.
## Evidence (perf lines of every session since 10 Nov; 10-fps windows = game minimized/unfocused, inactivityFpsLimit, excluded)
- options.txt: enableVsync true, maxFps 60, fullscreen. Since ~12 Nov fps sits at the 60 cap when the GPU frame is < ~16 ms;
  before (10-11 Nov) up to 115-122 fps. GPU frame with almost no LODs on screen is already 14.7-17 ms (render scale 67%):
  terrain 3.3, taa 1.9, resolve 1.0, light grid 0.2-0.9, hand+gui 0.8-2.0, uploads 0.5-1.5, translucent (water) 0.5-4,
  reflection copies 0.35, ao 0.3, bloom 0.25 + GPU idle inside the frame 2.5-4.5 ms. So every scene is at the 16.7 ms edge:
  with vsync, a frame that misses it waits for the next refresh (60 -> 30 steps) = unstable feel.
- Friday (13 Nov, 256 chunks) lows: 5-8 windows under 40 fps with the GPU having spare time = CPU-side stalls, not GPU.
- At ~100 chunks tonight (00:39-00:41): 5-7k LOD pages drawn, terrain 12-16 ms, 33-47 fps; 15-16 Nov at 96 chunks: 3.6k pages,
  terrain ~7 ms, 49-60 fps. LOD geometry per view about doubled since 16 Nov.
- Offline: 76-87% of LOD faces are 1x1 (per-corner AO blocks merging; ignoring AO = 32-41% fewer faces).
## fix19 (installed, SHA256 fb80e1fbf9f44db4...)
1. "[Imagine Metal] frames:" line every 10 s (Perf.framesLine): frame time p50/p90/p99/max, % over 20/34 ms, per frame: render
   thread CPU, waits by site (GPU in flight, fence, vsync/drawable, pacer, drain; WaitStats now always counts, take()), rest
   off-CPU, GC, MetaLODS render-thread time (MetaLods.frame timed). "[Imagine Metal] slow frame:" for frames > 50 ms (max 8/10 s).
2. MetaLods.probe + Benchmark steps (not drawn / vertex only / shading skipped) and a MetaLODS-screen button
   "Benchmark the far terrain (~40 s)" (Benchmark.start(true)). Shader: ml_vs params.z 12, ml_fs params.z 11.
3. fix18b underneath (crossfades in the masked pipeline only, sliding edge band).
Next: read his session's frames/slow-frame/benchmark lines, then fix what they show (CPU stall source; LOD faces vs pixels).

---

# START HERE — 2026-11-18 00:40 Claude fix17 INSTALLED: exact shell sampling on far L0, far-detail falloff, HUD % by columns

Jack 00:04 chose A + B from the measured proposal below (C not wanted). He also asked why the view "didn't finish in 3.4 min"
and the HUD said 0%.
- 0%: the HUD's "current view N% ready" was weighted by AREA. With nearest first, the L0 + L1 rings are 48% of the columns but
  2.6% of the area (L4 columns are 1,024 chunks each): it sat at 0-3% for most of a load. fix17 counts columns.
- 3.4 min was my offline model. In game tonight 4.7-4.8 tiles/s -> 1,318 tiles = ~4.7 min, ~1.4x the model (real-chunk ingest,
  re-meshes, Streams, tree grounds, JIT/GC). No session tonight ran long enough to finish (77 s to 5.5 min).

## fix17 in game (Jack 00:36-00:41, New World (12), 512 chunks, detail 10, far detail 6)
Joined 00:37:08, MetaLODS started 00:37:13, "0 asked, 0 jobs queued" at ~00:39:13: the whole view in ~2 min (887 tiles, 7.4-7.8
tiles/s at peak, MetaLODS 1.4-2.8 cores). HUD 100% then 92-94%: he moved ~130 blocks (-189,-239 -> -321,-232; real chunks
416 -> 1,105), ~200 new columns at the edges. Screens 00.41.07/22. His next asks: some LOD areas wrongly coloured (not
identified from these screens: asked him for F2 + note), and DH-style smooth transitions. metalods.properties now says
distance=112 (changed in the menu during/after the session).
Existing transition: a 16-block static per-pixel dither at the real terrain's edge (metalods.metal imLodEdge*, lighting.metal),
only once every chunk in that band has a ready LOD (transitionCovered). Hard swaps remain: a chunk arriving (mask bit flips),
LOD level swaps (4 children replace a parent at once). Shader changes can't be Metal-compiled from Claude's sandbox.

## fix17 changes
1. LodShellSampler (new) + LodNoise: far level-0 tiles (LodNoise.far, which has no noodle caves) take the density at the 4x4x4
   cells' corners first and sample block by block only through the y span of cells whose corners differ in sign (+1 cell).
   ShellCheck (ci/metalods, new): 40+25 Terralith tiles per level on two seeds and 20 vanilla: 0 heights and 0 of ~230M blocks'
   solid/air differ from LodSurfaceSampler. L0 115-131 -> 57-63 ms. Level 1 left block by block (corners cost nearly as much;
   vanilla L1 was slower with it). Near (whole router) tiles unchanged. -Dmetalods.shell=false turns it off. Stored columns stay
   valid (identical output): no folder rename.
2. MetaSettings.farDetail (default 6, 4-16, "Far detail" slider beside Detail, metalods.properties farDetail): the refine factor
   for levels 2-4. Level 1 (-> L0 ring) and level 5 keep `detail`, so with Jack's detail 10 L0 still reaches 640 blocks and L5
   is never drawn (untested level). Bands at 512 chunks: L0 0-640, L1 640-768, L2 -1,536, L3 -3,072, L4 -8,192.
   Tiles for a cold view 1,318 -> 810 (tiles.py). farDetail 16 (or = detail) = the old layout.
3. HUD percentage by columns (colsAll/colsReady in count()); the 95% detail gate and the done/backlog logic still use area.
Expected (model x 1.4 in-game factor, 1.7 cores): cold view ~4.7 -> ~2.6 min, the L0 ring ~67 -> ~54 s. Not measured in game.
Visible: nothing inside 640 blocks; beyond, voxels about twice as big at the same distance (see the measured section below).

## Build / install
javac --release 25 of all mod/src/main/java against libs.tgz; overlay onto fix16 of MetaWorld(+inner), MetaSettings,
MetaLodsScreen(+Slider), MetaLods, LodNoise(+inner), new LodShellSampler (the unchanged classes' bytecode was checked equal to
fix16's: same source). check.sh Java fixtures PASS; coverage_check.py not run (needs clang++). ShellCheck PASS also against the
jar's own classes. Installed SHA256 b53f0a00780a39d5..., copy `Imagine metal/imagine-metal-claude-fix17.jar`.
Rollback: game closed, copy `imagine-metal-claude-fix16.jar` over minecraft/mods/imagine-metal-0.1.0.jar (or set Far detail 16).

---

# START HERE — 2026-11-18 00:30 Claude: MetaLODS cost MEASURED (nothing changed, nothing installed; fix16 still installed)

Jack 23:32: make MetaLODS much cheaper (DH + SeedGen style: less detail far, full-detail ring near, nearest first), keep all
fixes (cave entrances, no ledges, Terralith arches/spikes/islands, game trees near, LOD water incl. Streams rivers + fix16 banks).
Measure first, then say what changes and what it trades away BEFORE building. Proposal below sent; waiting for his choice.

## 16 Nov 21:51 (log 2026-11-16-5) was a warm, cheaper load, not a cold one
- World `overlev`, opened at 96 chunks in 5 sessions on 15-16 Nov. gen7 file mtimes: 24,472 columns on disk before 21:51,
  2,392 written 21:51-21:53. The view at 512/detail 10 needs 5,063 columns (tonight's log: "5064 asked"), so ~half was
  cached, incl. nearly all of the L0/L1 ring. It made 710 tiles, mostly L2-L4.
- It ran fix9 (= renderer-work/backups/2026-11-17-recovery jar, SHA ce6999e4...): vanilla `overworld/sloped_cheese` + top
  slide (its log says so) = no Terralith extras, no cave entrances, 4x8-interpolated L0/L1.
- MetaLODS CPU was the same then: ~90 CPU-s of work in 61 s = ~1.5 cores (tonight 1.5-2.0).
- A cold view with the 16 Nov code would be ~146 CPU-s (~1.4 min); today's is ~342 CPU-s (~3.4 min at 1.7 cores).

## Per tile CPU (ms, one thread; container Temurin 25, nativeNoise=java; seed of New World (12), Terralith 2.6.5)
Calibration: in-game L1/L2 density wall x CPU share 0.30 = 179/210 ms vs offline 207/212: container ~= M1 per thread.
| level | 16 Nov (cheese) | now | notes |
| L0 far | 6 (4x8 grid) | 108-119 block-exact (LodFarTerrain) | far 4x8 grid 15; corners 4x4x4 only 29 |
| L0 near | 6 | 189 (whole router) | |
| L1 | 18 | 207 | far 4x8 grid 54; corners 104-111 |
| L2/L3/L4 | 79/83/81 (65x40x65) | 212-224 / 215 / 208 (65x49x65 far) | InterpolatedFunction stripping: no change (no densify overhead) |
Not density (in-game timers x 0.30): L0 game trees ~67, L0 materials+columns ~27, L1 ~31, L2+ biomes+materials+columns ~58.
Meshing (MeshBench on stored columns): 0.5-1.7 ms per mesh, near L0 with caves 4.9; current mesher +15-30% vs HEAD;
2.2 meshes per column tonight vs 1.4 on 16 Nov. Total meshing ~16 CPU-s of 342: small.
Cold view at 512/detail 10, rd 6 (tiles.py): L0 349 (50 near), L1 277, L2 272, L3 272, L4 148 = 1,318 tiles.
What each change since 16 Nov costs (cold view): Terralith-correct far shape at L2-L4 +93 CPU-s; block-exact L0/L1 +79;
meshing +10; trees/materials same both. Workers' CPU share unchanged (~1.5-2 cores; Mac saturated by C2ME + others).

## Candidate levers, measured
A (exact, no visible change): far L0 tiles from the 4x4x4 corners (the game's own interpolation points: Terralith's terrain
  is one InterpolatedFunction 4x4 cells, see tree dump), block by block only through cells whose corners change sign (+1 cell
  margin): 4.25% of blocks. 16 tiles: heights 100% equal, 3D voxels 0.00% differ. L0 108-119 -> 56 ms; L1 211 -> 175.
  NOT for near tiles (whole router: noodle caves fit between corners). Must be re-checked bit-exact on many tiles when built.
B (visible: coarser far): keep L0 ring, faster falloff beyond: refine factor 6 (now 10) for levels 2+. Tiles 1,318 -> 750
  (L0 to 640) / 658 (L0 to 512). Voxels beyond the ring ~1.7x bigger on screen. Adds L5 (32-block voxels) beyond ~6 km.
  L0 to 512 means MetaCanopy estimated trees from 512 instead of 640.
C (visible, small): L2+ density every 2nd cell, lerped (as the game does inside cells). L2 224 -> 64 ms: 0.30% voxels differ,
  98% of overhang/island/arch voxels kept, heights within 1 voxel. L3/L4 -> 58-59 ms but only 68-76% of those voxels kept.
  Narrow cave mouths/spikes < 2 cells (8 blocks at L2) can close/blur.
Cold view (CPU-s / min at 1.7 cores): now 342/3.4; A+B640 174/1.7; A+B512 160/1.6; +C at L2 158/1.5, 144/1.4;
+C at L2+ 120/1.2, 106/1.0. Full-detail L0 ring done: now ~48 s, A+B640 ~39 s, A+B512 ~27 s.
Nothing touches water (sea/aquifer, LodStreams + fix16 banks run on every level as now) or nearest-first priority.
Rejected: vertical sample every 16 blocks at L2+ (y16): loses 27-41% of overhang voxels for ~40% less cost; crop by a 32-block
pre-pass: no gain.

## Files
Bench sources + results: `Imagine metal/claude-scratch/s1117e/bench/` (CostBench.java in package mcopt.metal.lod, MeshBench.java
in imaginemetal.metalods, tiles.py view simulator, model.py cost model, bench2-4.txt). Columns sampled: s1117e/cols.tgz.
Run: javac with out/ (all of mod/src/main/java) + libs on the classpath; java -Dmcopt.lod.nativeNoise=java
mcopt.metal.lod.CostBench <terralith unzipped> -5565927190423133569 all 12.

---

# START HERE — 2026-11-17 23:40 Claude fix16 INSTALLED: Streams corridor banks fitted to the real world, game trees back on far L0

Jack 23:10 (fix15-diag session 23:08-23:10, NEW WORLD "New World (12)", same seed -5565927190423133569, screenshots 23.09.32-58): "its bad".
Screens: 23.09.42 round blob trees + red/beige mushroom caps in Old Growth Pine Taiga = fix13's estimated trees on far L0 tiles
(tiles near him were still "far", waiting in the refine queue: "0 near" all session). 23.09.32/34 standing inside LOD trees
(real chunks not loaded there yet). 23.09.56/58 from under the world (y -35, -184): the LODs are surface shells, hollow below.

## Speed: measured (fix15 diag lines, 23:09:13-23:09:53)
- The Mac's 8 cores at 100% the whole time. The game ~6.2 cores: Java threads ~4.9 (MetaLODS 1.5-2.0, C2ME 0.6-1.0, "other"
  1.0-1.6, render 0.4-0.7, server 0.1-0.3, Worker-Main 0-0.3) + ~1.3 GC/JIT/native. ~1.8 cores outside the game (other apps).
- MetaLODS workers RUNNABLE 96-100% of samples, never waiting on locks (<0.3%), yet 6 of them got 1.5-2 cores: time-sliced.
  CPU per tile matches offline cost. GC young pauses ~0.3-0.4 s per 10 s (~4%), heap 1.6-2.4 of 4 GB: not the problem.
- Worker time: ~50-60% density/noise (LodLatticeSampler, PerlinNoise, native blended/stack volumes), ~25-30% meshing
  (MetaMesher greedy/faces/exposed/mesh/closeCaves; ~9 meshes per tile), ~5% Streams plan, ~5% surface materials.
- Throughput 4.4-4.8 tiles/s, ~430 chunk-eq/s. The view at distance 512 / detail 10 is ~1,100 tiles (~300 CPU-s): ~3 min on
  ~1.8 cores. No bug: the CPU is full. Levers (not changed, Jack to choose): detail (biggest), meshing cost, the "other" threads
  (fix16 names them in the cpu line), apps outside the game.

## Rivers: measured, then fixed (fix16)
Plan dump (claude-diag/streams-plan-13_-167.csv) vs the real world's heightmaps vs the seed's fill: inChannel = 62% of real
water, 97% of it real water, plan waterY = real water top in 87%, bedY within 1 in 90%. Streams also reshapes inCorridor
(a strip up to ~12 blocks beside its channel): 41% of corridor columns lowered (p90 20), outside channel+corridor only 2%. The
LOD left corridor ground at seed height = the walls ("ledge"). fix16: corridor columns lowered to min(seed, waterY + BANK[d]),
d = 8-neighbour distance (blocks) to the channel, BANK = {2,2,3,8,19,21,25,26,27,28,31,31,31} fitted to the real world.
CorridorCheck (ci/metalods, inputs from session data): within 2 blocks of the real ground 40% -> 67%, mean error 7.4 -> 3.6
blocks; cross-validated halves 60->73% and 30->54%. Margin queried only for columns with a corridor (12 blocks of plan calls).
Data kept in `Imagine metal/claude-scratch/s1117d/` (real12.json, plan12.csv, data/hm.py region-heightmap reader).

## Trees
fix13's estimated trees on far L0 tiles reverted (game trees again); far folder renamed -far3 so the estimate columns stored by
fix13-15 are made again. LodForest ground dedupe + ring limit kept. fix14 QoS kept. fix15 diagnostics kept (+ "other threads").

Installed SHA256 bbc72f7e..., copy `Imagine metal/imagine-metal-claude-fix16.jar`. Rollback: fix15-diag / fix12 jars in the instance folder.

---

# START HERE — 2026-11-17 19:45 Claude fix15-diag INSTALLED: river banks reverted, diagnostics only (Jack: find the cause first)

Jack 19:20 (fix14 session 19:18-19:19, screenshots 19.19.01-45): still slow; fix13's banks look wrong ("how did u think that would
fix anything"). He asked to figure out the cause before fixing anything.

## Rivers: what the real world shows (measured, nothing changed but the revert)
Read the world's real region files (dimensions/minecraft/overworld/region, heightmaps OCEAN_FLOOR / MOTION_BLOCKING_NO_LEAVES,
189k columns, script `claude-scratch` ~/an/hm.py in the device VM) against the seed's own fill (GorgeCheck, final density +
aquifers). 72% of columns equal the seed within 1 block; Streams Reflowing's changes are a band of ground lowered >10 blocks
where its water is, often far wider than a stream (z -28: water 62 from x 92 to 156, seed ground 84-90 there). Its banks are
STEEP and NARROW: dry columns lowered >2 blocks: 38% at 1 block from water, 23% at 3, 14% at 5, 3% at 8, ~0 beyond 10 (p90
lowering 25, 16, 4, 1). fix13 cut a 1:1 cone out to 32 blocks: 4-5x too wide, so funnels/amphitheatres. REVERTED in fix15 to
fix12's channel cut. Still unknown: whether Streams' plan (inChannel/bedY/waterY) covers its whole wide water or only a stream in
it (the LOD gorge looked narrower than the real water). fix15 dumps the plan once per session around the player (384 x 384,
`saves/<world>/metalods/minecraft_overworld/claude-diag/streams-plan-X_Z.csv`: x,z,inChannel,inCorridor,bedY,waterY), to compare
against the real heightmaps. inCorridor is a public accessor of the plan record, only read.

## Speed: what fix14 showed
4.8 tiles/s (was 1.9), ~450 chunk-eq/s, but "CPU 28% of the tiles' wall time": while making a tile a worker runs ~28% of the time.
The CPU work per far L0 tile (652 ms wall x 0.28 = ~180 ms) matches the offline single-thread cost (~190 ms), so the generator isn't
slow, the workers don't get to run. Unknown: who holds the CPU (other threads, GC pauses, locks). fix15 logs every 10 s:
"[MetaLODS] cpu:" machine load, this game's cores, Java thread CPU by group (render, server, C2ME, MetaLODS, Worker-Main, ...),
GC count/ms per collector, heap; "[MetaLODS] workers:" RUNNABLE / BLOCKED / WAITING / IDLE shares from 20 Hz stack samples of the
workers; "worker frames:" top frames (first MetaLODS frame <- top frame); "worker locks:" which lock and its holder.
Next: read those lines and decide; don't change anything before.

Installed SHA256 6c894095..., copy `Imagine metal/imagine-metal-claude-fix15-diag.jar` (= fix14 + LodStreams back to fix12 + plan dump
+ MetaDiag). fix13's far-tree estimates and tree-ground limits and fix14's QoS stay.

---

# START HERE — 2026-11-17 18:25 Claude fix14 INSTALLED: workers at default QoS while a view fills; CPU-share stat

Jack 18:16 after fix13 (session 18:14-18:16 at 107 115 -28): "very very slow". Log: trees now 94-264 ms per L0 tile (were ~2,000;
grounds made 179, beyond the ring refused 19,736) but the far L0 surface rose to 1.9-3.6 s per tile (fix12 ~0.95) and tiles/s stayed
1.6-1.9: total throughput unchanged with a third of the work gone. Offline the same far 64x64 block-exact surface+3D is ~0.19 s
(java backend), so in game each worker got roughly a tenth of a core: the workers are utility QoS (MetaWorld.work, "so the game's
threads come first") and the M1's performance cores are held by the render thread (interactive), server (initiated) and C2ME's
6 worldgen threads; utility work is pushed to the efficiency cores.
fix14: each worker sets its own class every loop: default (0x15, C2ME's class) while jobs + details waiting > 24, else utility
(-Dmetalods.burstQos=default|initiated|off, -Dmetalods.burstJobs=N). New in the generation line: "CPU N% of the tiles' wall time"
(thread CPU time / wall time while making tiles): near 100% = the workers get their cores; far below = still starved.
Risk: FPS lower while a view fills (render thread stays above the workers). Not measured in game.
Installed SHA256 ce0326723eac8edf..., copy `Imagine metal/imagine-metal-claude-fix14.jar`; rollback `imagine-metal-claude-fix13.jar` (or fix12).

---

# START HERE — 2026-11-17 18:05 Claude fix13 INSTALLED: Streams river banks (the "ledge"), far level-0 trees as estimates, tree grounds

Jack 17:42 (fix12 session 17:39-17:40, screenshots 17.40.24/37/42 near spawn, 107 115 -28): the ledge is still there; still slow; take
more from DH + SeedGen ("it was fast earlier").

## The ledge was never the surface sampler
GorgeCheck (ci/metalods, seed -5565927190423133569 = New World (11), Terralith pack): the game's own fill (final density + aquifers,
NoiseChunk exactly as NoiseBasedChunkGenerator.doFill) has NO gorge at 60..250, -128..40: a plain at 83-94 with one small cave mouth
at 102-112, -10..-2. Aquifer barriers change nothing there. The vertical-walled gorge in the LODs is LodStreams (Streams Reflowing's
rivers): channel() cut every in-channel column straight down to the bed, columns whose plan had no water over the bed (wy < by) were
skipped and kept the full terrain, and nothing shaped the ground beside the channel. The real chunks have sloped, stepped banks.
fix13 LodStreams.apply: (1) also asks the plan in a margin around the column (min(16, 32/cell) cells, every 2nd cell at levels 0-1),
so rivers just outside the column cut its bank too; (2) channel cells without water: the ground lowered to the bed (lower()); (3)
every other column within 32 blocks of a channel: the ground capped at the nearest channel's water top + 1 voxel per cell away
(chamfer distance), its top 4/2/1 voxels (surface + under) moved down with it, trees on the cut ground removed, lakes untouched.
Only the plan's public accessors already used (plan, inChannel, bedY, waterSurfaceY, platesTouchingBox, platesReady).
BankCheck (ci/metalods, needs the MC classpath): river 22 blocks under a plain -> ground 88..66 in 1-voxel steps, grass kept.
Not verified in game: real Streams banks may be steeper/wider than 1:1 within 32 blocks.

## Speed (fix12 log: L0 tile 3.0 s = surface 0.95 + trees 2.0; "L0 1664" generate calls for 93 tiles = ~17 ring grounds per tile)
1. Far level-0 tiles (beyond max(192, transition+96) = 224 blocks) no longer run the game's tree features: impostor canopy at
   generation and MetaCanopy at derive (as levels 1+; MetaWorld.streamChannels also for store.isFar columns). Near tiles keep the
   game's trees and plants; a far tile is still remade with the whole router + game trees when the player comes near.
   At detail 10 level 0 reaches ~640 blocks: ~275 of ~315 level-0 tiles are far ones.
2. LodForest: grounds made once (in-flight futures: two workers made the same chunk's ground); while planting a tile only the tile's
   chunks + the 1-chunk ring may have their ground made, a lookup beyond answers air / no height (was a whole 16x16 ground per
   chunk two rings out). Stats: "grounds made N, beyond the ring refused M" after "tree chunks".
Expected (not measured): far L0 tile ~1 s instead of ~3 s; near trees roughly half. At detail 10 the view is ~1,100 tiles; detail
6-8 would cut that a lot (Jack's setting, not changed).

## Build
Same as before (javac --release 25 against minecraft-merged.jar 26.3 + gradle-home libraries staged from this Mac; overlay of the
changed classes onto fix12): LodStreams, MetaGen, LodForest, MetaWorld (+ inner classes), new VoxelLogs. check.sh all PASS.
Installed SHA256 ec6555c8339223141280e7e213de9e1fb254585e32bcf3980d7ca3a48658dc52, copy `Imagine metal/imagine-metal-claude-fix13.jar`.
Rollback: game closed, copy `imagine-metal-claude-fix12.jar` (or `../backups/2026-11-17-claude-fix12/`) over mods/imagine-metal-0.1.0.jar.

---

# START HERE — 2026-11-17 17:33 Claude fix12 INSTALLED: nearest first, far L0/L1 block-exact, far trees without plants

Jack 17:27 (fix11b session 17:16-17:26): trees much better; "blocky ledge turning into landscape" (17.24.38 -> 17.24.47, a
gorge drawn as flat stone walls), still too slow, nearer chunks must load first; asks why DH + SeedGen is faster and more accurate.
Log: 8.7 tiles/s, ~4.6-5.1k chunk-eq/s at peak, falling to ~5 as L0 trees rose to 800 ms/tile; near (whole router) tiles
2,683 ms each, far 430-780 ms. First tiles made were levels 4/3 (prio was distance / column width: coarse far columns first).
fix12:
1. Job priority = (distance + 16) blocks x (in view 1, else 3) for every level; TileJobs lanes 6 near : 1 far : 1 deferred.
2. LodNoise.far has accurateSurface = true: far L0/L1 use Codex's block-exact LodSurfaceSampler on the cave-free terrain (fix11
   interpolated 4x8 corners there = the blocky ledges). FarTerrainCheck: 64x64 block-exact surface+3D, plain backend: L0 whole
   router 248 ms -> far 135 ms, L1 532 -> 287 ms; heights within 1 block 95.4-95.7 %. Far folder renamed `-far2` so fix11's
   interpolated far L0/L1 columns are made again.
3. Far LodForest runs tree features only (no grass/flower features: invisible past ~200 blocks). Near tiles keep plants.
Installed SHA256 25dadca6a9b24d676e6a7f4e42a063e266c807ccacf90a5de72ec4cd3ca6aa15; previous jars in the instance folder.
check.sh fixtures all PASS. Not yet measured in game.

---

# START HERE — 2026-11-17 17:25 Claude fix11b INSTALLED: far terrain keeps cave entrances

Jack (17:08): leaving out cave entrances is wrong ("fallen into multiple ravines"). LodFarTerrain now keeps
`minecraft:overworld/caves/entrances`; it still drops the noodles and the deep cave branch (range_choice on sloped_cheese).
Installed SHA256 05cdc592b4a9f8ea432de805b6cb7198efee7f90b7a13ec17b7f3d98221b0c54 (copy `Imagine metal/imagine-metal-claude-fix11b.jar`);
rollback to fix11: `Imagine metal/imagine-metal-claude-fix11.jar`.
FarTerrainCheck (Terralith pack, plain backend), TRUE top solid block by point scan over 2,304 columns: equal 90.8 %, within 2
blocks 95.4 %, mean 0.53; 4.4 % of columns have an opening in the full router that far lacks (deep caves reaching the surface).
Cost: 65x49x65 volume full 617 ms -> far 336 ms. Note: the 20 % "height differs" figure of fix10 was mostly an artifact of
LodSurfaceSearch (it needs two solid 32-block samples, so a cave under a thin roof makes it skip down): the full router through
that search (Codex's L2+ path) put surfaces ~9 blocks too low in ~20 % of columns, likely part of the "ledges/plateaus".
Ravines are the `minecraft:canyon` carver (biome carvers), not density: no seed LOD (exact or far) has them; they appear once
the real chunk is captured. closeCaves (fix11) only fills sealed dark pockets; ravines and cave mouths are lit and stay open.

---

# START HERE — 2026-11-17 17:00 Claude fix11 INSTALLED (one pass everywhere, far L0/L1 upgraded near you, L0 cache moved, caves closed in meshes)

By Claude, on top of fix10 (section below). Same build method (javac + class overlay onto Codex's canopy-priority jar; Gradle
build of this checkout gives the same code). All CPU fixtures in `ci/metalods/check.sh` pass, plus new `CaveCheck`.
Installed SHA256 a70d1e21dd6f0bfe0ba8b1c5d27ca6bba94308ddfa9906bc5c98573e397eea41, copy `Imagine metal/imagine-metal-claude-fix11.jar`.
Rollback: `../backups/2026-11-17-claude-fix11/` (= fix10) or `../backups/2026-11-17-claude-fix10/` (= Codex canopy-priority).

## fix10 session (Jack 16:41-16:52): still slow, still bare grass
- "trees from 120 of 321" (was 18): the tree detection worked, but the bare fields (16.50.50 at 349,153,300, 16.51.13,
  16.52.22) are where he'd been before: level-0 columns cached on disk without Terralith's trees were reused.
- Slow: 0.6-1 tiles/s. Detail pass (Codex's surface-first) refined ~170 tiles: near L0 = exact surface ~600 ms + trees
  660-1,060 ms per tile, each made twice. Arena still near full at 98,304 pages: ~10-12 pages per drawn column vs 4.5 on Nov
  16 (5.3M quads drawn, GPU 46-52 ms, 20-25 fps). Codex's removal of the dark-air fill in MetaChunks (curtains under islands)
  made every cave wall of captured chunks into faces.
## fix11 changes
1. New tiles are made in ONE pass: near (exactHere: within max(192, transitionLimit+96) blocks) with the whole router, 3D and
   game trees; elsewhere generateFar. No more quick surface + detail for new tiles (needsDetail unused). Old stored previews
   still refine (near: whole router, far: generateFar).
2. Far L0/L1 columns are stored as kind "far" (`<gen>-far/` folders, MetaStore.isFar): when one is read and its tile is near
   the camera it is queued and made again from the whole router (refineOne skips far columns not near). So Codex's exact
   hand-over stays wherever the LODs meet the real terrain.
3. Level-0 folders are now `gen9-t2`, `gen9-t2-surface`, `gen9-t2-far`: the old gen9 L0 (bare Terralith forests) is left on
   disk, not read. L1+ unchanged (their canopy is MetaCanopy's estimate at derive time).
4. MetaMesher.closeCaves (mesh copy only, stored voxels untouched): an air run with sky light 0, <= 24 blocks tall (>> level),
   solid under AND over it, meshes as the block under it. Island gaps over water, gaps taller than 24 and lit cave mouths stay
   open (CaveCheck). Applies to old real2 captures too.
5. Stats line `[MetaLODS] tiles: N near (whole router) X ms each, M far (no caves) Y ms each; refined, to refine` replaces
   the surface-first line: read it to see which kind costs what.

---

# (superseded by fix11 below/above) 2026-11-17 16:40 Claude fix10 INSTALLED (arena thrash, Terralith trees, cave-free far terrain); Jack to test

Written by Claude (Codex out of usage). Same workflow as Codex: this checkout is the source of truth, changes left uncommitted
on `screenshot-audit` on top of Codex's staged/unstaged work. Claude can't push to GitHub from its session (add_repo denied), so
no CI: the jar is the installed canopy-priority jar with 20 class files replaced (javac --release 25 against MC 26.3 client.jar
+ libs in Claude's sandbox; every one of the 408 source files compiled clean). Shaders/native untouched. A full Gradle build of
this checkout now contains these changes (`../outputs/claude-fix10.patch` is the diff against Codex's tree).

## Evidence (Jack's 16:13-16:18 session, canopy-priority jar, distance 512 / detail 10 / overhangs / exact trees)
- 16:15:54 "GPU arena is full". From then `meshes` went 5,936 -> 112,139 in 2 min while columns stayed ~3,800 and tiles
  generated went 280 -> 324: `upload()` set a column with no pages back to state 0, the next frame asked for it again, it was
  re-meshed (derive + Streams + MetaCanopy each time) and failed again. All workers were busy re-meshing: that is the "very slow,
  no idea what it does". Columns not wanted were only freed after 600 frames (24 s at 25 fps).
- Per tile (before the thrash): L0 surface ~750 ms + trees ~340, L1 ~1,200, L2-L4 1,000-2,400 ms, then a detail pass made every
  tile a second time. The Nov 15/16 sessions (sloped_cheese path) made far tiles in ~250-1,100 ms, 10-11k chunk-equivalents/s.
- Screenshots 16.17.14 -> 16.17.19 (Dark Forest, 330,157,516): LOD hillside bare grass, dense dark forest once real. Cause:
  `LodForest.isTree` matched vanilla feature names only; Terralith's `terralith:dark/trees`, `highlands/temperate/dark_oak`,
  `flower/*/trees`, `forest/flower/*/trees_*` were skipped or run as "plants" ("trees from 18 of 321" in the log), so the exact
  L0 detail tiles replaced the preview's estimated canopy with nothing.

## Changes (fix10)
1. Arena (MetaWorld.upload/request/housekeeping, MetaLods): a column that finds no room backs off 90 frames instead of being
   re-meshed every frame; under pressure (arena full or < 1/8 free) eviction runs every 250 ms, frees columns unwanted for 60
   frames, and frees parents whose four children are all meshed (not drawn anyway; asked again when the view wants them).
   Arena 65,536 -> 98,304 pages (~216 MB; M1).
2. Trees (LodForest.growsTrees): a vegetal feature counts as trees if any feature in its random-selector tree is a TreeFeature
   or a huge mushroom (FallenTree/CoralTree excluded), besides the old names. Expect "trees from" well above 18 in the log.
3. Far terrain (new LodFarTerrain, LodNoise.far, MetaGen.far/farForest/generateFar): the world's own final density rewritten
   with DfRewriteRule: refs to `*caves/*` -> constant 64 (min with it dropped), range_choice on sloped_cheese -> its surface
   branch. Terralith's extra_terrain_sum (arches, spikes, skylands) and subtract_terrain_sum (cliffs) and both slides stay.
   Used for levels 2+, and for levels 0-1 tiles further than max(192, transitionLimit + 96) blocks from the camera when made
   (game trees at L0 via a second LodForest on that ground). Those are made in full at once and stored canonical (no preview,
   no detail pass). Levels 0-1 near the real terrain keep Codex's exact router + preview/detail. Stored L2+ previews still get
   refined (now with the cheap function). needsDetail is now level<=1 only.
   Measured in the sandbox with the installed Terralith pack (ci/metalods/FarTerrainCheck.java, plain backend, seed 923478761):
   65x49x65 volume full router 607-664 ms -> far 322-338 ms (sloped_cheese alone 193, extra 127, subtract 8, entrances 87);
   refined surface search 2.4-2.6x faster. Heights equal in 73 %, within 2 blocks in 80 %; the rest are where the router's
   caves/cave mouths cut the surface (far is never lower, by construction).

## Tell Jack to test
Same world. Fly over new land for a minute: F3 log lines `[MetaLODS] stats` should keep `meshes` close to `columns meshed`
(no runaway) and `generation:` tiles/s well above 1.4; Dark Forest/Terralith forests should have trees on the LODs. Check the
hand-over ring for odd steps/cliffs (L0/L1 far tiles are cave-free) and whether far Terralith arches/spikes look right.

## Rollback
Game closed, copy `../backups/2026-11-17-claude-fix10/imagine-metal-0.1.0.jar` (canopy-priority, SHA256 ab1ec09a...) over
`minecraft/mods/imagine-metal-0.1.0.jar`. Installed fix10 SHA256 7f7cd1bdbb357f5e6314fb3d9cd1c739e1721a4cca28a3b26f688e7ba596a16a,
copy at `Imagine metal/imagine-metal-claude-fix10.jar`.

## Still open
- Exact L0/L1 near tiles still cost ~1 s each plus the detail pass; real-world rate after fix10 not measured yet.
- Streams adapter costs 14-45 ms per column per derive; harmless without the thrash, worth caching per column if it shows.
- Meshes are ~1.8x bigger than on Nov 16 (caves kept as air in captured chunks, submerged faces): watch arena use.

---

# START HERE — 2026-11-17 nearby refinement / canopy corrections INSTALLED; user test pending

Latest user reports continued slow generation, huge temporary ledges that change on approach, “800k loaded” dropping to230k, empty forests / wrong species / foliage towers. Explicitly requested updated handoff for possible Claude continuation. Do not launch Minecraft: user is doing gameplay acceptance. No agent delegation was used. This section supersedes older START HERE sections for current behavior; older evidence retained below.

## Latest live evidence (previous surface-first jar, not this candidate)
- Inspected instance screenshots 2026-11-17_15.46.09/.21/.36/.54 and15.47.13/.59. Temporary plateaus with tall stone walls, absent forest, estimated foliage pillars; nearby captures reveal different terrain. Screenshots under `/Users/jack/Documents/instances/Imagine metal/minecraft/screenshots/`.
- Representative F3 positions: (2,137,-61) River; (101,122,-38) Valley Clearing; (-179,122,4) Valley Clearing; (94,149,128) Old Growth Spruce Taiga; (356,129,510) Dark Forest. 15.46.09 shows surfaces ready but1189 detail tiles pending. 15.47.13 shows the empty grass field.
- Latest run ended15:48:24. Approx1515 surface tiles in258.9s, ~3890 chunk-area-equivalents/sec (previous session~4793); approx227 completed detail tiles versus1288 pending. These area rates are NOT unique Minecraft chunk generation. Often refining=false while moving because the global95% coverage gate starved nearby detail. Meshes approx13.2ms each; arena almost full but no established allocation failure. Native NEON runs; cache0errors. Keep the original log for follow-up; archived to ../outputs/canopy-priority-before-latest.log.
- Counter drop is because “area ready” represented CURRENT selected view area, not lifetime cumulative loaded/saved terrain. Movement/LOD changes change this number. No evidence of deleting570k saved chunks.

## Current source changes
- `MetaWorld.refineOne` + `DetailJobs.takeNearest`: every fourth worker-loop opportunity can take the CURRENT camera's nearest eligible detail job inside max(192,transitionLimit+96) blocks even before global coverage95%. Busy coverage limits detail to1owner; idle queue allowsworkers-2owners. Other workers remain available for surfaces. Existing epoch/ownership/publication guards remain. This reduces nearby preview starvation; not proof of restored throughput.
- HUD shows current-view readiness percentage, mesh/detail queue sizes and cumulative seed tiles this session, avoiding the misleading loaded-area count. The surfaces-ready/detail-pending status remains.
- `LodTrees.configure(ServerLevel)` inspects actual world's biome generation features for TreeFeature foliage/trunk providers. Previously unknown biome names got NONE. Providers sampled without a world (null LevelAccessor, deterministic RNG); world-dependent providers skipped safely without generating chunks. Dominant observed foliage sets species; profile is still an approximation, not exact per-tree placement. Existing known-biome heights are kept if taller than selected feature height. Cover for unknown tree-bearing biome defaults .55. TreeFeature bushes can also be present; this does NOT reconstruct feature placement frequency or every custom Terralith structure/tree.
- `CanopyShape` + `MetaGen.cell`: estimated leaves occupy upper crown; only jittered fine-cell centres have wood trunks. Previously estimated foliage spanned ground-to-top, causing leaf towers. Exact feature-generated L0 path unchanged.
- `MetaCanopy`: weakly cached derivation-time correction for existing seed caches too; strips/rebuilds estimated vegetation above the highest terrain surface using current profiles, skips new trees over water, preserves ground/water. Scans RLE runs to find ground, avoiding per-air-voxel palette work. Applies to L1+ and preview/non-exact L0 BEFORE Streams and captured real overlays. Exact full L0/captured real chunk trees bypass it. No cache deletion/version bump. Old previews can be corrected without forcing all terrain regeneration.

## Remaining work / honesty
- SPEED IS NOT RESTORED OR LIVE-VERIFIED. No50k/s claim. Latest measured live results above are from previous jar. Main cost remains actual Terralith final-density sampling, feature detail and meshing at distance512/detail10. Earlier documented fast workload was distance256 (~630tiles), current distance512 (~5000columns). User's remembered speed still warrants real profiling; do not explain it away using incomparable counters.
- Huge ledges are not fully solved: far surface previews still use32-block vertical brackets and solid-fill terrain until full3D refinement; far cave/overhang/silhouette mismatch remains. New scheduler prioritizes nearby resolution, but distant silhouettes can still be wrong. L0/L1 surface heights are exact; distant heights/trees are estimates. Do not present the new jar as complete terrain-accuracy or performance fix.
- Ready-only Streams adapter unchanged: skips cold/unready drainage, never creates drainage networks from LOD workers. Full bank/lake/material/features behavior still not reproduced. Water/dither shader/native bytes unchanged this turn; previously GPU validated, latest user says water improved.
- Next useful performance work: real whole-pipeline profiling (generation vs feature detail vs meshing vs queue/arena pressure) and matching cold/warm workload, then reduce unnecessary sampling/remeshing while maintaining installed Terralith semantics. Actual giant trees/custom features and preview vertical accuracy still need work.

## Validation and deployment
Final Gradle build PASS (`../outputs/canopy-priority-build.log`). Installed actual Terralith registry profiles PASS:112 tree-bearing biomes; Valley Clearing oak bush height4, Old Growth Spruce Taiga spruce height16, Dark Forest dark oak height11. NOTE Valley Clearing has bush features, not a full tree placement rule; default unknown-biome cover remains an approximation. Synthetic unknown-biome spruce and cache repair L0..2/ground+water preservation/idempotence PASS (`canopy-check.log`, `canopy-terralith-check.log`). Existing geometry/ingest/mask/ZIP and expanded nearest eligible detail/six-worker ownership tests PASS (`canopy-priority-regressions.log`). Store reopen/ready-only Streams fixtures PASS (`canopy-priority-store.log`). Dither114688 decisions PASS. Jar integrity PASS; all12 shader/native resources identical to prior installed jar. No new GPU test needed for unchanged resources; no game launch or live benchmark performed.

Installed `/Users/jack/Documents/instances/Imagine metal/minecraft/mods/imagine-metal-0.1.0.jar`, SHA256 `ab1ec09a32ed5eff34d4ff5c42a947fa3d46e0ac8befc7c6f70ab67c1f235ccb`. Candidate `../outputs/imagine-metal-canopy-priority.jar`. Backup `../backups/2026-11-17-canopy-priority/imagine-metal-0.1.0.jar` (previous surface-first SHA3b9a6813d181632d379ff3855c11c172c2edf423cb821d006c562dd030951ed4). `../outputs/installation.json` updated. Confirmed Prism only/no Java process before install. Roll back only with Minecraft closed by copying this backup to installed path; backups remain outside mods. Existing world/cache/config untouched.

Next user test: same world, revisit screenshot locations; stand still30–60s to observe detail count decrease, then approach the old wall/forest. Check if crown/trunk shape and nearby refinement improve. Exit/reopen same location to check cache reuse. Do not delete caches or promise distant terrain exactness. Inspect new logs and screenshots after test; speed and far preview accuracy remain open.

Test sources: `ci/metalods/BiomeCanopyCheck.java`, `CanopyRepairCheck.java`, `canopy_check.py`, expanded `DetailCheck.java`, optional installed-pack canopy mode in `PackRouterCheck.java` / `pack_router_check.py`.

---

# START HERE — 2026-11-17 surface-first coverage INSTALLED; user asks why DH+SeedGen is faster

Latest user: "there loaded in a world ... still very slow ... water way better ... too slow to assessStreams" then "why is dh + seedgen so much faster tho?" Answered in commentary: SeedGen approximates assets and calculates seed surface directly; our previous foreground pipeline blocked on full block/cave/vegetation work. Primary SeedGen description https://modrinth.com/project/distant-horizons-seedgen confirms it realistically fakes assets such as trees rather than generating fullchunks. Do NOT state that DH+SeedGen universally builds fullchunks after preview; configurations differ.

Evidence latest run15:07–15:10:
- NativeNEON DOES run: logged45noise+1blend,0unsupported, SparseRoot. Cache succeeds0read/write errors. Streams ready-only works: cold skips; readycolumncost averages7–15ms. Fullraw generation~2.7k area/s firstcold session,~4–5k secondworldsession, thousandsqueued. PreviousStreamsfix helped but didnot restore fastcoverage.
- Current config distance512/detail10/exactTreestrue/overhangstrue,~5000columns. Historical documented fast1/fix2 test distance256 and~630tiles, with~5.5karea/s peak, NOT measured50k uniquechunks/s. Don't dismiss userexperience: current wait stillbad; these workloads and counters differ.
- Full generation waits for nearblockdensity+3Dcaves+actualfeatures before publication. Main newfix is publicationorder, not another minor primitive tweak.

Newinstalledpipeline:
- MetaGen.generateSurface publishes terrain at SELECTEDhorizontalvoxelresolution. L0/L1heightsexact fromactualfinaldensity; cavequeriesstoponceeachsurfacefound. FarL2+previews approximateverticalheight from32-blocksamplebrackets, no8-blockrefinement untildetail. Vegetation initiallybiome estimates. This is an intentional previewaccuracy tradeoff, not exactfullfar generation.
- Original fullgeneration API delegates to unchangedalgorithm with preview=false, preserving final3D/trees/settings. DetailJobs prioritizednearfirst, dedupedtileleases. Normally1backgrounddetailowner once95%mesharea ready; withsurfacequeueempty allowsup toworkers-2detailowners. At least2workers immediatelyavailablefornewcoverage. Detailcompletionupdates4columns+neighborborders; realcaptureoverlayremainsauthoritative. Epochguardsblockclear/close-stalepublication. Failuredetailstopsrefinement, keepvalidsurface/log/HUD instead of retry/OOMstorm.
- Previewfiles persisted separatelygen9-surface/gen9h-surface withqualitytracked; canonicalfullgen9/gen9h andcompatiblegen8far prefered. PendingIOcache returnslatestdata; fullcannotbedowngradedbystalepreview. CachedGenrechecks memory afterdiskIO toavoidoldpreviewoverwritingnewfullresult. Existingfullcachesunchanged/nocachedeletion/versionbump. Previewreopenqueuesdetailagain, no unnecessarysurface regeneration.
- NewLodSurfaceSearch hasrefined=true mode reproducingold32->16->8pointsearch throughonebatchedYband; previewmodeomitsrefinement. Testedrefinedmodeheights equalpointreference. OriginalfirstattemptmanymidpointplaneswasSLOWER andwasreplacedbeforeinstallation.
- NearLodSolidSampler.sampleSurface evaluatesmainterrain, then cavecuts onlyinsurfacebands untilsolidfound; fullmaskpathretainedforfull3D. Exactnearheightsvalidated.
- MetaGen.NANOS/timings nowpergeneratorinstance; avoids cross-worldstatic stagecounts confusinglogs. HUDsurfacesready/detailtiles distinction.

Benchmarks (be candid):
../outputs/surface-first-terralith.log: actualinstalledTerrpack full64x64nearheight-only~66.5msL0/134msL1 withfullheightsidentical; fullmaskrawparity preserved. Far cached-positionpreview6–9ms vsrawfull~160ms was misleadingifpresentedcold. Freshcontexts+3newpositions: rawfulldensity171–176ms ->surface61–62ms,2.8–2.9x. This is densitystage only, NOTgameFPS/endtoendtiles/s/live50k. Foregroundalsoavoidswaitingforactualvegetation. Needliveacceptance.

ChecksPASS:
- FullofflineGradlebuild ../outputs/surface-first-build.log (testsNO-SOURCE).
- ActualTerrrouternear/fullsign/heightparity; farrawbitparity;1024coarserefinedheightsmatcholdpointsearch;freshcontextbench asabove.
-96interpolatorcases+40vanillaroutervolumesplain/native,nearheights ../outputs/surface-first-density-final.log.
- Metastorepreviewclose/reopen/fullprecedence/latepreviewdowngrade/pendingwrite; existinggen8far+realcachefixtures;Streamsreadyfixtures ../outputs/surface-first-cache-check.log.
- Detailqueue100jobs/6workersownerlimit, priority,retry,oldclearcompletiondoesnoterasefreshkey,close; idle3ownerexpansion/backpressure; geometry/ingest/scheduling/mask/ZIP ../outputs/surface-first-regressions.log.
- Dithermath/guardsPASS. JARintegrity/diffcheckPASS; NOshader/nativebytechangesvspriorGPUvalidatedbuild.

Install ../outputs/imagine-metal-surface-first.jar ->instance mods/imagine-metal-0.1.0.jar; backup ../backups/2026-11-17-surface-first/imagine-metal-0.1.0.jar. installation.json SHAauthoritative. ConfirmedPrismonly/noMinecraftbeforeinstall. No game launched thisturn; userofferedtestingpreviously. Originallatestlog preserved ../outputs/surface-first-before-latest.log forcomparison.

Next: telluserinstalled, explainwhyDHfast and newpreview/fullqualitydistinction concisely, requestsameworldtest30–60s withoutdeletingcache. Needcheckfreshfarcoverage+detailprogress+warmreopen andwhetherloadingimprovesenoughtomatter. Existingwater/ditherpreserved. Avoidpromise50k orentireviewinsecondsuntilmeasured. Do not launchgamewhileusertests.

---

# START HERE — 2026-11-17 ready-only Streams regression FIX INSTALLED; user offered testing

Latest user: "i can check if u tell me what to test once the jar is in". JAR already installed, SHA087281a845e2a197b6824a4a13e33778862e5a40d5abc0a8d8b80d6df3415a8e. Give concise cold/warm generation + nearby Streams + reopen test. Do not launch again while user tests. No50k claim.

Diagnosis latestrun12:29–12:31:
- Wrongly added Streams plan calls from prior turn caused LOD workers to construct distant drainage networks. Latest.log SR plate/network build lines on MetaLODSworkers through shutdown; actual rate~225–677 generated chunk-equivalent area/s, L0 vegetation21sinitial, L1density16sinitial. This worsened rather than fixed generation. Acknowledge this regression plainly.
- Current pipeline generates LOD voxels from density directly, NOT fullMinecraftchunks first; nearby exactdensity, caves/overhangs and realfeaturevegetation can delay publishing a tile. No new surface-first/coarse-placeholder scheme added.
- Earlier diagnostics printf bypassMinecraft LoggingPrintStream.println, so absence inlatest.log is not evidence native backend was off! Changed root/backend and disk-cache diagnostics to println(String.format), visible on next run. Native defaultsNEON as previous build; do not falsely say newest log proves it ranplain.

Fixes in installedbuild:
- LodStreams only queries plan after platesTouchingBox + platesReady prove all affected regions ready. Cold requests return cached seed base; never call networkFor or whenPlatesReady (even the latter can triggerwork). Bounded polling observes regions prepared byStreamsitself and dirties only affected columns+4neighbors, no globalremeshstorm. Weak outcome cache includes unchangedreadycolumns, avoiding repeated1024planlookup passes fromneighbor meshing. Closed-world guard, cachehit/stagecost diagnostics.
- LodSolidSampler for exactnearL0/L1: final MIN sign short-circuit. Evaluate terrain then cavebranch only over Yrows where terrain can be solid, crop tosameYlattice; no signedmask interpolation. Maintains exact solid/air choices and surfaces, rawfar densityunchanged. Keeps currentnearaccuracy/caves. IsolatedTerrpaired3run full64x64 medians100.6->84.2msL0,168.6->147.4msL1 (modest15%; Streamsregressionfix is main win). No live result.
- No shader/native changes versus previousGPUvalidatedditherbuild; no cacheversionbump or config/world/cachedeletion.

Validation PASS:
../outputs/ready-streams-sign-build.log (Gradlefullbuild)
../outputs/streams-ready-check.log (productionadapterfakeenginefixture:32coldattempts0plan/gencalls, readyrevision+1024lookups, repeatreadyunchangedcached, closeguard; existingstore/channeltests)
../outputs/solid-mask-density-final.log (96bitexactinterpvolumes,40vanillaroutervolumesplain/native, exactnearheights)
../outputs/solid-mask-terralith.log (installedTerrpack:allnearblocksigns/heights vsoriginalpoint; fullL0/L1paired masks/heights identical3runs; farbitexact)
../outputs/ready-streams-regressions.log (existinggeometry/scheduling/ingest/mask/lifecycle/ZIPfixtures)
ci/metalods/dither_check.py PASS, gitdiffcheck PASS.

Install: ../outputs/imagine-metal-ready-streams-solid-mask.jar copied to instance mods/imagine-metal-0.1.0.jar; rollback ../backups/2026-11-17-ready-streams-profile/imagine-metal-0.1.0.jar (previousc7e6b2...). installation.json updated.

Attempted livecheck: Created isolated saves/MetaLODS profile 2026-11-17 by copying NewWorld(8), excludingmetalods/session.lock. Originalworld untouched. Prism CLI --launch 'Imagine metal' --world '<copy>' returned but did NOT start game (latest.log unchangedat12:31; processcheckPrismonly). CUA inventory succeeded; getAppPrism hung and userinterrupted it. No game/JFR/profile actuallyran. CUA namespace subsequentlyremoved; couldn't producecompletion_summary. Userofferedto test; stop launch attempts. Copy remains available for testing. No actualliveperformanceproof.

---

# START HERE — 2026-11-17 Streams/cache/native/dither INSTALLED

User asks: still seed gen? Slow, appears to redo on world relaunch; latest12.02 screenshots show Streams mismatch; explicitly requested DH noise/dither handoff (three supplied DH reference images). All prior edits preserved, no subagents/push/game launch.

Evidence:
- Latest live run12:01–12:02 using prior block-surface build: ~1.1–1.8k generated chunk-equivalent area/s, L1 nearby surface ~16seconds per tile, trees costly. Previous isolated Terralith benchmark used NEON but live default was PLAIN (no nativeNoise flag). Candidly told user isolated benchmark missed live cost.
- NewWorld(10) cache already saved464 gen9 columns,3304 gen8 columns and4 real2 region files. Progress tracks mesh area, so remeshing on every join can look like all seed generation repeats. Last accuracy build intentionally bumpedgen8->gen9; that forced a new pass. This build does NOT bumpversion.
- Viewed latest12.02.27 and12.02.30: missing unfinished far coverage plus actual riverbed/land changes on handoff. Streams applies post-density changes; exact Terralith density alone is not full Streams parity.

Installed changes:
1. LOD nativeNoise defaults NEON (explicit plain/off preserved); server default unchanged. All previously validated native leaves still bit-exact. Updated sampler handles unaligned requests directly from compact corner volumes, reproducing vanilla volume interpolation order/repeated Y additions. Bounds prevent hidden giant dense volumes; fully inspected small physical volumes also stay bulk. This accelerates accurate nearby sampling without going back to a cheaper router.
2. MetaStore reads gen8/gen8h L2+ when corresponding gen9/gen9h missing (those far values unchanged). L0/L1 requiregen9 accuracy data. Logs cache hits/misses/errors; HUD now counts cached columns reused, distinguishes mesh-area progress. Region file writes snapshot all chunk references before count+write, fixing concurrent snapshot corruption risk. Gen write failures now logged (first3). No cache/world/config deletion.
3. Initial optional LodStreams adapter uses public engineIfPresent/plan/inChannel/bedY/waterSurfaceY from installed Streams2.17.1; no second engine is created, no world mutated. Channels reshape predicted seed/cached columns before captured chunks override; neighbor edge samples use same correction. Keeps underlying caves (only a short bed crust filled), clears old canopy/water above planned channels; uses vanilla water/gravel proxies. Changed results weakly cached per world. THIS IS PARTIAL CHANNEL SUPPORT, NOT full bank/lake/material/features reproduction. Java fixtures test voxel transforms and actual installed API signatures were inspected; live engine integration and screenshots NOT tested. Plan calls may add cold planning cost. engineIfPresent null leaves base until later meshing/actual capture, so don't promise complete elimination of all mismatches.
4. edgeDither config defaults true. 16-block complementary screen hash transition at near edge. Near opaque, cutout depth prepass and translucent use same decision; masked LOD uses complement. LOD selection retains the overlap; fading waits for selected nonempty LOD meshes across every chunk that could hold faded near geometry. MetaLods.frame prepares current-frame pages before near draw; uploads spare ImFrame.lodFog.zw and MLFrame unusedcam components; ABI unchanged944bytes. Disabled in forward/debug/PBR/off states via Deferred.wanted; first-frame deferred-end fallback explicitly uses hard handoff. No noise on unsupported coverage. Visual scene acceptance pending.

Verification:
- Final offline Gradle :mod:build PASS, ../outputs/streams-cache-dither-build.log. Gradle test NO-SOURCE.
-96 whole/batched interpolator volumes bit exact across XZsteps1..32,Ysteps1/2/4/8; full coarse under128MiB;40 actual vanilla graph volumes plain/native, nearby exact surface parity. ../outputs/sparse-near-parity.log.
- Installed Terralith registry loaded through Minecraft loader: L0/L1 block density/sign/height parity against game point sampler, far volumes old/new bit exact. Full64x64 nearby isolated density+surface+3D ~107msL0/~170msL1, earlier198/546ms. This is NOT live rate or50k. ../outputs/streams-near-terralith.log.
- Production MetaStore fixture: generated/captured save-close-reopen, concurrent region snapshots, compatible old far fallback and invalid oldnear isolation PASS; channel transforms10cases acrosslevels0..4 andnegativeY,biome,caves PASS. ../outputs/cache-streams-check.log. Fake palette only for standalone IO isolation; actual world cache files not modified byfixture.
- Geometry/scheduling/ingest/336mask/ZIP fixtures PASS, ../outputs/cache-dither-regressions.log;576watercases/944byteABI PASS, ../outputs/cache-dither-water.log.
-114688complementary noise decisions, shared near/far helper equality, prepass/opaque/translucent calls, readiness/first-frame/deferred guards PASS: ci/metalods/dither_check.py.
- Actual Metal exact/plain libraries, near own_fs_g/own_fs_alpha/own_fs_tr specialization, deferred features on/off and all4LODpipelines PASS. Production ml_fs GPU dither branch over actual memoryless Gbuffers:146/256far pixels, bed retained elsewhere;12stackedwaterface/mask cases PASS. ../outputs/dither-pipeline-check.log. This does NOT test the full camera/world scene or near rasterization.
- JAR integrity and git diff --check PASS.

Installed ../outputs/imagine-metal-streams-cache-dither.jar to instance mods; backup ../backups/2026-11-17-streams-cache-dither/imagine-metal-0.1.0.jar. installation.json has hashes/limitations. Verified Prism only/noJava before copy. Ask user to restart/test; no new50k claim. Follow latest logs for native leaf replacement, cache-hit counters, stage costs, Streams API logs/errors, and whether edge transition activates. Main outstanding: actual cold/warm speed, native live parity/behavior, Streams plan semantics/banks/materials and full scene dither acceptance.

---

# START HERE — 2026-11-17 12:00 block-surface accuracy + bulk density INSTALLED

User clarified latest two screenshots (11.47.29, 11.47.34): moved forward; LOD water became solid real ground. This is terrain misprediction, not only water shading. User wants accurate smooth handoff AND earlier ~50k chunks/sec. Do not claim either live acceptance or 50k achieved.

Changes this turn:
- MetaLODS L0/L1 now samples the actual final density at integer block heights/column centers via LodSurfaceSampler. No interpolation of already squeezed/caved corner values for nearby surface detection. Stripes bound scratch memory. Keeps exact sampled nearby density for the 3D carve; eliminates the second approximate volume and its false solid/air decisions. L2+ retain existing sampling/detail. Actual terrain is still voxelized.
- Copied inspectable graphs carry SparseRoot lattice alignment metadata. Fully aligned requests use one bulk volume instead of thousands of safety batches. Unaligned/unsupported graphs retain bounded sampling. Mod non-record samplers conservatively disable whole-volume optimization.
- Per-noise shared atomic diagnostics opt-in via -Dmcopt.lod.nativeNoise.stats=true (or genBench), avoiding contention; normal per-stage timings retained.
- Generated cache version 9 (gen9/gen9h), preserves gen8 and real2. No cache/world/config deletion. Native and water shader bytes unchanged from the prior installed build.

Validation:
- Full offline Gradle :mod:build PASS; test NO-SOURCE. Log ../outputs/bulk-surface-build.log.
- density_check.py PASS: 24 interpolator volumes, four full coarse tiles under 128MiB, aligned layouts; 40 vanilla real-router volumes across plain/native, plus 1024 nearby surface heights match actual block-density point sampling. ../outputs/bulk-surface-final-parity.log.
- New pack_router_check.py loads this instance's installed Terralith_26.3_v2.6.5+26.3.jar through Minecraft RegistryDataLoader, no server/world opened. Nearby cells 1 and 2: all sampled block densities/signs and 128 heights match the original final-density point sampler. L2/L3/L4 full volumes old bounded vs bulk bit exact. ../outputs/terralith-bulk-surface-check.log.
- Terralith full L4 final warm timings ~210 ->164ms in isolated first run; final run with concurrent vanilla fixtures median222->174ms. Around20–25% density-stage improvement, NOT10x/live50k. Nearby full64x64 surface+3D sampled once ~198ms L0,546ms L1. More accurate near sampling costs more; no whole-game rate claim.
- Geometry/ingest/scheduling/mask/ZIP fixtures PASS, ../outputs/bulk-surface-regressions.log. git diff --check PASS. JAR integrity PASS; exactly12 existing Java entries changed,2added,0removed; shaders/native identical to prior installed JAR.

Installed ../outputs/imagine-metal-block-surface-bulk.jar into instance mods/imagine-metal-0.1.0.jar, backup ../backups/2026-11-17-block-surface/imagine-metal-0.1.0.jar. installation.json has hashes. Verified no Java/Minecraft process before copy, Prism only. No game launched, no subagents/push. Keep preexisting staged/unstaged edits.

Outstanding: actual game screenshots and cold/warm rate; 50k not achieved/proven. Streams Reflowing later river edits, structures/features may still differ before captured real chunks. Fixture uses Terralith actual pack but arbitrary fixed seed, NOT exact screenshot world seed or Streams generation hooks. No crossfade added; fixing density prediction is the priority. Follow up on actual next-run logs and screenshots before promising visual smoothness.

---

# START HERE — 2026-11-17 throughput + GPU-tested water INSTALLED

Supersedes the density-only / unverified-water status below. User requested repair of near-zero LOD loading and continued work while asleep. Read the existing handoff; preserved all prior staged/unstaged work, worlds, configs, gen8/real2 caches. No subagents, push, or game launch. Confirmed via permitted process inspection that only Prism Launcher remained (no Java/Minecraft process) before replacement; latest.log ends in shutdown.

## Diagnosed and fixed
- **Sparse density still doing dense work:** the previous 16-block batching prevented the giant heap allocation, but Minecraft's interpolator still filled every intermediate block when the LOD step was a multiple of its lattice width. `LodLatticeSampler` passes aligned sample volumes directly to the interpolator's input. Nonaligned requests retain the actual game sampler and bounded batching. `LodNativeNoise` now also copies interpolation nodes when native noise is off; no noise leaves are replaced in that mode. Native backend and final world router remain intact. No approximation, cache-version bump, or cache deletion.
- **Workers parked behind the same tile:** the old stripe index `Long.hashCode(packedKey) & 63` collapses many adjacent x coordinates onto the same lock. Four sibling columns also blocked four workers behind one tile. `TileGate` claims the exact tile key without waiting and holds ownership through publication of all four columns. Busy sibling requests are deferred; the worker continues other work. Gate release is in finally.
- **Distant coverage starved:** the newest log generated only L0 tiles while about 5,000 jobs waited. `TileJobs` fairly services near detail (two slots), selected distant levels >=2 (one slot), and deferred siblings (one slot), borrowing empty lanes. This does NOT enable coarse-first placeholders or lower detail/distance. Existing per-lane view/distance priorities remain.
- **Real-chunk ingest could starve all generation:** when >192 snapshots waited, every worker formerly drained ingest exclusively. It now uses at most three of four loop turns for ingest under that pressure, retaining a generation slot.
- **Unnecessary seed work under captured terrain:** derive now reads real chunks first; fully captured columns skip the seed tile whose every voxel would be overwritten. Partial columns still use the same generated base.
- **Misleading diagnostics:** generation logs explicitly label chunk-equivalent area, retain actual tiles/s, and now include density / biome / material stage timings.
- **Water rewrite validated and included:** full solid/lighting/water/lighting sequencing, separately shaded bed transmission, and thickness preservation from the earlier candidate are in the installed build. Found/corrected another issue: `im_defer_fs` preserved water's geometric `m.n` but still set shading `m.N` upward on sides/bottoms. Both normals now reconstruct the actual face axis because g3.ba holds thickness for water.

## Verification completed (all PASS)
- Full offline `:mod:build --no-daemon`: Java + ARM64 native + final JAR. Ran outside sandbox because Gradle's file-lock socket cannot initialize inside it. `../outputs/throughput-build.log`. Gradle test is NO-SOURCE; the standalone fixtures below are the regression tests.
- Minecraft's real interpolator: 24 whole/batched volumes including negative/nonaligned origins and steps 1..32; four coarse-volume comparisons under 128 MiB heap; aligned LOD layouts bit-exact. Level-3 inner samples 1,155,200 -> 207,025; level-4 4,291,249 -> 207,025. `density-throughput-check.log`.
- Actual compiled Overworld, Amplified, floating-island and End final-density graphs, separate cached contexts: 20 volumes plain + 20 native/NEON, including nonaligned origins. Every float bit-exact. `density-router-final.log`. These are vanilla router tests, not this instance's modded world acceptance.
- Full 65x49x65 L4 Overworld density volume, 3 paired runs, both sides plain backend: median **8238.5 -> 318.6 ms (25.9x)**, all 207,025 values bit-exact each run. `full-tile-density-benchmark.log`. This measures the density stage only, NOT live chunks/sec, total tile time, FPS, or the instance's datapack router.
- Production gate/queue fixture: eight formerly colliding independent tile keys, 24 simultaneous claims of one tile, ownership/release, distant/deferred fairness through 1,000 fine jobs, priority order, empty-lane lending and all-lane shutdown. Geometry, six submerged solid faces, water edges, capture deduplication, ingest ordering, 336 ownership cases, cooperative ZIP access all pass. `geometry-scheduling-final.log`.
- 576 shared water math cases + Java/native/MSL 944-byte ABI pass. `water-math-final.log`.
- Actual Metal libraries compile on this Mac outside sandbox: exact/plain terrain + sky, base terrain, bloom/light, patched entity/item/particle shaders. `other-metal-final.log`.
- Actual render pipelines: deferred lighting exact/plain with all feature constants on/off (4), LOD ML_MASK false/true x ML_WATER false/true (4), matching RGBA16Float + RGBA8 + RGBA8 + RGBA16Unorm + R32Float + Depth32Float formats. `water-pipeline-check.log`.
- **Actual offscreen GPU pixel test** of production `ml_fs` with fixture vertices, real memoryless G-buffer attachments and sequential overlapping water draws: all six faces x both mask variants. Verifies accumulated thickness still reaches the opaque bed, water depth/material, and the existing shaded bed colour survives water draws. The final deferred shader normal correction is compiled in both lighting pipelines; this fixture tests G-buffer production, not whole-scene lighting.
- Final JAR integrity/content checks and `git diff --check` pass. No unexpected removed entries; new classes and updated shader/native resources present.

## Installation and rollback
- INSTALLED `/Users/jack/Documents/instances/Imagine metal/minecraft/mods/imagine-metal-0.1.0.jar`
- SHA256 `3ec1e6bd43201ebf603d53a291d0385730764085b9ad95981b63fc7286dd6068`
- Candidate `../outputs/imagine-metal-throughput-water.jar`, same SHA256.
- Previous density-only build backed up at `../backups/2026-11-17-throughput/imagine-metal-0.1.0.jar`, SHA256 `477dd3b422fa56820632abefef53447820a4e5627cd5c89c1cd5ae6c0c4bb45a`.
- `../outputs/installation.json` is authoritative. Older candidates/backups kept. Current full source now matches the installed full build; the old warning that a full build differs from the density-only installation is superseded.
- Rollback with Minecraft closed: copy that backup over the installed JAR. No config/cache/world changes required.

## Acceptance still not performed
No game was launched, honoring the handoff. Do not represent the density-stage benchmark as measured whole-game generation speed or the GPU fixture as matched gameplay screenshots. On next user launch, measure cold/warm tiles/s and stage timings; revisit (-1327,113,1752) / (-1338,97,1728) for the near/far sea boundary and coverage. Retest survival -> title -> creative -> survival and above/below water, styles/WaterFx, sunsets and cloud reflections. Source/fixture fixes have passed; complete scene acceptance is unmeasured. Existing seed-generation limitations for structures, features and Streams edits remain (captured chunks are authoritative); cosmetic DH dithering remains deferred under the earlier priority.

## Reproduce
`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home GRADLE_USER_HOME=../gradle-home ./gradlew --offline :mod:build --no-daemon`
`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home sh ci/metalods/check.sh`
`python3 ci/metalods/water_check.py`
`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home GRADLE_USER_HOME=../gradle-home python3 ci/metalods/density_check.py`
The density script runs real router parity checks on both backends on ARM64 macOS (plain only on other hosts). RouterCheck's optional argument `bench` runs the three full-volume paired measurements. `ci/metalods/pipeline_check.m` is the reusable Metal pipeline + GPU pixel probe; compile with clang and Foundation/Metal frameworks, run against generated exact/plain terrain and production metalods.metal on an accessible Apple GPU.

---

# START HERE — 2026-11-17 latest screenshots: density fix INSTALLED; water rewrite NOT installed

Supersedes the earlier recovery status below. Active checkout remains this directory. Preserve all staged/unstaged work, worlds, configs and gen8/real2 caches. No subagents, GitHub push or game launch this session.

## Evidence from the three newest instance screenshots
- `2026-11-17_02.07.25.png`: opaque grey LOD water meets transparent near water at a hard straight boundary; biome alpine_highlands near (-1327,113,1752).
- `2026-11-17_02.07.32.png`: terrain terminates in vertical cutoffs and missing distant coverage, near (-1338,97,1728).
- `2026-11-16_18.51.42.png`: magenta/black inventory/player textures. This PRECEDES the installed recovery build; do not present it as a new regression after cooperative shutdown.
- Latest log is from 02:04–02:07 Nov 17 and ends with shutdown. At **02:06:34** MetaLODS logs `generation from the seed stopped (java.lang.OutOfMemoryError: Java heap space): only real chunks become LODs`. Stack: `DensityBuffer` -> `InterpolatedFunction$Sampler.sampleWithNonBlockStep` -> `LodNoise.generate0:283` -> `MetaGen` -> `MetaWorld.generated`.
- Coarse L4 surface generation measured ~40–41 seconds per tile. Generation then stops at 52 tiles while thousands of empty/visited meshes finish. The HUD's huge count represents summed mesh area, NOT unique generated Minecraft chunks; missing seed data can also mesh as empty. This explains why the apparent completed area was misleading. No new game throughput benchmark.

## Installed: bounded density sampling and honest HUD
- `LodDensityBatch` splits horizontal sampling requests into at most 16-block spans. The game's interpolator otherwise expands a sparse 65x65 coarse request across its full physical footprint, allocating/interpolating intermediate cells that were never requested. Same router and sample positions; no terrain approximation or cache-version change.
- Routed ordinary/fine volume, slide partial volume, terrainVolume and coarseSurface through the helper. Climate/material paths unchanged. Seed generation failures now gate waiting generation calls, and the HUD explicitly reports stopped generation instead of a zero-speed progress display; normal progress is labeled LOD mesh area in chunk-equivalents.
- Real Minecraft interpolator fixture (`DensityBatchCheck.java`, `density_check.py`): 24 whole/batched cases **bit-exact**, including negative/unaligned coordinates and steps 1,2,4,8,16,32. Four full 65x49x65 coarse volumes match point sampling under **128 MiB** heap; largest observed inner interpolation request **4500 floats**. This is a regression test, not measured whole-game heap use or terrain parity in a live world.
- Direct JDK25 compile of all changed Java classes PASS. Existing geometry/submerged faces, ingest ordering, ownership/coverage policies, cooperative ZIP checks PASS. Water CPU math + 944-byte ABI PASS. Gradle cannot start: sandbox denies its file-lock listener socket (`SocketException: Operation not permitted`). Used existing resolved game/dependency classpath and direct javac; no Gradle build claim.
- Packaged by copying the known recovery JAR and overlaying **only 9 density/MetaWorld class entries**. Verified exact changed-entry allowlist and ZIP integrity. Native binary and shader resources in the INSTALLED JAR remain the recovery versions.
- Installed candidate: `../outputs/imagine-metal-density-bounded.jar`; SHA256 `477dd3b422fa56820632abefef53447820a4e5627cd5c89c1cd5ae6c0c4bb45a`.
- Installed at instance `minecraft/mods/imagine-metal-0.1.0.jar` after confirming latest log ends in shutdown. Process enumeration is unavailable in the sandbox; no game was launched by this agent.
- Previous recovery preserved at `../backups/2026-11-17-density/imagine-metal-0.1.0.jar`, SHA256 `3ff8beae7f901a8786063b0ad1da71dba1738564b6598429d355a7cd4eeabe8b`. Older backups remain intact.

## Water rewrite: source and separate candidate only, NOT installed
- Added solid-only then water-only MetaLODS draws (ML_WATER vertex specialization). All masked/unmasked solids finish before the first lighting flush; their shaded bed stays in colour0 tile memory. Water writes its surface G-buffer/depth next and a second lighting flush touches only water pixels.
- `ml_fs` reads prior opaque depth to estimate ray thickness; g3.b stores thickness/64 for water (land retains its oct normal). If nearer water replaces farther water, the prior thickness is added so the opaque bed distance survives. Water still uses its geometric face normal.
- `im_defer_fs` reads colour0 and uses shared `imWaterTransmitted`, volume alpha and Fresnel alpha to composite over the separately shaded bed in linear light. Near `own_fs_tr` uses the same transmission helper; CPU check verifies the near legacy equation is preserved.
- Added native `mco_im_g_water_phase`, FFM binding, four mask/water PSOs and two water/non-water vertex functions. Java closes the water phase in finally. Frame ABI remains 944 bytes.
- Java + ARM64 native dynamic-library compilation PASS (`../water-native.log`). Shader/pipeline compilation NOT performed: sandbox has no Metal device, `xcrun metal` unavailable. CPU transmission checks do NOT prove GPU correctness.
- Candidate: `../outputs/imagine-metal-water-transmission-unverified.jar`; SHA256 `b5d1a3d32c797561e56c002bbcf7c71de7d0748181429e696152edfe882675f1`. Includes new shaders/native/classes. **Do not silently install it as though verified.** It needs actual Metal compilation/specialization/pipeline validation, then matched in-game acceptance.
- IMPORTANT: rebuilding the entire current source produces the water rewrite too. Installed source subset is density/MetaWorld only. To keep the verified density candidate, use its existing JAR, not an unqualified full rebuild. Source changes are all left unstaged as found/edited; no user changes discarded.

## Next actions
1. Fresh game with the installed density JAR: revisit the Nov 17 coordinates; compare cold and warm gen8 coverage, actual generation tile throughput, worker stalls and errors. No cache deletion. The 128 MiB fixture is NOT a promise about game RAM/FPS.
2. On a Metal-capable permitted environment compile generated `../outputs/msl-water/terrain_im.metal`, plain variant, and metalods. Specialize ML_MASK false/true x ML_WATER false/true, both vertex variants; create full matching pipelines (RGBA8/RGBA8/RGBA16Unorm/R32Float G-buffer).
3. Validate water frame order/attachments, shared depth reads, same-pixel multiple water faces, above/below water, shallow/deep, WaterFx on/off, water styles, sunset, clouds and near/far transition. Depth ratio uses the same reversed-Z approximation as nearby water; LOD refraction remains unshifted. Additional geometry/normal/fog differences can remain.
4. Only after shader/pipeline validation install the water candidate with a backup. Compare matched screenshots. If rendered validation is unavailable, retain it as an unverified candidate and say so.
5. Continue world-switch acceptance from older recovery handoff. The old magenta screenshot alone does not establish current behavior. Cosmetic DH dithering stays deferred.

Re-run density fixture after dependencies are present:
`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home GRADLE_USER_HOME=../gradle-home python3 ci/metalods/density_check.py`

---

# START HERE — 2026-11-17 recovery candidate INSTALLED

Supersedes the Nov 16 handoff below. User's latest priority: gamebreaking world switches, wrong generated terrain, missing underwater LODs, generation throughput. Cosmetic DH transition dithering was deprioritized. Do not re-audit the original 65 images.

## Completed source changes this session
- **Submerged geometry:** MetaMesher formerly emitted surfaces only against air; submerged solid seabeds/cliffs/ceilings against water had no faces. Added solid/leaves-to-water faces on all six axes, retaining water-to-water culling. The new production-mesher fixture failed before the change and passes after it. This directly addresses absent underwater terrain; rendered pixels still need comparison.
- **World-switch resource hazard:** archived `2026-11-16-2.log.gz` shows old MetaLODS workers generating after disconnect, then shared ZipFS `ClosedChannelException` on vanilla structures, followed by failed registry/datapack loading. MetaWorld.close interrupted workers which may read texture resources via LodColors. JDK 25 standalone reproduction confirms an interrupted reader closes the shared ZIP channel for later readers. Shutdown is now cooperative, queues cleared at level detach (OwnSolidExtractorMixin), old-world GPU-height publication blocked, height map reset. No interrupt of resource readers. This is a confirmed hazardous shutdown mechanism; the entire reported purple inventory/black-hand sequence was not reproduced live by this agent. A poisoned running JVM must be restarted; do not remove datapacks to work around it.
- **Wrong seed terrain:** MetaGen now selects the world's final density router, not the vanilla sloped_cheese replacement and hard-coded top slide. The old stable(OVERWORLD) test checks a registry key, not equivalence of datapack terrain operators. Surface and 3D carve now use the same actual router. Generated cache is gen8/gen8h; gen7 files and real2 remain intact. This remains voxel sampling and cannot reproduce all structures/features/Streams edits before actual chunks are captured. No claim of exact block-for-block parity. Initial gen8 generation may be slower; benchmark required.
- **Redundant work:** compare captured level-0 RLE including light before publishing/invalidating meshes; identical captures no longer remesh every dependent LOD. Stable striped generation locks prevent the old lock-removal-before-cache-publication race from generating the same tile concurrently. LatestIngest gates publication so older snapshots finishing last cannot overwrite newer ones, including after clear/close. No throughput increase claimed without measurement.
- **Coverage:** removed distance-only coarse fallback discard (it erased the selected parent before fine or near replacements existed). If the drawn-near mask is unavailable, retain LOD coverage; explicit legacy handoffDrawn=false behavior remains. hideUnderReal=false now also bypasses CPU allMasked column rejection.
- **Diagnostics:** `-Dmetalods.coverageDebug=1` material/opaque coverage, `=2` mask candidates, `=3` log distance depth, default 0. Near opaque grey, LOD solid orange, LOD water cyan; mode 2 additionally shows normally masked LOD candidates green where depth-visible. Magenta means no opaque depth. Near translucent terrain is suppressed to inspect the opaque buffers, so magenta alone is NOT proof normal translucent water is absent. Requires deferred, HDR, airPass and classic transparency; keep ordinary Imagine debug=0, shaders on, PBR-pack mode off. Mask mode changes selection/coverage and may request extra tiles. Colors can still be affected by later temporal/postprocessing/entity overlays; use static captures with TAA/MetalFX off for diagnosis. Diagnostic code compiled, not viewed in game.

## Verification actually completed
- Final Java/native/JAR build PASS, `../coverage-build-final.log`. Gradle test NO-SOURCE.
- CPU production RLE/mesher fixtures PASS including four horizontal water edges and all six submerged solid faces; unchanged/edited capture distinction PASS.
- Deterministic reversed-ingest completion, stale cleanup, independent chunks, clear/close publication checks PASS.
- 336 extracted shader ownership-math combinations (four edges, negative coordinates, levels 0–5) PASS. Coarse/fine traversal checks are source guards, NOT a rendered transition test.
- 576 water math cases + fog/volume/944-byte ABI PASS. `git diff --check` PASS.
- **Actual runtime Metal compilation now PASSED outside the sandbox** (sandbox cannot enumerate a Metal device): terrain with OWN_EXACT_POS/OWN_QREC and plain IM_SHADE, base terrain, sky, bloom, light, metalods. ml_fs specialized with ML_MASK=false AND true. This supersedes previous "Metal unavailable" notes. No complete render-pipeline draw or screenshot validation. Probe source/binary and generated sources were used locally; evidence summary in `../outputs/recovery-validation.txt`.
- No Minecraft launched by this agent. User confirmed Minecraft closed before replacement; latest.log also ended with shutdown. No world, config or existing cache deleted/modified by the agent. Normal generation on next run will populate gen8.

## Installation
- Installed SHA-256: `3ff8beae7f901a8786063b0ad1da71dba1738564b6598429d355a7cd4eeabe8b`.
- Installed path: `/Users/jack/Documents/instances/Imagine metal/minecraft/mods/imagine-metal-0.1.0.jar`.
- Previous experimental JAR backed up: `/Users/jack/Documents/instances/Imagine metal/renderer-work/backups/2026-11-17-recovery/imagine-metal-0.1.0.jar`; SHA-256 `ce6999e4110b5dd5b93903b797949ff1def0005a202dacb9a7c0b8fa608011ee`.
- Candidate: `/Users/jack/Documents/instances/Imagine metal/renderer-work/outputs/imagine-metal-recovery-candidate.jar`. Manifest/rollback: `../outputs/installation.json`. Original pre-audit backup remains preserved too.

## Evidence and immediate acceptance checks
- `docs/audit-2026-11-16/` contains contact sheets of the 15 newly supplied Nov 16 images (18:39–18:44), inventory and focused findings; only original 01:41:56 was reopened at full size to resolve coverage vs fog. Nov 14 sheet 3/4 consulted. Original audit preserved.
- Fresh JVM: open survival, quit to title, open creative, then return to survival. Confirm resources, inventory and hands remain valid; inspect logs for ZipFS/registry failures and old-world worker activity.
- Revisit 18:39:36 underwater and 18:40:39/44 terrain mismatch, plus 18:41:17–49 near/far sea. Underwater should now have actual seabed/cliff geometry. Compare normal and coverage/depth modes only if symptoms persist.
- Measure cold gen8 and warm cache separately; correct final density is more expensive than the old terrain shortcut. No FPS/chunks-per-second promises. Existing screenshot progress rate counts ready mesh area rather than literal newly generated server chunks.
- DH-style dither remains deliberately deferred under user's latest gamebreaking-first priority. A safe implementation needs both candidates' pixel coverage (separate near/far depth or equivalent), a complementary stable noise choice only where both exist, and sole-candidate fallback. Current deferred LOD water is opaque; do not independently discard near and far water.

## Continuation prompt for Claude
Continue in /Users/jack/Documents/instances/Imagine metal/renderer-work/Imagine-Metal. Read HANDOFF.md's newest section, docs/audit-2026-11-16/findings.md and ../outputs/installation.json. Preserve staged/unstaged changes, worlds and caches. Recovery candidate is installed; Java/native build, runtime Metal compilation and CPU checks passed, but no live acceptance test by Codex. Priorities: verify survival→title→creative→survival with no ZIP channel/resource corruption; underwater seabed now emitted against water; final-density world-router gen8 terrain comparison and measured cold/warm throughput. Review cooperative cancellation, duplicate captures, tile locking and latest-ingest publication. User did not notice white bands recently; prioritize terrain/underwater/world switches. Do not redo the full screenshot audit. Cosmetic dither is deferred. Record actual in-game outcomes and fix remaining observed failures rather than treating these source checks as visual proof.

---

# START HERE — 2026-11-16 water/geometry continuation

This section supersedes the Nov 15 status below; older Claude notes are preserved as history.

## Working location / workflow
- ACTIVE checkout: `/Users/jack/Documents/instances/Imagine metal/renderer-work/Imagine-Metal`
- GitHub: `swungback/Imagine-Metal`, base `metalods` at `7173d02ae1b9d003aa7d0bbe337737ef83187f3f`; local `screenshot-audit`, changes uncommitted. Some initial changes are staged, newer ones unstaged: inspect both.
- Old checkout under Documents/Codex is stale. `/Users/jack/minecraft-metal-shaders` is the separate mcopt repository and was not modified.
- User explicitly authorized continued fixes and replacing the mods JAR when done. Candidate is experimental: successful host build does not validate MSL or visuals.
- Follow Claude's reproduce → source change → CPU/Metal/build checks → backup/install → screenshot comparison → handoff workflow. GitHub push dry-run failed with 403; no push/CI/release occurred. Do not claim remote validation.

## New fixes beyond the Nov 15 mask/edit/empty-chunk/neighbor changes
1. Shared near/LOD water body tint, ripple luminance, Fresnel surface alpha and deep-volume alpha in `lighting.metal`; deferred water now uses these in `terrain.metal`. Removed old RGB `(0.42,0.66,0.72)` correction that tinted reflections too.
2. Water side/bottom normals retain their face orientation in `metalods.metal`; forced full skylight applies only to top water faces.
3. Extra far-distance haze shared by opaque air pass and near translucent surfaces. Added ImFrame.lodFog; Java/native/MSL frame now **944 bytes**, existing offsets preserved.
4. Internal water skirts removed in `MetaMesher.facesX/facesZ`; actual exposed sides retained. Targets rectangular opaque/reflection strips, not a proven cure for the white underwater band.
5. Removed `MetaChunks.build` zero-skylight air fill. Dark open air below an island is not enclosed rock: previous logic could extend water/stone from below to the island. Captured air stays air. Existing cached corrupted captures need recapture; **no cache/world deletion**. Generated tree pillars remain open.

## Checks actually run
- Final `:mod:build --no-daemon` PASS (Java/native ARM64/JAR); log `../water-build-final.log`. Gradle test has NO-SOURCE.
- `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home sh ci/metalods/check.sh` PASS: production RLE/mesher fixtures, including internal-water-face rejection and exposed-face retention.
- `python3 ci/metalods/water_check.py` PASS: 576 shader-math cases, volume/fog monotonicity and gates, Java/native/MSL 944-byte size. Extracts actual helper bodies into a C++ adapter; **not MSL compilation**.
- Added both CPU checks to `.github/workflows/build.yml` before existing Metal checks.
- `git diff --check` PASS. Metal compiler missing locally; no accessible Metal device. GPU specialization / screenshots remain untested.
- No direct Minecraft snapshot-capture regression test, no performance/memory measurements. Multiple ingest workers can still finish repeated snapshots out of order; reproduce/fix before claiming robust rapid-edit handling.

## Next actions / acceptance
1. Compile all Metal variants using existing CI workflow on a permitted remote or full Xcode environment; specialize both ML_MASK variants. Runtime failure: restore backup (installation manifest next to outputs).
2. Capture matched above/below-water shots at 22:30:23 and underwater ~(-54.12,50.45,67.56), facing south; deep/shallow water, elevated lakes, waterfall sides, sunset, WaterFx off and waterStyle variants. LOD lacks a separately shaded seabed: shallow transmission is still approximate. Underwater fallback and pass-order mismatches remain open.
3. Revisit island at 01:18:11; loaded chunks recapture old dark-air fill. If a curtain persists, inspect generated/real provenance before changing generation or caches. Monitor RLE size/meshing cost now that caves remain air.
4. Exercise edits and last-block removal near x/z=-16,0,16, repeated rapid edits, unload/reload, dimensions. Event hooks and asynchronous ordering need runtime checks.
5. Remaining foliage, cloud, motion and performance findings: `docs/audit-2026-11-15/findings.md`. All 65 screenshots indexed with hashes and six contact sheets. Two user attachments are Complementary+DH references, not Imagine failures.

## Installation completed — Nov 16
- Replaced `Imagine metal/minecraft/mods/imagine-metal-0.1.0.jar` after latest game log ended with shutdown.
- Installed SHA-256: `082a5b53020910210549a8b7898317db1517c165aaf97dec1816753183536942`.
- Original preserved outside mods at `Imagine metal/renderer-work/backups/2026-11-16-water-audit/imagine-metal-0.1.0.jar`.
- Original SHA-256: `ce6999e4110b5dd5b93903b797949ff1def0005a202dacb9a7c0b8fa608011ee`.
- `../outputs/installation.json` has exact paths/hashes and rollback; candidate and source patch are in that outputs directory.
- No game launched, configs changed, caches erased, or visual result claimed. First launch must check runtime shader errors.

## Rebuild
```sh
cd '/Users/jack/Documents/instances/Imagine metal/renderer-work/Imagine-Metal'
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
export GRADLE_USER_HOME='/Users/jack/Documents/instances/Imagine metal/renderer-work/gradle-home'
sh ci/metalods/check.sh
python3 ci/metalods/water_check.py
./gradlew :mod:build --no-daemon
```

---

# Imagine Metal: handoff (2026-11-15 screenshot audit)

## START HERE: 2026-11-15 — local audit candidate BUILT, NOT deployed, NOT visually verified

- User request: inspect **all screenshots** in `~/Documents/instances/Imagine metal/minecraft/screenshots`, especially Nov 13
  22:29:32 onward; compare with actual renderer, distinguish proof from hypotheses, implement defensible fixes, leave a
  resumable handoff in Claude's existing style. Two clipboard attachments are **Complementary + DH reference images**.
- Correct repo: `https://github.com/swungback/Imagine-Metal`, **metalods** branch. Base
  `7173d02ae1b9d003aa7d0bbe337737ef83187f3f`. Local branch `screenshot-audit` at
  `/Users/jack/Documents/Codex/2026-11-15/inspect-all-minecraft-screenshots-in-documents/work/Imagine-Metal`.
  The user-supplied `/Users/jack/minecraft-metal-shaders` is the separate mcopt backend and was NOT edited.
- Read `docs/audit-2026-11-15/findings.md` first: 12 issue categories, source symbols, evidence, confidence, fixes and retests.
  `inventory.md` accounts for all 65 PNGs, dimensions and SHA-256; `contact-sheets.html` covers every image. All reviewed at
  contact-sheet size; major seam/underwater/cloud/geometry examples also full size. Reference images are not our failures.
- Base `metalods.metal` exactly matches local fix9c JAR; fix9c SHA-1 `77419cf549d379bfcafd0bda26861e3c8854e227`.
  The Nov 14 01:18+ images cannot be assigned to fix9c just from their date; previous handoff only says built, not deployed.
  18:18:16 and 18:46:29 explicitly identify Gael's shader. Current Imagine Metal/backend JARs are disabled. No instance
  config, mods, worlds or caches were changed. No GitHub push, CI run, release or in-game test this session.

### What changed (source defects confirmed; screenshot causality not overclaimed)
1. `imaginemetal/shaders/metalods.metal:ml_fs`: solid vertical faces test 0.02 blocks inside their owning voxel for the
   real-terrain mask. Formerly a +X/+Z face at a chunk boundary floored to the next chunk, potentially discarding a needed
   wall or retaining a duplicate. Water sides retain fix8's deliberate front-cell test. This is NOT a proven cure for the
   underwater white band.
2. `MetaLodsClientLevelMixin`: forward actual `sendBlockUpdated` state changes to MetaLODS. `MetaLods`: coalesce per chunk,
   wait 150ms after its latest edit, capture at most 4 edited chunks per frame, clear edits on world change. Previously the
   LOD copy only refreshed on load/unload. Relevant to the Nov 14 18:05 broken-block report, but near terrain's own stale
   section publication remains an alternative cause. Stress-test rapidly repeated edits / worker completion order.
3. `MetaChunks.capture`: accept an entirely empty chunk, so removing the last block can erase its cached LOD geometry.
4. `MetaWorld.integrate`: real chunk updates dirty adjacent mesh columns when touching their border, not just the containing
   column. Neighbors' face culling depends on that edge. Uses floorDiv/floorMod for negative chunk coordinates.
5. Added `ci/metalods/check.sh` / `GeometryCheck.java`: CPU production RLE/mesher fixtures with a tiny test-only palette and
   nullable annotation stub. No Minecraft registry boot or GPU required.

### Validation actually run
- JDK: `/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`; Gradle wrapper 9.5.1.
- `GRADLE_USER_HOME=<chat>/work/gradle-home JAVA_HOME=<above> ./gradlew :mod:build --no-daemon`: **PASS**, Java + native ARM64
  dylib + JAR. Gradle `test NO-SOURCE` is NOT a passed gameplay suite. One FSEvents warning did not prevent compilation.
- `JAVA_HOME=<above> sh ci/metalods/check.sh`: **PASS** — empty replacement, floating shelf, changed neighbor edge, thin
  waterfall rejection, half-occupied water preservation. These do not verify event hooks or rendered pixels.
- `git diff --check`: **PASS**.
- Metal shader validation **unavailable**: `xcrun -sdk macosx metal --version` cannot find the tool. Runtime library compile
  could not run; an independent MTLCreateSystemDefaultDevice probe returned no device. No valid shader compilation result.
- Build artifact: `mod/build/libs/imagine-metal-0.1.0.jar`; exported as `outputs/imagine-metal-audit-unverified.jar` in this
  chat. `outputs/SHA256SUMS` identifies deliverables. Outputs also include source ZIP, binary patch, full handoff and audit.

### Still open — do not mark these fixed
- **Water color/reflection mismatch:** real water transmits/absorbs the actual seabed; deferred LOD water applies a deep-water
  approximation and fixed `(0.42,0.66,0.72)` correction. Needs shared water math/data, not another screenshot-specific tint.
- **White underwater band** at 01:41:54/56: inspect mask/depth/material views; distinguish missing coverage from fogged water.
- **Island curtain** at 01:18:11: earlier “coarse stone underside” explanation is inadequate. Dump tile level/provenance/RLE;
  inspect MetaGen density carving, heightfield mode, MetaChunks zero-skylight air filling and old caches separately.
- **Tree towers / near-far foliage changes:** generated impostor canopy is a solid vertical leaf interval; biome tint/model
  differences and binary handoff contribute. Complementary+DH references show dithered overlap; adding discard only to LODs
  would make holes. Align material/coverage first, then complementary world-stable overlap in both paths.
- **Water through clouds / leaf blur:** fix9c already has attempted fixes. Verify their build and test before reapplying them.
- **Ore/diamond glow, cave look, animal trails:** fixes/preferences in older notes preserved; motion needs video/frame series.
- **FPS/heap:** reported symptoms, not proof of a leak. No performance improvement measured; new edit captures add bounded
  per-frame work but still need generation/ingest stress testing.

### Next session, in order
1. Compile Metal / specialize both ML_MASK pipelines on GPU or CI. Build success alone does not validate runtime MSL.
2. Test edited chunks, last-block removal, neighbor remesh and mask ownership at x/z=-16,0,16. Rapid edits, unload/reload,
   dimension change; monitor queue/perf. Do this in a copied test world.
3. Ocean above/below, elevated lake, island; see findings for coordinates and screenshot names. Preserve original caches.
4. Cloud/water ordering and bushy leaves with native scale vs MetalFX; capture motion clips for temporal artifacts.
5. Record tested JAR hash, exact settings/camera/time/weather, before/after images and measured frame/pass times here.

The historical handoff below is preserved. Its deployment statements describe past builds, not this audit candidate.

---

# Imagine Metal: handoff (2026-11-14, ~01:10)

## START HERE: 2026-11-14 01:10, fix9c BUILT (440a3f9, CI 43531571576, sha1 77419cf5...), copy in `instances/Imagine metal/imagine-metal-metalods-fix9c.jar`, NOT yet in mods (his game was running): swap it in when latest.log ends with "Stopping!"
fix9 session (00:43-01:07, his chat): leaves "very blurry", "the waving part looks weird", "see through"; fps in forests 35-43
(terrain 11-18 ms, shadow maps 2-4 ms, was ~6 and ~1); "making new chunks again" + "lods are slow" (fix9's real2 dropped every
explored region, regenerated from the seed at ~280 chunks/s, trees 2/3 of an L0 tile); heap 99% and 1-4 fps stalls at 00:49 (GPU
idle 2.7 s: GC while regenerating); "water through clouds" (00.49.20: water drawn over the clouds-after-land). He LIKES sky light
reaching into caves/ravines: fix9's steeper sky curve reverted.
fix9c: leaf faces between two leaf blocks culled when Bushy leaves + Leaf culling are on (OwnFrapi/OwnBlockRenderer, no fill);
planes marked with pattern 1101 (OwnQuads.Layer.planeMode, set by OwnMesher): stiff sway by the block centre x0.6 (terrain.metal
own_vs via ownQrecPos), imLod x0.5 (one mip sharper, all three fragment paths), no shadow casting (shadow.metal); imsky.m mode 3
writes cloud cover into dst alpha, own_fs_tr mixes back toward imDst by it; imSkyCurve back to smoothstep; MetaStore reads old
real/ when real2 lacks a chunk (levels 1-4 re-mipped by the new rule, saved into real2); OwnFluidEdge: on a section's side a
neighbour in an unloaded chunk is the same fluid (the dark line/notches at the LOD water hand-over).
Floating island's grey block (00.54.07, generated, far): that is its stone underside at a coarse level, blocky, not water.
Test: forest fps and look with Bushy leaves on/off; flying in clouds over water; no regeneration of old areas; caves.

## (older) START HERE: 2026-11-13 ~23:05, fix9 DEPLOYED (fix9a + fix9b), not seen in game yet
mods = fix9: metalods cd0eb66, CI run 43514922999, jar sha1 2b152b87..., copy `instances/Imagine metal/imagine-metal-metalods-fix9.jar`.
Fallbacks in `instances/Imagine metal/`: fix8 (30d21a49...), fix7, fx1, fix5. Local Java compile check: JDK 25 + client.jar +
the 26.3 libraries + fabric-loader/mixin/asm/renderer-api jars, plain javac (see "Local compile" below); Metal only on CI.

### What Jack's fix8 session showed (latest.log 22:27-22:30, screenshots 22.29.30 to 22.30.47)
- Leaves (22.29.53, 22.30.47): fix8's culling + filled faces looked "horrible" (some see-through, some solid). He attached Way
  Better Leaves 2.0.0 (26.2 NeoForge, Luc1fer1o; its mods.toml says MIT) and asked for a one to one.
- 22.29.30/32 "iron still not lighting up properly": only iron's pale speck texels glowed (imOre returned the texel's red^1.5).
- 22.30.05 floating island (Terralith) far off: a huge blue block under it and a blue slab over the sea = its waterfalls grown
  by the LOD mip (water outranked air: one falling column -> 2/4/8/16 wide pillars). 22.30.27 dark flat squares at the falls'
  feet (likely the same data; check they are gone).
- 22.30.23 LOD water: a pale grey-lavender sheet (134,161,182) next to the real water (95,136,158) at the hand-over, and bright
  blue lines (water side faces at level seams).
- Perf (island): 44-51 fps, gpu 19-23 ms: terrain 6-8.8, translucent 2.2-4.2 (sea filling the view), light grid 0.9-1.6
  (refills while flying), LOD pages up to 3834. No far-shadows A/B and no rejoin (phantom check still not run: no
  "section table copy" line). exactPos table full (4096) -> coordinates rounded to the 1/2048 grid: harmless (< 1/4000 block).

### fix9a (065a1f3): Way Better Leaves' plus leaves, ported (mcopt/metal/own/BushyLeaves.java, BushyLeafSource.java)
- Every leaf block with a visible face (WBL's rule) gets 3 crossed double-sided planes 2 blocks wide through its centre, one of
  32 rotations by the position seed; bushy sprite per leaf texture in the blocks atlas (holes flood-filled, tiled 2x2, WBL's
  32x32 mask embedded as text). Added to the atlas by imaginemetal/mixin/SpriteSourceListMixin (load RETURN, minecraft:blocks).
  Tesselated in OwnMesher right after the block's model through the same path (FRAPI / own / vanilla), flat light, no AO.
  Plane coordinates snapped to 1/2048 (never the exactPos table). BushyLeaves.atlasReady (TextureAtlasMixin) clears the cache.
- fix8's own leaf face culling and FILL marking are off (OwnFrapi / OwnBlockRenderer leafCull = false); the FILL shader code is
  left unused. Settings (Surfaces): "Bushy leaves" (imaginemetal.bushyLeaves), "Leaf culling" = WBL's (no planes on buried leaves).
- Notice: third_party/waybetterleaves/NOTICE, shipped in META-INF/waybetterleaves. (fix8's note said WBL was All Rights
  Reserved; the 2.0.0 jar Jack gave declares MIT.) WBL's LabPBR leaf maps NOT ported (PbrAtlas would switch every block to the
  pack path and lose the generated normals).

### fix9b (cd0eb66)
- LOD water: MetaPalette.kindOf: flowing water/lava = air; Rle.pick: water only if >= 4 of 8 children are water; real chunks now
  in metalods/.../real2 (old real/ ignored, rebuilt as he plays). ml_fs: every water face shaded as the top (0x80, normal up).
  terrain.metal im_defer_fs: LOD water x(0.42, 0.66, 0.72) linear (measured against the real water at the hand-over). ASK Jack.
- Under water: inside the window the surface is a rippling sheet (alpha 0.38-0.62, brighter than the fog) (terrain.metal, bit 64).
- Iron ore: all speck texels glow alike (imOre case 1: colour to one brightness, 1.3); albedo not multiplied past 1 for it.
- Items: "soul" glow needs blue >= green: diamond tools/items no longer glow (lighting.metal imShadeEntity).
- Mobs: MetalFX reactive 0.8 -> 0.4 (bloom.metal im_bloom_motion_r), for "animals jittering". If they smear, go back up.
- Caves: imSkyCurve ^1.5 (partial sky light falls off faster), ImagineFrame.CAVE floor x1.5. ASK how 19.11-type ravines read.
- Far shadows: march stops above the highest land written into the height map (MetaArena.maxTop -> ImHeightP.range.y).

### Tell Jack to test
1. Forests: the bushy look vs his WBL screenshot; fps in a forest (F3) with "Bushy leaves" on/off.
2. Iron in a dark cave; a diamond pickaxe in hand (no glow); animals walking (jitter/smear).
3. LOD sea and the hand-over from high up (colour, no blue lines); the floating island (no blue block, no dark squares).
4. Under water looking up: the surface visible.
5. A cave/ravine going down: no sudden bright-to-black.
6. Still open: phantom check (Save and Quit to title + rejoin in ONE session; End and back), far shadows on/off perf A/B, vines.
- Known, not fixed: a thin dark line along the real water's outer edge (the real water's side faces at the render distance's
  edge, likely); translucent cost with the sea filling the view (2-4 ms) not reduced.

### Local compile (fast check before CI)
`tools/jc.sh` in the chat sandbox: javac --release 25 (Adoptium JDK 25) -cp client.jar (piston-meta 26.3) + the version json's
libraries + fabric-loader 0.20.1, sponge-mixin 0.16.5, mixinextras 0.5.0, asm/asm-tree 9.8, fabric-renderer-api-v1 17.0.15,
fabric-api-base 2.0.6. Compiles the whole mod's Java in ~10 s. Metal still only on CI.

## (older) START HERE: 2026-11-13 ~21:50, fix8 DEPLOYED (fix7 + fix8 + fix8b), not seen in game yet
mods = fix8: metalods 9eb9a42, CI run 43503788682, jar sha1 30d21a49..., copy `instances/Imagine metal/imagine-metal-metalods-fix8.jar`.
Fallbacks in `instances/Imagine metal/`: fix7 (92a4589c...), fx1, fix5. Jack's fix7 sessions: logs 2026-11-13-7 (18:29-18:55) and
latest (19:07-19:20); screenshots 18.31.26 to 19.20.10 all looked at.

### What the fix7 sessions showed
- Phantom blocks: NO "section table copy" line in either log: no level reset happened inside a running game (he quit to desktop
  between sessions; the only "mcopt-own reset" lines are at quit). The fix6 check has still never run. No section-table phantom in
  the screenshots. 18.53.17 ("back at spawn after being far away"): a grass-topped 8-block LOD box and a floating LOD slab in the
  near land = a coarse MetaLODS column made while he was far away, standing in for its children (MetaWorld.visit keeps old columns
  as stand-ins) in chunks the seam mask didn't count as drawn yet. fix8b: ml_fs drops levels >= 2 inside the near distance
  (masked columns only; at detail >= 4 those levels are never the right level there).
- Water (18.31.26-18.35.11): (a) bright textured blue strips along the near/LOD edge = LOD water voxels' SIDE faces on the edge
  facing into the near chunk, seen THROUGH the real water. fix8: ml_fs tests water sides' mask by the block in front of them;
  MetaWorld draws columns touching the mask (1 block wider test) with the masked pipeline so it runs. (b) 18.31.55 two chunk-sized
  dark teal squares in the LOD sea (71,135,142 vs the LOD water around 148,170,178): LOD water tops with sky light 0 (columns from
  real chunks ingested before their light), floor 10/15 dimmed the sky reflection. fix8: LOD water tops get sky 15.
  Not touched: 18.54.55 under water "still dont feel like i can see where water ends"; 18.51.39 mirror lake (Jack: he'll bring the
  reflection up again if it bothers him).
- Perf (both logs, perf lines every 10 s): normal play 58-60 fps at vsync, gpu ~16.5 ms with passes 12-14. Dips:
  * most 30-40 fps windows line up with resource reloads / options screens ("Reloading ResourceManager", "Changing view distance")
    and the 10 fps windows are the game paused / unfocused (hand+gui 2.33 flat): not steady-state.
  * real ones: terrain 8.5-9.7 ms (normal 5-6) at the village at night (18:35:48-18:36:18, "fps in 40s here") and while MetaLODS
    regenerates after travel (18:48-18:53, 19:08-19:09). Terrain vs LOD pages drawn: ~+0.5 ms per 1000 pages (1000 pages 5.5 ms,
    2500-3500 pages 7.1-7.3). translucent 4-6 ms 18:49:41-18:51:01 (snowy area with a lake filling the view: water SSR).
  * far shadows: NOT separable in the perf line (the deferred lighting is in the terrain pass, tile memory: can't be cut). fix8
    makes them cheaper (see below). A/B: MetaLODS "Far shadows" toggle, watch "terrain" in the perf line.
- Other chat notes, NOT built (not in his list): 18:33 "animals jittering", 18:36 "all ores dont get the proper shit lit up"
  (fix7d's ore glow not enough), 18:47 "pick is glowing on tips??", 19:20 "diamond items glow", 19:11 "looks bright"/"looks dark"
  (cave). Ask which he wants next.

### fix8 (66506ae) + fix8b (9eb9a42)
- Leaf culling (Jack: like Way Better Leaves' culling; WBL is All Rights Reserved, so our own code from the idea only): setting
  "Leaf culling" (Surfaces, default on, imaginemetal.leafCulling; toggling rebuilds chunks). OwnFrapi.emitted (his path: Fabric
  API loaded) and OwnBlockRenderer drop a leaf face whose cull-face neighbour is leaves; a kept face whose OPPOSITE neighbour is
  leaves is marked (OwnQuads.Layer.fillNext -> mat bit FILL -> material corner pattern 1101 = 13). terrain.metal: wc 13 waves whole
  like leaves; own_fs_g / own_fs_alpha (pre-pass) / ownFsBody keep its transparent texels, filled from the texel half a sprite over
  (else a quarter), x0.62 (imLeafFill, derivatives taken outside the branch); shadow.metal: wc 13 is not a plant, casts whole
  (ImShadowOut.fill). Plain culling looked hollow to Jack; this is meant to look like WBL's solid outer leaves. ASK how it looks.
- Vines: OwnQuads.writeMaterials gave wave 3 (vines) corners 0 (never swayed on our mesher): now 0101 like storeMaterials.
  terrain.metal: the vine's sway is in its wall's plane only (normal axis = the one where the vertex sits off the block grid,
  0.05 out for a wall vine, 0.95 for a ceiling one): d *= (0.8, 1.6, 0.8), the normal component zeroed.
- LOD water sides / tops: above. Far shadows (imFarSun): no march for faces with dot(n, L) <= 0, 12 steps x1.45 (was 18 x1.3, same
  ~1,200 block reach), stop once the ray is above y 336.
- Clouds: Jack 21:19 "clouds stay blocky": nothing to change.

### Tell Jack to test
1. Forest / jungle with F3: fps vs before; trees from outside (solid, not hollow?) and from inside a canopy; toggle "Leaf culling".
2. Vines on a wall: sway along the wall, never into it.
3. Fly over the sea and a lake at the near/LOD edge: no blue strips, no dark squares.
4. Go far (1000+ blocks), come back to spawn: no big LOD boxes in the near land.
5. The phantom check still needs a rejoin in ONE game session: Save and Quit to title, rejoin; then End and back
   (`/execute in minecraft:the_end run tp @s 0 70 0`, `/execute in minecraft:overworld run tp @s ~ ~ ~`). Log: "section table copy".
6. Far shadows A/B: MetaLODS "Far shadows" off/on while flying, perf line "terrain".


Where things stand, for picking the work up in a new conversation.

## (older) 2026-11-13 18:20, fix7 DEPLOYED (fix6 + MetalFX + fix7a-d), NOT seen in game yet
mods = fix7: metalods 6721afc, CI run 43471074869, jar sha1 92a4589c... (copy `instances/Imagine metal/imagine-metal-metalods-fix7.jar`).
Fallbacks in `instances/Imagine metal/`: fx1 (2df4bad5..., fix6 + MetalFX only), fix5 (4ce76e04..., before this chat).
Jack had NOT launched the game during this chat (latest.log still the 17:22-17:26 fix3 session). First thing: read his new log.

### 1. Phantom blocks: REAL CAUSE FOUND AND FIXED (fix6, 0e1d777)
- OwnTerrain keeps FRAMES (3) GPU copies of the section table; each frame copies only the slots changed in the last FRAMES frames
  (dirty[f]). setSlot calls made BETWEEN frames (disposeAll: leaving a world, a dimension change, a resource reload, dispatcher
  dispose) went into dirty[frame % FRAMES], whose table copy had already been made that frame, and that list is reset at the start
  of its next frame BEFORE the copy: so one of the 3 copies kept the whole old level's table (origins + record ranges that new
  meshes reuse) and every 3rd frame drew old/garbage geometry at old positions, half-transparent with the TAA: the stretched grass
  after rejoining (14.06.06), the End slabs near 0,0 after respawning (2026-11-12 13.43.38), the "phantom builds". The C2 level
  guard never fired in ANY log since Nov 12 (grep "level guard"): it was never the cause. Records also bake the slot (rec +4) and
  the shader reads the origin through it (terrain.metal ~1730), which is why the garbage lands at old origins.
- Fix: setSlot with the table closed (tableOpen false) goes into `late[]`, merged into the next frame's dirty list in beginFrame0
  after its reset. Check: after a reset, each copy is compared with the master right after its update and logged:
  `[Imagine Metal] section table copy K after a level reset: matches` (3 lines per reset; "N stale slots ... (mended)" = still wrong).
- 13.54.46 "visual glitch in lod on house": at full size it is the REAL roof (stairs stretched into the next roof), same family; ask.
- Repro for Jack: join, look around, Save and Quit, rejoin the same world, look at nearby grass; then
  `/execute in minecraft:the_end run tp @s 0 70 0` and back with `/execute in minecraft:overworld run tp @s ~ ~ ~`, look near 0,0.
  In the log: the "section table copy" lines, "mcopt-own reset", no "exactPos check ... wrong".

### 2. MetalFX: ON metalods (cee9079), deployed, default on
- Cherry-picked 4f9e852 (conflicts in Bloom.java / SettingsScreen.java resolved keeping both). Toggle "MetalFX upscaler" in
  Advanced > Debug (not saved); the 4 flip toggles stay as debug only: signs checked against Apple's WWDC25 sample
  (jitterOffsetX = pixelJitter.x, jitterOffsetY = -pixelJitter.y for a y-up projection jitter = the content's shift in y-down
  pixels; ours: the frame holds the scene shifted by +taa in texture uv, so +taa*size = the +1 defaults). Motion = current -> previous
  in pixels, as Apple's.
- Whales / cape: no per-entity motion vectors (Minecraft's pipelines). Instead EntityShade marks mob pixels in the frame's alpha
  (0.96, non-translucent entity pipelines, level pass only; terrain 1, waving plants 0.98) and the motion pass writes MetalFX's
  reactive mask from it (0.8 on mobs; macOS 14+: `MetalFX temporal scaler on (...), mobs reactive`). Ask Jack if whales/capes
  still smear. If yes: real motion vectors need the previous frame's entity vertex positions (big).

### 3. Far land vs DH + Gael (fix7a-c)
- Pale sky: measured (17.26.15) flat colourless (192,193,198) from top of frame to horizon; our sky model gives (122,150,195) at
  that elevation and no rain (weather.dat raining 0). Cause: he was flying at cloud height; every upward ray met the cloud cell
  right above the camera within a few blocks. Fix: clouds fade out within 6-40 blocks of the eye (sky.metal imClouds). NOT a sky
  colour change. (imaginemetal.haze=0.75 in his config is a dead key; bloom fog is 0 by clear day.)
- LOD water: material 0x80 (class 0 + bit 7, unused) on LOD water tops (ml_fs: the real water's surface colour from the biome's
  water colour), im_defer_fs shades it with kind 1 (waves, glint, sky reflection by angle) and lays it over the absorbed deep seabed
  (x a^(1/2.2), a = max(0.72, fresnel)). Was cyan ground next to deep-blue real water.
- Water seam: a coarser level's water voxel had its top on its own boundary (64 for the sea at level >= 1, real surface 62.89): a
  1-block step at L0/L1 borders whose side drew the dark line. ml_vs: a water top whose voxel holds the sea level goes to sea - 1/9
  (MLFrame.params.w = level.getSeaLevel()). Lakes above sea level keep the old rule.
- AO on LODs: MetaMesher computes 4 corner values per face voxel (vanilla's smooth-light rule from the 3 voxels in front), greedy
  merges only equal ones, a byte per quad after each page's quads (PAGE_BYTES 2304, AO_AT 2048), ml_fs interpolates per voxel
  (0.5 / 0.72 / 0.88 / 1). Water and skirts unshaded.
- Far shadows: MetaArena height map 1024x1024 uints (16-block texels, wrapping, tag in bits 12-31, top+1 in 0-11, finest level
  wins) written by workers after meshing (MetaArena.heights); MetaLods.drawInG hands it (and ImHeightP at arena.frame+160) to
  mcown.m (mco_im_heightmap, cleared by mco_im_defer each frame); im_defer_fs imFarSun marches 18 steps from 20 blocks x1.3 toward
  the light from 0.7 x shadowRange out; ImPre.farSun scales the term the shadow maps don't cover (sun = mix(open*farSun, sm.x, sm.y)).
  Setting MetaLODS "Far shadows" (farShadows=true). Cost unmeasured: check the perf line (terrain) vs fix5's ~5.2 ms.
- Colour: LOD albedo eased to 20 % less saturation and 6 % darker over the 384 blocks past the real terrain's edge (no step at the
  hand-over). Ask Jack if it's enough / too much (he wants to be asked about grading).
- Clouds blocky vs soft: NOT changed (Jack chose blocky; ask).

### 4. Older items done in fix7d
- LOD grass side overlay (13.55.21): LodColors.Look gets overUv/overTint (a second tinted quad on the side), palette model 64 bytes
  (overUv at +48, overlay tint kind in flags bits 18-20), ml_fs lays it on tinted.
- Ore glow (15.46.12): IM_MAT_ORE by sat / hi (0.1..0.22) instead of sat (0.08..0.2): iron's darker speck texels glow too.

### Still to ask Jack (the handoff's "ask" items; nothing built for them)
- Jungle fps dip (C2 ideas: leaves solid past ~32 blocks, leaf pre-pass off by default)? Vines on OwnQuads (wave 3 -> 0101)?
  01.03.44 pale streak? "water reflection" 16.22.13? Blocky vs soft clouds? Water surface from below readable now (fix4)? Haze right?
- Shadow pop-in case 2 (2026-11-12 23.48.17/22): needs Jack at that spot with Debug view "Shadow maps" and the two toggles.
- Cold start: read the new log's first `[MetaLODS] generation:` lines before changing anything.


## (older) Jack 17:39 (after fix5)
- The huge grass is the SAME family as the phantom blocks: the End ghost slabs (C2 level guard) and "phantom builds" he has seen
  for a long time. Treat it as one long-standing bug in the section/mesh lifecycle across level changes (old level's compiles,
  stores, table entries, slot reuse), not as a one-off. Find the real cause and fix it for good; fix4's checks are diagnostics.
- MetalFX: APPROVED ("metalfx is good lets go"). Bring branch `metalfx` onto `metalods` (B3 motion vectors / jitter included as
  far as built), build, deploy, keep the non-MetalFX jar as fallback.
- He wants the next chat to FIX the open items, not list them as open.

## (older) 2026-11-13 17:40, fix5 DEPLOYED (fix3 + fix4 + fix5), not seen in game yet
mods = fix5: metalods 9131809, CI run 43463452715, jar sha1 4ce76e04... (copy `instances/Imagine metal/imagine-metal-metalods-fix5.jar`).
Fallbacks: fix3 (2c39ca2e...), fix2 (eb129083...). Screenshots handled up to 2026-11-13_17.26.23 (+ his DH/Gael refs 16.02-16.04).
fix4 (618e373):
- Water surface from below (Jack 17:21, "especially ... looking up from underwater"): cause = C2b painted the surface past the
  window in exactly the under-water fog colour (imWaterFog), so it melted into the fog. terrain.metal (env.w bit 64,
  Settings.waterUnderSurface, toggle "Water surface visible from below" in the debug list): that colour x (1.35 + 0.7 ripple),
  ripple from the water texel's luma and the wave normal. Ask Jack if it reads as a surface now / too bright.
- Huge grass after rejoin (14.06.06): rows of grass quads floating ~1 block up, stretched along one axis, near the player, real
  terrain. Fits plant vertices (off-grid: random offsets -> OwnPosTable codes) reading WRONG table entries in the arena's copy.
  Not proven. fix4 adds: OwnPosTable.verify (arena copy vs VALUES, mends + logs "[Imagine Metal] exactPos check (...): N of M
  table entries in the arena were wrong") every frame for 600 frames after disposeAll, then every 30; a log line when the old
  level's dispatcher STORES into the arena after the change ("level guard: a section of the last level stored after the change");
  the "mcopt-own reset" line now reaches latest.log (println, renewed/kept). Note 14:03 that day: table FULL (4096) before the
  rejoin. NEXT: Jack leaves + rejoins a world, then read those log lines. If no mismatch is logged and plants still stretch, the
  cause is elsewhere (stale meshes; check OwnLife / store path).
fix5 (9131809), after Jack's DH + Gael screenshots (16.02.27, 16.03.00, 16.03.22, 16.04.02, 16.04.19 in the chat) vs ours
(17.24.36, 17.26.15, 17.26.23, fix3):
- Distance haze replaces fix3's far fog: air.metal, over near land AND LODs from 24 blocks, 1 - exp(-(d - 24) / 700 x strength x
  hf), hf = exp2(-(max(y,40) - 64) / 48) clamped 0.35..2.2 (valleys hazier), x (1 - cave), toward imSkyColor at the horizon.
  Strength = MetaLODS "Far fog" (default 1). Only while MetaLODS is active + air pass.
- LOD per-block brightness +-7 % (DH's noise texture; 16.04.19 shows DH's per-block speckle), ml_fs, not on water.
Differences seen in the comparison still NOT addressed (next look work, in this order of how much they show):
1. Our day sky is a pale flat white-grey even overhead (17.26.15/23, 16.43.12); Gael's is blue overhead with soft volumetric
   clouds. Check sky colour / haze 0.75 / bloom fog whiteout first.
2. No shadows on LODs (perf: "shadow maps 0.06-0.10 ms" high up = almost nothing cast; LODs never cast). Gael+DH: far hills and
   trees have sun/shade sides and cast shadows. Also no AO on LODs (plan: AO byte per quad, PAGE_BYTES 2048 -> 2304).
3. Our colours: saturated yellow-green flat; Gael's foliage darker/cooler, lower saturation far off. Revisit after 1 and 2.
4. 17.24.36: the water seam line is STILL there with fix3 (straight dark line, chunk jogs) and LOD water is cyan while the real
   water next to it is deep blue: LOD water colour = near water (open item) and the seam needs a second look (the 8/9 fix was not
   enough or not the cause).
5. Clouds: ours are big flat blocky slabs; Gael's soft. (Jack chose Reimagined blocky clouds earlier; ask before changing.)

## (older) 2026-11-13 17:05, fix3 DEPLOYED, not seen in game yet
mods = fix3: metalods 96abb9b, CI run 43457899089, jar sha1 2c39ca2e... (copy `instances/Imagine metal/imagine-metal-metalods-fix3.jar`).
Fallback fix2 (eb129083..., copy kept). From Jack's fix2 session 16:39-16:46 (screenshots 16.42.12 to 16.46.11, all handled):
- Far fog (Jack 16:43 + chat 16:49: Gael's with DH looks better "cus of fog in distance", hides the "lego bricks"): air.metal, past
  the real terrain's edge the land mixes toward imSkyColor at the horizon (direction's y raised to >= 0.02), 1 - exp(-x^2) with
  x = (dist - rd*16) / (fog end - rd*16) * 1.9 * setting. Setting MetaLODS "Far fog" 0-2 (default 1, metalods.properties farFog).
  Plumbing: Reflections puts start/density after the 16 floats of airM, imssr.m hands them on as ImAirParams.size.zw. Only with
  the air pass (default on). Look at it first: too thick/thin, colour at night, the sun's glow in it.
- Dark line across lakes along chunk edges (16.43.12, 16.43.45): near/LOD water seam. LOD water top was a full block, real water
  8/9: under the LOD sheet's edge the LOD seabed showed. ml_vs lowers water tops (top faces, sides' top edges) by 1/9 block
  (models buffer now bound to the vertex stage too, metalods.m).
- LOD water sheet over the real river (16.43.34/16.43.44, cyan + dark blue sheet with a side face, ~6-8 chunks out) and a
  sky-coloured strip between near land and LODs (16.44.09/16.44.17, flying): both the hand-off mask. OwnSeam.column: a chunk is
  "drawn" when every section holding one of its 256 WORLD_SURFACE top blocks is compiled (was: every section from the lowest to the
  highest surface, which never held with sealed sections in ravines / cave mouths); a surface section outside |dy| <= vd counts as
  not drawn. fix2's "every loaded chunk 2+ inside rd" (addLoaded) is off (it masked loaded-not-compiled chunks: the strip);
  -Dmetalods.maskLoaded=true brings it back. Check: caves/ravines (13.57.16, 13.58.58 cases) must stay clean.
- Progress line "keep getting metal making chunks notification" (16:43): once done it comes back only when >= 1/10 of the view is
  missing for over 1 s (teleport, join), not for the edge trickle while moving (MetaWorld.progressLine, progDone).
- Lava "doesnt look that hot anymore, no bloom?" / "not hot enough" (16.45.22, 16.46.11): imaginemetal.bloom was false in his config
  since 10:50 (saved a minute after his jungle "dipped to 28-29 fps" note: most likely switched off while chasing fps). Set back to
  true in config/imaginemetal.properties (game closed). If lava still isn't hot enough WITH bloom: then the look (ask what he means).
- fix2 numbers (latest.log 16:39): 240k chunks' worth in view at distance 256 (he has 256 set), 301,744 chunks' worth generated in
  69 s, 5,548 chunks/s peak at 29 s; real chunks ingested 5,531. GPU ~16.5-17 ms (60 fps vsync).
- Still open from before: AO on LODs, LOD grass side overlay, LOD water colour = near water, cold start, huge grass after rejoin,
  under-water top, mob motion vectors, ore glow per sprite.

## (older) 2026-11-13 16:25, fix2 DEPLOYED, bug list from the fast1 session (read this first)
mods = fix2: metalods 529fecf, CI run 43451128240, jar sha1 eb129083... (copy `instances/Imagine metal/imagine-metal-metalods-fix2.jar`).
Before it fast1 (57c2807b..., copy kept). Not seen in game yet. Contains, in order:
- 7e38f81 TAA offset for the pipelines EntityShade skipped (eyes, text, beams, leads): dragon eyes flicker.
- 68c285c on-screen progress (Jack asked 15:52, DH-style): "MetaLODS: N chunks ready, M left, R chunks/s", top left, gone 2 s after
  done, setting "Progress on screen". MetaLodsHudMixin -> MetaLods.hud -> MetaWorld.progressLine / count().
- 525ed8e no coarse stand-ins: a column whose children are wanted is no longer requested (COARSE_FIRST, -Dmetalods.coarseFirst=true
  = old). Jack: DH "doesn't load in huge blocks"; his 13.52.27 / 13.52.42 "why it look like this?? dh is instant".
- 529fecf (a) MetaArena frees pages by its own tick: release() used the world's frame, so a closed world's pages came back only
  when the next world reached the same frame number (log 14:05: 50,000 -> 11,000 free in a minute); (b) LODs hidden in every
  loaded chunk within rd - 2 on top of OwnSeam's drawn-whole mask (MetaWorld.addLoaded): a chunk with a never-compiled section
  never counted as drawn, its LOD (stone-filled, no caves) showed through caves/ravines and as trees in near land (13.57.13/16,
  13.58.58, 14.00.42 white quad in a cave, 14.06.06 "this after rejoining"); (c) sky light floor 10/15 in ml_fs (lq.y): real
  chunks' air under thick canopies is sky 0 and the mips turn leaf faces toward it: pitch black (15.41.48, 15.42.11, 15.42.23).

### Jack's answers 16:38 (after fix2 was deployed)
- "color change": the LOD part's colouring/shading differs visibly from the near land (13.56.49). Causes: LODs have no ambient
  occlusion, no texture normals, textures fade to the average colour past mip 2.5-4.5, no shadows. Next work: AO + keep textures
  further + match the near land's shading. Ask for his eye on it after.
- Mist (13.56.08/13): a mod's. Done.
- Under water: he can't tell where the water's TOP is from below (the surface isn't visible as a surface). Not the fog strength.
- "huge grass" (14.06.06, after rejoining another world): REAL terrain, not LODs: plant quads stretched over several blocks. Same
  look as the known stale-section problem after a level change (exactPos indices read against a renewed table; C2's level guard
  didn't catch this one). NOT fixed. Next: reproduce by leaving and joining a world; check "level guard" lines and the own table.
- "water way under where the water actually is" (13.58.58 pale water plane down in a ravine, 14.05.33 sheet over land after
  joining New World (8)): LOD water at sea level drawn inside the near land (chunks not counted as drawn whole). fix2's mask (rd - 2
  loaded chunks) should hide it; unverified.

### Speed, measured (fast1, logs 2026-11-13-4 and latest)
- First ~25 s cold: the first tiles took 1-7 s each (surface stage; JIT + the server generating spawn at the same time). Then the
  whole 128-chunk view (~51,000 chunks of area) was done ~40-65 s after join; ~700 tiles of which L4/L5 were stand-ins only.
- DH + SeedGen on Jack's screen (16:04): 125 chunks/s. "300,000 chunks in under a minute" was not what his DH showed.
- With 525ed8e the view needs ~300-400 tiles (L0 ~113, L1 ~85, L2 ~85, L3 ~22 by area) and no L4/L5. L0 cost: trees ~150 ms of
  ~230 ms a tile (the biggest stage). Cold start is the next thing to look at (warm the generator before the player is in).

### Jack's notes not fixed yet (screenshot: note -> cause as far as known)
- 13.55.21 "grass color on sides doesnt match": LOD sides use grass_block_side (its baked default green) without the biome-tinted
  overlay; needs the overlay sprite (MetaPalette Model: a third rect) tinted by kind 1 over the side.
- 13.56.49 "color change is too obvious": NOT identified (LOD vs real colour at the hand-over? a biome edge?). Ask him to point.
- 13.54.46 "visual glitch in lod on house": NOT identified at 1100 px; look at the full-size file.
- 13.56.08 / 13.56.13 "what is this": grey pixelated puff over a cave waterfall; shaders were OFF then (F8 13:55:54-13:56:26), so
  not our lighting; likely a mod's mist particle (Streams Reflowing?). Ask.
- 14.01.45 "cant really tell im underwater / when the water turns to air": the under-water look is too close to above-water (C3's
  wide window + weak water fog). Tune with Jack (toggle "Wide window up from under water").
- 14.02.01 "whales look blurry/pixelated underwater" and 15.43.56 / 15.44.00 "cape looks blurry / jittery": moving entities have no
  motion vectors, the TAA's history clamp smears them (B3), plus the 67 % render scale. Brown squares around the whale in
  14.02.01: unknown (particles?).
- 14.05.33 / 14.05.36 "uhmm" (another world, New World (8...)): a huge flat purple-blue sheet over the land. Unknown: LOD water at
  sea level from a generator that doesn't match that world, or LODs inside the near land (fixed by (b)?). Check with fix2.
- 15.46.12 "only some of the iron is bright": Imagine Metal's ore glow picks pixels by colour; some of iron's pixels miss. Look at
  the material code (imMaterial) and make it per sprite.
- DH comparison (16:09, his DH shots): DH far land "fits more" with the near land; DH's own flaw: water colour switches at the
  hand-over (near shader water vs far water). Ours should match the far water's colour to the near water.
- Next planned: ambient occlusion on LOD faces (design below, 16:00 section), LOD water colour = near water, grass side overlay.

## START HERE: 2026-11-13 15:40, Jack's questions and THE PLAN
Jack at 15:34: "does the handoff include the fps and eye flickering? what about the shading on lods? why dont u do the lods like
voxy + seedgen? that looks way better and runs well. there are also some visual bugs in the latest screenshots."

### 2026-11-13 ~16:00: progress on screen BUILT (Jack asked 15:52), not deployed (link still failing)
- metalods 68c285c, CI run 43447346699, jar sha1 630ef71d... (includes 7e38f81's eyes fix). Sent to Jack as a file to install
  himself (imagine-metal-metalods-progress1.jar); keep a copy as instances/Imagine metal/imagine-metal-metalods-progress1.jar.
- Jack 15:52: "dh + seedgen only properly generates at full speed with shaders off. if ours does that too then maybe something that
  switches em off till chunks are loaded, and a text on screen showing how many chunks are loaded and how many its loading like
  in distant horizons ... when done generating no text". Built: the text (MetaLodsHudMixin on Hud.extractRenderState TAIL ->
  MetaLods.hud -> MetaWorld.progressLine): "MetaLODS: N chunks ready, M left, R chunks/s", area in chunks of the columns the view
  wants (MetaWorld.count in visit), rate over ~3 s; gone 2 s after nothing is left; top centre with F3 up; not with F1. Setting
  "Progress on screen" (progress=true in metalods.properties).
- NOT built: the "shaders off until loaded" switch: he made it conditional ("if ours does that too"). Ours generates on worker
  threads; per frame only prepare() (requests + 3 ms of uploads) runs, and only inside the G-buffer pass, so with the deferred
  lighting off MetaLODS does nothing at all. Compare the counter's chunks/s first; if the shader's load slows it, the switch
  would be Imagine Metal's heavy passes (shadows, shafts, SSR) off while left > 0, not the deferred lighting itself.
- Started (not committed): LOD ambient occlusion design: quad bits full (x5 z5 y9 face3 w5 h5 | block16 biome8 sky4 block4),
  face bits unused by the shader (the page header has the face); plan: an AO byte per quad after the 256 quads in each page
  (PAGE_BYTES 2048 -> 2304, no native change), 4 corners x 2 bits from the front voxel's neighbours, greedy merges only equal
  AO, bilinear per voxel in ml_fs (fract of the quad's voxel coords), multiplied into the colour.

### 2026-11-13 ~15:50 (new chat): step 6 BUILT, not deployed (Mac link down the whole turn)
- metalods 7e38f81, CI run 43445278355, jar sha1 48e2b2db...: EntityShade.jitterOnly gives the pipelines it skipped (names with
  "eyes", "pipeline/text", "beam", "leash", "breeze", "energy_swirl", not "gui") the TAA's sub-pixel offset in the vertex stage
  only; fragment stays Minecraft's. Same toggle as mobs ("Mobs with TAA offset", entityJitter). Log line: "[Imagine Metal] TAA
  offset: <pipeline>". Copy in the sandbox at /mnt/user-data/outputs/imagine-metal-metalods-eyes1.jar; deploy it (and keep a copy
  as instances/Imagine metal/imagine-metal-metalods-eyes1.jar) once the link is back and the game is closed.
- Step 1 (fast1 session's screenshots + log) still NOT read. Step 2: asked Jack for the Voxy + SeedGen reference shots.

### Not done yet in that chat (the link to his Mac was down at 15:34)
- His newest screenshots (taken after 11.30.23, in the session with the "fast1" jar) and that session's log were NOT read.
  First thing in the new chat: `ls -t screenshots/*.png | head` and read only the ones newer than 2026-11-13_11.30.23, plus
  `logs/latest.log` (and the newest `logs/2026-11-13-*.log.gz` if he restarted): `[MetaLODS] generation: ...` (chunks/s vs his
  5,000), `stats`, `stopped:`, his `[CHAT] <herningstang>` notes. List every visual bug he shows, with its cause, before fixing.

### Answers given (keep them consistent)
- fps dip and dragon eyes: yes, both are in this file (section "Jack's other notes in that log" below) and the project doc.
- Shading on the LODs today: they are drawn into the deferred G-buffer, so they get the sun/sky lighting, haze, fog and TAA
  like the real terrain, with block textures (fading to the sprite's average colour far away) and biome tints. Missing:
  they cast no shadows; past the shadow range (256 blocks) nothing shadows them either; no ambient occlusion (flat faces, no
  corner darkening); no generated normals; water is opaque.
- Why not Voxy + SeedGen themselves: upstream Voxy needs OpenGL 4.6 and macOS stops at 4.1, and his game renders through
  mcopt's Metal backend, so Voxy can't run in his instance. (Third-party Mac ports exist: a Metal fork for 1.21.11 and a
  Zink/KosmicKrisp route, neither for 26.3 on our backend.) Both are All Rights Reserved: methods only, nothing copied.
  MetaLODS already IS the Voxy method (voxels in runs, mips, greedy faces, block textures) and the SeedGen method (terrain from
  the seed without chunks). The difference he sees is in what our generator and shader leave out, see the plan.

### THE PLAN (in this order; ask Jack where marked)
1. Read the fast1 session (above). Fix the visual bugs he shows first (bugs before features). Report the measured chunks/s.
2. Ask Jack for one or two Voxy + SeedGen reference screenshots (or a video frame) of what "looks way better" means to him,
   saved in `screenshots/reference/`. Don't guess the look (lesson from the grass "ghost copies").
3. The LODs' look, closer to Voxy + SeedGen (each item: what it costs, Jack picks):
   a. Ambient occlusion on LOD faces: per-corner darkening from the 8 neighbours when meshing (stored in the quad, nearly free
      on the GPU). Probably the biggest single gain: without it far land reads flat.
   b. LODs cast shadows: the LOD pages drawn into the far shadow cascade within its range, and past it a cheap sun
      "horizon" test against a coarse LOD heightmap. Costs GPU time: measure.
   c. Generator detail: surface rules as the game's (Terralith's bands, stone on steep slopes, snow lines), real trees on levels
      0-1 (impostors only past that), cave mouths and aquifer lakes from the final density only where it shows (level 0
      near the surface band). Trades some of the speed gained today: measure against the 5,000 chunks/s target.
   d. Translucent far water with the shader's water colour (today opaque with a sheen).
   e. The hand-over: real sections fading in from the fog colour over the LODs (OwnSeam.fadeMs only acts for mcopt's own far
      terrain): make them appear instantly while MetaLODS draws.
4. Speed, if step 1 shows < 5,000 chunks/s: skip intermediate levels where a finer one is the target (~22 % fewer tiles),
   band-limit the 3D noise, a QoS setting for the workers.
5. fps dip in the jungle (10.49.42, 28-29 fps under a canopy): leaves' G-buffer cost. The "C2 performance" ideas (leaves solid
   past ~32 blocks, leaf pre-pass off by default) were never agreed: ask Jack before building.
6. Dragon eyes flickering (11.01.17): the eyes render layer gets no TAA sub-pixel offset (EntityShade skips that pipeline). The
   open B3 item: jitter for the skipped pipelines (eyes, text, beams). Small, can go with step 1's build.
7. Then the older open items below (shadow pop-in case 2, MetalFX on its branch, etc.).
- MetaLODS stays on branch `metalods` until Jack is happy with it; then merge to main.


## START HERE: 2026-11-13 11:50, MetaLODS "fast1" DEPLOYED (branch `metalods` 917ca15, CI run 43411728436, NOT on main)
- mods = jar sha1 57c2807b... (copy: `instances/Imagine metal/imagine-metal-metalods-fast1.jar`). Before it: `imagine-metal-metalods-fix1.jar`
  (883ba593..., only the draw fix, the build Jack's 11:28 test ran). Fallback without MetaLODS: `imagine-metal-c2b-water.jar` (388bef08...).
- NOT seen in game yet: fast1 (speed, the gap fix). Seen in game: fix1 draws LODs (Jack's 11.30.18 / 11.30.23).

### What was wrong at 10:42-11:09 (Jack: "doesn't seem to be working")
- Not his setup. `MetaNative.DRAW`'s FunctionDescriptor lacked the `masked` int (16 arguments for a 17-argument `mml_draw`), so the
  first draw threw WrongMethodTypeException and MetaLODS stopped itself; nothing was ever drawn. Fixed in 15c685b.
- The one-off "no G-buffer pass this frame" line at world load is harmless (deferred lighting comes on a few seconds later).

### The strip of sky between the real terrain and the LODs (Jack 11:31, screenshots 11.30.18 / 11.30.23)
- Cause: the mask ("the real terrain draws here": LOD fragments discarded, columns skipped) was every LOADED chunk within
  rd x 16 blocks. The client holds chunks it doesn't draw (the outer ring, chunks not compiled yet): masked but empty.
- Fix (917ca15): `MetaWorld.updateMask` takes mcopt's `OwnSeam.mask` (a chunk counts once every section of it that can show
  holds a compile result in OwnTerrain's table, inside the game's distance rule); `-Dmetalods.handoffDrawn=false` = old rule.
  metalods.metal pushes the LODs' depth 0.05 % farther so the real faces win in chunks drawn only in part.
- Not done: the real sections still fade in from the fog colour when they take over (mcopt's `OwnSeam.fadeMs` only acts for
  mcopt's own far terrain). If Jack sees a pale flash at the hand-over, that is the place.

### Speed (Jack's target: Distant Horizons + SeedGen, ~300,000 chunks in under a minute, ~5,000 chunks/s)
- Measured on his Mac before (session 10:42, distance 256, 2 workers): the whole view = 630 tiles (L0 138, L1 136, L2 136, L3 136,
  L4 64, L5 20) in ~104 s: ~263,000 chunks of view in 104 s = ~2,500 chunks/s by area (7,450/s if every level's tiles x chunks are
  summed, but the levels overlap). A tile is 64 x 64 cells whatever the level, so the work is ~650 tiles at distance 256.
- Offline bench (sandbox, 26.3 + Fabric + Terralith + Lithostitched headless, x86, Java noise backend, 1 thread), ms a tile:
  before L0 213 / L1 200 / L2-5 130; after L0 66 / L1 37 / L2-5 ~90. For the 650 tiles: 106 s -> 48 s of CPU.
- Where the time went: levels 0-1 (and every tree chunk's ground) sampled the router's FINAL density (caves; ~9x a sample with
  Terralith, whose final density also can't be split, log "final density not split"); levels 2+ sampled two volumes (the surface
  every 32 blocks + the 3D carve's every 8). Meshing is cheap once warm (0.2-0.7 ms a column; the 4-17 ms in his log is JIT warm-up).
- Changes (c4df8df): surface from the terrain shape at all levels (`LodNoise.fineLevels = 0`, set by MetaGen), one volume for surface
  and carve (`LodTile.keepVolume / volume`), no samples at y >= 256 (the top slide's constant), the carve's density per column
  first; workers `cores - 2` (6 on the M1, was 2), still utility QoS; stored columns now `gen7` (gen6 is stale, 11 MB, can be deleted).
- 917ca15: workers take real chunks and columns in turn. Real chunks alone went first: joining a world queued ~1000 of them and
  no tile was generated for 25 s (11:28:07-11:28:32); in flight they kept the workers from the columns.
- Also seen in his log: while he flew (11:30:17-11:30:57) tiles took ~1.9 s each instead of ~0.3: the utility-class workers get
  little CPU while the game (c2me, chunk meshing) is busy. By design; more workers help; a QoS setting would be the next lever.
- NOT measured on the Mac yet. Read the new line (every 10 s while tiles are made):
  `[MetaLODS] generation: N tiles in T s with W workers = X tiles/s, Y chunks/s (..., levels summed); L0 n tiles: surface a + trees b + 3D c + columns d ms; ...`
  For the by-area figure: view chunks (pi x distance^2, ~206,000 at 256; the square the columns cover ~263,000) / seconds until
  "0 asked, 0 jobs queued".
- SeedGen's methods (public descriptions only, nothing copied): terrain from the seed without chunks, faked trees, and each area
  written only at the detail it is drawn at, finer on approach. We do the same but ALSO generate every coarser level over the fine
  areas (parents shown until the children are ready).
- Next levers, not done: (1) skip the intermediate levels where a finer one is the target (~22 % fewer tiles; needs the column
  choice in `MetaWorld.visit` reworked); (2) sample the blended 3D noise only in the y band where the rest of the density
  doesn't already decide the sign (it is ~58 % of a coarse tile); (3) y step 16/32 at levels 4/5; (4) a worker QoS setting.

### The offline bench (not in the repo; rebuild in a new sandbox if needed, ~15 min)
- JDK 25 (Adoptium), client.jar + libraries from the 26.3 version json, fabric-loader 0.20.1 + asm + sponge-mixin from
  maven.fabricmc.net; Terralith, Lithostitched, Fabric API jars staged from Jack's mods folder.
- javac over the mod tree minus OwnFrapi (stubbed), OwnIntLifeTaskMixin, OwnIntLifeSectionMixin, FlatFaceShadeMixin, plus a
  ModInitializer `mcopt.metal.lod.MetaBench` (ServerLifecycleEvents.SERVER_STARTED -> MetaGen.of(server.overworld()) -> generate
  tiles per level, hash the Rle output, mesh it); its own fabric.mod.json with only `LodMaterialContextAccess` as mixin.
- Run: `java -Dfabric.gameJarPath=client.jar -Dmcopt.lod.nativeNoise=java -cp <fabric + libs> net.fabricmc.loader.impl.launch.knot.KnotServer nogui`
  (the client jar runs the dedicated server's Main; eula=true, online-mode=false). JFR works (exit with System.exit, not halt).
- Output hashes equal before/after = an exact-output check for changes that should not alter the terrain.

### Jack's other notes in that log (not asked about yet)
- 10:49:40 "dipped to 28-29 fps here that vrazy" (screenshot 10.49.42, jungle canopy at 87 88 67 facing north, 32 fps). MetaLODS
  was not drawing then (stopped): terrain cost on leaves (C2 performance item, see below).
- 11:01:16 "eyes of this dragon flickering.. (taa maybe?)" (11.01.17, Ice and Fire dragon): the eyes layer is one of the
  pipelines EntityShade skips (no TAA offset): the open B3 item.


## Setup
- Repo: swungback/Imagine-Metal. Push to main → CI (.github/workflows/build.yml) compiles every Metal shader, builds the jar,
  publishes the "dev" release.
- Deploy: download `releases/download/dev/imagine-metal-0.1.0.jar?t=<time>` into
  `~/Documents/instances/Imagine metal/minecraft/mods`, check a marker string with `unzip -p`, then replace the jar.
- Jack's settings: `config/imaginemetal.properties` in that instance. Screenshots and `logs/latest.log` there too. His notes
  are chat lines in the log (`[CHAT] <herningstang> ...`) just before each screenshot.
- Reversed-Z depth everywhere (0 = far / sky). ImFrame is 928 bytes (lighting.metal, ImagineFrame.SIZE, mcown.m imFrame[928]).

## 2026-11-13 10:36: DEPLOYED (Jack: "swap jars"): mods = metalods branch head, CI run 43333737024, jar sha1 f03b83e6...
- Copy kept as `instances/Imagine metal/imagine-metal-metalods.jar`. Fallback: `imagine-metal-c2b-water.jar` (388bef08...).
  Not yet seen in game; waiting for Jack's first test (plan in docs/METALODS.md).

## START HERE: 2026-11-13 ~01:00, MetaLODS built overnight on branch `metalods` (was NOT deployed then, NOT on main)
- Jack's ask (11-13 00:00): a Voxy-style LOD system inside our shader, Metal-native, as fast as possible, its own menu next to
  Imagine Metal's, named MetaLODS; unexplored land from the seed like SeedGen; default 128 chunks. He closed the lid: nothing
  tested in game. Design, limits and his test plan: docs/METALODS.md.
- Deploy in the morning only with Jack's OK: the branch's CI artifact (`gh run list -R swungback/Imagine-Metal --branch metalods`,
  `gh run download <run> -n imagine-metal`), game closed, over `mods/imagine-metal-0.1.0.jar`. Fallback stays
  `imagine-metal-c2b-water.jar` (= main 0cc9a2e, what's in mods now).
- Look first in latest.log: `[MetaLODS]` lines (ready / dimension / stats every 10 s / "stopped: ..." with a stack trace).
- Branch state ~01:15: head = "skirts at column sides" (CI green, run 43333737024: the jar to deploy),
  includes C3 (merged from branch `underwater-window`: under water the window up through the surface is wide, Debug toggle
  "Wide window up from under water"). Generated LODs are 3D ("Overhangs", default on). Offline tests (not in the repo):
  runs/mips/meshing/file round trip and the 3D carve, against the bootstrapped 26.3 jar.
- Still open from before (Jack 00:00): under water he can only see out through a circle right above him (Snell's window from
  terrain.metal's faked total internal reflection, the 23:50 fix only recoloured the ring). He said it can wait till tomorrow.

## START HERE: 2026-11-12 23:40, Build C2b DEPLOYED (main 168bd4a, CI green run 43320786647). NOT tested yet.
- mods folder = C2b: jar sha1 200f0257..., 1524309 bytes. New fallback `imagine-metal-c2.jar` (C2, 9e558ce5...) next to c1/b2/b1.
- Why C2b: Jack tested C2 at 23:27 (screenshots 2026-11-12_23.27.22 / 23.27.25, grassy terraces, flying ~15-20 above them,
  looking toward a low sun): "shadows missing here, you didn't fix pop in, look when I get closer". The missing shadows were
  ~15-20 blocks away (crosshair blocks ~32 px wide at FOV 70), INSIDE the near map's reach, so C2's item-4 diagnosis (the
  near/far handover at 25-29 blocks + far-map offsets) was incomplete. Real cause: the near cascade is a box ACROSS the light
  centred on the camera; its ground coverage is centred where the light ray through the camera lands, H/tan(e) away from the
  sun (40+ blocks at H 20, low sun), so ground toward the sun left it ~17 blocks ahead. Fix (Settings.shadowNearGround, Debug
  "Near shadows follow the ground"): Shadows.prepare centres the near cascade's anchor on the MOTION_BLOCKING heightmap under
  the camera (min camera y, at most 48 below; camera y if the chunk isn't loaded or the heightmap is above the camera).
- C2's far-map fix (shadowFarFix) stays; unverified whether it shows step shadows past the near map (ask Jack to flip it).
- Ask Jack to retest at the same terraces, high up, facing the low sun; flip "Near shadows follow the ground".
- 23:50: water fix pushed (main 0cc9a2e, CI green run 43322492829, jar sha1 388bef08...). NOT in mods yet (game was running):
  it waits as `instances/Imagine metal/imagine-metal-c2b-next.jar`; copy it over the mods jar once the game is closed.
  Jack's 23.41.20 / 23.41.23 (under water at night): a dark murky ring around the see-through window when looking up. Cause:
  terrain.metal's faked total internal reflection (surface from below past a steep angle) painted the surface opaque in the
  water fog colour x0.2. Now imWaterFog's colour (Settings.waterSurfaceFog, Debug "Water surface from below: no dark ring",
  env.w bit 16). Not bloom fog (Jack checked).
- 23:50: water fix DEPLOYED (mods = sha1 388bef08..., main 0cc9a2e). Copy kept as `imagine-metal-c2b-water.jar`.
- OPEN, shadow pop-in case 2 (Jack 23:48, screenshots 2026-11-12_23.48.17 at 299 79 -341 and 23.48.22 at 292 79 -354, both
  facing north, low sun from the west/north-west; 23.48.30 at 281 78 -375 shows shadows fine): terraces 9-20 blocks ahead
  (blocks ~55 px tall in a 720-line copy) show no cast shadows; after moving ~13 blocks closer the same terraces have them.
  NOT the C2b cause (the camera is at the terrain's height there). Ruled out so far: near-map coverage (y' = world z +-25.6
  around the anchor, x' ~ -cos(e)*dy within +-14 for terraces up to 15 above); solid-section occlusion in the shadow cull
  (off: needs -Dmcopt.own.solidOcc); list overflow (listCap >= records in use). Suspects: casters missing from the near map
  (OwnTerrain.shadowCull / own_cull facing test: with the sun path in the x-y plane L.z = 0, so for z-facing units the
  "light point" z is the camera's), the near map kept a frame (shadowNearAlternate), or a receiver-side cut. Next: at that
  spot use Debug view "Shadow maps" and flip "Near shadow every 2nd frame" / "Far shadow cache"; consider logging the near
  cascade's unit count.

## 2026-11-12 23:15, Build C2 DEPLOYED (main f48edd7, CI green run 43316738527). NOT tested by Jack yet.
- mods folder = C2: jar sha1 9e558ce5..., 1523792 bytes. Fallbacks OUTSIDE mods in `instances/Imagine metal/`:
  `imagine-metal-c1.jar` (C1, sha1 e45f9981...), `imagine-metal-b2.jar` (b623f298...), `imagine-metal-b1.jar`. Swap: quit the
  game, copy one over `minecraft/mods/imagine-metal-0.1.0.jar`.
- MetalFX: still only on branch `metalfx`, NOT deployed (needs Jack's OK).
- Every C2 change has its own switch in Settings > Advanced > Debug (not saved; `-Dimaginemetal.<name>=false`): shaftsFull,
  plantShadows, shadowFarFix, petalsStill (rebuilds chunks), levelGuard, entityJitter. Benchmark has C2 steps + two water steps.
- Nothing in C2 was seen running. CI compiles only.

### Jack's C1 test (13:36-13:56) and his requests for C2 (17:53)
- Shafts: he did NOT want a cap, only the whiteout fixed. Beams under real canopy and at sunrise as strong as B2.
- Short grass / flowers must CAST shadows (correction of the C1 notes below, which said he might not want them). The old
  problem was a bug ("ghost copies": dark see-through copies beside the grass), not shadows; they had been turned off instead.
  Reference: his photo of Reimagined grass shadows on a path (soft, lighter, dappled, attached to the plant).
- Tall grass shadows "look off" (13.56.07 at 376 64 -66): dark, smeared, blocky.
- Shadow pop-in (13.36.58, 13.37.00, gravel beach near 1108 82 -771): cast shadows end at a line ~25-30 blocks out.
- Pink petal stems sway, heads don't (13.49.19): make them still.
- Ghost slabs after respawning from the End (13.43.38, -6 110 -18): yellow stretched slabs over a desert village.
- Pig's eyes look and move weird (13.55.18).
- 36 fps at 101 67 -62 facing east over a river (13.54.52), 41 at the pond 185 63 -47 (13.55.18).
- Desert look, clouds, blood, whiteout: no clear verdict yet ("maybe its not terrible idk" about shafts/whiteout).

### What C2 does, and the cause found for each
1. Shafts (shaftsFull): C1's ceiling (ImFrame.env.z 0.35, bloom.metal) removed, and the canopy strength back to B2's step
   (C1's ramp 0.7..0.93 on the eye's sky light weakened beams under trees, where the sky light is often 11-13). The whiteout
   fix kept = C1's "strong mode only for rays through shadow"; it was keyed on env.z > 0, now its own flag: env.w bit 1.
2. Plant shadows (plantShadows). Cause of the old "ghost copies": the receivers' normal offset and slope bias are sized for big
   faces (near map: 1.5 texels x up to 2.2 grazing, plus k=2 slope bias; the casters also get a 2-texel raster slope bias), which
   push a half-block plant's shadow off its foot: detached, sharp, beside the plant (and, while mob/contact shadows were on, the
   screen-space trace caught the waving plants). Fix: plants (quads that wave but aren't leaves 1111 or vines 0101; tall plant
   tops now get corner pattern 1010, Materials bit 6 -> OwnQuads/OwnTerrain, terrain.metal waves 10 whole) go into their own
   slice 2 of the shadow map, drawn right after the near cascade from the same cull list (cutout list only; shadow.metal
   ImShadowFrame.reserved.x = 2), swaying with the same breeze as own_vs (reserved.y = GameTime). The main maps get everything
   but plants (reserved.x 1). Receivers (lighting.metal imPlantShadow): the ground isn't in slice 2, so offset only 0.01 along
   the normal; a plant receiving is moved 0.25 toward the light past its own caster; 4 rotated taps 2.5 texels out; 70 % dark at
   most; near only, eased out 18..28 blocks. Valid flag env.w bit 2 (Shadows.plantsValid()).
3. Tall grass: same as 2 (its top half was a hard caster in the main map, as dark as a wall, with the near map's sharp filter
   stretched by a low sun).
4. Shadow line (shadowFarFix). Proved from code + log: the far map IS drawn (log "far cascade drawn on N of M frames", N>0
   every 10 s) and the cull lists can't overflow (listCap >= records in use). The line is the near map's edge (32 blocks
   half-width, faded 0.8..0.92 = 25.6..29.4 blocks across the light). Past it the far map's receivers used a normal offset of
   1.5 far texels (0.375 block) x up to 2.2 = up to 0.83 block, plus a slope bias (0.75 block at slope 4): the ground was looked
   up nearly a block above itself, so 1-block steps, fences, bushes cast nothing there. Fix: a quarter of that offset, 4 taps in
   the receiving face's own plane (each its exact depth), constant bias 0.00004; the casters' 2-texel raster bias covers acne.
   Expect the far shadows still a bit shorter than near ones (raster bias = 0.5 x slope blocks there). Watch for stripes/acne
   on far ground at sunrise; if so raise biasFar or the offset factor 0.25 in imShadowX.
5. Petals (petalsStill): pink_petals / wildflowers return wave 0 (their flat heads have no corner above mid-height).
6. Ghost slabs (levelGuard). Cause: vanilla resets level render data a frame after setLevel and makes a new
   SectionRenderDispatcher; compiles of the old one (the End) finishing after that still called setSectionMesh ->
   OwnTerrain.published(index...). Our table is keyed by the section INDEX, so an End section landed in the new level's slot and,
   over empty air, was never overwritten (OwnSolidExtractorMixin's disposeAll at setLevel runs before those late compiles).
   Fix: OwnRenderSectionMixin drops publishes whose dispatcher isn't levelRenderer.sectionRenderDispatcher(); logs
   "[Imagine Metal] level guard: a section of the last level dropped (at x y z)" (first 20). NOTE: disposeAll's own line
   ("mcopt-own reset") uses printf, which Minecraft's stdout redirect doesn't log, so it never shows in latest.log.
   The stretched look is probably the stale section's exactPos indices read against the renewed table (table full: "4096 of
   4096, ~1M rounded").
7. Pig's eyes (entityJitter). Cause from code: the TAA (bloom.metal im_bloom_taa) reads the frame at uv + jitter, undoing the
   terrain's sub-pixel offset; entities had none, so the TAA shifted them by the jitter every frame (up to 3/4 screen px at
   67 %), and tiny high-contrast details (eyes) swam. Fix: EntityShade.patchVs adds the same offset after SPIRV-Cross's y flip,
   in the level pass only (imOn.x 1; imOn.zw = ImFrame.taa xy, mcown.m mc_im_entity_bind, bit 8). Not covered: pipelines
   EntityShade skips (eyes layer, text, beams). Moving mobs still have no motion vectors (history clamp only).
8. FPS at the river: per-pass log at 13:54:37-13:55:17: translucent (water) 6-9 ms vs 0.1-0.4 ms elsewhere, terrain 9-11 ms
   (autumn forest leaves) vs 7-8, shadow maps 0.8-1.6. So the water pass is the extra ~8 ms. Inside it: forward imShade per
   water pixel plus the screen-space reflection trace (11 steps + 5 refinements within 64 blocks) and waves. Benchmark steps
   added to split it: "water and glass reflect only the sky (no screen trace)" (Settings.waterTrace, env.w bit 8) and "water
   waves off". Asked Jack to run the benchmark at 101 67 -62 facing east.

### Not changed (noticed, not asked): vines don't sway on the OwnQuads mesh path (wave 3 maps to corners 0 there; OwnTerrain's
path gives 0101). Ask Jack before touching.

## 2026-11-12 13:20, Build C1 (superseded above where they differ: the shafts' ceiling is gone in C2; small plants DO cast
## shadows in C2, Jack wants them)

### START HERE: 2026-11-12 13:20, Build C1 DEPLOYED (main), NOT tested by Jack yet. Nothing in it was seen running.
C1 = commits dafc331 + 4f73207 (CI runs 43214172540 and 43214849324 green; the jar in mods: sha1 e45f9981..., 1518996 bytes,
deployed 13:09 with the game closed). Fallbacks, both OUTSIDE mods, in
`instances/Imagine metal/`: `imagine-metal-b2.jar` (B2, 76b21cf, sha1 b623f298...) and `imagine-metal-b1.jar`. Swap: quit the game,
copy the file over `minecraft/mods/imagine-metal-0.1.0.jar`. MetalFX: still only on branch `metalfx`, NOT deployed (B3, later).
CI can only compile: every C1 change is logic nobody has looked at in game. Each has its own switch in Settings > Advanced >
Debug (none saved; `-Dimaginemetal.<name>=false` starts with it off), so Jack can compare and a bad one can be turned off live.
### What C1 changed (the six items of the project doc "C1 - next build", which replaced the plan below)
1. WHITEOUT (`shaftVeilFix`, Debug "Shaft veil fix"). ImagineFrame.shaftCover from a ramp on the eye's sky light (0 at >= 0.93,
   1 at <= 0.7; was a step at 0.97). bloom.metal imbShaftsNow: imbVlMult() is the old multiplier as a function; the "something
   overhead" strength counts only by how much of the ray's map-covered part lies in shadow (mapLit / mapAll: none below 8 %, all
   from 40 %), else the open-air strength (vlPlain); then a soft ceiling on what one ray adds, ImFrame.env.z = 0.35
   (v = c (1 - exp(-v / c)), every mode but under water). The ceiling also dims the strongest sunrise shafts and forest beams: if
   Jack misses them, raise env.z (ImagineFrame, "the light shafts' ceiling") before touching anything else.
   NOT confirmed that the whiteout was the shafts: if a veil survives, Debug > "Air pass" off, then Light shafts off.
2. BLOOD (`decalBias`, "Blood splat depth fix"). Simple Blood's ground splats are RenderTypes.entityTranslucent(its atlas)
   (read from its SurfaceRenderer.class), i.e. `minecraft:pipeline/entity_translucent`. MetalPipeline.imDecal for that and
   `_cull`: biasSlope() +1.5, biasConstant() +4 toward the camera (reversed Z: positive), only while TAA and its offset are on.
   Why slope: the terrain's jitter moves its depth by at most the surface's depth slope per pixel. Every translucent mob part
   gets the bias too (harmless as far as I can tell). The splats still aren't jittered (edges soft): that is B3.
3. FAR FLICKER (`taaFarSteady`, "TAA steadier far away"). im_bloom_taa, p.taaPrev.z (Bloom passes on = 2): farS =
   smoothstep(48, 144, distance) x still: box +0.75 spreads, 40 % of the history's out-of-box part kept, history share up to
   0.965, takeover x0.4; sharpen x0.4 far away always. Risk: faint trails behind far moving things for a few frames.
   Debug > "Near shadow every 2nd frame" (Settings.shadowNearAlternate) added for the village beam/dapple flicker suspect.
4. SHADOWS. Range default 256; Config.migrate moves a saved 160 up once (file version key `imaginemetal.configVersion=1`,
   written at the next save). Shadows.depth(1) = max(256, 1.5 x range) (corners of the square fell out of the depth range);
   FAR_MOVE max(8, range / 16); FAR_MIN_GAP_NS: at range 256 the kept far map is redrawn at most every 200 ms (0 at 160): one
   far drawing at 256 is ~2.5x the casters (estimated ~14 ms in the jungle, NOT measured), a low sun and loading chunks asked
   for it every other frame. Far shadows therefore tick up to 5x a second at dusk; the near map is not held back.
   `shadowSoftFar` ("Soft far shadows"): lighting.metal imShadowX: 5 reads of the far map on a disc of 2 blocks in the face's
   plane (rotated per pixel/frame) find the blocker's distance along the light; radius = distance x ImFrame.ambientDown.w (0.03),
   up to 2 blocks; from one far texel up an 8-tap disc replaces the 4 fixed taps; inside the near cascade it takes over from a
   radius of 0.6..1.5 blocks (casters 20-50 blocks off). Edge fade 0.8..0.98 (was 0.9). Known side effect: a soft dark band at
   the sunlit foot of tall cliffs (taps inside the cliff count as shadowed). Cost: 5 reads per lit pixel + 8 compares in penumbrae.
   `shadowOpenAir` ("Daylight under sky islands"): READ THIS, it changes the diagnosis in the 12:20 section. The "near-black,
   hard, block-stepped shadow" (01.06.59) is, as I now read the shot, not the sun's shadow (at sunrise that lies hundreds of
   blocks away) but the island's footprint in Minecraft's SKY LIGHT: 15 blocks of falloff, then 0, where `sun *= smoothstep(0,
   0.07, sky)` cut the sun with a block-stepped edge and the ambient is 0. C1: where sky < 0.45 and the player isn't
   underground, imShadowX's openAir (lit, or blocker 24..64+ blocks off) lets the sky's light (x0.8) and the sun back in
   (ImFrame.ambientUp.w). And ImagineFrame.highCover: first block above the eyes >= 16..40 blocks up AND full sky light at eye
   height within 128 blocks => not a cave (cave grade, shaftCover). Under the middle of an island wider than that it is still
   "a cave", as before. NOT verified in game; caves seen from outside may get some extra light.
5. DESERT (`desertLook`, "Desert look"; direction from Jack's reference, screenshots/reference/reimagined-desert-2026-11-12.png:
   sun-facing sand (227,199,148), lit tops (190,175,150), shade (84,88,76); ours at noon: lit (207,194,141), shade (133,131,114)).
   Haze.LOOK_DRY_C1 (sun 1.07/0.99/0.86, shade colour 0.5/0.68/1.15 pulled 0.5), ambient x(1 - 0.22 dry) in ImagineFrame,
   ImFrame.grade.w = Haze.dry: imGrade's vibrance boost x0.33 on beige, sand albedo saturation 1.3 -> 1.18 and x(0.955, 0.905,
   0.925). Estimated: lit sand ~(210,189,144), shade ~18 % darker. The big part of his "samey" is item 4 (cast shadows to 256).
6. CLOUDS (`cloudsLate`, "Clouds behind the land"). Sky.drawAfter leaves them (latePending) when Reflections.underWillRun();
   Reflections.captureUnder hands sky state, target, rot and the inverse clip to imssr.m im_under_clouds; im_under_capture draws
   them after the air pass and before the colour copy (imsky.m mode 3, sky.metal im_clouds_late_fs: cloud alpha x
   saturate((terrain distance - cloud distance) / 4), aurora on sky pixels only). Not drawn for 20 frames running => back to
   the sky pass for the session (log line). Log: `clouds on (after the opaque world, ...)`.
Benchmark: new steps "shadow range 160 (as before C1)", "soft far shadows off (C1)", "daylight under sky islands off (C1)",
"clouds drawn before the land (C1 off)", "shaft veil fix off (C1)", "TAA steadier far away off (C1)".
### Open questions for Jack / not done
- 12:36:40 "uhmm why no shadows here??" (12.36.41: short grass on a meadow): NOT a bug as far as I can see: short plants were
  made to cast none on purpose (shadow.metal im_shadow_vs: "a ghost copy beside it"); tall grass casts. Asked him if he wants them.
- 01.03.44 pale streak over the river: still to ask. C2 (performance) and B3 (jitter for everything, MetalFX): not agreed yet.
- ImFrame's free slots are now used: ambientUp.w, ambientDown.w, grade.w, env.z. Free: env.w. Size still 928.

## 2026-11-12 12:20 screenshot review of the B2 test (before C1; superseded above where they differ)
### Diagnoses (screenshots 00.53 to 01.15, chat notes in latest.log)
- WHITEOUT (01.07.17..33 ocean under a Terralith sky island; 01.09.33..45 forest; pink veil in 01.06.55): most likely the light
  shafts' "something overhead" mode (Bloom.shafts / ImagineFrame.shaftCover), old code. shaftCover went to 1 whenever the sky
  light at the eyes was < 0.97; then intense = 1: full strength at any time of day, and over open ground/sea the maps say "lit"
  along the whole ray: a uniform veil. Evidence: in 01.07.17 the sky is whited too (the air pass skips sky pixels).
- FLICKERING (00.57.44..49, village): Jack's correction: it was DISTANT blocks, he thinks the TAA. Between 00.57.49 and .49_2
  the light-shaft beams on the wall and the leaf-shadow dapples also change shape (suspects: near shadow map every other frame,
  the shafts' noise).
- BLOOD (01.14.50/.57): Jack's correction: whole splats vanish and come back; caused by the TAA (he checked).
- BLUR left side (00.53.01) and bamboo (00.55.15): TAAU at 67 % on sub-pixel leaf texels / thin stalks. Fix: B3 / MetalFX.
- "mountain looks like baby vomit" (00.58.27): palette + no cast shadows that far. "no shadows that far" (01.03.15): range 160.
- clouds see-through (01.06.01): drawn before the terrain without depth. sky island shadow (01.06.59): see C1 item 4.
### Why B2 reached 58 fps, not 75-90
- Terrain = a + b x pixels: 67 % -> 11.7 ms, 100 % -> 14.26 ms => b ~2.1 ms, a ~9.6 ms that does NOT depend on resolution: the
  G-buffer fill on tiny/overlapping leaf triangles (geometry, alpha test, imMaterial), not per-fragment lighting.
- Generated normals 3.4 ms (the bake saved ~0.5: imMaterial still runs per fragment). Leaf depth pre-pass off: +0.7.
### C2 (performance; NOT agreed, ask when C1 is tested)
Leaves solid beyond ~32 blocks (OwnMesher's cutoutLeaves / forceOpaque switch, per section by distance with hysteresis);
generated normals for axis-aligned faces moved into the lighting pass; leaf pre-pass off by default; benchmark steps that
split the G pass. Estimate given: 3-6 ms, flagged as a guess.

## 2026-11-12 11:50, Jack TESTED B2 (00:32-01:15): it works, 58 fps (superseded above where they differ)
B2 is still what's in mods (76b21cf); B1 jar still in the instance folder; MetalFX still only on branch `metalfx`.
### B2 results (latest.log of the 00:32 session; jungle spot benchmark, RD16, world 1158x724 -> 1728x1080)
- Log: `generated normals baked: 2395 textures (72 worked out live), 4096x2048, 210 ms`; `deferred lighting on`; `air pass on`.
- Everything on 58 fps / 17.3 ms (Build A: 47 / 21.3). Estimates had been ~60 for B1 and 75-90 for B2: B2 fell SHORT, say so.
- deferred lighting off: -3.8 ms (45 fps) = what B2 buys (hoped ~9). far shadow map not kept: -2.9 ms (50 fps). near shadow map
  every frame: -0.3. air pass off: +0.2 (i.e. the air pass buys nothing measurable with deferred on). parallax off -1.3 (noise?).
- Still costing: shaders off +8.8 (124 fps); render distance 8 +4.1 (78 fps); generated normals +3.4 (the bake did NOT make
  them cheap: look at why, imMaterial runs per G-buffer fragment incl. the tangent frame maths); sky and air +2.1; shadows
  +1.9; coloured light +1.4; PBR +1.2; TAA +1.1; render scale 100 %: -6.9 (41 fps).
- Passes: terrain 11.8 of 18.7 ms GPU (still the bulk, and now the G-buffer + lighting are one number), shadow maps 1.6,
  taa 1.2, resolve 0.8. So the terrain pass is still ~12 ms with lighting once per pixel: the G-buffer fill itself
  (own_fs_g on small leaf triangles: texture sample, imMaterial, alpha test) is the next thing to measure and cut.
### His notes after the benchmark (chat line, then the screenshots taken right after it; READ THE SCREENSHOTS FIRST)
- 00:52:58 "wtf is going on on the left side look at that blur": 00.53.01, 00.53.02
- 00:55:14 "blur in bamboo": 00.55.15, 00.55.16
- 00:57:43 "flickering": 00.57.44, 00.57.45, 00.57.45_2, 00.57.49, 00.57.49_2
- 00:58:26 "the mountain looks like baby vomit": 00.58.27
- 01:03:14 "no shadows that far looks alil off maybe is there a cheap fix or skip?": 01.03.15, 01.03.44
- 01:05:59 "stuff above clouds can be seen through": 01.06.01 (clouds are a blended sky pass drawn BEFORE the terrain, with no
  depth: known, see Build B1 notes)
- 01:06:53 "shadows from the sky island looks off": 01.06.55, 01.06.59
- 01:07:15 "whiteout", 01:07:24 "what happened", 01:09:37 "whiteout shit": 01.07.17 .. 01.07.33, 01.09.33 .. 01.09.45 (ten
  shots; suspects to check against them: the air pass's tone-curve inversion or border fade high up / far out, im_air_fs)
- 01:14:48 "blood flickering": 01.14.50, 01.14.50_2, 01.14.57, 01.14.57_2
Which of these are new with B1/B2 and which are old (blur = TAAU, known) is NOT sorted yet: compare with Debug > "Deferred
lighting" / "Air pass" off, and the benchmark doesn't cover them.

## Builds B1 + B2 as written (2026-11-11 23:45; superseded above where they differ)
State: mods folder = B2 (main 76b21cf, sha256 f494b26f...). Fallback = `instances/Imagine metal/imagine-metal-b1.jar` (B1, 7bda686,
sha256 9509c5a6...; OUTSIDE mods). Swap: quit the game, copy that file over `minecraft/mods/imagine-metal-0.1.0.jar`.
Softer switch first (no jar swap): Settings > Advanced > Debug > "Deferred lighting" off (live) or `-Dimaginemetal.deferred=false`:
B2 then shades exactly as B1 does.
NOTHING below was seen running: CI compiles the Metal shaders and the jar, that is all. Expect bugs in B2.
CI note: the main run for the B1 commit hung in GitHub's queue (never got a runner); B1's green build is the `b1` branch run
(43102310354, same commit), its jar taken from that run's artifact. B2 green on main (43102612022) = the dev release.
### What to check in the log first (latest.log)
- `[Imagine Metal] generated normals baked: N textures (M worked out live), 4096x2048, T ms` (resource load).
- `[Imagine Metal] air pass on (...)` once in a world (overworld, eyes in air).
- `[Imagine Metal] deferred lighting on (...)` ~20 frames into a world. `deferred lighting off ...` (stderr) = it fell back to
  forward by itself (pipeline could not be made): then B2 == B1 in speed, read the reason.
- benchmark at the jungle spot (/tp 1099.5 107 -55.5, RD16): was 47 fps on Build A. Estimates told to Jack: B1 ~60, B2 ~75-90.
  New benchmark steps: "far shadow map drawn every other frame (not kept)", "near shadow map drawn every frame", "air pass
  off", "deferred lighting off (... as Build B1)": that last one's delta IS what B2 buys.
### Build B1 (7bda686)
1. Far shadow cache ON by default (Settings.shadowFarCache). NEW: the near cascade every other frame
   (Settings.shadowNearAlternate, `imaginemetal.shadowNearAlternate`; Shadows.skipNear: the frame after it was drawn the same
   map serves, MATRIX[0] = its clip moved by the camera's move; far waits a frame when both are due). If Jack sees uneven
   frame pacing or shadows a frame late on fast flight: that switch. NOT done: "leaves solid in the far cascade" (it would
   darken far canopies: full shadow instead of dappled, and the far map is drawn ~1/s now anyway). Told Jack.
2. The air pass ("haze and sky colour once per pixel"): air.metal im_air_fs, drawn from imssr.m im_under_capture (the hook
   before the translucent pass, Reflections.captureUnder) in its own render pass on the HDR image, reading the depth copy:
   imAirFog + the border fade (imSkyColor) per screen pixel. The opaque terrain's fragments skip both when
   ImFrame.pbrPack.w = 1 (Reflections.promiseAir: ourFog, state exists, classic transparency, no debug view); imShade knows
   "opaque" by mat bit 16 (0x10000). Translucent keeps its own in-fragment air (drawn after). The picture is already through
   the tone curve there, so the curve is inverted per channel (TM_D = 1: x^a = c y/(1 - b y)), air applied, curve again; the
   grade re-applied only to the air's share; light past white (emission) just dimmed by T. Differences vs before, on purpose
   or accepted: mobs now get haze and the border fade; saturation of hazy distance may differ slightly. WATCH: distant colours,
   dawn mist, the edge of the render distance, lava/glow seen through haze. `imaginemetal.airPass=false` / Debug > "Air pass".
3. Baked generated normals: PbrAtlas.bake at atlas build reads each sprite's own png (same size as the sprite, else left
   unbaked = animated or resized), stores per texel the imGnDif to its 4 neighbours (RGBA8, 0 = unbaked), uploaded once
   (im_bevel_upload) and bound at texture 27 (the pack normal map's slot; a pack's maps replace it); ImFrame.grade.z = 1.
   imGenNormal reads that one texel; unbaked texels take the old path. 32 MB for his 4096x2048 atlas.
   `imaginemetal.bakedNormals=false`. Material params were NOT baked (imSurface is arithmetic on the tinted colour, no reads).
4. Seabed dark from above: looked at 17.05.57 / 17.06.04: hard block-edged dark area = sky light 0 (15+ blocks of water) where
   `sun *= smoothstep(0, 0.07, sky)` and the ambient cut everything. Fix in the mesher's light: OwnRegion.waterSky: a water
   block under open water (light rising 1 per block straight up to >= 12) gets surface - depth/4 instead of surface - depth.
   Needs chunks re-meshed (a restart does). `imaginemetal.waterSky=false`. Also changes the view from under water (brighter
   deep down): ask Jack if deep water is now too bright.
### Build B2 (76b21cf): deferred lighting in tile memory
- terrain.metal: own_fs_g (the G-buffer: c1 RGBA8 colour + flags(slanted, axis, fade), c2 RGBA8 texture + material byte,
  c3 RGBA16Unorm light 12 bit + torch lean 4 bit x2 and the octahedral normal, c4 R32F depth, 0 = nothing), im_defer_vs/fs
  (one triangle; discards where c4 = 0; place from depth x Deferred's inverse clip; imShade with ImPre; then imFogTail).
- lighting.metal: imMaterial() = everything that needs derivatives (face normal, generated normal, torch direction Lt, atlas
  texel); imShade(..., ImPre pre): pre.on false = computes it itself (forward: translucent, fallback), true = handed in.
  In deferred the per-texel numbers (coated, sculk) come from the world position in sixteenths, not the atlas texel.
- mcmetal.m: Enc.imG. mc_im_g_enter (end the pass, reopen with 4 memoryless attachments, cleared), endRender calls
  mc_im_g_flush first (the lighting draw), the three reopen paths clear memoryless attachments (so the uocc splits keep the
  G pass), mc_im_g_exit (flush, end, back to 1 attachment, reopen), mc_r_pipeline exits as a safety net.
- mcown.m: mco_draw enters at the first opaque kind (0,1,4,5,6) when imDeferOn and colour 0 is RGBA16Float (the HDR image);
  ownPipeline: G pipelines via ownStage with ownGStage (own_fs_g), write masks swapped; imDeferPipeline per feature set;
  any failure -> imDeferFailed, forward for good. mco_im_defer (per frame, from Deferred.frame at OwnTerrain's noteClip),
  mco_im_defer_end (OwnTerrain.drawOpaque's end, then restorePass).
- Off automatically: debug view, shaders off live, PBR pack in use, no HDR. PassTimes' terrain cuts don't happen in the G pass
  (mc_render_split refuses memoryless), so the benchmark's "terrain" split is one number with deferred on.
- KNOWN differences from forward: 8-bit colour into the lighting (banding possible in dark AO gradients); torch lean in 15 steps;
  cutout texels' alpha no longer reaches the TAA (only the waving 0.98 mark); coated/sculk patterns differ per texel.
- IF THE WORLD IS BLACK / GARBLED / UNLIT with B2: first Debug > "Deferred lighting" off. Likely suspects: the place from depth
  (sign of the jitter or y in im_defer_fs: shadows/reflections offset or swimming), a pass that draws between the terrain's
  opaque draws and Deferred.end (pixels left unlit: marker c4 lost), the flags/material bytes (wrong materials).
### MetalFX (branch `metalfx`, NOT merged, NOT deployed; Jack must test B2 first)
imbloom.m: Settings.metalfx (default on in that branch): motion pass (bloom.metal im_bloom_motion, camera motion only, from
depth + clipInv + prevClip, jitter removed) -> MTLFXTemporalScaler (taaCur + depth + motion -> fxOut RGBA16F screen size,
depthReversed, jitterOffset = sign x jitter uv x input size, motionVectorScale = sign) -> im_bloom_fxcopy onto the screen.
Falls back to our TAA if the scaler can't be made. OPEN: the jitter and motion sign conventions are NOT verified (4 debug
toggles in Advanced > Debug to find them in game); the input is display-encoded LDR, not linear HDR; mobs have no motion
vectors; jitter is still terrain-only (mobs unjittered). build.gradle links MetalFX. To deploy later: merge into main.
### Jack's A2 feedback this answers / still open
- performance ("think bro think"): B1 + B2 above. - "still blurry", "mobs pixelated and blurry": NOT touched (B3 / MetalFX).
- seabed from above: B1 item 4.

## Build B plan and A2 test (2026-11-11 17:30; superseded above where they differ)
A2 (606707a + CI fix) ran 16:54-17:07. Jack: "perfomance still ass, think bro think. how do we optimize??? what are we doing
wrong?? ... its for apple silicon only so we can use that to advantage". He is frustrated: wants a real plan, then results.
### His A2 feedback (screenshots 16.58.46 .. 17.06.45)
- "still blurry i feel, not as much shimmer but still a little"; "very blur fest" (river, 17.03.32); "mobs look pretty wrong
  n pixelated and blurry" (17.01.18): mobs aren't jittered but the TAAU treats them as if (known limit, see Build A notes).
- Shadow pop-in: "still fade in but its better much better". Leave the 30..96 band. Far shadow cache can go back ON.
- Water reflection: nothing wrong, he was only asked to look. Closed.
- "do shadows stretch this much irl" (17.05.32, low morning sun): yes, told him (length = height / tan(sun height)).
- "underwater still dark under certain point" + "when i look at the water": it is the view FROM ABOVE the water (17.05.57
  feet y 64.2, 17.06.04 eye just over the surface): seabed deeper than ~5 blocks is flat dark teal with hard block-stepped
  edges; the same sand from under water (17.06.00) is bright. My reading (NOT verified): the seabed's own sky light falls 1
  per block of water, and only with the eyes under water do the floors apply (skyF >= 0.22 etc., lighting.metal f.water.z);
  from above nothing lifts it, times the water's absorption. Fix idea: where the light is sampled for a face in water (mesher,
  mcopt/metal/own/OwnBlockRenderer or wherever the packed light comes from), take the sky light of the top of the water
  column, minus ~depth/4; then both views agree and it's smooth. The mob navy fog and the cave switch (A2) he didn't comment on.
### Why it is slow: the numbers (Build A benchmark, jungle spot, M1 8-core GPU, world 1158x724 -> 1728x1080, 47 fps / 21.3 ms)
- Passes (they sum to the frame here, so usable): terrain 13.7 (leaves' colour 8.0, other terrain 4.7, leaf depth 1.0),
  shadow maps 3.3-4.5, taa 1.1, resolve 0.7, shafts 0.6, clouds 0.5, reflection copies 0.5, culling 0.4, sky 0.4.
- Terrain pass at 100 % scale (1728x1080, 2.23x the pixels): 18.9 vs 13.7. So terrain = ~9.5 ms that does NOT depend on the
  resolution + ~4.2 ms that does. Shaders off the WHOLE frame is 7.8 ms at full size, so the 9.5 isn't vertex work: it is the
  heavy fragment shader run per small triangle (2x2 quads for sub-pixel leaf faces, helper pixels run the full shader) and on
  alpha-tested leaves. Render distance 16 -> 8: +4.5 ms, same story (and fewer shadow casters).
- Feature costs: shadows 4.5, generated normals 3.9 (imGenNormal: several atlas taps + maths per fragment), sky and air 3.0
  (imSkyColor per fragment for the haze colour), PBR 2.7, coloured light 1.3, far shadow cache ON would give 1.6.
- Gael's pack on the same Mac: ~150 fps with shading. His docs (on Jack's Mac, ~/minecraft-metal-shaders/docs/shader-bible.md
  E12, docs/research/apple-gpu.md 2.1): material data BAKED into atlases at stitch time (1 fetch per fragment), sky from a LUT,
  far cascade cached, and "single-pass TBDR deferred, memoryless G-buffer" as the Apple-specific design (his M3).
### Build B plan (told to Jack in this order; one test round per build)
B1 (small, safe, ~5-7 ms hoped): far shadow cache ON by default; air haze/sky colour out of the terrain fragment shader (a
  sky LUT, or the haze applied once per pixel in the resolve from depth); generated normals + material params baked into an
  atlas-layout texture at resource load (slot imPbrN/imPbrS exist: texture 27/28, labPBR path) so the fragment does one fetch;
  shadow casters: leaves solid (no alpha test) in the far cascade, near cascade maybe every 2nd frame.
B2 (the structural one): deferred lighting in ONE render pass on Apple's tile memory: terrain writes a small G-buffer
  (albedo+class, normal+light levels, params; memoryless attachments), then one full-screen lighting draw in the same pass reads
  it with [[color(n)]] (programmable blending). Lighting then runs once per screen pixel whatever the triangle count: kills
  the 9.5 ms resolution-independent part. Water/glass/translucent stay forward after it. Needs: Hdr/pass descriptor with extra
  attachments (mcopt MetalRenderPass / mcown.m pipelines), ownFsBody split into "material" and "light". Entities: either write
  the G-buffer too (EntityShade patch) or stay forward.
B3 (image quality at 67 %): jitter in the projection for everything (Gael's ShadeTaaCameraMixin way) so mobs are sharp, then
  either finish our TAAU (age + depth test) or MetalFX temporal scaler (Apple's, needs motion vectors: from depth for the world).
  half precision in the lighting maths where safe.
Also open: the seabed-from-above darkness (above). Estimates given to Jack: B1 ~47 -> ~60, B2 -> ~75-90 at the jungle spot,
flagged as estimates. DON'T promise more.
### Build A2 as written (for reference)
Jack's notes on Build A and what A2 did about them:
- "shit looks blurry", "blurry without taa [sub-pixel offset off] and shimmering with it", "leaves look jittery all around",
  "look how pixelated", "blur fest here fr". Measured on his 16.13.53 / 16.14.05 pair: offset ON was SOFTER than off (mean
  gradient 5.8 vs 6.6), so the TAAU wasn't adding detail. Signs re-checked by reading (own_vs flips y before the offset; uv runs
  with the unflipped clip): they are right. Cause, as I read it: the history was clamped to mean +- 1.0 sd of the frame's 3x3
  every frame (cuts the between-pixel detail back out, at a different place each frame as the offset moves), any clamping cut
  the history's share by up to 60 %, and waving plants (all leaves) kept only 35 % history: mostly this frame's jittered
  picture. A2 (bloom.metal im_bloom_taa): clip toward the box's middle along the line (hue kept), box = min/max AND mean +-
  gam sd with gam 1.25 moving, up to 1.5 still at 100 % and 2.0 still at 67 %; history share only cut from one box size
  outside (a >= 0.5 sat((outside-1)/2.5)); waving plants x0.35 (x0.7 scaled) only within ~10 blocks, none past 32; sharpen
  0.3 -> 0.5 at 67 %. WATCH: trails behind moving mobs (a note from before: "trail at 1.25"). If still blurry/shimmery: next is
  the jitter in the projection for everything (Gael's ShadeTaaCameraMixin way) and age-based accumulation + a depth test
  (GaelQuinta docs/dev/TAAU.md, on Jack's Mac in ~/minecraft-metal-shaders), or MetalFX temporal.
- "underwater coloring is still off": turtle olive from above the water (16.17.17), deep navy from under it 3 blocks away
  (16.17.22); fish navy silhouettes (16.20.59). Cause: Minecraft's own water fog is baked into the mob's colour before our
  light (entity.fsh apply_fog; water fog starts at -8 and ends at 96 x waterVision, 24 right after going under; navy). A2:
  EntityShade passes Minecraft's fog colour and amount (regex on main0's "constant Fog& x", in.sphericalVertexDistance /
  in.cylindricalVertexDistance; "float4(0)" if the shader isn't that shape), imShadeEntity takes it back out under water and
  lays the terrain's water fog over the mob (new lighting.metal imWaterFog, used by terrain.metal too: one formula).
- "underwter still goes very dark at a specific point not smooth at all": ImagineFrame.cave came from the sky light AT THE
  EYES, which falls 1 per block of water: 9-14 blocks down in open sea = "cave" = the cave grade. A2: with the eyes in water
  the sky light is read above the water column over the eyes (up to 64 blocks). Flooded caves under rock still count as caves.
- "water refleciton" (16.22.13, river): no words on what is wrong. Pale blocky patches in the reflection. ASK him.
- Pop-in: no comment from him yet on the 30..96 band.
### Benchmark, Build A, jungle spot (RD16, world 1158x724 -> 1728x1080): 47 fps / 21.3 ms (was 38 fps / 26 ms at 1440x900 100 %)
shaders off 129 fps (+13.6 ms); render distance 8: +4.5 ms (60 fps) (I had estimated 2-3: Jack was right to doubt, it's more);
shadows off +4.5; generated normals +3.9; sky and air +3.0; PBR +2.7; far shadow cache ON +1.6; coloured light +1.3; shadow
range 96 +1.1; TAA off +1.0; render scale 100 % -8.5 (33 fps at 1728x1080); leaf pre-pass off -4.2. Estimate had been ~50.
### Build A as written (for reference)
1. Pop-in: imShade's foliage softening band 26..40 -> 30..96 blocks (same 0.45 strength, still IM_MAT_FOLIAGE incl. grass
   blocks and dirt). If far canopy blotches come back or shadows still pop: next is leaves only / lower strength.
2. World-only render scale, live (Settings > Advanced > Speed > "Render scale", imaginemetal.renderScale, default 0.67; Jack's
   file already had 0.67 from the old experiment):
   - Hdr.begin: the HDR image is world(w, scale) x world(h, scale) (even, min 16) and the main target's DEPTH is swapped too
     (RenderTargetAccessor depth setters) for one that size; Hdr.end puts both back. Every game pass sizes itself from its
     attachments (RenderPassDescriptor's default render area; nobody calls withRenderArea), so the whole level draws small.
     The level's own clear pass (LevelRenderer lambda$render$0) clears the swapped pair. Off (scale 1) with Minecraft's
     improved transparency (its targets are made at RenderTarget.width/height and share the main depth) and without HDR.
   - imbloom.m: resolve into taaCur at the WORLD size, then the TAA pass writes the screen (dst) and the history at SCREEN
     size. The pass runs when TAA is on OR the sizes differ ("post"); taa.z 0 = no history: bilinear upscale of this frame.
   - bloom.metal im_bloom_taa rewritten as TAAU: per screen pixel, 3x3 of the world frame around uv + jitter, Gaussian
     (exp(-2.3 d^2)) reconstruction, variance box, history (now stored UNJITTERED at screen size, read at puv with no jitter
     terms), current weight scaled by how near the nearest sample is (0.3..1 scaled, 0.6..1 at 100 %), history weight up to
     0.93 when still with a scale, sharpen 0.3 clamped to the 3x3's min/max. im_bloom_resolve reads the HDR by uv.
   - Terrain texture detail: sampleNearest / sampleRGSS take lodScale = ImFrame.taa.w (the render scale): gradients / mip
     level as at full size. own_fs_alpha (leaf pre-pass) takes the same so its alpha test matches the colour pass (EQUAL).
   - JITTER WAS DEAD BEFORE THIS BUILD: ImagineFrame.update ran before Hdr.begin and asked Hdr.active(), always false there,
     so jitterX/Y were 0 and the "TAA" was a plain temporal blend. Now Hdr.usable(). So this build is the first with a moving
     sub-pixel offset: N = clamp(round(8 / scale^2), 8, 32) Halton phases, in WORLD pixels. Only the terrain is jittered
     (own_vs imJit); entities, sky, clouds, particles are not, so they sit up to half a world pixel off from where the TAA
     assumes: expect slightly soft mob edges. If Jack sees shimmer/wobble: Settings > Advanced > Debug > "TAA sub-pixel
     offset" (Settings.taaJitter, not saved) turns it off to check. Proper fix later: jitter in the projection (Gael's way).
3. Output resolution: imaginemetal.output (default 1080, restart; Advanced > "Output resolution*": Window size / 1080p /
   1440p). Resolution.target: framebuffer height = min(output, Retina height), width by the window's shape -> 1728x1080 on
   his 1440x900 screen (0 = points, the old behaviour). The old whole-frame renderScale experiment is gone (Resolution.SCALE).
   His guiScale is 4: at 1080 lines that is allowed (was capped to 3 at 900), so the HUD is ~11 % bigger.
   So default now: screen 1728x1080, world 1158x724 (0.84 MP; was 1.30 MP), i.e. his Gael setup.
4. Benchmark: "render scale 100% (every pixel drawn)" (a negative number = what the scale buys), and LAST "render distance 8"
   (sets Minecraft's option, restore() puts it back; 4 s settle, the reference window after it waits 12 s and isn't used for
   its delta). "Shaders off (live)" now also sets renderScale 100.
### First things to check with Jack (in this order)
- Does it start and draw at all (log: "[Imagine Metal] HDR on (1158x724, the world at 67 % of 1728x1080)").  If the world is
  black/garbled or the game crashes on entering a world: set imaginemetal.renderScale=1 in the config (then only the TAA
  rewrite and the jitter are new) and read latest.log.
- Hand / held item: drawn after the level into the real full-size depth (cleared by render3dHud before the hand). The F3 3D
  crosshair's depth integration (integrate3DHudDepth) now sees no world depth: ignore unless he notices.
- Sharpness still and in motion, mob edges, leaves (pre-pass holes = lodScale mismatch, look at own_fs_alpha), water
  reflections (they trace the world-size history), shafts/AO (half of the world size now: coarser).
- Glowing-entity outlines, rain/particles, clouds, End/Nether (all draw into the swapped target; none tested).
- fps at the jungle spot vs 38: estimate was ~50.
### Measured earlier today (jungle spot: /tp 1099.5 107 -55.5, RD16, 1440x900, both shadow experiments off; build afe4983)
- Benchmark: everything on 37-39 fps (26 ms). shaders off 131 fps; shadows off +6.3 ms; sky and air +4.0; generated normals
  +3.8; PBR +3.7; coloured light +1.7; far shadow cache ON +1.1 (41 fps); near view cull ON +0.2; leaf pre-pass off -6.0.
- Whole-frame render scale (HDR size from the log): 100 % 1440x900 = 38 fps (26.0 ms); 75 % 1080x676 = 50-55 fps (~19 ms);
  67 % 964x604 = 55-58 fps (~17.5 ms; Jack: "looks like shit", expected: no upscaler, HUD scaled too).
  Fit: frame = ~10.7 ms fixed + ~11.8 ms per megapixel. So ~60 % per-pixel, ~40 % fixed at 100 %. (I had told him "almost
  all per-pixel": too strong, corrected.) The fixed part: shadow-map drawing (~4-6 ms) + geometry.
- DON'T trust the per-pass GPU ms in absolute terms: with shaders off the frame is 7.6 ms yet the sampled passes say
  "terrain 16.29". The sampled frames run in a slower mode. Use fps / frame ms for conclusions, the passes only for ratios.
- Debug view "Shadow maps" (his 15.02.19 screenshot, experiments off): both cascades full of shadow detail far out. So the
  maps are fine and the pop-in is the foliage softening (smoothstep(26, 40) x 0.45 on IM_MAT_FOLIAGE incl. grass blocks) plus
  the low-sun lift. He hasn't said whether the far cache changed the pop-in (asked at 15:10, unanswered).
- His Gael instance (instances/metal shaders/minecraft/config/mcopt.properties): mcopt.shade.resolution=67%,
  mcopt.shade.output=1080, preset balanced. As I read it: world shaded at ~1158x724 (0.84 MP), TAA-upscaled to 1080p.
  Ours shades 1.30 MP. His "metal testing" instance also has mcopt.metal.framegen=true.
### Plan agreed with Jack (estimates I gave him at the jungle spot: 38 -> ~50 -> ~58 -> ~62-65 -> ~68-72 fps)
Build A: done above, one test round.
Build B: shadow maps cheaper (near cascade every frame is the cost; far cache back on once pop-in is settled); fog and sky
  from a LUT like Gael's skyLut; half precision where safe; normals and PBR cheaper at distance. Also from Build A's test:
  projection-level jitter (entities too) if mob edges are soft.
Later: Distant Horizons style far terrain (he wants render distance to matter), frame generation (MetalFX) as the last step.
He asked "all at once?": told him no, two builds, one test round each. "Lets optimize then": he wants Gael-like fps, our look.
Answer his questions BEFORE working when he asks any.
Tools note: Minecraft 26.3's client.jar is unobfuscated and downloadable in the cloud sandbox (piston-meta); `javap -p -c`
  on it answers "what does the game do" questions (used for RenderTarget, RenderPassDescriptor, render3dHud). No JDK 25 or
  Metal compiler in the sandbox: CI is the only compile.

## Overnight 2026-11-11 (written ~01:30; build db9ee42; superseded above where they differ)
- NOT DEPLOYED: Jack's Mac went offline (asleep) before the build finished, so the instance still has 6f65e15 (near view cull
  off, far cache ON). First thing next session: download the dev release jar, check it (unzip -p ... Benchmark.class | strings |
  grep 'experimental, off'), copy to instances/Imagine metal/minecraft/mods/imagine-metal-0.1.0.jar with the game closed.
- What db9ee42 has on top of 6f65e15:
  1. shadowFarCache OFF by default too (both shadow experiments off = shadows exactly as on 2026-11-10 21:30). Toggles for
     both under Settings > Advanced > Debug (not saved). Benchmark steps turn each ON ("... (experimental, off)"): a negative
     cost there is its gain.
  2. Debug view "Shadow maps" (Settings.debug 5, lighting.metal): red = near cascade, blue = far, purple = blend band, yellow =
     no map; bright = map says lit, dark = shadowed. The RAW map value, before the low-sun lift and the foliage softening.
  3. Render scale experiment: imaginemetal.renderScale (Advanced > "Render scale*", 100/85/75/67 %, restart). Whole
     framebuffer, GUI included (Resolution.scaled, WindowMixin), so menus and HUD get soft: only to MEASURE what fewer pixels buy.
- Pop-in, where it stands. Jack's 00:27 screenshots were on 6f65e15 (view cull off) and still pop, so the view cull is cleared.
  All three pairs: shadows near the camera, none further out, appearing on approach. Two of the three were at a low sun.
  PRIME SUSPECT now, and NOT new code: imShade's `if (cls == IM_MAT_FOLIAGE) sun = mix(sun, max(sun, open), 0.45 *
  smoothstep(26.0, 40.0, length(p)))` (added earlier for blotchy far canopy shadows; the class includes grass blocks, so it
  fades shadows on the GROUND between 26 and 40 blocks) plus the low-sun lift `sun = mix(sun, max(sun, open * 0.55), lowSun)`.
  Together, on grass and leaves beyond 40 blocks at a low sun, a shadow keeps about a quarter of its contrast: descending or
  walking toward it, it "pops in" across a 14-block band. Jack may simply never have looked for it before I told him to.
  TEST (2 minutes, decides it): with both experiments off, stand where shadows are missing at distance, switch Debug view to
  "Shadow maps". Dark shapes out there in the debug view = the maps are fine and it is the softening: then narrow it to leaves
  (not grass blocks; e.g. only the cutout layer) and/or stretch the band (26..40 -> something like 30..90), with Jack's eyes on
  it. No dark shapes = the maps really lack them: then it is casters/cull, and predates the cache. Then turn "Far shadow cache"
  on and see if anything changes: if not, the cache is innocent and goes back on by default (it was worth 2-4 ms).
- What Gael's pack does differently (read tonight: GaelQuinta/minecraft-metal-shaders 90d4f9e, metal/src/main/resources/mcopt/
  shade/terrain_own.metal, shade_common.metal, docs/dev/TAAU.md). Methods only; Jack doesn't want its look.
  * It does NOT shade every pixel: TAA upscaling with dynamic resolution is on by default (docs/dev/TAAU.md: preset "balanced"
    starts at 0.80 scale, range 0.67-0.90, holds 120 fps; "performance" 0.67). At 0.80 that is 64 % of the pixels, at 0.67
    45 %. Mip bias log2(scale) keeps textures sharp; sharpen 0.25-0.40. THE biggest difference. (Which base resolution his
    runs at on Jack's Mac, points or Retina, I could not check: Mac offline.)
  * half precision nearly everywhere in the fragment stage (half3 albedo, normals, light levels).
  * "lean variants": the terrain fragment shader is compiled in several versions with whole blocks #ifdef'd out by what the
    frame needs (overworld only, dry, no dynamic light near, camera above water). His comment: "the water shader is
    register-bound". Ours does this with function constants (IM_FC_*), and the opaque passes already drop the water/glass
    code (imKind is constant 0 unless CUTOUT_LIMIT < 0.2), so not much left there.
  * sky colour for fog and reflections from a small LUT texture (skyLut), not computed per pixel (our imSkyColor is ~40 lines
    of maths, but only runs for reflections; imAirFog doesn't call it).
  * no shadow lookup where sky light <= 0.3 (ours: always, then multiplied by smoothstep(0, 0.07, sky)). Candidate, small.
  * a much lighter lighting model overall (shadeLit2): no gamma-space light sum (ours: 5 vec3 pow per pixel), no generated
    normals, PBR only with a pack.
- What I did NOT do, and why: no blind cuts to imAirFog / generated normals / PBR. Reading them, earlier sessions already
  gated each (normals fade with texel size and read <= 3 texels; reflections trace only for smooth surfaces within 96 blocks;
  the grid's reads are skipped in daylight; fog skips < 6 blocks). What is left are 0.1-0.3 ms items that need the benchmark
  to confirm, and the benchmark needs Jack's Mac. And the "shade each pixel once" (deferred) plan is mostly moot on this GPU:
  Apple's hidden-surface removal already shades opaque pixels once, the leaf pre-pass does it for cutouts, and mcopt's unit
  occlusion culls the rest. So the frame is simply (pixels) x (a heavy shader). Levers, by expected size:
  1. fewer pixels: a proper WORLD-ONLY render scale with the TAA upscaling (Hdr.java already swaps the main target's colour
     for our HDR image during the level: scale that image and the depth, resolve up in Bloom's map into the 8-bit image; GUI
     and hand stay sharp). Tonight's whole-frame renderScale is only the measuring stick for it. Jack's call (softer picture).
  2. half precision in imShade (big edit, needs eyes: banding in dark gradients is the risk).
  3. the gamma-space sum's pows, the sky-light gate on the shadow lookup, fog per vertex: small, measurable ones.
- Jack, 00:26: render distance 8 doesn't raise the fps, 43 costs ~13: the frame is per-pixel. 00:32: wants Gael's METHODS,
  not his look. Wants answers before work when he asks questions ("can u answer questions first then work dude").

## Jack's 22:34 test of the shadow-map change (2026-11-11 00:30; superseded above where they differ)
- It worked on the GPU: "shadow maps" 3.0-5.9 ms -> 1.2-1.7 ms (3.1-3.5 while flying fast through the jungle); far cascade
  drawn on 20 of 507 frames hovering, ~130 of 480 flying. Jungle spot after /tp: 46-48 fps (was 39-43), GPU 21-24 ms,
  terrain 13.6-14.9. Forest: 55-64 fps, GPU 18-20, terrain 10-12.5. No benchmark run yet on this build.
- BUG he reported: "shadows popping in a bit" (screenshots 2026-11-10_22.34.36 and .39, hovering ~25 blocks up over a
  birch/spruce forest, looking down ~45 degrees). In .36 the right half of the ground has tree shadows and the left half none,
  with a straight cut through one birch's shadow; 3 s later (.39, moved and turned a little) all shadows are there.
  Two readings fit and I could NOT tell them apart: (a) the near view cull (Shadows.viewCull) leaves out casters for part of the
  screen in some views (its maths checks out in simulation, so the suspect is its inputs: ImagineFrame.camProj / camView may not
  be what I assumed); (b) the far map was empty there and the cut is the near cascade's edge (no mechanism found: it's redrawn
  at least every second). Acted on (a): shadowNearViewCull is now OFF by default (benchmark step turns it on to measure it).
  If the popping is still there on the next build it's the far cache: then look at shadowFarCache instead. To debug (a) properly:
  draw the cull rectangle / the near map on screen, don't reason about it again.
- Jack: render distance 8 doesn't raise the fps, 43 only costs ~13 fps. So the frame is per-pixel cost (terrain's fragment
  shader, 10-19 ms at 1440x900), not geometry or distance. NEXT is therefore old step 3, before the mob shadows: make terrain
  cheaper per pixel (imAirFog 4.2 ms, generated normals 3.5, PBR 3.0 in the 21:29 benchmark), or shade fewer pixels (there is
  no render scale yet: rendering below native and letting the TAA upscale would cut every per-pixel cost at once; mcopt's
  docs/dev/TAAU.md in GaelQuinta's repo is the reference), or the deferred split. Ask Jack before a render scale: it changes the look.

## Shadow maps made cheaper (fe99d1a; near view cull since turned off, see above)
- Step 1 of the 21:29 list, Java only (Shadows.java, OwnTerrain.setSlot/beginFrame0, Settings, Benchmark); no shader or native
  change.
- Far cascade kept (Settings.shadowFarCache, -Dimaginemetal.shadowFarCache=false): drawn again only when (a) its own light step
  turns (RUN_STEP x floor(range / 32): 0.005 at range 160, about once a second at noon, every 2-3 frames at a low sun), (b) the
  camera is 8 blocks (summed over axes) from where it was drawn, (c) a section in its box changed (OwnTerrain.setSlot ->
  Shadows.terrainChanged, with the frame whose section table first holds the change), (d) range changed / another OwnTerrain,
  (e) it's 1 s old (safety net). Never two frames running when shadowFarAlternate is on, so the worst case is the old cost.
  The far cascade now has its own light direction and basis (LIGHT[1], slightly off the near one's between steps).
- Near cascade: casters culled to what the view can see (Shadows.viewCull, Settings.shadowNearViewCull,
  -Dimaginemetal.shadowNearViewCull=false): the cull's box is the bounding rectangle, across the light, of the view's wedge
  (CULL[0]); the map's matrix and texel grid are untouched. Whole square when the view looks along the light. Checked against
  brute force in a script (20 000 random views, no receiver outside the box).
- Log every 10 s: "[Imagine Metal] shadows: far cascade drawn on X of Y frames". Benchmark has two new steps: "far shadow map
  redrawn every other frame (cache off)" and "near shadow casters not culled to the view" (both should come out SLOWER).
- To check in game: (1) standing still at noon the log should show the far cascade on a few frames in a hundred; (2) place /
  break a block 40+ blocks away: its shadow must follow within a frame or two (if it lags ~1 s, terrainChanged isn't
  firing: only the safety net is); (3) walk and fly: far shadows mustn't pop or vanish at the 8-block redraws; (4) turn the view
  fast near trees: no shadows missing at the screen's edges (if any, viewCull: try the flag off); (5) far shadows tick once a
  second at noon instead of gliding: if Jack sees it, lower the far step (prepare, farStep).
- If the frame still doesn't move: the near cascade is the cost (every frame), then try near casters capped by distance along
  the light, or the near cascade at half rate with reprojection.
- Then steps 2 and 3 of the list below.

## 21:29 jungle benchmark on d810a0d (same spot, RD16, 1440x900, M1 8 GPU cores)
- Everything on 39 fps / 25.4 ms (was 40 / 25.0). Shaders off 125 fps / 8.0 ms (was 136 / 7.3: the measuring now costs a little,
  see the event below). Shadows off 53 fps: -7.0 ms. Sky/air off -4.2, normals off -3.5, PBR off -3.0, shadow range 96 -1.8,
  coloured light off -1.6 (was -4.9), leaf pre-pass off +5.9. Mob shadows off -0.1 (Jack keeps them off: they look broken, below).
- The d810a0d cuts worked on the GPU: terrain 18.2 -> 15.8 ms (leaf colour 9.3 -> 7.9, solid + rest 6.7 -> 6.0, leaf depth 1.1).
  BUT the frame didn't get faster. Reading (likely, not proven): the frame is now held by geometry work running alongside the
  pixel shading, mostly the shadow maps (the jungle's leaves drawn twice more from the sun, cutout alpha-tested, both cascades,
  every frame). Evidence: shadows off -7.0 ms of frame; shadow range 96 -1.8; terrain's -2.4 ms GPU didn't show in the frame.
- With the event (sampled frames run alone, d810a0d) "shadow maps" now reads 1.2-10 ms, ~6-9 when the wait landed: its cost
  alone, where before it was mostly hidden under the previous frame's fragment work. GPU totals now 23-30 ms vs a 25 ms frame
  (the overlap is worth ~2 ms). One "generated normals off" window had idle 49 ms: a pipeline-compile hitch, ignore.
- Shaders-off check STILL fails with the event: GPU 17.3 ms at 8.0 ms, terrain 12.9. So it wasn't (only) frames running under
  it. Probably the GPU clocking down when lightly loaded (Apple DVFS), making wall time per frame meaningless there; not
  verified. Everything-on (GPU saturated) stays consistent, so use those. Consider dropping the event (it costs ~0.4-0.7 ms
  average fps in the benchmark) once the shadow-map cost is understood, or keep it only for a benchmark step.
- NEXT (in this order):
  1. Shadow maps cheaper: far cascade cached (redraw only when the sun has turned past a step, its snapped origin moved, or
     terrain in range changed; keep its world-space matrix and re-derive the camera-relative one each frame), near cascade
     every frame. Check Shadows.java / imshadow.m / OwnTerrain.shadowCull for caster culling against the visible receivers.
     Measure: "shadow maps" tag and the shadows-off delta.
  2. Mob shadows via the shadow map (entities, near cascade only, capped, no block entities), delete imContactShadow.
  3. Then terrain again (sky/air 4.2 in imAirFog per pixel; PBR 3.0; normals 3.5) or the deferred split if terrain stays > ~11.

## 21:00 jungle benchmark on 1d653de (timing now usable with everything on) and the cuts made from it (d810a0d, deployed)
- Everything on 40 fps / 25.0 ms; GPU total 24-25 ms = the frame (passes + idle add up; idle 0.1). Terrain's number moves with
  the frame for each feature (normals -3.6 vs -4.2, PBR -2.1 vs -2.1, leaf pre-pass +5.8 vs +5.8): trusted.
- Shaders off FAILED the check (GPU 18.1 ms in a 7.3 ms frame, terrain 16.4): with GPU headroom the next frames run under a
  sampled one. Fix in d810a0d: the submit after a lead/sampled one waits for it (MTLEvent, imt_cmd_begin). Re-check.
- Everything on: terrain 18.2 (timing step: leaf colour 9.3, solid + rest 6.7, leaf depth 1.15), shadow maps 1.4, resolve 1.2,
  shafts 0.9, reflection copies 0.8, taa 0.7, sky 0.5, light grid 0.4.
- Feature costs (fps): shadows 5.8 (incl. mob shadows 1.8: IM_FC_MOBSHADOWS needs IM_FC_SHADOWS), coloured light 4.9,
  normals 4.2, PBR 2.1, shafts 2.0, sky/air 1.7, TAA 1.2. They sum past terrain's share: register pressure of the uber-shader.
- Decision (told Jack): cheaper exact cuts first, deferred later only if terrain stays > ~11 ms. Done in d810a0d:
  coloured light = 2 hardware samples of filter-weighted copies (light.metal filt/open, textures 29/30, imBlockLightF; entities
  keep the 8-read imBlockLight); generated normals share the centre-texel read (1-3 reads, exact).
- Mob shadows look broken (Jack 21:15, and 19.04.47 "floating 3d shadow built up of disconnected blocks": a grey translucent
  stepped patch by a rabbit over sand and a grass block's side). Vanilla entity shadows are off (options.txt entityShadows:false),
  so it's our contact trace (lighting.metal imContactShadow): 8 fixed steps 0.2 block apart through LAST frame's depth, so the
  mob's silhouette lands once per step (stacked copies on whatever surfaces are there) and trails a moving mob by a frame.
  Plan: draw entities into the sun's shadow maps (as Iris/Reimagined do; last frame's entity geometry is fine) and delete the
  screen trace: right shapes, soft like the rest, and the trace's ~1.8 ms off terrain. Until then: mob shadows off in settings.
- Next candidates: contact (mob) shadows only near entities (needs entity spheres in ImFrame); far cascade every other frame
  (keep its world-space matrix, re-derive camera-relative); then the deferred split if needed.

## Pass timing rewritten 2026-11-10 ~21:00 (1d653de, deployed to the instance; needs a restart, then the benchmark in the jungle)
- Two faults in the old numbers: (1) encoders overlap and wait behind each other, so first-to-last spans summed to ~2x the
  frame; (2) the GPU runs frames into each other, so a sampled frame's first encoders waited behind the PREVIOUS frame's work:
  the frame's own span was 45-76 ms at a 28-31 ms frame, 41.6 ms at 7.4 ms with shaders off (20:31 log).
- Now (imtime.m): a sampled submit's encoders in GPU order (pre buffer, then main, each in encoding order); each booked from the
  latest end before it to its own end; gaps with nothing running booked as idle. The frame before a sampled one is a "lead"
  (timed, nothing booked, no pass cuts): the sampled submit starts counting where the lead's work ended. So passes + idle = span,
  and the span should now be <= the frame time. The perf/benchmark line shows "passes X + idle Y" next to the total.
- Also timed now: mcown's opCompute (uocc test), the pyramid base pass, mcmetal clears, the frag clear blit.
- Benchmark step "(timing) terrain cut into its parts" (right after the first "everything on"): on sampled frames the terrain
  encoder is cut between the leaf depth pre-pass (tag 19), the solid (terrain, 4) and the leaf colour (20) (OwnFrag.layer,
  PassTimes.terrainPart). Each cut stores/reloads the attachments, so its total is a bit high; it's left out of the cost list.
- Check before trusting: shaders-off step's "gpu" total must be <= 7.4 ms and terrain a few ms; everything-on total <= ~28 ms.
  If the total is still over the frame, the lead isn't landing (slots busy?) or completion order isn't queue order.
- Then decide step 2 (deferred G-buffer vs cheaper cuts) from: terrain solid vs leaf colour vs leaf depth, shadow maps, and the
  fixed passes (sky, shafts, ao, resolve, taa). Note: solid own_fs has no discard (CUTOUT is a function constant), so Apple's
  HSR already shades opaque terrain about once a pixel; with the leaf pre-pass the leaves are once a pixel too. A G-buffer only
  pays if shading per pixel is still the bulk AND quad overshading / register pressure is the problem, not overdraw.

## 20:31 jungle benchmark on 20823cd (same spot, RD16, 1440x900): leaves no longer reported flickering
- Everything on 35 fps / 28.4 ms (was 28.5 fps / 35 ms at 18:58). Shaders off (live) 136 fps / 7.4 ms: ~21 ms a frame is ours.
- Leaf pre-pass works: "leaf depth pre-pass off" was 6.9 ms slower. Shadows off saves 6.9 ms, generated normals 2.9; everything
  else within noise (+-3 ms: "wet off" -3.5 and "glass reflections off" -2.6 are drift, not real costs).
- The per-pass numbers are NOT usable yet: they add up to ~54 ms while the frame is 28 ms, and with shaders off they still show
  terrain 23 ms at a 7.4 ms frame; present 13 ms and hand+gui 13 ms are impossible for those passes. An encoder's first-to-last
  stage sample includes time it waits behind the encoders before it (Apple overlaps and queues them), so spans overlap.
  Next: attribute by end-to-end deltas in submission order (each encoder = its last end sample minus the previous encoder's last
  end sample, across pre + main command buffers), which partitions the frame and can't sum past it; check that the parts add up
  to the span and that shaders-off terrain comes out around a few ms. Only then decide the deferred rewrite.

## 20:14 test of 2411150: leaves flickered with holes (sky through them, 20.14.32) and the pass timing read 200-600 ms per tag
- Fixed in 20823cd (deployed): VOut.pos [[position, invariant]] (preserveInvariance only covers outputs marked invariant; the
  pre-pass and the EQUAL colour pass link different fragment stages, so their vertex stages rounded differently). Timing: a slot's
  samples older than the GPU clock when it was taken are stale (stages that didn't run keep old values) and are skipped.
- If leaves still flicker: -Dmcopt.own.prepass=false in instance.cfg JvmArgs turns the pre-pass off.

## Pushed 2026-11-10 night (jar 2411150 deployed to the instance; needs a restart; not yet confirmed in game)
- FINDING: the cutout depth pre-pass was never on in game. OwnTerrain.PREPASS only reaches the base path's drawList; the indie
  profile draws through the frag path (OwnFrag, uocc + splitNear 216 + testLate), which had no pre-pass at all. So the 18:58
  benchmark ran with every jungle leaf layer shaded in full (alpha test defeats Apple's hidden surface removal).
  Now: OwnFrag.preMode / cutoutLayer: phase A's cutout depth (kind 6, own_fs_alpha, bias 1) after the unit test and before the
  far solid, then the cutout colour with depth EQUAL + the same bias; phase B the same (pre-pass, solid, colour). The near solid
  (before the test) is unchanged, and so are the a2Exact table draws. Live switch OwnTerrain.leafPrepass (Settings.leafPrepass,
  not saved, benchmark step "leaf depth pre-pass off"); -Dmcopt.own.prepass=false starts with it off. If leaves vanish or get
  holes: EQUAL mismatch, look here first.
- Per-pass GPU timing (native imtime.m, Java PassTimes): every 6th frame every encoder gets stage-boundary timestamps (sample
  buffer attachment 1; mcopt's trace keeps 0), booked under a tag. Our passes tag themselves (shadow, light grid, sky, clouds,
  shafts, ao, bloom, resolve, taa, reflection copies, present, culling = pre buffer); the game's are tagged from
  LevelRendererMixin (sky lambda, executeSolid = terrain, PreparedFrame.executeSolid = entities, executeClassicTransparency =
  translucent, executeOutline = overlay, after the main lambda = weather/particles, render RETURN = hand+gui). On a sampled frame
  the main pass is cut at each tag change (mc_render_split + MetalBridge.restorePass). Perf prints a second line every 10 s:
  "[Imagine Metal] perf: gpu X ms (...): terrain a, shadow maps b, ...". The benchmark logs "benchmark passes:" per step and
  ends with the everything-on pass breakdown in chat. -Dimaginemetal.passTimes=false turns it off. The old "gpu" number
  (command buffer start..end, 60-87 ms at 28 fps) is gone from the line.
- Terrain shading features as function constants (lighting.metal imFc*, 40-53; mcown.m ownFeat / mco_im_features / ownMakeFs;
  Java ShadeFeatures, every frame from Settings): shadows, pbr, normals, pbr pack, parallax, coated, mob shadows, glass sun,
  coloured light, held light, sky/air, wet, glass reflections, portals. Off = not compiled into own_fs / own_fs_tr; each keeps
  its runtime test too, so "on" is exactly the old shader. Pipelines are kept per set (OWN_MAX_LAYOUTS 160): first use of a set
  compiles (a hitch), later switches are instant. -Dimaginemetal.shadeFeatures=false keeps all compiled in. With Jack's
  everything-on settings this saves nothing by itself; it makes "off" free and the benchmark's per-feature numbers honest.
- NOT done: the deferred G-buffer rewrite. Decide it from the new per-pass numbers: if terrain is still most of the frame after
  the pre-pass, split imShade into a surface part (normals, pbr pack, parallax, coated, material) and a lighting part; G-buffer
  pass = surface part (cheap, helper lanes cheap), full-screen lighting pass = lighting part, translucent keeps calling both.
  The terrain draws inside the game's single main RenderPass (executeSolid + transparency share it): the G-buffer needs the
  main encoder ended after terrain (mc_render_split-style), its own attachments + the main depth, a lighting pass into the main
  colour, then the main pass reopened (MetalBridge.restorePass) for entities/translucent.
- Jack's 19:03-19:09 notes (not looked into yet): "ghost grass again" (19.03.51: sharp detached shadows of tall grass on sand),
  "weird shadow" (19.03.54), "floating 3d shadow built up of disconnected blocks" (19.04.47: a grey translucent block-shaped
  patch over a grass block's side, near a rabbit), "completely dark under certain point in water" (19.07.06), "am i supposed to
  only see this far out" (19.08.18), "rain could look better (the particles themselves)".

## Pushed 2026-11-10 (from Jack's 11-05 and 11-06..08 notes; not yet confirmed in game)
- Terrain alpha from the texture alone (vertex alpha x the fade mix hid the portal and copper torch, glass showed only tint).
- Water texture speckle calmed; under-water sun shadows soft; the pre-water depth (texture 26) for contact shadows.
- Contact shadows count only what the sun's shadow maps don't hold (imInShadowMap): no ghost "3D" plant doubles; thin lines
  (selection outline) need the screen 2 px either side to hold them.
- Light grid: extra spread steps after moves/changes (catchUp); moving lights (dropped light items, mobs holding lights, burning
  mobs) as seeds rewritten each frame (LightVolume.dynamicLights).
- Mobs: directional block light from the grid in front/behind the face, faint outline in the dark.
- Dimension change releases the old level's sections (OwnSolidExtractorMixin).
- Nether haze half as thick over lava, lit by it.
- Vertex light blended over the whole quad (imQuv/imQL, own_vs): the quad-diagonal seam (obsidian triangle, wall by a torch).
- Dry biome look: shade cool, not yellow (the "piss" desert). Day ambient 0.52 -> 0.42 for sun/shade contrast.
- Generated normals at 0.6 of the setting. Vines don't wave. Emitters glow only in bright parts (jack o'lantern).
- Lava a brightness ramp (crust red-orange, hot yellow), not pink. Armour shinier, torch highlight. Glint x1.25 (purple sword).
- Outline patch also matches lines_translucent and block_outline pipelines (style setting didn't change it).
- AO near only (fades 20-48 blocks). TAA: clip 1.0 sd, less history where rejected. Underwater fog hue held blue-green.
- Dawn mist halved, forward glow softer, soft ceiling on air glow (pink whiteout by the river at sunrise). Vignette 0.25.
- Light fog a^6 (a ball round a light, not a column of air).

- Later the same day: vibrance leaves near-grey alone (netherite sword read purple; it was unenchanted, not the glint);
  refraction water clearer (absorb 0.26, volume 0.09 x 0.72).
- 11-10 14:46 crash was ColorLight (colorlight-26.3 beta, NPE in its engine inside OwnFrapi meshing): Jack removed it. Keep it out.
- mcopt far terrain exists (-Dmcopt.lod=true -Dmcopt.lod.radius=100, JVM args in instance.cfg JvmArgs; DH disabled). Not yet
  wired to our lighting/haze/sky (mclod.m / columns.metal composite: CompFrame fogColor, skyLight, faceShade). Jack wants
  DH-like far view with low real render distance; next step after he tries it: feed it imAirFog + day colours.
- Test round 11-10 ~15:00: Jack screenshots with chat notes; read logs/latest.log [CHAT] lines.
- Jack wants to be told when to start a new chat (save usage); hand off via this file.

## Pushed 2026-11-10 ~15:40-16:00 (from Jack's 14:55-15:26 test round; jar 249fbbf deployed to the instance, not yet confirmed in game)
- ROOT CAUSE of "invisible" things: Reflections.captureUnder runs at the head of executeClassicTransparency while the level's
  render pass is open. mc_frame_cmd ended that encoder for the blit and nothing reopened it, so every draw after it went to a nil
  encoder (silently dropped): water, nether portal, translucent-layer blocks (Weskerson's 3D torches, copper torch: 26.x puts
  sprites with partial alpha in the translucent layer) and the selection outline. Fix: imssr.m reopens the pass
  (mcmetal.m mc_render_reopen, colours and depth loaded) and returns 2; Java calls MetalBridge.restorePass (rebinds pipeline,
  buffers, scissor, uniforms). If water/portal/outline still don't show, look here first (rpLog: -Dmcopt.metal.rpLog).
- Lava: ramp deeper orange-red, hot spots orange-yellow, emission 1.15 + 1.5 l^2 (white specks read "like acne").
- Emitters (jack o'lantern): emission 1.9 x smoothstep(0.45, 0.98) x l, colour deepened (pow 0.4) so the face keeps detail.
- Armour shine follows the texture's brightness, torch highlight much smaller (gold armour was one flat yellow shape).
- Ores: iron softer, ore glow x1.5 (was 2), ore pixels' highlight 1.4 (iron specks were white blobs).
- Gems' reflections tinted by their colour (amethyst cluster showed grey-white squares in a cave).
- Light fog a^8, 0.009, 7 steps, faded where the picture is bright (a thrown torch fogged a whole cave room). Cave bloom fog
  1.4 -> 0.6 (lush cave grey veil). NOTE Jack earlier asked for cave fog ("no cave fog"); this round he called the veil "fog
  issues". Ask what he wants: a dark Photon-like cave fog is probably it, not a bright veil.
- Night: MOON and NIGHT_SKY a quarter darker; nightBrightness now also scales the night sky/haze colours (nightUp/Mid/Down),
  at the lowest setting the night barely changed before.
- Low sun or moon: direct light x(1 - 0.35 lowSun), shadows at least 0.55 lit (Reimagined's shadowTime idea) ("shadows huge").
- Contact (mob) shadows: 8 steps over 1.6 blocks (was 10 over 3: rabbit's long smear); imInShadowMap also checks a ring a
  quarter block out (waving plants: ghost copies on the sand).
- Hand items: sky ambient 65 % neutral (netherite sword read purple from the sky's blue).
- FPS: water/glass reflection trace 11 steps x1.75 (was 18 x1.4); light fog 7 steps; shafts 12/8 steps (was 15/10).
- Shafts: shadow maps faded into each other at their edges (the moon's band).

## Second round 2026-11-10 16:25-17:00 (Jack's 16:28-16:48 notes; jar a2/latest deployed, needs a restart to test)
- Confirmed working by Jack in game: outline, torches visible, water drawn, light fog "way better", sunset "better".
- Water: told by its tint too (Streams' flowing water sprites aren't Minecraft's: were drawn as glass, grey); Reimagined colour
  turquoise; seabed through water brighter; refraction 0.02; foam only on shallow open shores, faint.
- Night sky line: the nebula's atan2 seam at due west (sky.metal), not the shafts.
- Translucent-layer flames glow (texel rule in ownFsBody). Lights glow only in their lit parts (copper bulb frame).
- Dropped items glow only for flame colours (red mushroom lit up). Lava redder, hot spots harder.
- Hand: no vibrance, block light half neutral (netherite sword maroon/purple).
- Caves: toe 0.42, AO x(1 + 0.6 cave), cave bloom fog darkened. Jungle shade olive not teal.
- Vines sway again (wave code 3 -> corner pattern 0101, small across the wall).
- Small ground plants (wave code 1) don't cast sun shadows: their sharp pale offset shadows were the "ghost copies".
- Armour as a metal (less diffuse, mirrored light weighted by texel luma squared); mobs/armour generated bumps from texture
  derivatives; mobs a soft sheen; mobs under water get our water fog.
- Clouds "gone": options.txt has renderClouds:"false" (Distant Horizons turned vanilla clouds off on 11-09). Jack to turn
  Clouds back on in Video Settings.
- FPS: still 35-80. Needs the in-game Benchmark (Settings > Advanced) by the sea and in a jungle to see per-feature costs.
- Open: "compat with texture packs generated pbr" (unclear: ask), mobs look weird under water (check after fog change),
  "waves look off / too symmetrical" (water style 1 ripple from the texture), sun drawn as a hollow square (not reported).

## Third round 2026-11-10 17:10-17:20 (Jack away; deployed, untested)
- Live shaders toggle: imaginemetal.Live (pause menu "Shaders: on/off" button; F8 via GLFW by reflection if present).
  Off = every live setting off (snapshot kept, restored on), ImFrame flags.z 0: imShade's plain lightmap-like path,
  entities vanilla. Settings.save() turns it back on first so the file keeps real values.
- Benchmark rewritten: Live.snapshot/restore of every live Settings field (reflection), ~27 steps incl. shadow range 96,
  normals, parallax, held light, water vanilla, sky/air, haze, wet, aurora, storm, stars, and "shaders off (live)". Results
  in ms per frame, sorted, top 10 in chat, all in latest.log ("[Imagine Metal] benchmark:").
- Resource pack / modded compat: translucent-layer opaque texels (texel.a > 0.95) shaded as normal surfaces, not glass;
  modded blocks classed by name words (MOD_METAL/GEM/STONE/WOOD) before their sound.
- Under water: fog and ambient a vibrant turquoise (0.08, 0.78, 0.82), not cold blue.
- FPS suspects not yet measured: LambDynamicLights (rebuilds chunk sections whenever a light moves: 154 "exactPos table
  full" rebuild lines in 20 min; our held light and moving-light seeds already do its job), EMF/ETF, sound physics (CPU).
  Next: read Jack's benchmark lines and cut the top costs.

## Fourth round 2026-11-10 18:00-18:15 (from Jack's 17:36-17:56 notes; deployed, untested)
- Jungle benchmark (17:55, cut short at "bloom fog" when chat opened): everything on 26.7 fps / 37.4 ms. Shadows off -6.6 ms,
  PBR -2.9, coloured light -2.8, generated normals -2.7, shadow range 96 -2.7; the rest within noise. No single feature: the
  cost is per-fragment shading x leaf overdraw. So: mcopt's cutout depth pre-pass is now ON by default (OwnTerrain PREPASS,
  -Dmcopt.own.prepass=false to disable), terrain library compiled with preserveInvariance for its depth EQUAL. If leaves
  flicker or get holes, that's the first suspect.
- F8 doesn't work on 26.3 (no org.lwjgl.glfw: the game isn't on GLFW). Pause menu button works.
- Lights: own block light on EMITTER/FLAME/DIM faces x0.3 (whole blocks glowing); emitter warm parts need max channel > 0.86.
- Brown mushroom (light 1-2) not an emitter. Lava: emission 0.55 + 2.6 l^3, ramp x(0.15 + 0.85 l^2) (texture visible).
- Foam off (shore vs wall can't be told from depth). Water hue pulled to one turquoise in all three paths (purple/blue seabeds).
- Under water: fog turquoise (0.12, 0.75, 0.7), thicker and darker with eye depth below y 60. Mobs' water fog x0.8.
- Held light gives a specular glint on glossy terrain; GEM smoother, its reflections/torch glint less dimmed (obsidian).
- Mob bumps 0.03 (0.06 noisy on husk/camel), sheen 0.15.
- Ghosts: waving-plant fragments output alpha 0.98; TAA keeps 0.35x history where the 3x3 has one.
- Shafts no longer cut when the eye's sky light < 0.07 (lush cave under its opening: beams vanished a step in).
- Nebula band axis (0.8, 0.2, 0.55): crosses the moon's path instead of lying along it; a bit brighter.
- Far canopy: foliage receivers past 26-40 blocks get shadows softened toward the sky (far map blotches).

## Perf diagnosis 2026-11-10 evening (Jack's 18:58 jungle benchmark, ~1099 107 -56, render distance 16, 1440x900)
- Shaders off (live) 108 fps / 9.2 ms; everything on 29 fps / 35 ms: ~26 ms a frame is ours. (It was believed the cutout depth
  pre-pass was on in that jar; it wasn't: see "night" above.) Jack: over 100 fps with shadows "when the mod was more basic".
- Per feature, after allowing for drift (aurora, stars and nether storm "saved" 3.5 ms each by day in a jungle: the one
  reference at the start was stale): shadows ~4-7 ms, our sky and air ~3-6, haze ~1-5, everything else 2 ms or less. No single
  feature explains 26 ms. Reading: the cost is the structure. imShade is a ~700-line uber-shader run inside the terrain (and
  translucent) fragment stage, every feature compiled in and switched by runtime flags, so register pressure and texture
  fetches stay high whatever is off; plus the always-on passes (HDR, sky, shafts/bloom chain, TAA).
- Benchmark is now A/B: an "everything on" window before and after every step, each step against their average (pushed).
- Old builds for a same-spot comparison (CI artifacts, Jack downloads them from the Actions runs while logged in; publishing
  releases from branches is blocked in Claude sessions): branch perf-check-nov05 (6f99f5c, run 42873033549, FPS 70-115 era)
  and perf-check-nov04 (89f72c9, run 42873033681, shadows and the basics). Delete the branches when done.
- Jack's instance JvmArgs now: -XX:+UseZGC -XX:+ZGenerational -Dmcopt.own.prepass=true (the flag is redundant: default on).

### Plan (start in a fresh chat)
1. Measure per pass on the GPU: MTLCounterSampleBuffer timestamps at stage boundaries for each encoder (shadow maps, terrain
   solid, cutout + pre-pass, translucent, entities, sky, shafts/bloom chain, TAA, resolve) printed in the perf line. The
   current "gpu" number (60-87 ms at 28 fps) is a command buffer's start-to-end and can't be trusted.
2. Deferred opaque lighting: terrain and cutout write a small G-buffer (albedo, octahedral normal + material byte, light
   levels + AO); one full-screen lighting pass shades each pixel once (Apple: keep it in tile memory with imageblocks if the
   pass structure allows). Translucent stays forward with a lighter path.
3. Function constants per feature so what's off isn't compiled in; split rarely-used paths (End, Nether, portals, parallax
   packs) into their own specializations.
4. Compare against the nov04/nov05 builds at the same spot before adding anything new.

### Jack's 18:57 look notes
- "Bamboo cooked", far terrain blurry/low-res and smudgy, terracotta one flat colour at distance (worse than his usual half-res
  Sodium Extra setup at the same 1440x900). Suspects: vanilla 26.x texture filtering RGSS (F3 "Filtering: RGSS"), TAA history,
  air haze flattening distant contrast.
- Overall "flat and mid, atmosphere missing": canopy reads one saturated green with no visible shadowing (91b78b1 softens far
  foliage shadows toward the sky; day ambient 0.42), no sense of depth/air. Needs a side-by-side with Reimagined after the
  rewrite, and Jack's input on colour grading (he asked to be asked).

## Still open after the first round
- Moon "line there??" (15.09.24): a faint vertical band from the moon straight down the sky, sharp left edge. Probable cause
  fixed: the shafts switched hard between the near map, the far map and the eye's brightness at each map's edge, and a map's
  edge is a plane along the light, a line through the moon on screen. Now faded (bloom.metal imbShaftsNow). Confirm in game;
  if it's still there, try bloom off / shafts off.
- "far away looks blurry/very low res even with TAA off" (15.00.58): likely air fog + RGSS texture filtering at distance.
- "this looks off" (15.24.53): canyon to a lush cave, black blocky shadow patches high on the cliff, teal tint on stone.
- Held torch barely lights a cave (15.14.30) though the log shows held light 14. Check imShade's held-light term in game.
- Mobs look flat (no PBR), zombie in the dark too bright/flat (15.16.22).
- FPS: 45-80 typical (gpu 25-49 ms in the perf log). Ask Jack to run Settings > Advanced > Benchmark standing still in a
  forest and by the sea: the per-feature costs land in the log; optimize the top ones.

## Still open (older)
- End portal vanishing when backing away; water refraction shifting the selection outline; "running water looks off";
  cave fog wanted ("no cave fog"); FPS over water.
- Jack's instance on 11-09: our jar renamed .disabled; GaelQuinta's metal-shaders and Distant Horizons added (both conflict
  with ours: only one Metal backend at a time).

## Fixed today (in the current jar, not yet confirmed in game)
- Water depth mode: the under-water copy was never taken (imssr.m skipped it while a pass was open). Now copies; thickness from
  depth ratio; absorption and murk by the biome colour.
- Nether wobble (underwater waver ran on the Nether's fog mode). Every biome's own daylight (Haze.look).
- IterationT End ported 1:1 (sky.metal it* functions, IT_NOISE = its noise.png), light (0.856, 0.276, 0.436), eclipse cycle.
- Light fog: trilinear reads of the light grid, strength 0.016 at a^4. Contact shadows: fixed 0.5 dither, stricter threshold.
- Outline patch no longer compiles lighting.metal in (it had stopped drawing). End portal surface back to vanilla's.
- Stems' glow halved; caves' floor light doubled; slight vibrance in imGrade.

## Open (from Jack's 15:18-15:43 screenshots)
1. Nether portal invisible (15.23.34): the portal quads don't show at all; particles do. Was fine a few builds ago. Suspects:
   own_fs_tr changes, the under copy ending the pass, or the portal not hitting the translucent path in 26.3. Needs a check
   with portals on/off and waterFx on/off.
2. Water with "Water refraction and foam" on still looks wrong (no labelled screenshot yet). Check the capture actually runs
   (log a line once from Reflections.captureUnder), and that thickness isn't ~0 (foam everywhere).
3. Seabed seen from above (waterFx off): dark straight-edged patches (15.39.28) and bright wavy lines (the water texture?).
4. Stained glass on grass (15.37.39-51): tinted patch fine; a stale grey patch stays after the glass is removed (glass seeds
   in LightVolume not cleared on block change?).
5. Mobs: dark in caves, flat; want normals and light from farther lights. Dropped light items should light (dynamic light).
6. Copper torch invisible when placed (15.32.38).
7. "Lighting loading in weird" in caves (15.31.41): light grid filling in blotchy as you move.
8. Phantom chunks in the End (15.27.34: floating end-city pieces). mcopt own terrain.
9. End portal disappears when backing away (15.24.24): check after the vanilla surface change.
10. Distant Nether lava sea looks flat pink (15.20.04): Nether haze over lava.
11. Overall "bland and dead" in the overworld: grading/contrast pass needed, compared side by side with Reimagined.
12. FPS: 70-115 typical, ~43 over water. Next: per-feature specialization of the terrain shader, cheaper water path.
