"""Arithmetic boundary and CLI contract checks for the local planning calculator."""
from dataclasses import replace
import json
from pathlib import Path
import subprocess
import sys
import unittest

from local_cost_model import Assumptions, calculate


class LocalCostModelTests(unittest.TestCase):
    def test_plan_baseline_and_escalation_subset(self):
        result = calculate(Assumptions())
        work, compute = result["workload_per_day"], result["compute_per_day"]
        self.assertEqual(work["feed_impressions"], 2_000_000)
        self.assertEqual(work["factual_versions"], 260_000)
        self.assertEqual(work["claim_passage_comparisons"], 1_170_000)
        self.assertEqual(work["heavier_route_versions"], 13_000)
        self.assertAlmostEqual(compute["scorer_gpu_hours"], 11.6071428571)
        self.assertAlmostEqual(compute["heavier_route_gpu_hours"], 46.0488888889)
        self.assertAlmostEqual(compute["subtotal_gpu_hours"], 57.6560317460)
        self.assertAlmostEqual(compute["percent_of_nominal_gpu_hours"], 30.0291832011)

    def test_generation_sensitivity_exceeds_nominal_hours(self):
        result = calculate(Assumptions(generation_route_fraction=0.2))
        self.assertGreater(result["compute_per_day"]["share_of_nominal_gpu_hours"], 1)
        self.assertEqual(result["workload_per_day"]["claim_passage_comparisons"], 1_170_000)

    def test_gpu_count_changes_denominator_not_work(self):
        four = calculate(Assumptions(gpus=4))["compute_per_day"]
        eight = calculate(Assumptions())["compute_per_day"]
        self.assertEqual(four["subtotal_gpu_hours"], eight["subtotal_gpu_hours"])
        self.assertEqual(four["share_of_nominal_gpu_hours"], 2 * eight["share_of_nominal_gpu_hours"])

    def test_zero_work_is_valid_and_does_not_fake_zero_speed(self):
        for field in ("users", "impressions_per_user", "unique_fraction", "factual_fraction"):
            with self.subTest(field=field):
                self.assertEqual(calculate(replace(Assumptions(), **{field: 0}))["compute_per_day"]["subtotal_gpu_hours"], 0)
        with self.assertRaises(ValueError):
            calculate(Assumptions(users=0, pairs_per_second=0))

    def test_bad_domains_and_nonfinite_values_are_rejected(self):
        for field, values in {
            "users": [-1, 1.5, True], "gpus": [0, -1, 1.5],
            "unique_fraction": [-0.01, 1.01], "factual_fraction": [-1, 2],
            "generation_route_fraction": [-1, 2], "impressions_per_user": [-1],
            "claims_per_version": [0, -1], "candidates_per_claim": [0, 1.5],
            "pairs_per_second": [0, -1, float("nan"), float("inf")],
            "heavy_seconds": [0, -1, float("-inf")],
        }.items():
            for value in values:
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    calculate(replace(Assumptions(), **{field: value}))
        with self.assertRaises(ValueError):
            calculate(Assumptions(impressions_per_user=1e308))

    def test_json_cli_contains_caveats_and_invalid_cli_fails(self):
        command = [sys.executable, str(Path(__file__).with_name("local_cost_model.py"))]
        valid = subprocess.run(command + ["--json", "--pairs-per-second", "4.4"], check=True, text=True, capture_output=True)
        result = json.loads(valid.stdout)
        self.assertAlmostEqual(result["compute_per_day"]["scorer_gpu_hours"], 73.8636363636)
        self.assertTrue(result["exclusions"])
        self.assertTrue(any("accuracy" in statement for statement in result["interpretation"]))
        invalid = subprocess.run(command + ["--json", "--pairs-per-second", "nan"], text=True, capture_output=True)
        self.assertEqual(invalid.returncode, 2)
        self.assertEqual(invalid.stdout, "")
        self.assertIn("finite", invalid.stderr)


if __name__ == "__main__":
    unittest.main()
