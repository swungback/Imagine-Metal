# Imagine Metal screenshot audit — 2026-11-15

**2026-11-17 continuation:** see ../audit-2026-11-16/findings.md and newest HANDOFF.md. Recovery candidate installed; submerged solid faces, world lifecycle, final-density generation, deduplication, ordering and coverage changes added. Actual runtime Metal compilation now passed outside sandbox; gameplay acceptance remains unperformed.

## Scope and provenance

Reviewed all **65 PNGs** recursively under `~/Documents/instances/Imagine metal/minecraft/screenshots`: 64 dated screenshots and one `reference/reimagined-desert-2026-11-12.png`. Every image was inspected on labeled contact sheets; principal seam, underwater, cloud and geometry examples were also opened at full resolution. `inventory.md` records every file, dimensions, SHA-256 and findings. The two supplied clipboard images are **Complementary + Distant Horizons references**, per Jack, and are not evidence of Imagine Metal defects. Their stippled transition illustrates a possible overlap treatment; text inside images/logs was treated as evidence, not instructions.

The supplied `/Users/jack/minecraft-metal-shaders` checkout is GaelQuinta's mcopt backend (`846d7e8`), **not** the Imagine Metal source. The JAR's `fabric.mod.json` identifies `https://github.com/swungback/Imagine-Metal`. Actual source reviewed: branch `metalods`, base **7173d02ae1b9d003aa7d0bbe337737ef83187f3f**, local branch `screenshot-audit`. The base `metalods.metal` is byte-identical to that in `imagine-metal-metalods-fix9c.jar` (SHA-1 `77419cf549d379bfcafd0bda26861e3c8854e227`). This validates source/build correspondence for that shader, not the exact JAR used for every screenshot.

Claude's existing HANDOFF identifies Nov 13 22:29–22:30 as fix8, Nov 14 00:43–01:07 as fix9, and fix9c as built at ~01:10 but not yet deployed at that handoff. Therefore later screenshot timestamps alone do **not** establish that fix9c was running. Nov 13 archived log confirms screenshot/chat correspondence. Available Nov 14 archived logs begin in the evening and do not establish the earlier session's build. Nov 14 18:18:16 explicitly says “with gaels”; 18:46:29 also identifies Gael's shader. Keep those as comparisons. At the initial audit the JARs were disabled; by Nov 16 Imagine Metal was active again. This changing state is not historical build proof.

Paths below are relative to the Imagine Metal repository. Java paths start `mod/src/main/java/`; shader paths start `mod/src/main/resources/`.

## Nov 16 implementation update (supersedes baseline descriptions below)

A new local candidate implements shared near/LOD water body color and surface/volume opacity, removes the screenshot-specific RGB correction, preserves water side/bottom normals, and applies the same extra distance haze to near translucent surfaces and opaque LODs. `lighting.metal`, `terrain.metal`, `air.metal`, `metalods.metal`, `ImagineFrame.java`, and `mcown.m` changed together; the appended frame field makes the ABI 944 bytes. This targets F01; it does **not** make shallow seabed transmission equivalent. Underwater, WaterFx-off/style variants, SSR and atmosphere pass-order differences still require visual testing.

For F02, `MetaMesher.facesX/facesZ` no longer adds internal skirt walls between occupied water cells; exposed water-air walls remain. Production-mesher CPU fixtures verify both cases. This is a confirmed source defect, but the white underwater screenshot band is not proven resolved.

For F03/F04, `MetaChunks.build` now preserves captured air regardless of skylight. The old fill copied the last solid (including water) upward through dark air below a higher island. Removing that heuristic prevents this data corruption on new captures. Screenshot provenance remains unknown. Existing `real2` caches retain previously filled voxels until their chunks are recaptured; no caches/worlds were deleted. Generated tree pillars and density-carving approximations remain separate open issues. The floating-shelf fixture tests downstream RLE/meshing, **not** Minecraft snapshot capture; no direct capture regression or memory/performance measurement yet.

Validation: final Java/native/JAR build PASS; production RLE/mesher fixtures PASS; 576 extracted shader-math CPU combinations plus fog/volume invariants and 944-byte ABI check PASS; whitespace check PASS. CI now runs CPU checks before its existing Metal compilation. CPU translation is not a Metal compiler. Local Metal compiler/GPU unavailable; GitHub push dry-run returned 403, so no remote CI validation. Runtime and screenshot comparison remain pending.

