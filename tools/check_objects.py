import json
import os
import platform
import re
import subprocess
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    output = root / "build" / "meshnet"
    output.mkdir(parents=True, exist_ok=True)
    environment = {**os.environ, "TMPDIR": "/tmp"}
    binary = output / "object_terrain"
    command = [
        "clang", "-fno-objc-arc", "-O2", "-Wall", "-Wextra", "-arch", "arm64",
        "-mmacosx-version-min=15.0", "-I", "metal/src/main/native",
        "tests/object_terrain.m", "-Lmetal/build/natives/natives", "-lmcmetal",
        f"-Wl,-rpath,{root}/metal/build/natives/natives", "-framework", "Foundation",
        "-framework", "Metal", "-o", str(binary),
    ]
    build = subprocess.run(command, cwd=root, env=environment, capture_output=True, text=True)
    (output / "test-build.log").write_text(build.stdout + build.stderr)
    if build.returncode:
        raise SystemExit(f"test compile failed: {output / 'test-build.log'}")
    environment.update(MTL_DEBUG_LAYER="1", MTL_SHADER_VALIDATION="1")
    run = subprocess.run(
        [str(binary), "metal/src/main/resources/mcopt/own/terrain.metal"],
        cwd=root, env=environment, capture_output=True, text=True, timeout=180,
    )
    log = run.stdout + run.stderr
    (output / "gpu-tests.log").write_text(log)
    match = re.search(r"PASS (\d+) exact color-and-depth comparisons", run.stdout)
    validation_errors = bool(re.search(r"(validation error|failed assertion|execution of the command buffer was aborted)", log, re.I))
    passed = run.returncode == 0 and match is not None and not validation_errors
    report = {
        "passed": passed,
        "comparisons": int(match[1]) if match else 0,
        "macos": platform.mac_ver()[0],
        "architecture": platform.machine(),
        "metal_api_validation": True,
        "metal_shader_validation": True,
        "comparison": "exact rgba8 color and float32 depth against mcopt compute plus indirect drawing",
        "scope": "synthetic offscreen scenes; no Minecraft performance claim",
        "log": str(output / "gpu-tests.log"),
    }
    (output / "validation.json").write_text(json.dumps(report, indent=2) + "\n")
    if not passed:
        print("\n".join(log.splitlines()[-12:]))
        raise SystemExit(f"GPU validation failed: {output / 'gpu-tests.log'}")
    print(match[0])
    print(output / "validation.json")


if __name__ == "__main__":
    main()
