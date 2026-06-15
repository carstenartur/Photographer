#!/usr/bin/env python3
"""
Generate docs/cr3-reference-manifest.md from discovered reference sidecars.

Scans the samples directory for every pair of
  <stem>.reference.tiff  (RAW_LINEAR)
  <stem>.reference.png   (PREVIEW_RENDERED)

and writes a fully-populated manifest markdown that replaces the old
hand-maintained file.  Running this script is the canonical way to
update the manifest after running export_references.py.

Prerequisites (see requirements.txt):
  pip install numpy imageio tifffile Pillow

Usage:
  # Regenerate the manifest from the current sidecars:
  python generate_manifest.py

  # Write to a custom path:
  python generate_manifest.py --manifest-out /path/to/manifest.md

  # Dry-run (print generated manifest to stdout):
  python generate_manifest.py --dry-run
"""

import argparse
import datetime
import hashlib
import sys
from pathlib import Path

import numpy as np
import tifffile
from PIL import Image

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

SCRIPT_DIR = Path(__file__).parent
REPO_ROOT = SCRIPT_DIR.parent.parent
DEFAULT_SAMPLES_DIR = (
    REPO_ROOT
    / "canon-cr3-imageio"
    / "src"
    / "test"
    / "resources"
    / "samples"
)
DEFAULT_MANIFEST_OUT = REPO_ROOT / "docs" / "cr3-reference-manifest.md"
TOOL_NAME = "LibRaw"
# This version string is a placeholder used when generating a fresh manifest.
# The actual locked version for each entry should reflect the exact tool version
# used to produce the sidecar; update this before running generate_manifest.py
# to capture a new set of sidecars.
TOOL_VERSION = "rawpy 0.27.0 / LibRaw 0.22.1"
EXPORT_SCRIPT = "tools/reference-export/export_references.py"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def image_info_tiff(path: Path) -> tuple[int, int, int]:
    """Return (width, height, bit_depth) for a TIFF sidecar."""
    arr = tifffile.imread(str(path))
    if arr.ndim == 2:
        height, width = arr.shape
    else:
        height, width = arr.shape[:2]
    bit_depth = arr.dtype.itemsize * 8
    return width, height, bit_depth


def image_info_png(path: Path) -> tuple[int, int, int]:
    """Return (width, height, bit_depth) for a PNG sidecar."""
    with Image.open(path) as img:
        width, height = img.size
        bit_depth = 8  # PNG previews are always 8-bit in this pipeline
    return width, height, bit_depth


def reference_type(sidecar_path: Path) -> str:
    """Classify a sidecar as RAW_LINEAR or PREVIEW_RENDERED."""
    suffix = sidecar_path.suffix.lower()
    if suffix == ".tiff":
        return "RAW_LINEAR"
    if suffix == ".png":
        return "PREVIEW_RENDERED"
    return "UNKNOWN"


def command_for(fixture_rel: str, sidecar_type: str) -> str:
    """Return the canonical command string that produced this sidecar."""
    return (
        f"python {EXPORT_SCRIPT} "
        f"--fixture {fixture_rel} "
        f"--type {sidecar_type}"
    )


# ---------------------------------------------------------------------------
# Core: discover sidecars and build TSV rows
# ---------------------------------------------------------------------------


def discover_pairs(samples_dir: Path) -> list[dict]:
    """Discover all (fixture, sidecar) pairs under *samples_dir*."""
    pairs = []
    for sidecar in sorted(
        list(samples_dir.rglob("*.reference.tiff"))
        + list(samples_dir.rglob("*.reference.png"))
    ):
        # Derive the fixture path: replace ".reference.tiff/.reference.png"
        # with ".cr3" (case-preserving for the stem, lower for the extension).
        stem = sidecar.name.split(".reference.")[0]
        cr3 = sidecar.parent / f"{stem}.cr3"
        if not cr3.is_file():
            cr3 = sidecar.parent / f"{stem}.CR3"
        if not cr3.is_file():
            print(f"  warning: no matching CR3 for {sidecar}, skipping", file=sys.stderr)
            continue

        fixture_rel = cr3.relative_to(samples_dir).as_posix()
        sidecar_rel = sidecar.relative_to(samples_dir).as_posix()
        # sidecar column in manifest is the portion after <stem>, e.g. "reference.tiff"
        sidecar_col = ".".join(sidecar.name.split(".")[1:])

        suffix = sidecar.suffix.lower()
        if suffix == ".tiff":
            width, height, bit_depth = image_info_tiff(sidecar)
            sidecar_type = "tiff"
        else:
            width, height, bit_depth = image_info_png(sidecar)
            sidecar_type = "png"

        sha = sha256_of(sidecar)
        ref_type = reference_type(sidecar)

        # geometry_matches_crx_big is computed externally by the Java IT;
        # we default to True for RAW_LINEAR (tiff) and False for PREVIEW_RENDERED.
        geometry_matches = "true" if ref_type == "RAW_LINEAR" else "false"

        pairs.append(
            {
                "fixture": fixture_rel,
                "sidecar": sidecar_col,
                "tool": TOOL_NAME,
                "version": TOOL_VERSION,
                "command": command_for(fixture_rel, sidecar_type),
                "sha256": sha,
                "width": width,
                "height": height,
                "bitDepth": bit_depth,
                "referenceType": ref_type,
                "geometryMatchesCrxBig": geometry_matches,
            }
        )

    return pairs