## Findings (baseline observations and remaining work)

### F01 — Abrupt near/LOD water color, reflection and lighting change (high priority, visually confirmed; open)

**Evidence:** `2026-11-13_22.30.23.png`: near ocean is transparent cyan/deep blue with visible kelp; far ocean is a smooth pale grey-blue sheet. A straight, chunk-stepped boundary crosses the lower-left/middle of the frame; additional rectangular blue edges lie farther out. `2026-11-14_01.40.54.png` shows another unmistakable straight color boundary. Island views at 00:50:12/18 and 00:51:20 show dark teal/cyan patches.

**Confirmed implementation difference:** `imaginemetal/shaders/metalods.metal:ml_fs` tags all LOD water faces as `0x80`, forces an up normal and sky light 1. `mcopt/own/terrain.metal:im_defer_fs` calls water lighting but then multiplies by a fixed linear RGB correction `(0.42,0.66,0.72)` and a deep-water opacity approximation. Near water in `own_fs_tr` instead reads the actual under-water color/depth and applies depth-dependent absorption and transmission. `imaginemetal/shaders/lighting.metal:imShade` supplies reflections. Sharing imShade does not make these two compositing models equivalent.

**Cause assessment:** These are confirmed differences capable of producing the observed seam. The precise contribution of exposure, SSR history, depth, forced sky light and the fixed correction cannot be recovered from screenshots alone. Claude's fix9 correction is already present; applying it again is not a fix.

**Proposed fix:** Factor common water body/absorption/Fresnel math out of the near and far paths. Supply a representative floor depth and biome tint for LOD water; preserve real side/bottom orientation; use compatible sky/SSR fallback and fog. Test calm/deep/shallow water, sunset and underwater before changing coefficients. Nov 16 candidate implements shared surface math and removes the fixed correction; see implementation update. Representative floor depth remains unimplemented.

### F02 — Water edge strips and white underwater gaps (high priority, visually confirmed; partly diagnosed)

**Evidence:** 22:30:23's rectangular strips; `2026-11-14_01.41.54.png` and `01.41.56.png`: a wide white band with rectangular ends across the water boundary, behind kelp, not a subtle color difference. `2026-11-13_18.54.55.png` separately shows an unclear surface boundary from below.

**Sources:** `metalods.metal:ml_vs/ml_fs`, `MetaMesher.facesY/facesX/facesZ`, `MetaWorld.updateMask/prepare`, `mcopt/metal/own/OwnFluidEdge.java`, `terrain.metal:own_fs_tr`.

**Confirmed code facts:** Water heights are adjusted toward sea level in ml_vs; non-sea-level lakes keep a simpler offset. fix8 masks water sides by the *front* chunk. fix9c includes an unloaded-neighbor fluid-height fix. MetaMesher treats water as occupied when deciding whether adjacent faces exist. LOD water is opaque deferred geometry, unlike real water. These facts narrow the investigation but do not prove which pass creates the white band.

**Implemented related correction:** Solid vertical faces previously floored their exact plane position for the chunk mask: a +X face at x=16 belongs to the voxel ending at 16 (chunk 0), but tested chunk 1. Now solid faces sample 0.02 blocks inside their owner; water sides retain the intentional front-cell test. This is a confirmed ownership bug, **not a claim that the underwater band is fixed**.

**Next:** At the 01:41:56 location (~-54.12, 50.45, 67.56; facing south), compare water-only, LOD level colors, mask and depth views. Test all four chunk-edge directions and negative coordinates. Determine whether white pixels are sky, fogged far water or absent geometry. A dither cannot repair missing coverage.

### F03 — Waterfall pillars and floating flat patches (high priority, visible in older build; fix already attempted)

**Evidence:** `2026-11-13_22.30.05.png` blue cuboid beneath floating island and detached horizontal patch; 22:30:27 dark rectangular patches at falls' feet. Nov 14 00:50:12/18/00:51:20 still show large flat hanging faces and sheets.

