#!/usr/bin/env python3
"""Build a deterministic, media-free clean-room evidence ZIP."""

from __future__ import annotations

import hashlib
import json
import sys
import zipfile
from pathlib import Path

import manage_cr3_generated_evidence
import validate_cleanroom_evidence

FIXED_PATHS = (
    "docs/cr3-current-state.md",
    "docs/cr3-open-hypotheses.md",
    "docs/cr3-reference-inventory.md",
    "docs/cr3-reference-manifest.md",
    "docs/cr3-results-index.md",
    "canon-cr3-imageio/src/test/resources/samples/reference-manifest.json",
)
GLOBS = (
    "docs/cleanroom/*.md",
    "docs/cleanroom/*.json",
    "docs/cr3-results/*.md",
    "docs/cr3-results/*.tsv",
    "docs/cr3-results/*.json",
    "docs/cr3-results/*.csv",
)
MEDIA_SUFFIXES = {".cr3", ".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bin"}
ZIP_TIME = (1980, 1, 1, 0, 0, 0)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def collect(root: Path) -> list[Path]:
    paths: set[Path] = set()
    for relative in FIXED_PATHS:
        path = root / relative
        if path.is_file():
            paths.add(path)
    for pattern in GLOBS:
        paths.update(path for path in root.glob(pattern) if path.is_file())
    selected = sorted(paths, key=lambda path: path.relative_to(root).as_posix())
    media = [path for path in selected if path.suffix.lower() in MEDIA_SUFFIXES]
    if media:
        raise ValueError(f"media files are not permitted in evidence bundle: {media}")
    return selected


def manifest(root: Path, paths: list[Path]) -> bytes:
    records = []
    for path in paths:
        data = path.read_bytes()
        records.append({
            "path": path.relative_to(root).as_posix(),
            "sha256": sha256(data),
            "bytes": len(data),
        })
    payload = {
        "schemaVersion": 1,
        "files": records,
    }
    return (json.dumps(payload, indent=2, sort_keys=True) + "\n").encode("utf-8")


def write_entry(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data)


def build(root: Path, output: Path) -> str:
    errors = validate_cleanroom_evidence.validate_repository(root)
    generated_errors = manage_cr3_generated_evidence.validate_repository(root)
    errors.extend(generated_errors)
    if errors:
        raise ValueError("invalid clean-room evidence:\n" + "\n".join(errors))
    paths = collect(root)
    output.parent.mkdir(parents=True, exist_ok=True)
    bundle_manifest = manifest(root, paths)
    with zipfile.ZipFile(output, "w") as archive:
        write_entry(archive, "BUNDLE-MANIFEST.json", bundle_manifest)
        for path in paths:
            write_entry(archive, path.relative_to(root).as_posix(), path.read_bytes())
    digest = sha256(output.read_bytes())
    checksum = output.with_suffix(output.suffix + ".sha256")
    checksum.write_text(f"{digest}  {output.name}\n", encoding="utf-8")
    return digest


def main(argv: list[str]) -> int:
    root = Path(argv[1]).resolve() if len(argv) > 1 else Path.cwd().resolve()
    output = (Path(argv[2]).resolve() if len(argv) > 2
              else root / "target" / "cleanroom-evidence" / "cleanroom-evidence.zip")
    try:
        digest = build(root, output)
    except (OSError, ValueError) as exc:
        print(f"Clean-room evidence bundle failed: {exc}", file=sys.stderr)
        return 1
    print(f"Created {output}")
    print(f"sha256:{digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
