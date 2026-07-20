#!/usr/bin/env python3

from __future__ import annotations

import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import build_cleanroom_evidence_bundle as bundler


class CleanroomEvidenceBundleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "docs" / "cleanroom").mkdir(parents=True)
        (self.root / "docs" / "cr3-results").mkdir(parents=True)
        (self.root / "docs" / "source.md").write_text("source\n", encoding="utf-8")
        (self.root / "docs" / "cr3-results" / "D1.md").write_text(
            "report\n", encoding="utf-8"
        )
        source = {
            "id": "SRC-001",
            "class": "public-specification",
            "title": "Source",
            "citation": "Citation",
            "verifiedDate": "2026-07-20",
            "permittedUse": "Permitted",
            "prohibitedUse": "Prohibited",
            "repositoryEvidence": ["docs/source.md"],
            "integrity": None,
        }
        experiment = {
            "id": "D1",
            "title": "Experiment",
            "issue": 1,
            "sourceIds": ["SRC-001"],
            "oneChangedVariable": "One",
            "fixedInputs": "Fixed",
            "implementationScope": "Test",
            "commit": "1" * 40,
            "workflowRun": 1,
            "artifactDigest": "sha256:" + "2" * 64,
            "durableReport": "docs/cr3-results/D1.md",
            "verdict": "REJECTED",
            "productionDecision": "No change",
            "status": "complete",
        }
        (self.root / "docs" / "cleanroom" / "source-register.json").write_text(
            json.dumps({"schemaVersion": 1, "sources": [source]}), encoding="utf-8"
        )
        (self.root / "docs" / "cleanroom" / "experiment-ledger.json").write_text(
            json.dumps({"schemaVersion": 1, "experiments": [experiment]}), encoding="utf-8"
        )
        (self.root / "docs" / "cleanroom" / "README.md").write_text(
            "clean room\n", encoding="utf-8"
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_bundle_is_deterministic_and_contains_manifest(self) -> None:
        first = self.root / "first.zip"
        second = self.root / "second.zip"
        first_digest = bundler.build(self.root, first)
        second_digest = bundler.build(self.root, second)
        self.assertEqual(first_digest, second_digest)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with zipfile.ZipFile(first) as archive:
            names = archive.namelist()
            self.assertEqual(sorted(names), names)
            self.assertIn("BUNDLE-MANIFEST.json", names)
            manifest = json.loads(archive.read("BUNDLE-MANIFEST.json"))
            paths = [item["path"] for item in manifest["files"]]
            self.assertIn("docs/cleanroom/source-register.json", paths)
            self.assertIn("docs/cr3-results/D1.md", paths)

    def test_media_is_excluded_from_bundle(self) -> None:
        media = self.root / "docs" / "cleanroom" / "private.png"
        media.write_bytes(b"not an image")
        output = self.root / "bundle.zip"
        bundler.build(self.root, output)
        with zipfile.ZipFile(output) as archive:
            names = archive.namelist()
            self.assertNotIn("docs/cleanroom/private.png", names)
            manifest = json.loads(archive.read("BUNDLE-MANIFEST.json"))
            paths = [item["path"] for item in manifest["files"]]
            self.assertNotIn("docs/cleanroom/private.png", paths)


if __name__ == "__main__":
    unittest.main()
