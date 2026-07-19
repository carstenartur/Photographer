#!/usr/bin/env python3
"""Validate the checked-in CR3 reference manifest without decoder dependencies."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

EXPECTED_HEADER = (
    "fixture|sidecar|tool|version|command|sha256|width|height|bitDepth|"
    "referenceType|geometryMatchesCrxBig"
)
VALID_REFERENCE_TYPES = {"RAW_LINEAR", "PREVIEW_RENDERED", "UNKNOWN"}
FORBIDDEN_COMMAND_PHRASE = "exact CLI not preserved"
START_MARKER = "<!-- reference-manifest-tsv:start -->"
END_MARKER = "<!-- reference-manifest-tsv:end -->"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path("docs/cr3-reference-manifest.md"),
        help="Path to the Markdown manifest.",
    )
    parser.add_argument(
        "--samples",
        type=Path,
        default=Path("canon-cr3-imageio/src/test/resources/samples"),
        help="Root directory containing CR3 fixtures and reference sidecars.",
    )
    return parser.parse_args()


def extract_rows(manifest_text: str) -> list[str]:
    pattern = re.compile(
        rf"{re.escape(START_MARKER)}(.*?){re.escape(END_MARKER)}", re.DOTALL
    )
    match = pattern.search(manifest_text)
    if match is None:
        raise ValueError("TSV markers not found in manifest")

    rows = [
        line.strip()
        for line in match.group(1).splitlines()
        if line.strip() and not line.strip().startswith("```")
    ]
    if not rows:
        raise ValueError("no lines found in TSV block")
    if rows[0] != EXPECTED_HEADER:
        raise ValueError(f"unexpected header: {rows[0]}")
    return rows[1:]


def validate_rows(rows: list[str], samples_root: Path) -> list[str]:
    errors: list[str] = []
    for row in rows:
        columns = [column.strip() for column in row.split("|")]
        if len(columns) != 11:
            errors.append(f"Wrong column count ({len(columns)}): {row[:80]}")
            continue

        (
            fixture,
            sidecar,
            _tool,
            _version,
            command,
            sha256,
            _width,
            _height,
            _bit_depth,
            reference_type,
            _geometry_matches,
        ) = columns

        if not fixture or not sidecar:
            errors.append(f"Empty fixture/sidecar: {row[:80]}")
        if re.fullmatch(r"[0-9a-f]{64}", sha256) is None:
            errors.append(f"Invalid SHA-256 for {fixture}: {sha256[:16]}…")
        if reference_type not in VALID_REFERENCE_TYPES:
            errors.append(f"Invalid referenceType '{reference_type}' for {fixture}")
        if FORBIDDEN_COMMAND_PHRASE in command:
            errors.append(f"Forbidden phrase in command for {fixture}: {command[:60]}")
        if not command:
            errors.append(f"Empty command for {fixture}")

        fixture_path = Path(fixture)
        sidecar_path = (
            samples_root
            / fixture_path.parent
            / f"{fixture_path.stem}.{sidecar}"
        )
        if not sidecar_path.is_file():
            errors.append(f"Sidecar not found on disk: {sidecar_path}")

    return errors


def main() -> int:
    args = parse_args()
    try:
        rows = extract_rows(args.manifest.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    errors = validate_rows(rows, args.samples)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print(f"OK: manifest validated ({len(rows)} entries, 0 errors)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
