import argparse
import json
import os
import subprocess
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = root / "build" / "meshnet"
    baseline = output / "pacing-baseline"
    baseline.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(options.baseline) as archive:
        (baseline / "libmcmetal.dylib").write_bytes(archive.read("natives/libmcmetal.dylib"))
    environment = {**os.environ, "TMPDIR": "/tmp", "MTL_DEBUG_LAYER": "1"}
    binary = output / "present_cadence"
    command = ["clang", "-O2", "-Wall", "-I", "metal/src/main/native", "tests/present_cadence.m",
               "-framework", "AppKit", "-framework", "Metal", "-framework", "QuartzCore", "-o", str(binary)]
    compiled = subprocess.run(command, cwd=root, env=environment, capture_output=True, text=True)
    (output / "cadence-build.log").write_text(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        raise SystemExit(f"cadence compile failed: {output / 'cadence-build.log'}")
    reports = {}
    for variant, library, sync in (("before", baseline / "libmcmetal.dylib", "0"),
                                   ("after", root / "metal/build/natives/natives/libmcmetal.dylib", "1")):
        path = output / f"presentation-{variant}.json"
        run = subprocess.run([str(binary), str(library), sync, str(path)], cwd=root, env=environment,
                             capture_output=True, text=True, timeout=25)
        (output / f"presentation-{variant}.log").write_text(run.stdout + run.stderr)
        if run.returncode or not path.exists():
            raise SystemExit(f"cadence test failed: {output / f'presentation-{variant}.log'}")
        reports[variant] = json.loads(path.read_text())
        if reports[variant]["scanout_samples"] < 30:
            raise SystemExit(f"insufficient actual scanout samples: {path}")
        print(f"{variant}: {reports[variant]['scanout_fps']:.1f} displayed FPS; p99 interval {reports[variant]['p99_interval_ms']:.3f} ms")
    path = output / "presentation-comparison.json"
    path.write_text(json.dumps({"scope": "standalone presentation only; not Minecraft frame-time measurements", **reports}, indent=2) + "\n")
    print(path)


if __name__ == "__main__":
    main()
