#!/usr/bin/env python3

import contextlib
import io
import json
import sys
import tempfile
from unittest.mock import patch
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from run_jmh_compare_refs import (  # noqa: E402
    DEFAULT_PATTERN,
    jmh_command,
    maven_command,
    ordered_revisions,
    parse_args,
)


class RunJmhCompareRefsTest(unittest.TestCase):

    def test_base_head_order_is_explicit(self) -> None:
        revisions = ordered_revisions("base-head", "a" * 40, "b" * 40)
        self.assertEqual(("base", "head"), tuple(item.label for item in revisions))
        self.assertEqual(("a" * 40, "b" * 40), tuple(item.sha for item in revisions))

    def test_head_base_order_is_supported_for_drift_checks(self) -> None:
        revisions = ordered_revisions("head-base", "a" * 40, "b" * 40)
        self.assertEqual(("head", "base"), tuple(item.label for item in revisions))

    def test_maven_command_builds_only_benchmark_reactor_with_tests_skipped(self) -> None:
        command = maven_command()
        self.assertEqual("mvn", command[0])
        self.assertIn("-DskipTests", command)
        self.assertIn("benchmarks", command)
        self.assertIn("canon-cr3-benchmark", command)
        self.assertIn("-am", command)

    def test_jmh_command_uses_fixture_and_jar_from_selected_worktree(self) -> None:
        worktree = Path("/tmp/revision")
        result = Path("/tmp/results/base.json")

        command = jmh_command(worktree, result, DEFAULT_PATTERN, ("-prof", "gc"))

        self.assertIn(
            "-Dsamples.dir=/tmp/revision/canon-cr3-imageio/src/test/resources/samples",
            command,
        )
        self.assertIn("/tmp/revision/canon-cr3-benchmark/target/benchmarks.jar", command)
        self.assertEqual(str(result), command[-1])
        self.assertIn("-prof", command)
        self.assertIn("gc", command)

    def test_fork_selection_is_passed_to_jmh_once(self) -> None:
        command = jmh_command(Path("/checkout"), Path("out.json"), DEFAULT_PATTERN, forks=3)
        self.assertEqual(1, command.count("-f"))
        self.assertEqual("3", command[command.index("-f") + 1])

    def test_two_ref_gate_fails_only_for_the_slow_parameter(self) -> None:
        # Inject only the benchmark execution. Exercise CLI semantics, parsing,
        # per-parameter classification, output reporting and the CI exit code.
        from run_jmh_compare_refs import main

        def emit_jmh_results(*, revision, output_dir, **kwargs):
            rows = []
            for sample in ("stable.cr3", "slower.cr3"):
                score = 140 if sample == "slower.cr3" and revision.label == "head" else 100
                rows.append({
                    "benchmark": "sentinel.parse", "params": {"sample": sample},
                    "mode": "avgt", "primaryMetric": {
                        "score": score, "scoreUnit": "ms/op",
                        "scoreConfidence": [score - 1, score + 1],
                    },
                })
            (output_dir / f"{revision.label}.json").write_text(json.dumps(rows))

        with tempfile.TemporaryDirectory() as directory, \
                patch("run_jmh_compare_refs.run_revision", side_effect=emit_jmh_results), \
                patch("run_jmh_compare_refs.command_output", return_value="test"), \
                contextlib.redirect_stdout(io.StringIO()):
            args = ["HEAD", "HEAD", "--order", "head-base", "--output-dir", directory]
            self.assertEqual(1, main([*args, "--fail-on-regression"]))
            self.assertEqual(0, main(args))
            report = json.loads((Path(directory) / "comparison.json").read_text())
            self.assertEqual(1, report["confirmedRegressions"])
            self.assertEqual({"slower.cr3": "REGRESSION", "stable.cr3": "STABLE"},
                             {dict(case["params"])["sample"]: case["verdict"]
                              for case in report["comparisons"]})

    def test_cli_has_no_github_specific_required_arguments(self) -> None:
        args = parse_args(("main", "feature", "--dry-run"))
        self.assertEqual("main", args.base_ref)
        self.assertEqual("feature", args.head_ref)
        self.assertTrue(args.dry_run)
        self.assertEqual("base-head", args.order)

    def test_reverse_order_and_output_directory_are_configurable(self) -> None:
        args = parse_args((
            "main",
            "feature",
            "--order",
            "head-base",
            "--output-dir",
            "results",
        ))
        self.assertEqual("head-base", args.order)
        self.assertEqual(Path("results"), args.output_dir)


if __name__ == "__main__":
    unittest.main()