**Sources:** `MetaPalette.kindOf`, `Rle.pick/mip`, `MetaStore.readRegion`, `mcopt/metal/lod/MetaGen.java`.

**Confirmed historical mechanism:** Pre-fix9 water outranked air during downsampling, expanding thin falls into coarse volumes. Current source classifies flowing liquid as air and requires >=4/8 water children. The CPU fixture confirms a one-column waterfall is rejected and a half-full water layer survives. Existing source fixes are not proof of visual resolution. Generated voxels and migrated caches need independent examination.

**Next:** Inspect a failing voxel's source (generated vs real vs legacy migrated cache), block ID and level; compare a copied cache with freshly generated tiles. Preserve the user's original caches. Do not delete all caches to hide the symptom.

### F04 — Island curtains, tree towers and overhang discontinuities (high priority, visually confirmed; exact cause unconfirmed)

**Evidence:** `2026-11-14_01.18.11.png` has a huge flat grey curtain beneath the detailed island, with a straight bottom and thin horizontal marks. Calling this merely “a coarse stone underside” is insufficient: it is a major silhouette mismatch. 00:54:45/48 and 01:20:28–47 show long vertical green tree/vegetation columns and changing representations. 01:36:28 gives another canopy comparison.

**Sources:** `MetaGen.cell/carve`, `MetaChunks.build`, `Rle.pick`, `MetaWorld.derive/visit`, `MetaStore.GEN_VERSION` and `MetaSettings.overhangs`.

**Confirmed approximation:** MetaGen's impostor-tree path fills from the ground to canopy top with leaf material; Rle chooses the most solid child. MetaChunks fills zero-skylight air under the column top with the previous solid. That heuristic can erase shadowed open space; it is not a visibility proof. Heightfield mode intentionally loses overhangs. Coarse/fine source transitions and stored tiles can preserve old shapes.

**Hypotheses:** The island curtain could involve generated volume sampling, the dark-air fill, stale cached geometry, or a coarse fallback. A screenshot cannot identify its material or data provenance. The mesher fixture preserves a supplied floating shelf's air gap, so the ordinary mesher does not inherently require a ground-to-island wall.

**Proposed fix:** Instrument per-tile provenance/level and dump RLE columns at the island. Preserve open connected air; restrict enclosed-air filling using connectivity/visibility rather than skylight alone. Use separate canopy/trunk occupancy or actual tree runs. Version only the affected generated cache format after reproducing the faulty source data.

### F05 — Visible terrain/foliage transition and color discontinuity (visually confirmed; several contributors)

**Evidence:** 00:47:40/42/47, 00:51:42, 00:54:42/43, 01:19:29/30 and 01:21:04/06/10/15: nearby olive textured/bushy leaves meet vivid green blockier distant canopy. Geometry density and terrain color change together.

**Sources:** `MetaWorld.updateMask`, `metalods.metal:ml_fs`, `MetaPalette.writeBiome`, `MetaChunks.capture`, `MetaGen.cell`, `OwnMesher` and bushy-leaf code.

**Confirmed:** Handoff is a binary mask, not the Complementary+DH stippled overlap. MetaPalette evaluates grass tint at (0,0) once per biome; captured biome IDs are sampled per 4x4 column region; shader adds LOD-only noise and distance desaturation. Near foliage has different geometry/alpha handling. These can create mismatches, but biome boundaries and changed camera position also affect these images.

**Proposed fix:** First align tint sampling, material, normals, ambient light and coverage; then add a short, complementary, world-stable dither overlap to *both* near and far visibility decisions. Keep a fallback when either mesh is absent. A unilateral LOD discard would create holes. No cosmetic dither was added before ensuring coverage.

### F06 — Water drawn through clouds (visually confirmed in fix9-era images; existing fix9c unverified)

**Evidence:** `2026-11-14_00.49.20.png`, `00.49.21.png`, `00.49.21_2.png`: blue river appears through/over broad white cloud coverage. The third has the explicit chat report.

**Sources:** `mod/src/main/native/imsky.m` mode 3 alpha blending; `imaginemetal/shaders/sky.metal:im_clouds_late_fs`; `terrain.metal:own_fs_tr`.

