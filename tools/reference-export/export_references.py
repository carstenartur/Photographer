#!/usr/bin/env python3
"""
Export CR3 reference sidecars for the CR3 research pipeline.

For each CR3 fixture this script produces two sidecar files:

  <stem>.reference.tiff  – RAW-linear Bayer mosaic, 16-bit, single-channel.
                           The raw sensor values are written verbatim as a
                           uint16 TIFF using tifffile; no debayering, no
                           tone-mapping, no gamma.  The resulting image has
                           the same geometry as the CRX_BIG track that the
                           Java decoder targets, making pixel-exact residual
                           comparison possible.

  <stem>.reference.png   – Rendered sRGB preview, 8-bit, 1620 × 1080.
                           Produced by rawpy.postprocess() with camera white
                           balance and default output settings, then scaled
                           with Pillow LANCZOS to the canonical preview
                           dimensions.  Used for visual sanity checks; not
                           suitable as a RAW-linear evidence source.

Prerequisites (see requirements.txt):
  pip install rawpy numpy imageio tifffile Pillow

Usage:
  # Export all fixtures in the default samples directory:
  python export_references.py

  # Export a single fixture (relative to samples-dir):
  python export_references.py --fixture sample.cr3

  # Export only the TIFF or only the PNG sidecar:
  python export_references.py --fixture sample.cr3 --type tiff
  python export_references.py --fixture sample.cr3 --type png

  # Point at a custom samples directory:
  python export_references.py --samples-dir /path/to/samples

  # Dry-run (print what would be done, write nothing):
  python export_references.py --dry-run

Canonical invocation recorded in docs/cr3-reference-manifest.md:
  python tools/reference-export/export_references.py --fixture <fixture> --type tiff
  python tools/reference-export/export_references.py --fixture <fixture> --type png
"""

import argparse
import hashlib
import sys
from pathlib import Path

import numpy as np
import rawpy
import tifffile
from PIL import Image

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

PREVIEW_WIDTH = 1620
PREVIEW_HEIGHT = 1080
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


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def sha256_of(path: Path) -> str:
    """Return the lowercase hex SHA-256 digest of the file at *path*."""
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def export_tiff(cr3_path: Path, out_path: Path, dry_run: bool) -> None:
    """Write a RAW-linear 16-bit single-channel TIFF sidecar.

    The raw sensor array (``rawpy.RawPy.raw_image``) is written verbatim as a
    uint16 TIFF.  No debayering, no white-balance scaling, no tone-mapping.
    The geometry matches the full sensor readout including optical-black rows
    and columns as reported by LibRaw, which equals the CRX_BIG track
    dimensions used by the Java decoder.
    """
    if dry_run:
        print(f"  [dry-run] would write RAW-linear TIFF → {out_path}")
        return
    with rawpy.imread(str(cr3_path)) as raw:
        sensor = np.array(raw.raw_image, dtype=np.uint16)
    tifffile.imwrite(str(out_path), sensor, photometric="minisblack")
    sha = sha256_of(out_path)
    print(f"  wrote TIFF  {out_path.name}  ({sensor.shape[1]}×{sensor.shape[0]}, 16-bit)  sha256={sha}")


def export_png(cr3_path: Path, out_path: Path, dry_run: bool) -> None:
    """Write a rendered sRGB preview PNG sidecar at 1620 × 1080.

    Uses rawpy.postprocess() with camera white balance enabled, 8-bit output,
    and default sRGB color space.  The result is scaled to the canonical
    preview dimensions with Pillow LANCZOS resampling.
    """
    if dry_run:
        print(f"  [dry-run] would write preview PNG   → {out_path}")
        return
    with rawpy.imread(str(cr3_path)) as raw:
        rgb = raw.postprocess(
            no_auto_bright=False,
            use_camera_wb=True,
            output_bps=8,
        )
    img = Image.fromarray(rgb)
    img = img.resize((PREVIEW_WIDTH, PREVIEW_HEIGHT), Image.LANCZOS)
    img.save(str(out_path), format="PNG", optimize=False)
    sha = sha256_of(out_path)
    print(f"  wrote PNG   {out_path.name}  ({PREVIEW_WIDTH}×{PREVIEW_HEIGHT}, 8-bit)   sha256={sha}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def discover_fixtures(samples_dir: Path) -> list[Path]:
    """Return all *.cr3 fixtures under *samples_dir* (recursive, sorted)."""
    return sorted(samples_dir.rglob("*.cr3")) + sorted(samples_dir.rglob("*.CR3"))


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Export CR3 reference sidecars (RAW-linear TIFF + preview PNG).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--samples-dir",
        type=Path,
        default=DEFAULT_SAMPLES_DIR,
        help="Root directory that contains the CR3 fixture files (default: %(default)s)",
    )
    parser.add_argument(
        "--fixture",
        metavar="FIXTURE",
        help="Relative path of a single CR3 fixture to export (e.g. 'sample.cr3'). "
             "If omitted all discovered CR3 files are processed.",
    )
    parser.add_argument(
        "--type",
        choices=["tiff", "png", "both"],
        default="both",
        help="Which sidecar type to generate (default: both)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print what would be done without writing any files.",
    )
    args = parser.parse_args()

    samples_dir: Path = args.samples_dir
    if not samples_dir.is_dir():
        print(f"error: samples directory not found: {samples_dir}", file=sys.stderr)
        return 1

    if args.fixture:
        fixtures = [samples_dir / args.fixture]
        missing = [f for f in fixtures if not f.is_file()]
        if missing:
            print(f"error: fixture not found: {missing[0]}", file=sys.stderr)
            return 1
    else:
        fixtures = discover_fixtures(samples_dir)

    if not fixtures:
        print("No CR3 fixtures found.", file=sys.stderr)
        return 1

    errors = 0
    for cr3 in fixtures:
        rel = cr3.relative_to(samples_dir)
        print(f"Processing {rel} …")
        stem = cr3.stem  # filename without extension
        out_dir = cr3.parent

        try:
            if args.type in ("tiff", "both"):
                tiff_out = out_dir / f"{stem}.reference.tiff"
                export_tiff(cr3, tiff_out, args.dry_run)
            if args.type in ("png", "both"):
                png_out = out_dir / f"{stem}.reference.png"
                export_png(cr3, png_out, args.dry_run)
        except (rawpy.LibRawError, rawpy.LibRawFatalError, rawpy.LibRawNonFatalError,
                OSError, ValueError) as exc:
            print(f"  ERROR: {exc}", file=sys.stderr)
            errors += 1

    print(f"\nDone. {len(fixtures)} fixture(s) processed, {errors} error(s).")
    return 0 if errors == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
