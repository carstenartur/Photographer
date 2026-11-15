#!/usr/bin/env python3
"""Inspect every reachable historical CR3/image blob for identifying metadata.

Unlike audit_public_release.py --media-metadata, this command materializes every unique
media blob reachable from any local branch or tag and inspects it with ExifTool. Values
are never printed; findings contain only the blob, historical path and field name.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Iterator, Sequence

AUDIT_PATH = Path(__file__).with_name("audit_public_release.py")
SPEC = importlib.util.spec_from_file_location("audit_public_release", AUDIT_PATH)
assert SPEC is not None and SPEC.loader is not None
AUDIT = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = AUDIT
SPEC.loader.exec_module(AUDIT)

MEDIA_SUFFIXES = frozenset(
    {".cr3", ".jpg", ".jpeg", ".tif", ".tiff", ".png"}
)
EXIFTOOL_BATCH_SIZE = 40


class HistoricalMediaAuditError(RuntimeError):
    """Raised when historical media cannot be inspected completely."""


def historical_media_objects(root: Path) -> list[tuple[str, str]]:
    """Return unique reachable blob IDs with a representative historical media path."""
    output = AUDIT.run_git(root, "rev-list", "--objects", "--all")
    objects: dict[str, str] = {}
    for raw_line in output.splitlines():
        parts = raw_line.decode("utf-8", errors="surrogateescape").split(" ", 1)
        if len(parts) != 2:
            continue
        object_id, path = parts
        if Path(path).suffix.lower() not in MEDIA_SUFFIXES:
            continue
        objects.setdefault(object_id, path)
    return sorted(objects.items(), key=lambda item: (item[1], item[0]))


def iter_git_blobs(
    root: Path, objects: Sequence[tuple[str, str]]
) -> Iterator[tuple[str, str, bytes]]:
    process = subprocess.Popen(
        ["git", "-C", str(root), "cat-file", "--batch"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert process.stdin is not None
    assert process.stdout is not None
    try:
        for object_id, path in objects:
            process.stdin.write(object_id.encode("ascii") + b"\n")
            process.stdin.flush()
            header = process.stdout.readline()
            if not header:
                raise HistoricalMediaAuditError(
                    "git cat-file terminated unexpectedly"
                )
            parts = header.rstrip(b"\n").split()
            if len(parts) < 3:
                raise HistoricalMediaAuditError(
                    "unexpected git cat-file response: "
                    + header.decode("utf-8", errors="replace")
                )
            object_type = parts[1].decode("ascii", errors="replace")
            size = int(parts[2])
            payload = process.stdout.read(size)
            terminator = process.stdout.read(1)
            if terminator != b"\n":
                raise HistoricalMediaAuditError(
                    "git cat-file returned a malformed batch payload"
                )
            if object_type != "blob":
                raise HistoricalMediaAuditError(
                    f"historical media object {object_id} is {object_type}, not a blob"
                )
            yield object_id, path, payload
    finally:
        process.stdin.close()
        process.terminate()
        process.wait(timeout=5)
        process.stdout.close()
        if process.stderr is not None:
            process.stderr.close()


def _chunks(values: Sequence[Path], size: int) -> Iterator[Sequence[Path]]:
    for index in range(0, len(values), size):
        yield values[index : index + size]


def inspect_materialized(
    exiftool: str,
    paths: Sequence[Path],
    source_by_path: dict[str, str],
) -> list[AUDIT.Finding]:
    findings: list[AUDIT.Finding] = []
    for batch in _chunks(paths, EXIFTOOL_BATCH_SIZE):
        command = [exiftool, "-json", "-G1", "-s", "-n", "--"]
        command.extend(str(path) for path in batch)
        completed = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if completed.returncode != 0:
            message = completed.stderr.decode("utf-8", errors="replace").strip()
            raise HistoricalMediaAuditError(f"ExifTool failed: {message}")
        try:
            records = json.loads(completed.stdout.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise HistoricalMediaAuditError(
                f"ExifTool returned invalid JSON: {exc}"
            ) from exc
        if not isinstance(records, list):
            raise HistoricalMediaAuditError(
                "ExifTool JSON must contain a list of metadata records"
            )
        for record in records:
            if not isinstance(record, dict):
                continue
            raw_source = str(record.get("SourceFile", ""))
            key = str(Path(raw_source).resolve())
            record["SourceFile"] = source_by_path.get(key, "<unknown-history-blob>")
        findings.extend(AUDIT.metadata_findings(records))
    return findings


def audit_historical_media(root: Path, exiftool: str) -> list[AUDIT.Finding]:
    AUDIT.require_complete_history(root)
    objects = historical_media_objects(root)
    if not objects:
        return []
    with tempfile.TemporaryDirectory(prefix="photographer-history-media-") as temp_dir:
        directory = Path(temp_dir)
        materialized: list[Path] = []
        source_by_path: dict[str, str] = {}
        for index, (object_id, historical_path, payload) in enumerate(
            iter_git_blobs(root, objects)
        ):
            suffix = Path(historical_path).suffix.lower() or ".bin"
            target = directory / f"{index:06d}-{object_id[:12]}{suffix}"
            target.write_bytes(payload)
            materialized.append(target)
            source_by_path[str(target.resolve())] = (
                f"{object_id[:12]}:{historical_path}"
            )
        return inspect_materialized(exiftool, materialized, source_by_path)


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Inspect all reachable historical CR3/image blobs for identifying "
            "metadata using ExifTool."
        )
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Repository root.",
    )
    parser.add_argument(
        "--exiftool",
        default=None,
        help="ExifTool executable (default: resolve exiftool from PATH).",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    root = args.root.resolve()
    exiftool = args.exiftool or shutil.which("exiftool")
    if not exiftool:
        print(
            "historical media audit error: ExifTool is required; install exiftool "
            "or libimage-exiftool-perl",
            file=sys.stderr,
        )
        return 2
    try:
        AUDIT.run_git(root, "rev-parse", "--show-toplevel")
        findings = audit_historical_media(root, exiftool)
    except (RuntimeError, OSError) as exc:
        print(f"historical media audit error: {exc}", file=sys.stderr)
        return 2

    unique = sorted(
        set(findings),
        key=lambda item: (
            item.category,
            item.source,
            item.line or 0,
            item.field or "",
        ),
    )
    if unique:
        print(
            "Historical media audit failed. Reachable media blobs contain "
            "identifying metadata:"
        )
        for finding in unique:
            print(f"  - {finding.describe()}")
        print("Values are deliberately redacted.")
        return 1

    print("Historical media audit passed: all reachable media blobs inspected.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