**Confirmed:** Clouds are composed after opaque land and before translucents. fix9c encodes cloud coverage into destination alpha; the translucent path decodes it and blends back toward the cloudy destination. That is already in the audited source. Destination alpha is also used for temporal reactivity, and this approximate coverage scheme does not carry cloud depth or independent transmittance through stacked translucents.

**Next:** Reproduce on a verified fix9c/audit build; test water behind clouds, water in front of clouds and multiple translucent layers. If still wrong, use cloud depth/transmittance in ordered composition rather than globally masking water by cloud coverage. No claim of a new cloud fix here.

### F07 — Soft, hollow and awkwardly moving leaves (appearance confirmed; motion is reported rather than measurable)

**Evidence:** 22:29:53/22:30:47 shows filled/cutout leaf inconsistency. 00:46:08, 00:47:24/25, 00:51:13 and 01:24:10/_2 show broad bushy shapes, overlapping planes, blur and gaps. 01:19:29/30 reports general blur and animal tracing.

**Sources:** `mcopt/metal/own/BushyLeaves.java`, `BushyLeafSource.java`, `OwnMesher.java`, `OwnFrapi.java`, `OwnBlockRenderer.java`, `OwnQuads.java`; `terrain.metal:own_vs`, `shadow.metal`, `bloom.metal` temporal paths.

**Confirmed:** fix9c already changes plane culling, marks planes 1101, sharpens their mip and uses rigid plane sway without casting their shadows. LOD leaves remain a separate opaque approximation. Static PNGs cannot confirm temporal stability or ghost trail duration.

**Proposed validation/fix:** Same stationary forest camera with Bushy leaves on/off, native scale vs MetalFX, then controlled camera motion. Audit depth prepass vs visible-pass alpha and wave position agreement. Separate geometry cost from temporal blur before increasing sharpening.

### F08 — Removed blocks remain visible / stale LOD edits (reported in images; source defects confirmed and patched)

**Evidence:** `2026-11-14_18.05.36.png` through `18.05.44_2.png` show the targeted stone and later break particles; chat says blocks remain after breaking. This short series does not establish how long the ghost persisted or whether it was near terrain or an overlapping LOD.

**Sources and changes:**
- `imaginemetal/mixin/MetaLodsClientLevelMixin.java` previously captured only load/unload. Added block-state update notification.
- `MetaLods.blockChanged/pumpEdits` coalesces by chunk, waits 150ms after the latest edit and captures at most four edited chunks per frame; dimension changes clear queued edits. It does not rebuild synchronously for each block.
- `MetaChunks.capture` previously returned null for entirely empty chunks, preserving old geometry. Empty snapshots now replace it.
- `MetaWorld.integrate` previously dirtied only the containing column. Its neighbor meshes also depend on the edited border. Now adjacent columns are dirtied when the chunk touches a column boundary, using floor division/modulo for negative coordinates.

**Limits:** This does not establish that the photographed close-up ghost was a LOD; near-section publication/retirement in `OwnTerrain` and the earlier three-frame section-table fixes remain separate suspects. Worker completion order and long continuous edit streams require stress testing. No live game verification was performed.

### F09 — Ore and held-item emission (visible older behavior; existing corrections)

**Evidence:** 22:29:30/32 shows isolated bright iron specks and much stronger surrounding light in the second frame; held-item/light changes confound a strict brightness comparison. 19:20:05/10 shows bright cyan diamond item/tool texels.

**Sources:** `lighting.metal:imOre`, material classification, `imShadeEntity`; `Materials.java` and `HeldLight.java`.

**Status:** fix9 already equalizes iron speck glow and tightens the soul-emission color test to avoid diamonds. Reported goals are documented in Claude's handoff; no new arbitrary brightness change. Retest iron with fixed held light/exposure and diamond tools in a dark room. Check other ores independently.

### F10 — Cave brightness transition and surface visibility from below (mixed visual evidence/preference)

**Evidence:** 19:11:32/37 vs 19:11:43 shows a strong cave brightness difference; 18:54:55 lacks a clear overhead water surface. Later underwater white bands are F02. The 18:46:29 Gael comparison shows a more legible patterned surface.

**Sources:** `lighting.metal:imSkyCurve/imWaterFog/imShade`, `ImagineFrame` cave floor and fog parameters, `terrain.metal` below-water branch.

