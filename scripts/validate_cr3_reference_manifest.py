#!/usr/bin/env python3
"""Validate the committed CR3 reference manifest and its sidecar paths.

This script is intentionally independent of GitHub Actions. Run it from any
checkout with Python 3.11 or newer:

    python3 scripts/validate_cr3_reference_manifest.py
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
from dataclasses import dataclass
from pathlib import Path

START_MARKER = "<!-- reference-manifest-tsv:start -->"
END_MARKER = "<!-- reference-manifest-tsv:end -->"
EXPECTED_HEADER = (
    "fixture|sidecar|tool|version|command|sha256|width|height|bitDepth|"
    "referenceType|geometryMatchesCrxBig"
)
VALID_REFERENCE_TYPES = {"RAW_LINEAR", "PREVIEW_RENDERED", "UNKNOWN"}
VALID_GEOMETRY_FLAGS = {"true", "false", "unknown"}
FORBIDDEN_COMMAND_TEXT = "exact CLI not preserved"
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")


@dataclass(frozen=True)
class ManifestRow:
    fixture: str
    sidecar: str
    tool: str
    version: str
    command: str
    sha256: str
    width: str
    height: str
    bit_depth: str
    reference_type: str
    geometry_matches_crx_big: str

    @classmethod
    def parse(cls, line: str) -> "ManifestRow":
        columns = [column.strip() for column in line.split("|")]
        if len(columns) != 11:
            raise ValueError(f"wrong column count ({len(columns)}): {line[:120]}")
        return cls(*columns)


def positive_integer(value: str, field: str, fixture: str) -> str | None:
    try:
        parsed = int(value)
    except ValueError:
        return f"{field} is not an integer for {fixture}: {value!r}"
    if parsed <= 0:
        return f"{field} must be positive for {fixture}: {value!r}"
    return None


def sidecar_path(samples_dir: Path, row: ManifestRow) -> Path:
    fixture = Path(row.fixture)
    return samples_dir / fixture.parent / f"{fixture.stem}.{row.sidecar}"


def validate(manifest_path: Path, samples_dir: Path) -> list[str]:
    errors: list[str] = []

    try:
        manifest = manifest_path.read_text(encoding="utf-8")
    except OSError as error:
        return [f"cannot read manifest {manifest_path}: {error}"]

    start = manifest.find(START_MARKER)
    end = manifest.find(END_MARKER)
    if start < 0 or end < 0 or end <= start:
        return ["reference-manifest TSV markers are missing or out of order"]

    block = manifest[start + len(START_MARKER) : end]
    lines = [
        line.strip()
        for line in block.splitlines()
        if line.strip() and not line.strip().startswith("```")
    ]
    if not lines:
        return ["reference-manifest TSV block is empty"]
    if lines[0] != EXPECTED_HEADER:
        errors.append(f"unexpected TSV header: {lines[0]!r}")
        return errors

    rows: list[ManifestRow] = []
    seen_fixtures: set[str] = set()
    for line_number, line in enumerate(lines[1:], start=2):
        try:
            row = ManifestRow.parse(line)
        except ValueError as error:
            errors.append(f"TSV line {line_number}: {error}")
            continue

        rows.append(row)
        if not row.fixture:
            errors.append(f"TSV line {line_number}: fixture is empty")
        elif row.fixture in seen_fixtures:
            errors.append(f"duplicate fixture entry: {row.fixture}")
        else:
            seen_fixtures.add(row.fixture)

        if not row.sidecar:
            errors.append(f"sidecar is empty for {row.fixture or f'line {line_number}'}")
        if not row.tool:
            errors.append(f"tool is empty for {row.fixture}")
        if not row.version:
            errors.append(f"tool version is empty for {row.fixture}")
        if not row.command:
            errors.append(f"command is empty for {row.fixture}")
        elif FORBIDDEN_COMMAND_TEXT in row.command:
            errors.append(f"non-reproducible command text for {row.fixture}: {row.command[:80]}")

        if not SHA256_PATTERN.fullmatch(row.sha256):
            errors.append(f"invalid SHA-256 for {row.fixture}: {row.sha256!r}")

        for field, value in (
            ("width", row.width),
            ("height", row.height),
            ("bitDepth", row.bit_depth),
        ):
            error = positive_integer(value, field, row.fixture)
            if error:
                errors.append(error)

        if row.reference_type not in VALID_REFERENCE_TYPES:
            errors.append(
                f"invalid referenceType for {row.fixture}: {row.reference_type!r}"
            )
        if row.geometry_matches_crx_big.lower() not in VALID_GEOMETRY_FLAGS:
            errors.append(
                "invalid geometryMatchesCrxBig for "
                f"{row.fixture}: {row.geometry_matches_crx_big!r}"
            )

    for row in rows:
        fixture_path = samples_dir / row.fixture
        if not fixture_path.is_file():
            errors.append(f"fixture not found: {fixture_path}")

        reference_path = sidecar_path(samples_dir, row)
        if not reference_path.is_file():
            errors.append(f"sidecar not found: {reference_path}")
            continue

        try:
            digest = hashlib.sha256(reference_path.read_bytes()).hexdigest()
        except OSError as error:
            errors.append(f"cannot read sidecar {reference_path}: {error}")
            continue
        if SHA256_PATTERN.fullmatch(row.sha256) and digest != row.sha256:
            errors.append(
                f"sidecar SHA-256 mismatch for {row.fixture}: "
                f"manifest={row.sha256}, actual={digest}"
            )

    return errors


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path("docs/cr3-reference-manifest.md"),
        help="path to the Markdown manifest",
    )
    parser.add_argument(
        "--samples-dir",
        type=Path,
        default=Path("canon-cr3-imageio/src/test/resources/samples"),
        help="root containing CR3 fixtures and reference sidecars",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    errors = validate(args.manifest, args.samples_dir)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print(f"OK: CR3 reference manifest validated: {args.manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
