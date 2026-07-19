#!/usr/bin/env python3
"""Unit tests for the checkout-local CR3 reference-manifest validator."""

from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

from validate_cr3_reference_manifest import (
    END_MARKER,
    EXPECTED_HEADER,
    START_MARKER,
    validate,
)


class ReferenceManifestPathValidationTest(unittest.TestCase):

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.samples = self.root / "samples"
        self.samples.mkdir()
        self.manifest = self.root / "manifest.md"

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_accepts_nested_fixture_and_matching_sidecar(self) -> None:
        fixture = self.samples / "research" / "sample.cr3"
        sidecar = self.samples / "research" / "sample.reference.tiff"
        fixture.parent.mkdir()
        fixture.write_bytes(b"fixture")
        sidecar.write_bytes(b"reference")

        self.write_manifest(
            "research/sample.cr3",
            "reference.tiff",
            hashlib.sha256(b"reference").hexdigest(),
        )

        self.assertEqual([], validate(self.manifest, self.samples))

    def test_rejects_absolute_fixture_path(self) -> None:
        self.write_manifest(
            "/tmp/sample.cr3",
            "reference.tiff",
            "0" * 64,
        )

        errors = validate(self.manifest, self.samples)

        self.assertTrue(any("unsafe path segment" in error for error in errors), errors)

    def test_rejects_parent_traversal_fixture_path(self) -> None:
        self.write_manifest(
            "../sample.cr3",
            "reference.tiff",
            "0" * 64,
        )

        errors = validate(self.manifest, self.samples)

        self.assertTrue(any("unsafe path segment" in error for error in errors), errors)

    def test_rejects_platform_specific_fixture_path(self) -> None:
        self.write_manifest(
            "C:\\outside\\sample.cr3",
            "reference.tiff",
            "0" * 64,
        )

        errors = validate(self.manifest, self.samples)

        self.assertTrue(any("portable POSIX path" in error for error in errors), errors)

    def test_rejects_sidecar_path_instead_of_filename_component(self) -> None:
        self.write_manifest(
            "sample.cr3",
            "../../outside.tiff",
            "0" * 64,
        )

        errors = validate(self.manifest, self.samples)

        self.assertTrue(any("unsafe sidecar name" in error for error in errors), errors)

    def test_rejects_symlink_that_resolves_outside_samples_root(self) -> None:
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "sample.cr3").write_bytes(b"fixture")
        (outside / "sample.reference.tiff").write_bytes(b"reference")
        try:
            (self.samples / "research").symlink_to(outside, target_is_directory=True)
        except OSError as error:
            self.skipTest(f"symbolic links are unavailable: {error}")

        self.write_manifest(
            "research/sample.cr3",
            "reference.tiff",
            hashlib.sha256(b"reference").hexdigest(),
        )

        errors = validate(self.manifest, self.samples)

        self.assertTrue(any("escapes samples directory" in error for error in errors), errors)

    def write_manifest(self, fixture: str, sidecar: str, digest: str) -> None:
        row = "|".join(
            (
                fixture,
                sidecar,
                "LibRaw",
                "test-version",
                "python export.py",
                digest,
                "1",
                "1",
                "16",
                "RAW_LINEAR",
                "true",
            )
        )
        self.manifest.write_text(
            "\n".join(
                (
                    "# Test manifest",
                    START_MARKER,
                    "```text",
                    EXPECTED_HEADER,
                    row,
                    "```",
                    END_MARKER,
                    "",
                )
            ),
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
