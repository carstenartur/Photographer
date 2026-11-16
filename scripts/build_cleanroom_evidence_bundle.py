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
    "LICENSE",
    "docs/cr3/current-state.md",
    "docs/cr3/decoder.md",
    "docs/cr3/format.md",
    "docs/cr3/index.md",
    "docs/cr3/open-hypotheses.md",
    "docs/cr3/reports/index.md",
    "docs/cr3/research.md",
    "docs/cr3-current-state.md",
    "docs/cr3-open-hypotheses.md",
    "docs/cr3-reference-inventory.md",
    "docs/cr3-reference-manifest.md",
    "docs/cr3-results-index.md",
    "docs/reference-pipeline.md",
    "canon-cr3-imageio/src/test/resources/samples/reference-manifest.json",
    "scripts/research/public-crx/craw-sensors.tsv",
    "scripts/research/public-crx/lossless-holdout.tsv",
)
REPORT_SUFFIXES = {".md", ".tsv", ".json", ".csv"}
ZIP_TIME = (1980, 1, 1, 0, 0, 0)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def evidence_path(root: Path, relative: Path) -> Path:
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError(f"invalid repository-relative evidence path {relative}")
    if any((root / part).is_symlink() for part in (relative, *relative.parents)):
        raise ValueError(f"symlink in evidence bundle path {relative}")
    return root / relative


def collect_documents(root: Path) -> set[Path]:
    cleanroom = Path("docs/cleanroom")
    candidates = {Path(relative) for relative in FIXED_PATHS}
    tracked = set(manage_cr3_generated_evidence.repository_files(root))
    candidates.update(
        path for path in tracked
        if path.parent == cleanroom and path.suffix in {".md", ".json"}
    )
    paths = set()
    for relative in sorted(candidates & tracked):
        path = evidence_path(root, relative)
        if path.is_file():
            paths.add(path)
    return paths


def manifest(files: dict[str, bytes]) -> bytes:
    records = [
        {
            "path": name,
            "sha256": sha256(data),
            "bytes": len(data),
        }
        for name, data in files.items()
    ]
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
    root = root.resolve()
    paths = collect_documents(root)
    errors = validate_cleanroom_evidence.validate_repository(root)
    generated_errors, reports = manage_cr3_generated_evidence.validate_inventory(root)
    errors.extend(generated_errors)
    if errors:
        raise ValueError("invalid clean-room evidence:\n" + "\n".join(errors))

    for relative in reports:
        if relative.suffix.lower() not in REPORT_SUFFIXES:
            continue
        path = evidence_path(root, relative)
        if not path.is_file():
            raise ValueError(f"missing registered evidence file {relative}")
        paths.add(path)
    bundle_files = {
        path.relative_to(root).as_posix(): path.read_bytes()
        for path in sorted(paths, key=Path.as_posix)
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w") as archive:
        write_entry(archive, "BUNDLE-MANIFEST.json", manifest(bundle_files))
        for name, data in bundle_files.items():
            write_entry(archive, name, data)
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
