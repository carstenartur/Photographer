#!/usr/bin/env python3
"""Validate the machine-readable CR3 reference manifest without decoder dependencies."""

from __future__ import annotations

import re
import sys
from pathlib import Path

EXPECTED_HEADER = (
    "fixture|sidecar|tool|version|command|sha256|width|height|bitDepth|"
    "referenceType|geometryMatchesCrxBig"
)
VALID_REFERENCE_TYPES = {"RAW_LINEAR", "PREVIEW_RENDERED", "UNKNOWN"}
FORBIDDEN_COMMAND_PHRASE = "exact CLI not preserved"


def validate(repository_root: Path) -> list[str]:
    manifest_path = repository_root / "docs" / "cr3-reference-manifest.md"
    manifest = manifest_path.read_text(encoding="utf-8")
    match = re.search(
        r"<!-- reference-manifest-tsv:start -->(.*?)"
        r"<!-- reference-manifest-tsv:end -->",
        manifest,
        re.DOTALL,
    )
    if match is None:
        return ["TSV markers not found in manifest"]

    lines = [
        line.strip()
        for line in match.group(1).splitlines()
        if line.strip() and not line.strip().startswith("```")
    ]
    if not lines:
        return ["no lines found in TSV block"]
    if lines[0] != EXPECTED_HEADER:
        return [f"unexpected header: {lines[0]}"]

    errors: list[str] = []
    samples = repository_root / "canon-cr3-imageio" / "src" / "test" / "resources" / "samples"

    for line in lines[1:]:
        columns = [column.strip() for column in line.split("|")]
        if len(columns) != 11:
            errors.append(f"Wrong column count ({len(columns)}): {line[:80]}")
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
            errors.append(f"Empty fixture/sidecar: {line[:80]}")
        if re.fullmatch(r"[0-9a-f]{64}", sha256) is None:
            errors.append(f"Invalid SHA-256 for {fixture}: {sha256[:16]}…")
        if reference_type not in VALID_REFERENCE_TYPES:
            errors.append(f"Invalid referenceType '{reference_type}' for {fixture}")
        if FORBIDDEN_COMMAND_PHRASE in command:
            errors.append(f"Forbidden phrase in command for {fixture}: {command[:60]}")
        if not command:
            errors.append(f"Empty command for {fixture}")

        fixture_path = Path(fixture)
        sidecar_path = samples / fixture_path.parent / f"{fixture_path.stem}.{sidecar}"
        if not sidecar_path.is_file():
            errors.append(f"Sidecar not found on disk: {sidecar_path}")

    return errors


def main() -> int:
    repository_root = Path(__file__).resolve().parent.parent
    errors = validate(repository_root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print("OK: CR3 reference manifest validated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
