#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import subprocess
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
        self.generated = {
            "schemaVersion": 1,
            "resultsRoot": "docs/cr3-results",
            "entries": [{
                "id": "D1-report",
                "classification": "historical-manual",
                "ownerModule": "docs",
                "inputs": ["docs/source.md"],
                "outputs": ["docs/cr3-results/D1.md"],
                "ledgerExperiment": "D1",
                "timestampPolicy": "not-applicable",
                "rationale": "Synthetic historical report used by the bundle test.",
            }],
        }
        (self.root / "docs" / "cleanroom" / "source-register.json").write_text(
            json.dumps({"schemaVersion": 1, "sources": [source]}), encoding="utf-8"
        )
        (self.root / "docs" / "cleanroom" / "experiment-ledger.json").write_text(
            json.dumps({"schemaVersion": 1, "experiments": [experiment]}), encoding="utf-8"
        )
        self.write_inventory()
        (self.root / "docs" / "cleanroom" / "README.md").write_text(
            "clean room\n", encoding="utf-8"
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_inventory(self) -> None:
        (self.root / "docs/cleanroom/generated-evidence.json").write_text(
            json.dumps(self.generated), encoding="utf-8"
        )

    def write_file(self, relative: str, data: bytes) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

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
            self.assertIn("docs/cleanroom/generated-evidence.json", paths)
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

    def test_nested_registered_text_is_in_archive_and_manifest(self) -> None:
        rg4 = "docs/cr3-results/2026-11-14-crx-native"
        rg5 = "docs/cr3-results/2026-11-14-crx-wavelet"
        reports = {
            f"{rg4}/README.md": b"# RG4\n",
            f"{rg4}/full-sensor.tsv": b"camera\terror\nEOS R\t0\n",
            f"{rg5}/README.md": b"# RG5\n",
            f"{rg5}/craw-sensors.tsv": b"camera\terror\nEOS RP\t0\n",
        }
        registered_media = f"{rg4}/preview.png"
        for relative, data in reports.items():
            self.write_file(relative, data)
        self.write_file(registered_media, b"excluded media")
        self.generated["entries"][0]["outputs"].extend(
            [f"{rg4}/**", f"{rg5}/README.md", f"{rg5}/craw-sensors.tsv"]
        )
        self.write_inventory()

        output = self.root / "bundle.zip"
        bundler.build(self.root, output)
        with zipfile.ZipFile(output) as archive:
            manifest = json.loads(archive.read("BUNDLE-MANIFEST.json"))
            files = {entry["path"]: entry for entry in manifest["files"]}
            for relative, data in reports.items():
                self.assertIn(relative, archive.namelist())
                self.assertEqual(archive.read(relative), data)
                self.assertEqual(files[relative], {
                    "path": relative,
                    "bytes": len(data),
                    "sha256": hashlib.sha256(data).hexdigest(),
                })
            self.assertNotIn(registered_media, archive.namelist())
            self.assertNotIn(registered_media, files)

    def test_canonical_documentation_and_license_are_included(self) -> None:
        expected = {
            "CONTRIBUTING.md",
            "LICENSE",
            "docs/cr3/current-state.md",
            "docs/cr3/fiji-update-site.md",
            "docs/cr3/format.md",
            "docs/cr3/imageio.md",
            "docs/cr3/open-hypotheses.md",
            "docs/cr3/reports/index.md",
            "docs/public-release.md",
        }
        for relative in expected:
            self.write_file(relative, b"text\n")

        output = self.root / "bundle.zip"
        bundler.build(self.root, output)
        with zipfile.ZipFile(output) as archive:
            self.assertLessEqual(expected, set(archive.namelist()))

    def test_untracked_text_is_excluded_in_a_git_checkout(self) -> None:
        subprocess.run(["git", "init", "-q", self.root], check=True)
        subprocess.run(["git", "-C", self.root, "add", "."], check=True)
        untracked = (
            "docs/cr3-results/local-scratch.md",
            "docs/cleanroom/local-scratch.md",
        )
        for relative in untracked:
            self.write_file(relative, b"untracked text\n")

        output = self.root / "bundle.zip"
        bundler.build(self.root, output)
        with zipfile.ZipFile(output) as archive:
            self.assertTrue(set(untracked).isdisjoint(archive.namelist()))

    def test_rejects_symlinked_registered_text_before_writing_archive(self) -> None:
        report = self.root / "docs/cr3-results/D1.md"
        report.unlink()
        report.symlink_to(self.root / "docs/source.md")

        output = self.root / "bundle.zip"
        with self.assertRaisesRegex(ValueError, r"symlink.*D1\.md"):
            bundler.build(self.root, output)
        self.assertFalse(output.exists())

    def test_rejects_registered_text_under_a_symlinked_directory(self) -> None:
        subprocess.run(["git", "init", "-q", self.root], check=True)
        subprocess.run(["git", "-C", self.root, "add", "."], check=True)
        results = self.root / "docs/cr3-results"
        output = self.root / "bundle.zip"

        with tempfile.TemporaryDirectory() as outside:
            target = Path(outside) / "results"
            results.rename(target)
            results.symlink_to(target, target_is_directory=True)

            with self.assertRaisesRegex(ValueError, r"symlink.*D1\.md"):
                bundler.build(self.root, output)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
