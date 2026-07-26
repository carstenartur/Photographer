#!/usr/bin/env python3
"""Audit a checkout for high-confidence secrets and sensitive media metadata."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator, Sequence

MAX_TEXT_BLOB_BYTES = 2_000_000

SECRET_PATTERNS: tuple[tuple[str, re.Pattern[bytes]], ...] = (
    (
        "private-key",
        re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----"),
    ),
    (
        "github-token",
        re.compile(
            rb"\b(?:gh[pousr]_[A-Za-z0-9]{20,255}|github_pat_[A-Za-z0-9_]{20,255})\b"
        ),
    ),
    ("google-api-key", re.compile(rb"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("aws-access-key", re.compile(rb"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    (
        "openai-api-key",
        re.compile(rb"\bsk-(?:proj-)?[A-Za-z0-9_-]{20,255}\b"),
    ),
    ("slack-token", re.compile(rb"\bxox[baprs]-[0-9A-Za-z-]{20,255}\b")),
)

SENSITIVE_METADATA_KEYS = frozenset(
    {
        "gpslatitude",
        "gpslatituderef",
        "gpslongitude",
        "gpslongituderef",
        "gpsposition",
        "gpscoordinates",
        "gpslocation",
        "geolocation",
        "location",
        "locationcreated",
        "sublocation",
        "city",
        "state",
        "province",
        "country",
        "countrycode",
        "cameraserialnumber",
        "bodyserialnumber",
        "internalserialnumber",
        "lensserialnumber",
        "serialnumber",
        "cameraownername",
        "ownername",
        "artist",
        "creator",
        "author",
        "copyright",
    }
)

MEDIA_GLOBS = (
    "*.cr3",
    "*.CR3",
    "*.jpg",
    "*.JPG",
    "*.jpeg",
    "*.JPEG",
    "*.tif",
    "*.TIF",
    "*.tiff",
    "*.TIFF",
    "*.png",
    "*.PNG",
)


@dataclass(frozen=True)
class Finding:
    category: str
    source: str
    line: int | None = None
    field: str | None = None

    def describe(self) -> str:
        location = self.source
        if self.line is not None:
            location += f":{self.line}"
        detail = f" field={self.field}" if self.field else ""
        return f"{self.category}: {location}{detail}"


def run_git(root: Path, *args: str) -> bytes:
    completed = subprocess.run(
        ["git", "-C", str(root), *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        message = completed.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"git {' '.join(args)} failed: {message}")
    return completed.stdout


def is_probably_text(data: bytes) -> bool:
    return b"\x00" not in data[:8192]


def scan_bytes(source: str, data: bytes) -> list[Finding]:
    """Return high-confidence findings without retaining or printing secret values."""
    if len(data) > MAX_TEXT_BLOB_BYTES or not is_probably_text(data):
        return []

    findings: list[Finding] = []
    for category, pattern in SECRET_PATTERNS:
        for match in pattern.finditer(data):
            line = data.count(b"\n", 0, match.start()) + 1
            findings.append(Finding(category=category, source=source, line=line))
    return findings


def tracked_paths(root: Path, globs: Sequence[str] | None = None) -> list[Path]:
    args = ["ls-files", "-z"]
    if globs:
        args.extend(["--", *globs])
    output = run_git(root, *args)
    return [
        Path(item.decode("utf-8", errors="surrogateescape"))
        for item in output.split(b"\x00")
        if item
    ]


def audit_current_tree(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for relative_path in tracked_paths(root):
        path = root / relative_path
        if not path.is_file():
            continue
        try:
            data = path.read_bytes()
        except OSError as exc:
            raise RuntimeError(f"cannot read tracked file {relative_path}: {exc}") from exc
        findings.extend(scan_bytes(str(relative_path), data))
    return findings


def iter_history_blobs(root: Path) -> Iterator[tuple[str, str, bytes]]:
    objects: dict[str, str] = {}
    for raw_line in run_git(root, "rev-list", "--objects", "--all").splitlines():
        if not raw_line:
            continue
        parts = raw_line.decode("utf-8", errors="surrogateescape").split(" ", 1)
        object_id = parts[0]
        path = parts[1] if len(parts) == 2 else "<unknown>"
        objects.setdefault(object_id, path)

    process = subprocess.Popen(
        ["git", "-C", str(root), "cat-file", "--batch"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert process.stdin is not None
    assert process.stdout is not None

    try:
        for object_id, path in objects.items():
            process.stdin.write(object_id.encode("ascii") + b"\n")
            process.stdin.flush()

            header = process.stdout.readline()
            if not header:
                raise RuntimeError("git cat-file terminated unexpectedly")
            header_parts = header.rstrip(b"\n").split()
            if len(header_parts) < 3:
                raise RuntimeError(
                    "unexpected git cat-file response: "
                    + header.decode("utf-8", errors="replace")
                )
            object_type = header_parts[1].decode("ascii", errors="replace")
            size = int(header_parts[2])
            data = process.stdout.read(size)
            terminator = process.stdout.read(1)
            if terminator != b"\n":
                raise RuntimeError("git cat-file returned a malformed batch payload")

            if object_type == "blob" and size <= MAX_TEXT_BLOB_BYTES:
                yield object_id, path, data
    finally:
        process.stdin.close()
        process.terminate()
        process.wait(timeout=5)
        process.stdout.close()
        if process.stderr is not None:
            process.stderr.close()


def audit_history(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for object_id, path, data in iter_history_blobs(root):
        findings.extend(scan_bytes(f"{object_id[:12]}:{path}", data))
    return findings


def normalize_metadata_key(key: str) -> str:
    leaf = key.rsplit(":", 1)[-1]
    return re.sub(r"[^a-z0-9]", "", leaf.lower())


def metadata_findings(records: Iterable[dict[str, object]]) -> list[Finding]:
    findings: list[Finding] = []
    for record in records:
        source = str(record.get("SourceFile", "<unknown-media>"))
        for key, value in record.items():
            if normalize_metadata_key(key) not in SENSITIVE_METADATA_KEYS:
                continue
            if value is None or value == "" or value == [] or value == {}:
                continue
            findings.append(
                Finding(
                    category="sensitive-media-metadata",
                    source=source,
                    field=key,
                )
            )
    return findings


def audit_media_metadata(root: Path) -> list[Finding]:
    media_paths = tracked_paths(root, MEDIA_GLOBS)
    if not media_paths:
        return []

    exiftool = shutil.which("exiftool")
    if exiftool is None:
        raise RuntimeError(
            "ExifTool is required for --media-metadata. Install exiftool or "
            "libimage-exiftool-perl and rerun the audit."
        )

    command = [exiftool, "-json", "-G1", "-s", "-n", "--"]
    command.extend(str(root / path) for path in media_paths)
    completed = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        message = completed.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"ExifTool failed: {message}")

    try:
        records = json.loads(completed.stdout.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"ExifTool returned invalid JSON: {exc}") from exc
    if not isinstance(records, list):
        raise RuntimeError("ExifTool JSON must contain a list of metadata records")
    return metadata_findings(record for record in records if isinstance(record, dict))


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Scan tracked text for high-confidence credentials. Optionally scan "
            "all reachable Git history and privacy-sensitive metadata in tracked media."
        )
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Repository root (default: parent of scripts/).",
    )
    parser.add_argument(
        "--history",
        action="store_true",
        help="Also scan every reachable text blob in Git history.",
    )
    parser.add_argument(
        "--media-metadata",
        action="store_true",
        help="Also scan tracked CR3/image metadata using ExifTool.",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    root = args.root.resolve()

    try:
        run_git(root, "rev-parse", "--show-toplevel")
        findings = audit_current_tree(root)
        if args.history:
            findings.extend(audit_history(root))
        if args.media_metadata:
            findings.extend(audit_media_metadata(root))
    except RuntimeError as exc:
        print(f"public-release audit error: {exc}", file=sys.stderr)
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
        print("Public-release audit failed. Potential sensitive data was found:")
        for finding in unique:
            print(f"  - {finding.describe()}")
        print(
            "Values are deliberately redacted. Remove, rotate, or explicitly "
            "replace the affected data."
        )
        return 1

    scopes = ["current tracked tree"]
    if args.history:
        scopes.append("all reachable Git history")
    if args.media_metadata:
        scopes.append("tracked media metadata")
    print("Public-release audit passed: " + ", ".join(scopes) + ".")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
