# Imagine Metal: handoff (2026-11-10, evening)

Where things stand, for picking the work up in a new conversation.

## Setup
- Repo: swungback/Imagine-Metal. Push to main → CI (.github/workflows/build.yml) compiles every Metal shader, builds the jar,
  publishes the "dev" release.
- Deploy: download `releases/download/dev/imagine-metal-0.1.0.jar?t=<time>` into
  `~/Documents/instances/Imagine metal/minecraft/mods`, check a marker string with `unzip -p`, then replace the jar.
- Jack's settings: `config/imaginemetal.properties` in that instance. Screenshots and `logs/latest.log` there too. His notes
  are chat lines in the log (`[CHAT] <herningstang> ...`) just before each screenshot.
- Reversed-Z depth everywhere (0 = far / sky). ImFrame is 928 bytes (lighting.metal, ImagineFrame.SIZE, mcown.m imFrame[928]).

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
- Shaders off (live) 108 fps / 9.2 ms; everything on 29 fps / 35 ms: ~26 ms a frame is ours. The cutout depth pre-pass was
  already on in that jar (ffc606f) and didn't fix it. Jack: over 100 fps with shadows "when the mod was more basic".
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
