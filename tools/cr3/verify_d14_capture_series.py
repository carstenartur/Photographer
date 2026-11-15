#!/usr/bin/env python3
"""Verify the file provenance and repeat layout of a captured D14 series."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


MANIFEST = Path(__file__).resolve().parents[2] / "docs/cr3-patterns/pattern-manifest.json"
CR3_EXTENSIONS = {".cr3"}
JPEG_EXTENSIONS = {".jpg", ".jpeg"}
COMPANION_EXTENSIONS = JPEG_EXTENSIONS | {".hif", ".heif"}


def capture_order(metadata: dict, known_patterns: set[str]) -> list[dict]:
    if metadata.get("schema") != "cr3-d14-capture-metadata-v1":
        raise ValueError("Expected cr3-d14-capture-metadata-v1 in capture-metadata.json")
    rows = metadata.get("captureOrder")
    if not isinstance(rows, list) or not rows:
        raise ValueError("captureOrder must list the captured exposures in order")

    pending_pattern = None
    completed_patterns = set()
    for index, row in enumerate(rows, start=1):
        if not isinstance(row, dict):
            raise ValueError(f"Invalid captureOrder entry {index}")
        pattern = row.get("patternId")
        repeat = row.get("repeat")
        if not isinstance(pattern, str) or pattern not in known_patterns:
            raise ValueError(f"Unknown pattern ID {pattern!r} in captureOrder entry {index}")

        if pending_pattern is None:
            if repeat != "A" or pattern in completed_patterns:
                raise ValueError(f"Expected repeat A for a new pattern, found {pattern}/{repeat}")
            pending_pattern = pattern
        else:
            if pattern != pending_pattern or repeat != "B":
                raise ValueError(f"Expected {pending_pattern}/B immediately after {pending_pattern}/A, "
                                 f"found {pattern}/{repeat}")
            completed_patterns.add(pattern)
            pending_pattern = None

    if pending_pattern is not None:
        raise ValueError(f"Missing repeat B for {pending_pattern}")
    return rows


def verify_file(series: Path, row: dict, kind: str, extensions: set[str]) -> tuple[Path, str]:
    original_name = row.get(f"{kind}File")
    pattern, repeat = row["patternId"], row["repeat"]
    description = f"{'CR3' if kind == 'cr3' else 'companion'} file for {pattern}/{repeat}"
    if (not isinstance(original_name, str) or "/" in original_name or "\\" in original_name
            or Path(original_name).suffix.lower() not in extensions):
        raise ValueError(f"Record the original camera filename for the {description}")

    directory = series / pattern
    candidates = [path for path in directory.iterdir()
                  if path.stem == repeat and path.suffix.lower() in extensions and path.is_file()]
    if not candidates:
        raise ValueError(f"Missing {description}")
    if len(candidates) > 1:
        raise ValueError(f"Multiple files match the {description}")
    path = candidates[0]
    relative_path = path.relative_to(series)
    if path.stat().st_size == 0:
        raise ValueError(f"Empty capture file {relative_path}")
    original_suffix = Path(original_name).suffix.lower()
    if (original_suffix in JPEG_EXTENSIONS) != (path.suffix.lower() in JPEG_EXTENSIONS):
        raise ValueError(f"Companion format differs from the original filename for {pattern}/{repeat}")

    expected_hash = row.get(f"{kind}Sha256")
    if not isinstance(expected_hash, str) or not expected_hash:
        raise ValueError(f"Record the SHA-256 for the {description}")
    with path.open("rb") as capture:
        actual_hash = hashlib.file_digest(capture, "sha256").hexdigest()
    if actual_hash != expected_hash.lower():
        raise ValueError(f"SHA-256 mismatch for {relative_path}")
    return relative_path, actual_hash


def verify_series(series: Path) -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    known_patterns = {pattern["id"] for pattern in manifest["patterns"]}
    metadata = json.loads((series / "capture-metadata.json").read_text(encoding="utf-8"))
    if not isinstance(metadata, dict):
        raise ValueError("capture-metadata.json must contain a JSON object")
    rows = capture_order(metadata, known_patterns)
    recorded_patterns = {row["patternId"] for row in rows}
    for directory in sorted(series.iterdir()):
        if directory.is_dir() and directory.name not in recorded_patterns:
            raise ValueError(f"Pattern directory {directory.name!r} is missing from captureOrder")

    seen_cr3_hashes = {}
    for row in rows:
        path, digest = verify_file(series, row, "cr3", CR3_EXTENSIONS)
        if digest in seen_cr3_hashes:
            raise ValueError(f"Repeated CR3 contents in {path} and {seen_cr3_hashes[digest]}")
        seen_cr3_hashes[digest] = path
        verify_file(series, row, "companion", COMPANION_EXTENSIONS)
    return len(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("series", type=Path, help="series directory containing capture-metadata.json")
    args = parser.parse_args()
    try:
        exposures = verify_series(args.series)
    except (OSError, ValueError) as error:
        parser.exit(1, f"Capture preflight failed. {error}\n")
    print(f"Verified {exposures // 2} patterns, {exposures} exposures and {exposures * 2} file hashes.")


if __name__ == "__main__":
    main()
