#!/usr/bin/env python3
"""Tests for audit_historical_media.py."""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).with_name("audit_historical_media.py")
SPEC = importlib.util.spec_from_file_location("audit_historical_media", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
HISTORY = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = HISTORY
SPEC.loader.exec_module(HISTORY)


def git(root: Path, *args: str) -> None:
    subprocess.run(
        ["git", "-C", str(root), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


class HistoricalMediaAuditTest(unittest.TestCase):

    def test_lists_removed_historical_media_blob(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            git(root, "init")
            git(root, "config", "user.email", "audit@example.invalid")
            git(root, "config", "user.name", "Audit Test")

            fixture = root / "fixture.cr3"
            fixture.write_bytes(b"first binary fixture")
            git(root, "add", "fixture.cr3")
            git(root, "commit", "-m", "first fixture")
            fixture.write_bytes(b"second binary fixture")
            git(root, "commit", "-am", "replace fixture")
            fixture.unlink()
            git(root, "commit", "-am", "remove fixture")

            objects = HISTORY.historical_media_objects(root)
            paths = [path for _, path in objects]
            self.assertEqual(["fixture.cr3", "fixture.cr3"], paths)

            payloads = [payload for _, _, payload in HISTORY.iter_git_blobs(root, objects)]
            self.assertEqual(
                {b"first binary fixture", b"second binary fixture"},
                set(payloads),
            )

    def test_exiftool_findings_are_mapped_to_redacted_history_source(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            directory = Path(temp_dir)
            media = directory / "000001.cr3"
            media.write_bytes(b"fixture")
            fake_exiftool = directory / "exiftool"
            fake_exiftool.write_text(
                "#!/usr/bin/env python3\n"
                "import json, sys\n"
                "paths = sys.argv[sys.argv.index('--') + 1:]\n"
                "print(json.dumps([{'SourceFile': p, "
                "'ExifIFD:SerialNumber': 'private-value'} for p in paths]))\n",
                encoding="utf-8",
            )
            os.chmod(fake_exiftool, 0o755)
            source = "0123456789ab:old/fixture.cr3"

            findings = HISTORY.inspect_materialized(
                str(fake_exiftool),
                [media],
                {str(media.resolve()): source},
            )

            self.assertEqual(1, len(findings))
            self.assertEqual(source, findings[0].source)
            self.assertEqual("ExifIFD:SerialNumber", findings[0].field)
            self.assertNotIn("private-value", findings[0].describe())

    def test_shallow_clone_cannot_certify_historical_media(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            source.mkdir()
            git(source, "init")
            git(source, "config", "user.email", "audit@example.invalid")
            git(source, "config", "user.name", "Audit Test")
            (source / "old.cr3").write_bytes(b"old media")
            git(source, "add", "old.cr3")
            git(source, "commit", "-m", "original")
            git(source, "rm", "old.cr3")
            git(source, "commit", "-m", "remove media")
            shallow = root / "shallow"
            git(root, "clone", "--depth=1", source.as_uri(), str(shallow))
            with self.assertRaisesRegex(RuntimeError, "full clone"):
                HISTORY.audit_historical_media(shallow, "unused")

    def test_non_media_paths_are_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            git(root, "init")
            git(root, "config", "user.email", "audit@example.invalid")
            git(root, "config", "user.name", "Audit Test")
            (root / "README.md").write_text("not media", encoding="utf-8")
            git(root, "add", "README.md")
            git(root, "commit", "-m", "text")

            self.assertEqual([], HISTORY.historical_media_objects(root))


if __name__ == "__main__":
    unittest.main()
