#!/usr/bin/env python3

import sys
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
