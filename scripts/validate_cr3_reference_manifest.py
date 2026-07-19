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
from pathlib import Path, PurePosixPath

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
SIDECAR_NAME_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
HASH_CHUNK_SIZE = 1024 * 1024


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

    @property
    def key(self) -> tuple[str, str]:
        return self.fixture, self.sidecar


def positive_integer(value: str, field: str, fixture: str) -> str | None:
    try:
        parsed = int(value)
    except ValueError:
        return f"{field} is not an integer for {fixture}: {value!r}"
    if parsed <= 0:
        return f"{field} must be positive for {fixture}: {value!r}"
    return None


def portable_relative_path(value: str, field: str) -> PurePosixPath:
    """Parse a manifest path without allowing absolute or parent traversal."""
    if not value:
        raise ValueError(f"{field} is empty")
    if "\\" in value or "\x00" in value:
        raise ValueError(f"{field} is not a portable POSIX path: {value!r}")

    raw_parts = value.split("/")
    if any(part in {"", ".", ".."} for part in raw_parts):
        raise ValueError(f"{field} contains an unsafe path segment: {value!r}")
    if any(":" in part for part in raw_parts):
        raise ValueError(f"{field} contains a platform-specific drive or scheme: {value!r}")

    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError(f"{field} must stay relative to the samples directory: {value!r}")
    return path


def safe_sidecar_name(value: str, fixture: str) -> str:
    """Validate the sidecar suffix as a filename component, not another path."""
    if SIDECAR_NAME_PATTERN.fullmatch(value) is None:
        raise ValueError(f"unsafe sidecar name for {fixture}: {value!r}")
    return value


def confined_path(root: Path, relative: PurePosixPath, field: str) -> Path:
    """Resolve a candidate and require it to remain below the canonical root."""
    canonical_root = root.resolve()
    candidate = (canonical_root / Path(*relative.parts)).resolve()
    if not candidate.is_relative_to(canonical_root):
        raise ValueError(f"{field} escapes samples directory: {relative}")
    return candidate


def sidecar_relative_path(fixture: PurePosixPath, sidecar: str) -> PurePosixPath:
    return fixture.parent / f"{fixture.stem}.{sidecar}"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(HASH_CHUNK_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


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
        return [f"unexpected TSV header: {lines[0]!r}"]

    file_entries: list[tuple[ManifestRow, Path, Path]] = []
    seen_entries: set[tuple[str, str]] = set()
    for line_number, line in enumerate(lines[1:], start=2):
        try:
            row = ManifestRow.parse(line)
        except ValueError as error:
            errors.append(f"TSV line {line_number}: {error}")
            continue

        if not row.fixture:
            errors.append(f"TSV line {line_number}: fixture is empty")
        if not row.sidecar:
            errors.append(f"sidecar is empty for {row.fixture or f'line {line_number}'}")
        if row.fixture and row.sidecar:
            if row.key in seen_entries:
                errors.append(
                    f"duplicate fixture/sidecar entry: {row.fixture} / {row.sidecar}"
                )
            else:
                seen_entries.add(row.key)

        if not row.tool:
            errors.append(f"tool is empty for {row.fixture}")
        if not row.version:
            errors.append(f"tool version is empty for {row.fixture}")
        if not row.command:
            errors.append(f"command is empty for {row.fixture}")
        elif FORBIDDEN_COMMAND_TEXT in row.command:
            errors.append(
                f"non-reproducible command text for {row.fixture}: {row.command[:80]}"
            )

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

        if not row.fixture or not row.sidecar:
            continue
        try:
            fixture_relative = portable_relative_path(row.fixture, "fixture")
            sidecar = safe_sidecar_name(row.sidecar, row.fixture)
            fixture_path = confined_path(samples_dir, fixture_relative, "fixture")
            reference_path = confined_path(
                samples_dir,
                sidecar_relative_path(fixture_relative, sidecar),
                "sidecar",
            )
            file_entries.append((row, fixture_path, reference_path))
        except ValueError as error:
            errors.append(f"TSV line {line_number}: {error}")

    for row, fixture_path, reference_path in file_entries:
        if not fixture_path.is_file():
            errors.append(f"fixture not found: {fixture_path}")

        if not reference_path.is_file():
            errors.append(f"sidecar not found: {reference_path}")
            continue

        try:
            digest = sha256(reference_path)
        except OSError as error:
            errors.append(f"cannot read sidecar {reference_path}: {error}")
            continue
        if SHA256_PATTERN.fullmatch(row.sha256) and digest != row.sha256:
            errors.append(
                f"sidecar SHA-256 mismatch for {row.fixture} / {row.sidecar}: "
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
