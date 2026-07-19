#!/usr/bin/env python3

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from compare_jmh import BenchmarkResult, classify  # noqa: E402


class CompareJmhTest(unittest.TestCase):

    @staticmethod
    def result(
        score: float,
        low: float,
        high: float,
        confidence_available: bool = True,
    ) -> BenchmarkResult:
        return BenchmarkResult(
            benchmark="example.Benchmark.measure",
            params=(("sample", "sample.cr3"),),
            mode="avgt",
            score=score,
            score_unit="ms/op",
            confidence_low=low,
            confidence_high=high,
            confidence_available=confidence_available,
        )

    def test_confirms_regression_only_when_intervals_separate(self) -> None:
        comparison = classify(
            self.result(100.0, 95.0, 105.0),
            self.result(130.0, 120.0, 140.0),
            1.15,
        )
        self.assertEqual("REGRESSION", comparison.verdict)
        self.assertFalse(comparison.confidence_intervals_overlap)

    def test_reports_overlapping_large_change_as_noisy_warning(self) -> None:
        comparison = classify(
            self.result(100.0, 90.0, 110.0),
            self.result(125.0, 100.0, 140.0),
            1.15,
        )
        self.assertEqual("NOISY_WARNING", comparison.verdict)
        self.assertTrue(comparison.confidence_intervals_overlap)

    def test_missing_confidence_cannot_confirm_regression(self) -> None:
        comparison = classify(
            self.result(100.0, 100.0, 100.0, False),
            self.result(140.0, 140.0, 140.0, False),
            1.15,
        )
        self.assertEqual("NOISY_WARNING", comparison.verdict)
        self.assertFalse(comparison.confidence_available)
        self.assertIsNone(comparison.confidence_intervals_overlap)

    def test_detects_statistically_separated_improvement(self) -> None:
        comparison = classify(
            self.result(100.0, 95.0, 105.0),
            self.result(75.0, 70.0, 80.0),
            1.15,
        )
        self.assertEqual("IMPROVEMENT", comparison.verdict)

    def test_rejects_modes_where_score_direction_is_unknown(self) -> None:
        base = self.result(100.0, 95.0, 105.0)
        head = BenchmarkResult(
            benchmark=base.benchmark,
            params=base.params,
            mode="thrpt",
            score=100.0,
            score_unit="ops/s",
            confidence_low=95.0,
            confidence_high=105.0,
            confidence_available=True,
        )
        with self.assertRaisesRegex(ValueError, "mode changed"):
            classify(base, head, 1.15)


if __name__ == "__main__":
    unittest.main()
