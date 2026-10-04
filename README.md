# mcopt

A Minecraft performance mod for Apple Silicon Macs. **Very much alpha:** expect bugs, and back up your worlds.

## Requirements

- An Apple Silicon Mac on macOS 26 or later
- Java 25
- Minecraft 26.3 with Fabric Loader 0.19.5
- Sodium 0.9.3, a separate download:
  [sodium-fabric-0.9.3-alpha.1+mc26.3.jar](https://cdn.modrinth.com/data/AANobbMI/versions/v4PSXean/sodium-fabric-0.9.3-alpha.1%2Bmc26.3.jar)

## Install

Drop the mcopt jar and the Sodium jar into `~/Library/Application Support/minecraft/mods`. Done.

F3 shows the fps. To keep it on screen, press F3 + F6 (Debug Options) and set `fps` to Always.

## Settings

- The perf profile is on by default and picks its settings for your Mac.
- `profile=none` in `config/mcopt.properties` turns it off. The first launch writes that file.
- Far terrain is an experimental opt-in: `mcopt.lod=true` in the same file, for Macs with 10 or more GPU cores.

## Numbers

fps spinning / flying, same test world, 1920x1080, render distance 16, VSync off, two runs each:

| Mac | previous build | 0.2.0-alpha.1 |
|---|---|---|
| Mac mini M4 (10-core GPU, 16 GB) | 1094-1103 / 977-989 | 1072-1119 / 994-1002 |
| MacBook Neo (A18 Pro, 5-core GPU, 8 GB) | 379-449 / 324-339 | 460-471 / 415-432 |

## Known issues

- Only Sodium 0.9.3 works. With another version, Fabric stops at launch and says which one to install.
- Mods that call OpenGL directly can't draw on the Metal backend. `-Dmcopt.metal=false` switches mcopt's renderer off.
- Distant Horizons is one of those mods: with mcopt's renderer on, it crashes on the first frame. To use it, add
  `mcopt.metal=false` to `config/mcopt.properties`; mcopt's other optimizations stay on.
- Far terrain is experimental. On Macs with fewer than 10 GPU cores, it costs most of the fps.
- With fewer than 10 GPU cores, or 8 GB of memory or less, the profile leaves out the bigger chunk cache.
- With Lithium, C2ME or ScalableLux installed, some of mcopt's chunk patches step aside on purpose.

## Plans

- Far terrain on by default, with tiers for smaller Macs
- Our own shaders (BSL-level) as an option
- Faster world generation (native noise, bit-exact)
- More exact server-tick and chunk optimizations, held for later alphas
- Faster startup
- Wider hardware testing

## Licence and credits

[Apache License 2.0](LICENSE). Keep the [NOTICE](NOTICE) file with any copy, and credit mcopt. Files adapted from
[Sodium](https://github.com/CaffeineMC/sodium) keep the [PolyForm Shield License 1.0.0](LICENSES/PolyForm-Shield-1.0.0.md).
Each of those files says so in its header, and NOTICE lists them. mcopt runs on top of Sodium, by JellySquid and its
contributors.
