#!/usr/bin/env python3

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("prepare_cpd_report.py")
REVISION = "0123456789abcdef0123456789abcdef01234567"
HTML = b'<html><link rel="stylesheet" href="css/site.css"></html>\n'


class PrepareCpdReportTest(unittest.TestCase):

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        workspace = Path(temp.name)
        self.xml = workspace / "cpd.xml"
        self.report = workspace / "duplication-report"
        self.output = workspace / "cpd-site"
        (self.report / "css").mkdir(parents=True)
        (self.report / "cpd.html").write_bytes(HTML)
        (self.report / "css/site.css").write_bytes(b"body { color: black; }\n")

    def run_script(self):
        return subprocess.run([
            sys.executable, str(SCRIPT),
            "--xml", str(self.xml),
            "--report-dir", str(self.report),
            "--output-dir", str(self.output),
            "--revision", REVISION,
        ], capture_output=True, text=True)

    def test_stages_maven_report_assets_and_duplicate_group_count(self):
        xml = b"""<?xml version="1.0" encoding="UTF-8"?>
<pmd-cpd xmlns="https://pmd-code.org/schema/cpd-report">
  <file path="One.java" totalNumberOfTokens="700" />
  <duplication lines="20" tokens="120">
    <file path="One.java" line="11" />
    <file path="One.java" line="58" />
    <file path="Two.java" line="1" />
  </duplication>
  <duplication lines="30" tokens="230">
    <file path="Three.java" line="34" />
    <file path="Four.java" line="12" />
  </duplication>
</pmd-cpd>
"""
        self.xml.write_bytes(xml)

        result = self.run_script()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.output / "cpd.html").read_bytes(), HTML)
        self.assertEqual((self.output / "css/site.css").read_bytes(),
                         (self.report / "css/site.css").read_bytes())
        self.assertEqual((self.output / "cpd.xml").read_bytes(), xml)
        self.assertEqual(json.loads((self.output / "badge.json").read_text()), {
            "schemaVersion": 1, "label": "CPD groups", "message": "2", "color": "blue",
        })
        summary = json.loads((self.output / "summary.json").read_text())
        self.assertEqual(summary["revision"], REVISION)
        self.assertEqual(summary["duplicate_groups"], 2)

    def test_legacy_namespace_counts_only_direct_duplication_children(self):
        self.xml.write_text("""<cpd:pmd-cpd xmlns:cpd="http://pmd.sourceforge.net/report/cpd/1.0.0">
  <cpd:duplication lines="20" tokens="100" />
  <cpd:metadata><cpd:duplication /></cpd:metadata>
</cpd:pmd-cpd>""", encoding="utf-8")

        result = self.run_script()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads((self.output / "badge.json").read_text())["message"], "1")

    def test_successful_zero_report_replaces_older_publication(self):
        self.xml.write_text('<pmd-cpd><file path="One.java" /></pmd-cpd>', encoding="utf-8")
        self.output.mkdir()
        (self.output / "badge.json").write_text('{"message": "7"}', encoding="utf-8")
        (self.output / "stale.css").touch()

        result = self.run_script()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads((self.output / "badge.json").read_text())["message"], "0")
        self.assertEqual(json.loads((self.output / "summary.json").read_text())["duplicate_groups"], 0)
        self.assertFalse((self.output / "stale.css").exists())

    def test_invalid_inputs_leave_previous_badge_untouched(self):
        self.output.mkdir()
        badge = self.output / "badge.json"
        old_badge = b'{"message": "7"}\n'
        cases = {
            "missing XML": (None, True, "cpd.xml"),
            "malformed XML": ("<pmd-cpd>", True, "Malformed CPD XML"),
            "wrong root": ("<pmd/>", True, "pmd-cpd"),
            "processing error": (
                '<pmd-cpd xmlns="https://pmd-code.org/schema/cpd-report">'
                '<error filename="BadFile.java" msg="Lexical error" /></pmd-cpd>',
                True, "BadFile.java",
            ),
            "empty scan": ("<pmd-cpd/>", True, "no scanned files"),
            "unrelated child": (
                '<pmd-cpd><metadata><file path="One.java" /></metadata></pmd-cpd>',
                True, "no scanned files",
            ),
            "blank source path": ('<pmd-cpd><file path=" " /></pmd-cpd>', True, "no scanned files"),
            "missing HTML": ('<pmd-cpd><file path="One.java" /></pmd-cpd>', False, "cpd.html"),
        }

        for name, (xml, has_html, error_text) in cases.items():
            with self.subTest(name=name):
                badge.write_bytes(old_badge)
                if xml is None:
                    self.xml.unlink(missing_ok=True)
                else:
                    self.xml.write_text(xml, encoding="utf-8")
                if has_html:
                    (self.report / "cpd.html").write_bytes(HTML)
                else:
                    (self.report / "cpd.html").unlink()

                result = self.run_script()

                self.assertNotEqual(result.returncode, 0)
                self.assertIn(error_text, result.stderr)
                self.assertEqual(badge.read_bytes(), old_badge)


if __name__ == "__main__":
    unittest.main()
