# Reference Export Pipeline

This document describes how to reproduce, update, and validate the CR3
reference sidecars used by the residual-diagnostics research infrastructure.

## Background

`ReferenceManifestValidationIT` treats reference sidecars as the trust boundary
for decoder evidence.  To maintain that trust, every sidecar must be:

1. **Reproducibly generated** – a developer with the right tools can regenerate
   any file from its CR3 source.
2. **Hash-locked** – the manifest records the SHA-256 of every file; any drift
   is caught by the IT gate before it can silently corrupt comparisons.
3. **Classified** – each sidecar carries a `referenceType` of `RAW_LINEAR`
   (Bayer mosaic, no demosaicing) or `PREVIEW_RENDERED` (debayered sRGB
   preview), so downstream consumers know exactly what they are working with.

## Prerequisites

| Component | Minimum version | Installation |
|-----------|----------------|-------------|
| Python | 3.10 | <https://python.org> |
| rawpy | 0.22.0 | `pip install rawpy` |
| LibRaw | 0.22.0 | bundled with rawpy wheel |
| numpy | 1.24.0 | `pip install numpy` |
| tifffile | 2023.1.23 | `pip install tifffile` |
| Pillow | 10.0.0 | `pip install Pillow` |
| imageio | 2.31.0 | `pip install imageio` |

Install all at once from the project root:

```bash
pip install -r tools/reference-export/requirements.txt
```

> **Note:** exact SHA-256 values for existing sidecars were computed with
> rawpy 0.27.0 / LibRaw 0.22.1.  A newer library version may produce
> different raw pixel values (e.g. due to black-level policy changes).
> `ReferenceRebuildValidationIT` detects and reports such differences via
> MAE / RMSE / histogram-delta metrics instead of failing the build.

## Generating reference sidecars

### All fixtures

```bash
python tools/reference-export/export_references.py
```

This scans `canon-cr3-imageio/src/test/resources/samples/` recursively for
`*.cr3` files and writes both a `*.reference.tiff` and a `*.reference.png`
sidecar next to each.

### Single fixture

```bash
# Both sidecars
python tools/reference-export/export_references.py --fixture sample.cr3

# RAW-linear TIFF only
python tools/reference-export/export_references.py --fixture sample.cr3 --type tiff

# Preview PNG only
python tools/reference-export/export_references.py --fixture sample.cr3 --type png
```

### Custom samples directory

```bash
python tools/reference-export/export_references.py --samples-dir /path/to/samples
```

### Dry run

```bash
python tools/reference-export/export_references.py --dry-run
```

## What the export script produces

### `*.reference.tiff` – RAW_LINEAR

The raw Bayer mosaic is read from the CR3 via `rawpy.RawPy.raw_image` and
written as a 16-bit single-channel TIFF using `tifffile.imwrite`.  No
debayering, no white-balance scaling, no tone-mapping, no gamma.

The geometry of the output (`width × height`) matches the full sensor readout
as reported by LibRaw, which corresponds to the CRX_BIG track that the Java
decoder targets.  This enables pixel-exact residual comparison.

Equivalent operation:

```python
import rawpy, tifffile, numpy as np

with rawpy.imread("sample.cr3") as raw:
    sensor = np.array(raw.raw_image, dtype=np.uint16)
tifffile.imwrite("sample.reference.tiff", sensor, photometric="minisblack")
```

### `*.reference.png` – PREVIEW_RENDERED

A rendered sRGB preview is produced by `rawpy.postprocess()` with camera
white balance and default output settings, then scaled to 1620 × 1080 with
Pillow LANCZOS resampling.

Equivalent operation:

```python
import rawpy
from PIL import Image

with rawpy.imread("sample.cr3") as raw:
    rgb = raw.postprocess(no_auto_bright=False, use_camera_wb=True, output_bps=8)
img = Image.fromarray(rgb)
img = img.resize((1620, 1080), Image.LANCZOS)
img.save("sample.reference.png", format="PNG")
```

## Updating the manifest

After regenerating sidecars, regenerate the manifest:

```bash
python tools/reference-export/generate_manifest.py
```

The script scans the samples directory, computes SHA-256 for every sidecar,
reads image dimensions and bit depth, assigns `referenceType`, and writes
`docs/cr3-reference-manifest.md`.

Verify the updated manifest passes the IT gate:

```bash
mvn -B verify -pl canon-cr3-imageio -am -Djava.awt.headless=true
```

## Validating existing sidecars

```bash
# Java IT gate (checks existence, hash, dimensions, referenceType, geometry):
mvn -B verify -pl canon-cr3-imageio -am -Djava.awt.headless=true \
    -Dit.test=ReferenceManifestValidationIT

# Rebuild validation (skips if rawpy is not installed):
mvn -B verify -pl canon-cr3-imageio -am -Djava.awt.headless=true \
    -Dit.test=ReferenceRebuildValidationIT
```

## Adding a new fixture

1. Place the `.cr3` file under
   `canon-cr3-imageio/src/test/resources/samples/` (or a subdirectory).
2. Run the export script:
   ```bash
   python tools/reference-export/export_references.py --fixture my-new.cr3
   ```
3. Regenerate the manifest:
   ```bash
   python tools/reference-export/generate_manifest.py
   ```
4. Update `docs/cr3-reference-inventory.md` to include the new entry.
5. Run the IT gate to confirm everything is consistent:
   ```bash
   mvn -B verify -pl canon-cr3-imageio -am -Djava.awt.headless=true \
       -Dit.test=ReferenceManifestValidationIT
   ```
6. Commit the `.cr3`, both sidecars, the updated manifest, and the updated
   inventory.

## Troubleshooting

| Symptom | Likely cause | Resolution |
|---------|-------------|-----------|
| `ImportError: No module named 'rawpy'` | rawpy not installed | `pip install -r tools/reference-export/requirements.txt` |
| Hash mismatch in `ReferenceManifestValidationIT` | Sidecar regenerated with a different rawpy/LibRaw version | Re-run `generate_manifest.py` to lock the new hash, or revert the sidecar |
| `ReferenceRebuildValidationIT` skipped | Python or rawpy not found on `PATH` | Install Python 3.10+ and rawpy |
| `No CRX_BIG track` error | Fixture has unusual CR3 structure | Inspect the container with `canon-cr3-cli` and check track types |
