import argparse
import csv
import json
import math
import statistics
from pathlib import Path


SCENARIOS = ("panorama", "loaded-flight", "dense-city", "unexplored-flight", "entity-heavy")
METRICS = ("wallUs", "cpuUs", "renderUs", "extractUs", "compileUs", "ownBeginUs", "inflightUs", "fenceUs", "drawableUs", "paceUs", "drainUs")


def percentile(values, fraction):
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def read_run(run, directory):
    source = (directory / run["frame_log"]).resolve()
    start, end = int(run["start_ms"]), int(run["end_ms"])
    if end <= start:
        raise ValueError(f"invalid measurement window: {source}")
    with source.open(newline="") as stream:
        reader = csv.DictReader(stream)
        required = {"endMs", "wallUs", "cpuUs"}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError(f"missing frame-log columns: {source}")
        rows = [row for row in reader if start <= int(row["endMs"]) < end]
    if len(rows) < 100:
        raise ValueError(f"need at least 100 frames in measurement window: {source}")
    result = {"scenario": run["scenario"], "variant": run["variant"], "trial": run["trial"], "frames": len(rows),
              "window_ms": end - start, "source": str(source), "metrics_ms": {}}
    for name in METRICS:
        if name not in rows[0]:
            continue
        values = [float(row[name]) / 1000 for row in rows]
        if any(not math.isfinite(value) or value < 0 for value in values):
            raise ValueError(f"invalid {name} in {source}")
        if name == "wallUs" and any(value <= 0 for value in values):
            raise ValueError(f"nonpositive frame time in {source}")
        result["metrics_ms"][name.removesuffix("Us")] = {
            "median": statistics.median(values), "p99": percentile(values, 0.99),
        }
    return result


def compare(manifest, directory):
    runs = []
    identities = set()
    for entry in manifest.get("runs", []):
        identity = (entry["scenario"], entry["variant"], entry["trial"])
        if identity in identities or identity[0] not in SCENARIOS or identity[1] not in ("mcopt", "objects"):
            raise ValueError(f"invalid or duplicate run: {identity}")
        identities.add(identity)
        runs.append(read_run(entry, directory))
    comparisons = []
    for scenario in SCENARIOS:
        baseline = {run["trial"]: run for run in runs if run["scenario"] == scenario and run["variant"] == "mcopt"}
        objects = {run["trial"]: run for run in runs if run["scenario"] == scenario and run["variant"] == "objects"}
        paired = sorted(baseline.keys() & objects.keys())
        deltas = {"median": [], "p99": []}
        for trial in paired:
            a, b = baseline[trial], objects[trial]
            if a["window_ms"] != b["window_ms"]:
                raise ValueError(f"mismatched measurement durations: {scenario}, trial {trial}")
            for metric in deltas:
                old = a["metrics_ms"]["wall"][metric]
                new = b["metrics_ms"]["wall"][metric]
                deltas[metric].append(1 - new / old)
        reductions = {name: statistics.median(values) if values else None for name, values in deltas.items()}
        enough = len(paired) >= 3 and baseline.keys() == objects.keys()
        profile = manifest.get("cpu_limit_evidence", {}).get(scenario)
        status = "unmeasured" if not paired else "needs-more-trials" if not enough else "needs-cpu-profile" if not profile else "measured"
        target = None if status != "measured" else reductions["median"] >= 0.3 and reductions["p99"] >= 0
        comparisons.append({"scenario": scenario, "status": status, "paired_trials": len(paired),
                            "frame_time_reduction": reductions, "cpu_limit_evidence": profile,
                            "meets_30_percent_median_target_without_p99_regression": target})
    return {"settings": manifest.get("settings", {}), "runs": runs, "comparisons": comparisons,
            "interpretation": "paired trial medians; GPU timings require Metal System Trace or mcopt GPU profiling; CPU utilization alone does not establish a CPU limit"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    options = parser.parse_args()
    manifest = json.loads(options.manifest.read_text())
    result = compare(manifest, options.manifest.resolve().parent)
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    print(options.output.resolve())


if __name__ == "__main__":
    main()
