import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location("compare_frames", Path(__file__).resolve().parents[1] / "tools" / "compare_frames.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FrameComparisonTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.manifest = {"runs": [], "cpu_limit_evidence": {"panorama": "trace shows render-thread command encoding dominates"}}
        for trial in range(3):
            for variant, duration in (("mcopt", 10000), ("objects", 6000)):
                name = f"{variant}-{trial}.csv"
                with (self.directory / name).open("w", newline="") as stream:
                    writer = csv.writer(stream)
                    writer.writerow(("endMs", "wallUs", "cpuUs"))
                    writer.writerow((999, 900000, 800000))
                    for frame in range(120):
                        writer.writerow((1000 + frame, duration, duration * 0.8))
                    writer.writerow((1120, 900000, 800000))
                self.manifest["runs"].append({"scenario": "panorama", "variant": variant, "trial": trial,
                                              "frame_log": name, "start_ms": 1000, "end_ms": 1120})

    def test_paired_target_excludes_outside_window(self):
        report = MODULE.compare(self.manifest, self.directory)
        case = report["comparisons"][0]
        self.assertEqual(case["status"], "measured")
        self.assertAlmostEqual(case["frame_time_reduction"]["median"], 0.4)
        self.assertTrue(case["meets_30_percent_median_target_without_p99_regression"])
        self.assertEqual(report["runs"][0]["frames"], 120)
        self.assertEqual(report["runs"][0]["metrics_ms"]["wall"]["p99"], 10)

    def test_cpu_evidence_required(self):
        self.manifest["cpu_limit_evidence"] = {}
        case = MODULE.compare(self.manifest, self.directory)["comparisons"][0]
        self.assertEqual(case["status"], "needs-cpu-profile")
        self.assertIsNone(case["meets_30_percent_median_target_without_p99_regression"])

    def test_unpaired_trials_are_incomplete(self):
        self.manifest["runs"].pop()
        case = MODULE.compare(self.manifest, self.directory)["comparisons"][0]
        self.assertEqual(case["status"], "needs-more-trials")

    def test_duplicate_rejected(self):
        self.manifest["runs"].append(self.manifest["runs"][0])
        with self.assertRaises(ValueError):
            MODULE.compare(self.manifest, self.directory)

    def test_mismatched_duration_rejected(self):
        self.manifest["runs"][1]["end_ms"] = 1119
        with self.assertRaises(ValueError):
            MODULE.compare(self.manifest, self.directory)

    def test_empty_plan_has_no_performance_claim(self):
        report = MODULE.compare({"runs": []}, self.directory)
        self.assertEqual(len(report["comparisons"]), 5)
        self.assertTrue(all(case["status"] == "unmeasured" for case in report["comparisons"]))

    def test_p99_regression_fails_target(self):
        for trial in range(3):
            path = self.directory / f"objects-{trial}.csv"
            rows = path.read_text().splitlines()
            for frame in range(110, 115):
                rows[frame + 2] = f"{1000 + frame},50000,6000"
            path.write_text("\n".join(rows) + "\n")
        case = MODULE.compare(self.manifest, self.directory)["comparisons"][0]
        self.assertGreater(case["frame_time_reduction"]["median"], 0.3)
        self.assertLess(case["frame_time_reduction"]["p99"], 0)
        self.assertFalse(case["meets_30_percent_median_target_without_p99_regression"])


if __name__ == "__main__":
    unittest.main()
