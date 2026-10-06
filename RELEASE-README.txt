mcopt Metal @VERSION@

A Minecraft performance mod for Apple Silicon Macs. VERY MUCH ALPHA: expect bugs, and back up your worlds.

You need
- An Apple Silicon Mac (M1 or newer) on macOS 26 or later
- Java 25 or newer (your launcher has to start the game on Java 25+)
- Minecraft: Java Edition 26.3
- Fabric Loader 0.19.5 or newer for 26.3
- Sodium 0.9.2 or 0.9.3 for 26.3, a separate download (below)

Install
1. Install Fabric for Minecraft 26.3: https://fabricmc.net/use/installer/
   (or in Prism Launcher: new instance, version 26.3, loader Fabric)
2. Download Sodium 0.9.2 (stable) or 0.9.3 (alpha) for 26.3:
   https://cdn.modrinth.com/data/AANobbMI/versions/bAZQdGpg/sodium-fabric-0.9.2%2Bmc26.3.jar
   https://cdn.modrinth.com/data/AANobbMI/versions/v4PSXean/sodium-fabric-0.9.3-alpha.1%2Bmc26.3.jar
3. Put mcopt-metal-@VERSION@.jar and the Sodium jar in your mods folder.
   Official launcher: ~/Library/Application Support/minecraft/mods
   (in Finder: Go > Go to Folder, paste that path; create the mods folder if it's missing)
4. Start Minecraft with the Fabric profile.

The jar is a mod, not an app: double-clicking it only shows these install steps.

For the most fps: Options > Video Settings, VSync off and Max Framerate Unlimited.
To see your fps all the time: press F3 + F6, find fps in the list and set it to Always.

If Sodium is missing or the wrong version, Fabric stops at launch and tells you which version to install.
On a computer the mod can't run on (not an Apple Silicon Mac, or macOS older than 26), the game stops at launch
and says what's missing; remove mcopt-metal from the mods folder to play there.
To check it's working, the game log says "Using graphics backend Metal".

mcopt turns on its measured speed-ups by itself (the alpha profile), picked for your Mac. The first launch writes
config/mcopt.properties in your game folder; set profile=none there to run with only the Metal renderer.
-Dmcopt.metal=false switches mcopt's renderer off.

Licence: Apache-2.0 (LICENSE, NOTICE). Files adapted from Sodium keep the PolyForm Shield License 1.0.0;
the jar's LICENSES folder has its text and NOTICE lists the files.
