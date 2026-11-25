#!/usr/bin/env python3
"""Exercise dependency retries with a real subprocess in place of Maven."""

import json
import os
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parent
DRIVER = """
import os
from pathlib import Path
from unittest.mock import patch
from run_maven_with_retry import main
def record_delay(seconds):
    with Path(os.environ['MAVEN_TEST_SLEEPS']).open('a') as stream:
        stream.write(str(seconds) + '\\n')
with patch('run_maven_with_retry.time.sleep', side_effect=record_delay):
    raise SystemExit(main())
"""
TRANSFER_429 = (
    "[ERROR] The following artifacts could not be resolved: example:plugin:pom:1: "
    "Could not transfer artifact example:plugin:pom:1 from/to central "
    "(https://repo.maven.apache.org/maven2): HTTP Status: 429\n"
)
PARENT_503 = (
    "[FATAL] Non-resolvable parent POM: Could not transfer artifact "
    "example:parent:pom:1 from/to central (https://repo.maven.apache.org/maven2): "
    "status code: 503, reason phrase: Service Unavailable (503)\n"
)
COMPILE_FAILURE = (
    "[ERROR] Failed to execute goal org.apache.maven.plugins:"
    "maven-compiler-plugin:3.15.0:compile on project example: Compilation failure\n"
)
TEST_FAILURE = (
    "[ERROR] Failed to execute goal org.apache.maven.plugins:"
    "maven-surefire-plugin:3.5.6:test on project example: There are test failures.\n"
)


class RunMavenWithRetryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.calls = self.directory / "calls.jsonl"
        self.delays = self.directory / "sleeps.txt"
        self.scenario = self.directory / "scenario.json"
        maven = self.directory / "mvn"
        maven.write_text(f"#!{sys.executable}\n" + """
import json
import os
from pathlib import Path
import signal
import sys

calls = Path(os.environ['MAVEN_TEST_CALLS'])
attempt = len(calls.read_text().splitlines()) if calls.exists() else 0
with calls.open('a') as stream:
    stream.write(json.dumps(sys.argv[1:]) + '\\n')
steps = json.loads(Path(os.environ['MAVEN_TEST_SCENARIO']).read_text())
step = steps[min(attempt, len(steps) - 1)]
print(step.get('stdout', ''), end='', flush=True)
print(step.get('stderr', ''), end='', file=sys.stderr, flush=True)
if step.get('wait_for_input'):
    input()
if step.get('signal'):
    os.kill(os.getpid(), signal.SIGTERM)
sys.exit(step.get('exit', 0))
""")
        maven.chmod(0o755)
        self.environment = {
            **os.environ,
            "PATH": str(self.directory) + os.pathsep + os.environ.get("PATH", ""),
            "PYTHONPATH": str(SCRIPTS),
            "MAVEN_TEST_CALLS": str(self.calls),
            "MAVEN_TEST_SCENARIO": str(self.scenario),
            "MAVEN_TEST_SLEEPS": str(self.delays),
        }

    def run_maven(self, steps, arguments=("-B", "verify")):
        self.scenario.write_text(json.dumps(steps))
        self.calls.unlink(missing_ok=True)
        self.delays.unlink(missing_ok=True)
        result = subprocess.run(
            [sys.executable, "-c", DRIVER, *arguments],
            env=self.environment, capture_output=True, text=True, timeout=10,
        )
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()] \
            if self.calls.exists() else []
        return result, calls

    def test_success_preserves_arguments_and_both_output_streams_without_retry(self):
        arguments = ("-B", "verify", "-Dfixture=directory with spaces")
        result, calls = self.run_maven([
            {"stdout": "[INFO] BUILD SUCCESS\n", "stderr": "stderr diagnostic\n"},
        ], arguments)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([list(arguments)], calls)
        self.assertIn("[INFO] BUILD SUCCESS\n", result.stdout)
        self.assertIn("stderr diagnostic\n", result.stdout)
        self.assertFalse(self.delays.exists())

    def test_transient_resolution_failure_retries_with_forced_dependency_update(self):
        for diagnostic in (TRANSFER_429, PARENT_503):
            with self.subTest(diagnostic=diagnostic):
                result, calls = self.run_maven([
                    {"stderr": diagnostic, "exit": 1},
                    {"stdout": "[INFO] BUILD SUCCESS\n"},
                ])
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual([["-B", "verify"], ["-U", "-B", "verify"]], calls)
                self.assertIn(diagnostic, result.stdout)
                self.assertIn("[INFO] BUILD SUCCESS", result.stdout)
                self.assertEqual(["45"], self.delays.read_text().splitlines())

    def test_retry_limit_preserves_the_final_failure_exit_code(self):
        result, calls = self.run_maven([{"stderr": TRANSFER_429, "exit": 17}])
        self.assertEqual(17, result.returncode, result.stderr)
        self.assertEqual(3, len(calls))
        self.assertEqual(3, result.stdout.count(TRANSFER_429))
        self.assertEqual(["45", "45"], self.delays.read_text().splitlines())

    def test_existing_update_option_is_not_duplicated(self):
        result, calls = self.run_maven([
            {"stderr": TRANSFER_429, "exit": 1}, {},
        ], ("-U", "verify"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([["-U", "verify"], ["-U", "verify"]], calls)

    def test_warning_or_unrelated_http_error_does_not_retry_a_build_failure(self):
        for diagnostic in (
            TRANSFER_429.replace("[ERROR]", "[WARNING]") + COMPILE_FAILURE,
            "[ERROR] Remote service returned HTTP Status: 429\n" + TEST_FAILURE,
            "[ERROR] Unknown lifecycle phase 'verfy'\n",
        ):
            with self.subTest(diagnostic=diagnostic):
                result, calls = self.run_maven([{"stderr": diagnostic, "exit": 9}])
                self.assertEqual(9, result.returncode, result.stderr)
                self.assertEqual(1, len(calls))

    def test_compile_test_or_permanent_resolution_failure_blocks_retries(self):
        for diagnostic in (
            COMPILE_FAILURE, TEST_FAILURE,
            TRANSFER_429.replace("429", "404"),
            TRANSFER_429.replace("429", "401"),
            "[ERROR] Could not find artifact missing:artifact:jar:1 in central\n",
        ):
            with self.subTest(diagnostic=diagnostic):
                result, calls = self.run_maven([
                    {"stderr": TRANSFER_429 + diagnostic, "exit": 7},
                ])
                self.assertEqual(7, result.returncode, result.stderr)
                self.assertEqual(1, len(calls))

    def test_new_attempt_does_not_reuse_previous_transient_failure(self):
        result, calls = self.run_maven([
            {"stderr": TRANSFER_429, "exit": 1},
            {"stderr": COMPILE_FAILURE, "exit": 12},
        ])
        self.assertEqual(12, result.returncode, result.stderr)
        self.assertEqual(2, len(calls))

    def test_terminated_maven_is_not_retried_and_preserves_shell_signal_status(self):
        result, calls = self.run_maven([{"stderr": TRANSFER_429, "signal": True}])
        self.assertEqual(143, result.returncode, result.stderr)
        self.assertEqual(1, len(calls))

    def test_missing_maven_returns_command_not_found_status(self):
        empty_path = self.directory / "empty"
        empty_path.mkdir()
        self.environment["PATH"] = str(empty_path)
        result, calls = self.run_maven([{}])
        self.assertEqual(127, result.returncode, result.stderr)
        self.assertEqual([], calls)
        self.assertFalse(self.delays.exists())

    def test_output_reaches_caller_before_maven_exits(self):
        self.scenario.write_text(json.dumps([
            {"stdout": "Maven is still running\n", "wait_for_input": True},
        ]))
        with subprocess.Popen(
            [sys.executable, str(SCRIPTS / "run_maven_with_retry.py"), "verify"],
            env=self.environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True,
        ) as process:
            try:
                ready, _, _ = select.select([process.stdout], [], [], 5)
                self.assertTrue(ready, "Maven output was buffered until process exit")
                self.assertEqual("Maven is still running\n", process.stdout.readline())
                self.assertIsNone(process.poll())
            finally:
                process.communicate("\n", timeout=5)
            self.assertEqual(0, process.returncode)


if __name__ == "__main__":
    unittest.main()
