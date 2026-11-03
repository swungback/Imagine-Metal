# Imagine Metal

Shaders for Minecraft: Java Edition on Apple Silicon Macs, written for Apple Metal. The goal is Complementary Reimagined's
colours, effects and style on a Vibrant Visuals-like PBR base: coloured lighting, glowing ores, integrated PBR, Reimagined
water and clouds, god rays, aurora, depth of field, and the Nether and End looks. `FEATURES.md` is the full list.

The shader engine (package `imaginemetal`) is our own, written while studying how other packs do things. Some parts are
ports of other packs' formulas, translated to Metal: see Credits.

## Requirements

- Apple Silicon Mac (M1 or newer), macOS 15 or later
- Minecraft 26.3, Fabric Loader 0.19.5 or newer, Java 25
- No Sodium (the backend draws the terrain itself)

## Install

Download the jar from the latest successful run under **Actions > Build** (artifact `imagine-metal`), unzip it, and put
`imagine-metal-<version>.jar` in the `mods` folder of a Fabric 26.3 instance.

## Build it yourself

```sh
xcode-select --install
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./gradlew :mod:build
```

The jar lands in `mod/build/libs/`.

## Credits

- Rendering backend: [mcopt](https://github.com/noahdunnagan/mcopt) by Noah Dunnagan, Apache-2.0. Its code lives under
  `mod/src/main/java/mcopt` and `mod/src/main/native`; its license and notice are in `third_party/mcopt` and ship inside
  the jar.
- Reference reading: [Metal Shaders](https://github.com/GaelQuinta/minecraft-metal-shaders) by Gael Quintanilla
  (Apache-2.0).
- [Photon](https://github.com/sixthsurge/photon) by SixthSurge: the air fog, its biome values and some material formulas
  are ported from it. Photon Shaders License Agreement, in `licenses/Photon-LICENSE.txt`.
- [Complementary Reimagined](https://www.complementary.dev) by EminGT: the sky colours, clouds, aurora, sun highlight,
  generated normals and reflection blend are ported from it. It is under the Complementary License Agreement, which
  limits redistribution of its code.
