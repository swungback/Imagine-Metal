# Feature checklist

Target: Complementary Reimagined's colours, effects and style on a deferred, Vibrant Visuals-like base, native on Metal,
Minecraft 26.3 Fabric. One setup tuned for one Mac, every effect with its own switch. No quality profiles.

## Engine
- [x] Mod scaffold on mcopt's Metal backend, Mac builds on GitHub Actions
- [ ] Hook into the backend's render passes (PassDelegate)
- [ ] Deferred pipeline: G-buffer kept in tile memory, lighting in the same pass
- [ ] HDR target, tonemap, TAA/upscaling
- [ ] Shadow maps
- [ ] Settings menu with a switch per effect

Every item gets ticked off against the reference screenshots as the engine is built.

## Lighting
- [ ] Warm sun, sky-coloured shadows, time-of-day colour grading
- [ ] Light wraps past the terminator (no flat dark sides)
- [ ] Quadrature light mix (torch barely shows in sunlight, owns a dark cave, nothing clips)
- [ ] Directional face shading (tops, bottoms, N/S vs E/W)
- [ ] Torches fade in daylight
- [ ] Bluish minimum light in caves, fake bounce light
- [ ] Torch flicker
- [ ] Held light
- [ ] AO re-curved by light, SSAO
- [ ] Sun highlight on surfaces
- [ ] Coloured block light (flood through air, blocked by walls, colours mix)
- [ ] Stained glass tints block light passing through it
- [ ] Coloured light glow in the air (coloured light fog)
- [ ] Light sources glow in their own colour
- [ ] Glowing ores
- [ ] Distant light bokeh (far torches as soft dots)

## Shadows
- [ ] Soft sun/moon shadows, entity shadows
- [ ] Coloured shadows through stained glass and ice
- [ ] Water caustics
- [ ] Cave light-leak fix
- [ ] Light through leaves (subsurface)
- [ ] Soft penumbra near walls

## Materials
- [ ] Integrated PBR: metals (iron, gold, copper, netherite), gems, ice, glass, polished and glazed blocks, wood sheen
- [ ] Generated normals (not on foliage, glass, liquids)
- [ ] labPBR resource packs (pack maps override integrated ones)
- [ ] Parallax (POM) for labPBR packs
- [ ] Coated textures option
- [ ] Wet surfaces and puddles in rain
- [ ] Snowy world option

## Water, glass, reflections
- [ ] Reimagined pixel-grid water ripples
- [ ] Depth-based clarity, shoreline foam, refraction
- [ ] Water SSR with sky, clouds and aurora fallback
- [ ] Glossy glass: stained glass, ice, slime and honey reflect (tinted)
- [ ] Screen-space reflections on shiny opaque blocks (metals reflect surroundings), half res, temporal
- [ ] Underwater fog, tinted god rays
- [ ] Underwater screen wobble
- [ ] Fancy glass look (tinted clear parts, distant glass more reflective)
- [ ] Nether portal parallax, End portal stars

## Sky and atmosphere
- [ ] Sky gradient, sun/moon glare
- [ ] Reimagined square sun and moon look
- [ ] Stars, moon phases
- [ ] Night nebula option
- [ ] Aurora in Reimagined pink/purple
- [ ] Rainbow after rain
- [ ] Reimagined blocky clouds: rounded edges, self-shadow, optional second layer, optional cloud shadows
- [ ] Fog: cave, valley haze, border fog into the sky colour, water/lava/powder snow
- [ ] "Bloom fog" (distance, rain, night, caves glow more)
- [ ] God rays; [ ] scene-aware strength (strong under trees and in caves)

## Weather
- [ ] Lit rain and snow, wind lean, lightning
- [ ] Biome weather (dusty fog in dry biomes, bluish in snowy, Pale Garden shafts, sulfur caves fog)

## Nether and End
- [ ] Nether fog and lava glow; [ ] per-biome palette matched to the references, ash storms
- [ ] End sky and fog; [ ] End beams (purple with orange streaks), End flashes, End stars matched to the references

## Camera and post
- [ ] Bloom without a threshold (the dreamy glow)
- [ ] Film-like tonemap, vibrance; [ ] highlight path to white, dark lift, dark desaturation
- [ ] Depth of field
- [ ] Lens flare, motion blur, chromatic aberration (options)
- [ ] Vignette, TAA
- [ ] Sharpening
- [ ] Waving leaves, grass, vines, lily pads
- [ ] Selection outline styles, world outline, pixelated lighting, light level display, hand sway
