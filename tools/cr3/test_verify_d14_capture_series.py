"""Exercise the D14 intake contract with temporary test-only file contents."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/cr3/verify_d14_capture_series.py"
TEMPLATE = ROOT / "docs/cr3-patterns/capture-metadata-template.json"


class CapturePreflightTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.series = Path(temporary.name) / "series directory"
        self.series.mkdir()
        self.metadata = json.loads(TEMPLATE.read_text(encoding="utf-8"))
        self.metadata.update(seriesId="preflight-test", captureOrder=[])
        self.write_pair("U00")
        self.write_pair("EX25", companion_extension="HIF")

    def write_pair(self, pattern_id, companion_extension="JPG"):
        directory = self.series / pattern_id
        directory.mkdir()
        for repeat in ("A", "B"):
            exposure_number = 1001 + len(self.metadata["captureOrder"])
            row = {"patternId": pattern_id, "repeat": repeat}
            for stem, extension in (("cr3", "CR3"), ("companion", companion_extension)):
                content = f"test bytes {pattern_id} {repeat} {extension}".encode()
                (directory / f"{repeat}.{extension}").write_bytes(content)
                row[f"{stem}File"] = f"IMG_{exposure_number}.{extension}"
                row[f"{stem}Sha256"] = hashlib.sha256(content).hexdigest()
            self.metadata["captureOrder"].append(row)

    def write_metadata(self):
        (self.series / "capture-metadata.json").write_text(
            json.dumps(self.metadata), encoding="utf-8"
        )

    def run_preflight(self):
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(self.series)],
            cwd=self.series.parent,
            capture_output=True,
            text=True,
            check=False,
        )

    def snapshot(self):
        return {p.relative_to(self.series): p.read_bytes()
                for p in self.series.rglob("*") if p.is_file()}

    def test_verifies_present_pairs_without_writing_inputs(self):
        self.write_metadata()
        before = self.snapshot()

        result = self.run_preflight()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("2 patterns, 4 exposures and 8 file hashes", result.stdout)
        self.assertEqual(self.snapshot(), before)

    def test_rejects_an_unrecorded_repeat(self):
        self.metadata["captureOrder"].pop()
        self.write_metadata()

        result = self.run_preflight()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing repeat B for EX25", result.stderr)

    def test_rejects_nonconsecutive_repeats(self):
        order = self.metadata["captureOrder"]
        order[1], order[2] = order[2], order[1]
        self.write_metadata()

        result = self.run_preflight()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("EX25/A", result.stderr)
        self.assertIn("U00/B", result.stderr)

    def test_rejects_an_unknown_pattern_id(self):
        self.metadata["captureOrder"][0]["patternId"] = "WRONG"
        self.write_metadata()

        result = self.run_preflight()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unknown pattern ID", result.stderr)
        self.assertIn("WRONG", result.stderr)

    def test_rejects_a_pattern_directory_missing_from_capture_order(self):
        self.write_metadata()
        (self.series / "XG0").mkdir()

        result = self.run_preflight()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("XG0", result.stderr)
        self.assertIn("captureOrder", result.stderr)

    def test_rejects_a_missing_repeat_file(self):
        self.write_metadata()
        (self.series / "U00/B.CR3").unlink()

        result = self.run_preflight()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing CR3 file for U00/B", result.stderr)

    def test_rejects_an_empty_capture_even_when_its_hash_matches(self):
        (self.series / "EX25/B.HIF").write_bytes(b"")
        self.metadata["captureOrder"][3]["companionSha256"] = hashlib.sha256(b"").hexdigest()
        self.write_metadata()

        result = self.run_preflight()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Empty capture file EX25/B.HIF", result.stderr)

    def test_rejects_a_companion_hash_mismatch(self):
        self.write_metadata()
        (self.series / "EX25/A.HIF").write_bytes(b"changed fixture")

        result = self.run_preflight()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SHA-256 mismatch", result.stderr)
        self.assertIn("EX25/A.HIF", result.stderr)

    def test_rejects_a_copied_cr3_used_as_a_second_capture(self):
        (self.series / "U00/B.CR3").write_bytes((self.series / "U00/A.CR3").read_bytes())
        order = self.metadata["captureOrder"]
        order[1]["cr3Sha256"] = order[0]["cr3Sha256"]
        self.write_metadata()

        result = self.run_preflight()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Repeated CR3 contents", result.stderr)
        self.assertIn("U00/B.CR3", result.stderr)


if __name__ == "__main__":
    unittest.main()
