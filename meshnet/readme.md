# meshnet

an experimental m3 terrain path built on [mcopt](https://github.com/noahdunnagan/mcopt). the upstream snapshot is `7b1d0fb49b4aa03f0a8ebd31a42f93c5fa1cbc6c`, targeting minecraft 26.3, fabric loader 0.19.5 or later, java 25, and macos 15 or later.

## what the source audit found

mcopt already has persistent geometry, incremental uploads, deferred reclamation, gpu culling, indirect drawing, optional mesh shaders, and hierarchical depth tests. the new experiment reuses that storage and adds an object shader ahead of a mesh shader. it does not duplicate the allocator or replace the mesher.

each object group examines 32 resident records. it checks section ownership, the optional visibility mask, render distance, bounds, and facing. visible records become meshlets of up to 16 quads, 64 vertices, and 32 triangles. one direct dispatch covers opaque terrain. its compute list is skipped; cutout still uses mcopt's basic indirect path, and translucent rendering keeps its existing path.

this mode uses conservative frustum culling without temporal occlusion. camera motion and newly published sections need no visibility history. the record arena's unused capacity is scanned too, so sparse worlds can waste gpu work. upstream's optimized opaque and cutout classification and occlusion settings are bypassed in this mode. this is an experiment to measure, with no promised fps gain.

## build and try it

run `./gradlew :metal:build`. the mod jar is in `metal/build/libs`. it replaces mcopt in a test instance; keep the mod id and upstream notices. sodium cannot be installed alongside it.

enable the java system property `meshnet.objects=true`. the exact launcher arguments are in `benchmark-plan.json`. the startup log confirms when object terrain is enabled. leave the property off for the original renderer. unsupported gpu families or an object pipeline creation failure fall back to mcopt. frame capture also uses the original path because its replay format does not record object draws.

## validation

run `./gradlew :metal:testobjects`. this requires an apple family 9 or newer gpu. the test uses the runtime metal compiler, so a separate offline shader compiler is unnecessary. it renders offscreen with metal api and shader validation enabled, comparing every color and depth byte with mcopt's compute and indirect path.

the cases cover meshlet boundaries, partial object groups, empty scenes, unloaded and republished sections, stale records, masks, frustum and distance rejection, facing, cutout, excluded translucency, and positive and negative world-border coordinates. they exercise raw vertices, compact vertices, compressed quad records and their fallback copies, exact position tables, and both matrix paths. results are written to `build/meshnet/validation.json` and `build/meshnet/gpu-tests.log`.

these synthetic checks do not establish full in-game visual equivalence, compatibility with arbitrary mods, or a performance improvement.

## frame-time consistency

meshnet.2 enables refresh-synchronized presentation for paced rendering by default. the display clock follows the screen containing the game window, including moves between monitors. near the refresh rate, every completed frame is kept. at much higher render rates, redundant presents can still be skipped.

the fps limiter keeps a continuous deadline instead of moving the schedule after each late timer wake. missed deadlines recover without an extra wait, and changing the fps cap or returning from inactivity resets the schedule. gpu lead estimation uses the gpu completion timestamp, avoiding delays in callback delivery being mistaken for slow rendering.

these changes apply to the regular mcopt path too; enabling object terrain is optional. the fps counter may fall as presentation follows the display. the launch log reports whether presentation synchronization is active. the java system property `meshnet.smooth=false` restores unsynchronized paced presentation, and an explicit pacing synchronization property takes precedence.

run `./gradlew :metal:testpacing` for deadline, hitch recovery, and refresh-rate simulations. a separate on-screen cadence check is available through `python3 tools/check_present_cadence.py --baseline metal/build/libs/metal-0.3.0-alpha.2-meshnet.1.jar`. it briefly opens a small window and records actual drawable presentation times. results are in `build/meshnet/presentation-comparison.json`. this isolates presentation timing; full minecraft gameplay frame times still require a live run.

## benchmark it

use the settings and launcher arguments in `benchmark-plan.json`. run each scenario at least three times per variant, alternating their order. keep the world, camera trajectory, resource packs, entities, weather, resolution, and render distance identical. use copied worlds for unexplored flight so earlier runs do not generate the next run's chunks. repeat the complete experiment at 24 chunks in a separate manifest.

quit normally after each run to save mcopt's `framelog.csv`, then rename it before the next launch. add each measured window to the manifest's `runs` list using the listed fields. windows must exclude menus, world loading, and warmup. record evidence from a render-thread profile and metal system trace under `cpu_limit_evidence` for each scenario; low aggregate cpu use is not sufficient.

run `python3 tools/compare_frames.py meshnet/benchmark-plan.json --output build/meshnet/benchmark.json`. it reports per-trial median and p99 frame time, render-thread cpu time, timed regions, and waits. its acceptance check requires three paired trials, a cpu-limit evidence reference, at least 30% median frame-time reduction, and no p99 regression. the empty supplied plan produces an explicitly unmeasured report.

gpu execution time, visual comparisons during fast camera movement, entity-heavy gameplay, and full minecraft benchmarks still need live measurements. profile vanilla and sodium in separate instances to establish the wider baseline; this fork's direct comparison is against mcopt.
