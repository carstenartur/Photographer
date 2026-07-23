#!/usr/bin/env python3
"""Tests for manage_cr3_generated_evidence.py."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from manage_cr3_generated_evidence import (
    EvidenceContractError,
    compare_generated,
    git_blob_sha1,
    update_generated,
    validate_repository,
)


class GeneratedEvidenceContractTest(unittest.TestCase):

    def test_accepts_complete_unique_inventory_and_locked_managed_output(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._base_files(root)
            output = self._write(root, "docs/cr3-results/managed.tsv", "a\tb\n")
            self._write(root, "docs/cr3-results/history.md", "# History\n")
            self._manifest(root, [
                self._managed(output),
                self._historical(),
            ])

            self.assertEqual([], validate_repository(root))

    def test_rejects_unregistered_result(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._base_files(root)
            output = self._write(root, "docs/cr3-results/managed.tsv", "a\tb\n")
            self._write(root, "docs/cr3-results/unregistered.md", "# Missing\n")
            self._manifest(root, [self._managed(output)])

            self.assertTrue(any(
                "unregistered generated evidence" in error
                for error in validate_repository(root)
            ))

    def test_rejects_duplicate_output_ownership(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._base_files(root)
            output = self._write(root, "docs/cr3-results/managed.tsv", "a\tb\n")
            first = self._managed(output)
            second = self._historical("duplicate")
            second["outputs"] = ["docs/cr3-results/managed.tsv"]
            self._manifest(root, [first, second])

            self.assertTrue(any(
                "multiple owners" in error
                for error in validate_repository(root)
            ))

    def test_rejects_historical_entry_without_rationale(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._base_files(root)
            self._write(root, "docs/cr3-results/history.md", "# History\n")
            historical = self._historical()
            historical.pop("rationale")
            self._manifest(root, [historical])

            self.assertTrue(any(
                "requires a rationale" in error
                for error in validate_repository(root)
            ))

    def test_rejects_stale_git_blob_lock(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._base_files(root)
            output = self._write(root, "docs/cr3-results/managed.tsv", "a\tb\n")
            managed = self._managed(output)
            managed["gitBlobLocks"][output.relative_to(root).as_posix()] = "0" * 40
            self._manifest(root, [managed])

            self.assertTrue(any(
                "stale Git blob lock" in error
                for error in validate_repository(root)
            ))

    def test_compare_is_read_only_and_detects_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._base_files(root)
            output = self._write(root, "docs/cr3-results/managed.tsv", "same\n")
            self._manifest(root, [self._managed(output)])
            generated = self._write(root, "generated/results.tsv", "same\n")

            compare_generated(root, "managed", generated.parent)
            generated.write_text("changed\n", encoding="utf-8")
            with self.assertRaisesRegex(EvidenceContractError, "generated output differs"):
                compare_generated(root, "managed", generated.parent)
            self.assertEqual("same\n", output.read_text(encoding="utf-8"))

    def test_update_copies_generated_output_and_refreshes_lock(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._base_files(root)
            output = self._write(root, "docs/cr3-results/managed.tsv", "old\n")
            self._manifest(root, [self._managed(output)])
            generated = self._write(root, "generated/results.tsv", "new\n")

            update_generated(root, "managed", generated.parent)

            self.assertEqual("new\n", output.read_text(encoding="utf-8"))
            manifest = json.loads(
                (root / "docs/cleanroom/generated-evidence.json").read_text(
                    encoding="utf-8"))
            lock = manifest["entries"][0]["gitBlobLocks"][
                "docs/cr3-results/managed.tsv"]
            self.assertEqual(git_blob_sha1(output), lock)

    def test_managed_entry_requires_generator_command_mapping_and_lock(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self._base_files(root)
            output = self._write(root, "docs/cr3-results/managed.tsv", "a\n")
            managed = self._managed(output)
            managed.pop("generateCommand")
            managed["generatedMappings"] = []
            managed["gitBlobLocks"] = {}
            self._manifest(root, [managed])

            errors = validate_repository(root)
            self.assertTrue(any("requires generateCommand" in error for error in errors))
            self.assertTrue(any("requires generatedMappings" in error for error in errors))

    def _base_files(self, root: Path) -> None:
        self._write(root, "generator.java", "class Generator {}\n")
        self._write(root, "inputs/manifest.json", "{}\n")
        self._write(root, "docs/cleanroom/experiment-ledger.json", json.dumps({
            "schemaVersion": 1,
            "experiments": [{"id": "D12"}],
        }))

    def _managed(self, output: Path) -> dict:
        destination = output.as_posix().split("/docs/", 1)[-1]
        destination = "docs/" + destination
        return {
            "id": "managed",
            "classification": "managed-generated",
            "ownerModule": "module",
            "generator": "generator.java",
            "inputs": ["inputs/manifest.json"],
            "outputs": [destination],
            "ledgerExperiment": "D12",
            "timestampPolicy": "prohibited",
            "generateCommand": "tool generate",
            "generatedMappings": [{
                "source": "results.tsv",
                "destination": destination,
            }],
            "gitBlobLocks": {destination: git_blob_sha1(output)},
        }

    @staticmethod
    def _historical(entry_id: str = "historical") -> dict:
        return {
            "id": entry_id,
            "classification": "historical-manual",
            "ownerModule": "docs",
            "inputs": ["inputs/manifest.json"],
            "outputs": ["docs/cr3-results/history.md"],
            "timestampPolicy": "not-applicable",
            "rationale": "Preserved historical snapshot.",
        }

    def _manifest(self, root: Path, entries: list[dict]) -> None:
        self._write(root, "docs/cleanroom/generated-evidence.json", json.dumps({
            "schemaVersion": 1,
            "resultsRoot": "docs/cr3-results",
            "entries": entries,
        }, indent=2) + "\n")

    @staticmethod
    def _write(root: Path, relative: str, content: str) -> Path:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main()
