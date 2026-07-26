#!/usr/bin/env python3
"""Tests for audit_public_release.py."""

from __future__ import annotations

import contextlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).with_name("audit_public_release.py")
SPEC = importlib.util.spec_from_file_location("audit_public_release", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
AUDIT = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = AUDIT
SPEC.loader.exec_module(AUDIT)


def git(root: Path, *args: str) -> None:
    subprocess.run(
        ["git", "-C", str(root), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def initialize_repository(root: Path) -> None:
    git(root, "init")
    git(root, "config", "user.email", "audit@example.invalid")
    git(root, "config", "user.name", "Audit Test")


class PublicReleaseAuditTest(unittest.TestCase):

    def test_detects_high_confidence_secret_without_exposing_value(self) -> None:
        token = "ghp_" + ("A" * 36)
        findings = AUDIT.scan_bytes("config.txt", f"token={token}\n".encode())
        self.assertEqual(1, len(findings))
        self.assertEqual("github-token", findings[0].category)
        self.assertNotIn(token, findings[0].describe())

    def test_detects_armored_pgp_private_key_block(self) -> None:
        header = b"-----BEGIN PGP " + b"PRIVATE KEY BLOCK-----"
        findings = AUDIT.scan_bytes("private.asc", header + b"\nredacted\n")
        self.assertEqual(1, len(findings))
        self.assertEqual("private-key", findings[0].category)
        self.assertEqual(1, findings[0].line)

    def test_rejects_non_numeric_git_batch_object_size(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "invalid git cat-file object size"):
            AUDIT._parse_batch_header(b"0123456789abcdef blob not-a-size\n")

    def test_skips_binary_payload(self) -> None:
        token = b"ghp_" + (b"A" * 36)
        self.assertEqual([], AUDIT.scan_bytes("fixture.cr3", b"\x00" + token))

    def test_current_tree_skips_large_tracked_file_before_reading_it(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            initialize_repository(root)
            large_file = root / "large-generated.txt"
            with large_file.open("wb") as handle:
                handle.seek(AUDIT.MAX_TEXT_BLOB_BYTES)
                handle.write(b"X")
            git(root, "add", large_file.name)
            git(root, "commit", "-m", "large tracked output")

            self.assertEqual([], AUDIT.audit_current_tree(root))

    def test_history_scan_finds_removed_secret_with_large_blob_present(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            initialize_repository(root)

            large_file = root / "large.bin"
            with large_file.open("wb") as handle:
                handle.seek(AUDIT.MAX_TEXT_BLOB_BYTES)
                handle.write(b"\x00")
            secret_file = root / "secret.txt"
            secret_file.write_text("safe\n", encoding="utf-8")
            git(root, "add", "large.bin", "secret.txt")
            git(root, "commit", "-m", "safe and large")

            token = "github_pat_" + ("B" * 40)
            secret_file.write_text(token + "\n", encoding="utf-8")
            git(root, "commit", "-am", "add temporary credential")
            secret_file.write_text("safe again\n", encoding="utf-8")
            git(root, "commit", "-am", "remove credential")

            self.assertEqual([], AUDIT.audit_current_tree(root))
            history_findings = AUDIT.audit_history(root)
            self.assertTrue(
                any(item.category == "github-token" for item in history_findings)
            )

    def test_main_normalizes_a_subdirectory_to_repository_toplevel(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            initialize_repository(root)
            (root / "README.md").write_text("safe\n", encoding="utf-8")
            subdirectory = root / "nested" / "directory"
            subdirectory.mkdir(parents=True)
            git(root, "add", "README.md")
            git(root, "commit", "-m", "safe repository")

            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                status = AUDIT.main(["--root", str(subdirectory)])

            self.assertEqual(0, status)
            self.assertIn("Public-release audit passed", output.getvalue())

    def test_metadata_scan_flags_location_identity_and_serial(self) -> None:
        records = [
            {
                "SourceFile": "sample.cr3",
                "EXIF:GPSLatitude": 50.0,
                "EXIF:BodySerialNumber": "123",
                "XMP:Creator": "Example Person",
                "EXIF:ISO": 200,
            }
        ]
        findings = AUDIT.metadata_findings(records)
        fields = {item.field for item in findings}
        self.assertEqual(
            {"EXIF:GPSLatitude", "EXIF:BodySerialNumber", "XMP:Creator"},
            fields,
        )

    def test_metadata_scan_ignores_empty_sensitive_fields(self) -> None:
        records = [
            {
                "SourceFile": "sample.cr3",
                "EXIF:GPSLatitude": None,
                "EXIF:SerialNumber": "",
                "EXIF:ISO": 200,
            }
        ]
        self.assertEqual([], AUDIT.metadata_findings(records))


if __name__ == "__main__":
    unittest.main()
