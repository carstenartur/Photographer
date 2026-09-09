#!/usr/bin/env python3
"""Exercise research verification without Maven or private image fixtures."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class ResearchVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="research verification ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repository"
        scripts = self.root / "scripts"
        scripts.mkdir(parents=True)
        self.script = scripts / "verify-cr3-research.sh"
        shutil.copyfile(Path(__file__).with_name(self.script.name), self.script)
        self.bin = Path(self.temp.name) / "bin"
        self.bin.mkdir()
        for name, body in {
            "mvn": 'pwd > "$CALL_LOG/cwd"\nprintf "%s\\n" "$@" > "$CALL_LOG/maven"\nexit "${MAVEN_EXIT:-0}"\n',
            "python3": 'printf "%s\\n" "$@" > "$CALL_LOG/evidence"\nexit "${EVIDENCE_EXIT:-0}"\n',
        }.items():
            stub = self.bin / name
            stub.write_text("#!/bin/sh\n" + body)
            stub.chmod(0o755)
        self.env = {**os.environ, "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
                    "CALL_LOG": self.temp.name}
        self.env.pop("MAVEN_EXIT", None)
        self.env.pop("EVIDENCE_EXIT", None)

    def run_script(self, *args, **env):
        return subprocess.run(
            ["bash", str(self.script), *args], cwd=self.temp.name,
            env={**self.env, **env}, capture_output=True, text=True, check=False)

    def log(self, name):
        return (Path(self.temp.name) / name).read_text().splitlines()

    def test_focused_builds_test_jar_prerequisites_then_compares_evidence(self):
        result = self.run_script("--focused", "-Dmaven.repo.local=/tmp/empty research cache")
        self.assertEqual(0, result.returncode, result.stderr)
        args = self.log("maven")
        self.assertEqual(["clean", "verify"], args[2:4])
        self.assertEqual("canon-cr3-research", args[args.index("-pl") + 1])
        self.assertIn("-am", args)
        self.assertIn("-Pcr3-research-it", args)
        self.assertIn("-Dit.test=ExactBracketKDiscriminatorIT", args)
        self.assertIn("-Dmaven.repo.local=/tmp/empty research cache", args)
        self.assertFalse(any("skip" in arg.lower() or arg == "install" for arg in args))
        self.assertEqual([str(self.root)], self.log("cwd"))
        self.assertIn("D12-metrics", self.log("evidence"))

    def test_full_runs_all_integration_tests_without_focused_comparison(self):
        result = self.run_script("--full")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-am", self.log("maven"))
        self.assertFalse(any(arg.startswith("-Dit.test=") for arg in self.log("maven")))
        self.assertFalse((Path(self.temp.name) / "evidence").exists())

    def test_default_is_focused(self):
        self.assertEqual(0, self.run_script().returncode)
        self.assertIn("-Dit.test=ExactBracketKDiscriminatorIT", self.log("maven"))

    def test_build_failure_does_not_compare_stale_evidence(self):
        result = self.run_script("--focused", MAVEN_EXIT="23")
        self.assertEqual(23, result.returncode)
        self.assertFalse((Path(self.temp.name) / "evidence").exists())

    def test_evidence_mismatch_fails_verification(self):
        self.assertEqual(19, self.run_script("--focused", EVIDENCE_EXIT="19").returncode)

    def test_invalid_mode_fails_before_invoking_maven(self):
        result = self.run_script("--unknown")
        self.assertEqual(2, result.returncode)
        self.assertIn("Usage:", result.stderr)
        self.assertFalse((Path(self.temp.name) / "maven").exists())


if __name__ == "__main__":
    unittest.main()
