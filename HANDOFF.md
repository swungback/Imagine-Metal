# Imagine Metal: handoff (2026-11-12, ~23:40)

Where things stand, for picking the work up in a new conversation.

## Setup
- Repo: swungback/Imagine-Metal. Push to main → CI (.github/workflows/build.yml) compiles every Metal shader, builds the jar,
  publishes the "dev" release.
- Deploy: download `releases/download/dev/imagine-metal-0.1.0.jar?t=<time>` into
  `~/Documents/instances/Imagine metal/minecraft/mods`, check a marker string with `unzip -p`, then replace the jar.
- Jack's settings: `config/imaginemetal.properties` in that instance. Screenshots and `logs/latest.log` there too. His notes
  are chat lines in the log (`[CHAT] <herningstang> ...`) just before each screenshot.
- Reversed-Z depth everywhere (0 = far / sky). ImFrame is 928 bytes (lighting.metal, ImagineFrame.SIZE, mcown.m imFrame[928]).

## START HERE: 2026-11-13 ~01:00, MetaLODS built overnight on branch `metalods` (NOT deployed, NOT on main)
- Jack's ask (11-13 00:00): a Voxy-style LOD system inside our shader, Metal-native, as fast as possible, its own menu next to
  Imagine Metal's, named MetaLODS; unexplored land from the seed like SeedGen; default 128 chunks. He closed the lid: nothing
  tested in game. Design, limits and his test plan: docs/METALODS.md.
- Deploy in the morning only with Jack's OK: the branch's CI artifact (`gh run list -R swungback/Imagine-Metal --branch metalods`,
  `gh run download <run> -n imagine-metal`), game closed, over `mods/imagine-metal-0.1.0.jar`. Fallback stays
  `imagine-metal-c2b-water.jar` (= main 0cc9a2e, what's in mods now).
- Look first in latest.log: `[MetaLODS]` lines (ready / dimension / stats every 10 s / "stopped: ..." with a stack trace).
- Branch state ~01:10: head = "columns clear of the real terrain drawn without the mask's discard" (CI green, run 43333077681),
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
