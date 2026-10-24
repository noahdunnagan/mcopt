import json
import os
import subprocess
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    output = root / "build" / "meshnet"
    classes = output / "pacing-classes"
    classes.mkdir(parents=True, exist_ok=True)
    environment = {**os.environ, "TMPDIR": "/tmp"}
    commands = [
        ["clang", "-O2", "-Wall", "-Wextra", "-Werror", "-I", "metal/src/main/native", "tests/frame_pacing.c", "-o", str(output / "frame_pacing")],
        [str(output / "frame_pacing")],
        ["javac", "-d", str(classes), "metal/src/main/java/mcopt/metal/FrameDeadline.java", "tests/FrameDeadlineTest.java"],
        ["java", "-cp", str(classes), "mcopt.metal.FrameDeadlineTest"],
    ]
    results = []
    for command in commands:
        run = subprocess.run(command, cwd=root, env=environment, capture_output=True, text=True, timeout=30)
        results.append({"command": command, "exit_code": run.returncode, "output": run.stdout + run.stderr})
        if run.returncode:
            break
    passed = len(results) == len(commands) and all(result["exit_code"] == 0 for result in results)
    report = {"passed": passed, "scope": "deadline and display selection simulations; not Minecraft frame-time measurements", "results": results}
    path = output / "pacing-validation.json"
    path.write_text(json.dumps(report, indent=2) + "\n")
    for result in results:
        if result["output"]:
            print(result["output"].strip())
    print(path)
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