**Status:** Different view direction/light/configuration prevents isolating a bug from these stills. Claude records that Jack wanted sky light to reach ravines and reverted the steeper curve. Preserve that decision. fix9's rippling underwater sheet is already present. Verify with the same camera/light and isolate surface alpha vs water fog; do not darken all caves as an assumed fix.

### F11 — Severe stalls and forest rendering cost (symptoms confirmed; leak not established)

**Evidence:** 00:49:21 reads 1 FPS with heavy heap use; 00:53:57 reports 99% heap; 18:06:56 and 18:10:23 report ~40/~30 FPS. 18:18:16 is explicitly a Gael comparison, not an Imagine Metal after-fix measurement.

**Sources:** `MetaWorld` generation/ingest queues, `MetaStore` region/cache migration, `MetaGen`, bushy-leaf meshing, `PassTimes` and `Benchmark`.

**Status:** Claude's contemporaneous handoff attributes the earlier stalls to regeneration and GC and notes heavy bushy-leaf terrain cost. Screenshots alone prove neither a memory leak nor its absence. fix9c already restores legacy-cache migration. No FPS improvement is claimed for this audit.

**Next:** Record allocations, queue sizes and GPU pass timings at a fixed forest/water camera; compare warm cache with first generation and bushy on/off. Bound/coalesce pending ingest work if a measured backlog grows. Distinguish GPU work, GC, pause/unfocus and resource reloads.

### F12 — Fine cloud striping and broad image softness (visible, cause provisional)

**Evidence:** Cloud edges in 22:30:23, 01:18:11 and 00:49:21 have fine repeated vertical stippling/striping; several foliage views are soft while UI remains sharp. Some is ordinary voxel cloud edge shape or reduction in the contact sheet; the cited examples were checked full size.

**Sources:** `sky.metal` cloud integration/dither, `bloom.metal` TAA/MetalFX masks, `Resolution.java`/`Bloom.java`, texture mip selection.

**Next:** Compare native-resolution screenshots with temporal reconstruction off, then fixed camera over multiple frames. Do not diagnose temporal aliasing from one frame or conflate it with the deliberate Complementary+DH transition noise in the attachments.

## Validation and remaining work

- Base Java compilation: passed.
- Patched `:mod:build` (JDK 25, Gradle 9.5.1): passed; includes Java, native ARM64 dylib and JAR packaging. Gradle reported `test NO-SOURCE`; this is not an automated gameplay test suite.
- `JAVA_HOME=<JDK25> sh ci/metalods/check.sh`: five CPU geometry fixtures passed. Compiles production Rle/Voxel/MetaMesher with tiny fixture-only palette/annotation stubs; tests empty meshes, floating shelf air, neighbor-border change, thin waterfall rejection and half-water preservation. Does not test Minecraft event injection or actual GPU pixels.
- `git diff --check`: passed.
- Offline Metal compiler: unavailable (`xcrun ... metal` not installed). Runtime compile attempt could not run: separate probe reports `Metal device: unavailable`. No shader compiler diagnostic establishing a source failure was produced. Metal compilation/pipeline specialization and in-game screenshots remain required.
- No instance files, caches, settings or installed mods were changed; no game launched; no GitHub push or dev release performed. The candidate JAR is labeled **unverified**.

### Reproduction order for the next session

1. Compile the shader and specialize ML_MASK=true/false on an accessible Metal device/CI before deploying the candidate.
2. On a copied test world, place/remove blocks at x/z=-16, 0 and 16; clear the last block in a chunk; cross the near/LOD boundary and return. Confirm no lingering block and no new holes. Stress rapid edits/unload/reload and dimension changes.
3. Revisit the ocean above and below water (01:40–01:41), record depth/mask/LOD level views. Compare sea-level water and an elevated lake, both edge directions.
4. Revisit the island and dump generated/real/cache provenance before changing density/cavity logic. Compare warm-cache and fresh-test-cache behavior.
5. Cloud/water overlap and bushy leaves at fixed resolution; then motion clips for animals/leaves. Use the two attachments only as Complementary+DH transition references.
6. Record candidate hash, settings, coordinates, time/weather, screenshots and per-pass timings in HANDOFF. Mark each issue visually verified only after that evidence exists.
