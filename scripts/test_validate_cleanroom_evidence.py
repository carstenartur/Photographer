#!/usr/bin/env python3

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import validate_cleanroom_evidence as validator


class CleanroomEvidenceValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "docs" / "cleanroom").mkdir(parents=True)
        (self.root / "docs" / "cr3-results").mkdir(parents=True)
        (self.root / "docs" / "source.md").write_text("source", encoding="utf-8")
        (self.root / "docs" / "cr3-results" / "D1.md").write_text(
            "report", encoding="utf-8"
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_manifests(self, source_overrides=None, experiment_overrides=None) -> None:
        source = {
            "id": "SRC-001",
            "class": "public-specification",
            "title": "Public source",
            "citation": "Citation",
            "verifiedDate": "2026-07-20",
            "permittedUse": "Permitted",
            "prohibitedUse": "Not permitted",
            "repositoryEvidence": ["docs/source.md"],
            "integrity": None,
        }
        experiment = {
            "id": "D1",
            "title": "Experiment",
            "issue": 1,
            "sourceIds": ["SRC-001"],
            "oneChangedVariable": "One variable",
            "fixedInputs": "Fixed fixtures",
            "implementationScope": "Test only",
            "commit": "1" * 40,
            "workflowRun": 1,
            "artifactDigest": "sha256:" + "2" * 64,
            "durableReport": "docs/cr3-results/D1.md",
            "verdict": "REJECTED",
            "productionDecision": "No change",
            "status": "complete",
        }
        if source_overrides:
            source.update(source_overrides)
        if experiment_overrides:
            experiment.update(experiment_overrides)
        (self.root / "docs" / "cleanroom" / "source-register.json").write_text(
            json.dumps({"schemaVersion": 1, "sources": [source]}), encoding="utf-8"
        )
        (self.root / "docs" / "cleanroom" / "experiment-ledger.json").write_text(
            json.dumps({"schemaVersion": 1, "experiments": [experiment]}), encoding="utf-8"
        )

    def test_accepts_valid_repository(self) -> None:
        self.write_manifests()
        self.assertEqual([], validator.validate_repository(self.root))

    def test_rejects_unknown_source_id(self) -> None:
        self.write_manifests(experiment_overrides={"sourceIds": ["SRC-999"]})
        errors = validator.validate_repository(self.root)
        self.assertTrue(any("unknown source IDs" in error for error in errors))

    def test_rejects_missing_repository_path(self) -> None:
        self.write_manifests(source_overrides={"repositoryEvidence": ["docs/missing.md"]})
        errors = validator.validate_repository(self.root)
        self.assertTrue(any("path does not exist" in error for error in errors))

    def test_rejects_completed_experiment_without_run_metadata_or_reason(self) -> None:
        self.write_manifests(experiment_overrides={
            "commit": None,
            "workflowRun": None,
            "artifactDigest": None,
        })
        errors = validator.validate_repository(self.root)
        self.assertTrue(any("missingRunMetadataReason" in error for error in errors))

    def test_accepts_documented_historical_metadata_gap(self) -> None:
        self.write_manifests(experiment_overrides={
            "commit": None,
            "workflowRun": None,
            "artifactDigest": None,
            "missingRunMetadataReason": "Historical experiment",
        })
        self.assertEqual([], validator.validate_repository(self.root))

    def test_rejects_invalid_digest(self) -> None:
        self.write_manifests(experiment_overrides={"artifactDigest": "sha256:bad"})
        errors = validator.validate_repository(self.root)
        self.assertTrue(any("invalid artifact digest" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