# ---------------------------------------------------------------------------
# Render manifest markdown
# ---------------------------------------------------------------------------

MANIFEST_TEMPLATE = """\
# CR3 Reference Manifest

This manifest is the provenance gate for CR3 reference sidecars used by residual diagnostics.

- Scope: `canon-cr3-imageio/src/test/resources/samples/**/*.reference.tiff` and `**/*.reference.png`
- Gate: `ReferenceManifestValidationIT`
- Generated: {generated_at}
- Policy: Reference-comparison output is only evidence-grade when this manifest validates.

## Provenance records

<!-- reference-manifest-tsv:start -->
```text
fixture|sidecar|tool|version|command|sha256|width|height|bitDepth|referenceType|geometryMatchesCrxBig
{rows}
```
<!-- reference-manifest-tsv:end -->

## Notes

- `reference.tiff` sidecars are RAW-linear Bayer mosaics (`referenceType=RAW_LINEAR`) and must match
  CRX_BIG geometry exactly.  Exported via `export_references.py --type tiff`.
- `reference.png` sidecars are rendered/preview artifacts (`referenceType=PREVIEW_RENDERED`); not
  suitable as RAW-linear evidence.  Exported via `export_references.py --type png`.
- To regenerate all sidecars and this manifest run:
  ```
  pip install -r tools/reference-export/requirements.txt
  python tools/reference-export/export_references.py
  python tools/reference-export/generate_manifest.py
  ```
- Any sidecar/hash/metadata drift must update this manifest and pass `ReferenceManifestValidationIT`
  before reference-based diagnostics are accepted.
"""


def render_manifest(pairs: list[dict]) -> str:
    rows = "\n".join(
        "{fixture}|{sidecar}|{tool}|{version}|{command}|{sha256}|{width}|{height}|{bitDepth}|{referenceType}|{geometryMatchesCrxBig}".format(
            **p
        )
        for p in pairs
    )
    generated_at = datetime.datetime.now(tz=datetime.timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%SZ"
    )
    return MANIFEST_TEMPLATE.format(rows=rows, generated_at=generated_at)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate docs/cr3-reference-manifest.md from discovered sidecars.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--samples-dir",
        type=Path,
        default=DEFAULT_SAMPLES_DIR,
        help="Root directory that contains the reference sidecar files (default: %(default)s)",
    )
    parser.add_argument(
        "--manifest-out",
        type=Path,
        default=DEFAULT_MANIFEST_OUT,
        help="Output path for the generated manifest (default: %(default)s)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the generated manifest to stdout without writing the file.",
    )
    args = parser.parse_args()

    samples_dir: Path = args.samples_dir
    if not samples_dir.is_dir():
        print(f"error: samples directory not found: {samples_dir}", file=sys.stderr)
        return 1

    print(f"Scanning {samples_dir} for reference sidecars …")
    pairs = discover_pairs(samples_dir)
    if not pairs:
        print("No reference sidecars found.", file=sys.stderr)
        return 1

    print(f"Found {len(pairs)} sidecar(s).  Rendering manifest …")
    manifest = render_manifest(pairs)

    if args.dry_run:
        print(manifest)
    else:
        args.manifest_out.parent.mkdir(parents=True, exist_ok=True)
        args.manifest_out.write_text(manifest, encoding="utf-8")
        print(f"Manifest written to {args.manifest_out}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
