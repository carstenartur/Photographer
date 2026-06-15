# CR3 Reference Inventory

Canonical inventory of every CR3 fixture and its reference sidecars.

- **RAW_LINEAR TIFF** – verbatim Bayer mosaic, 16-bit, matches CRX_BIG geometry exactly.
- **PREVIEW_RENDERED PNG** – debayered sRGB preview at 1620 × 1080.
- **Status VERIFIED** – file exists, hash matches manifest, `ReferenceManifestValidationIT` passes.
- **Status MISSING** – sidecar not yet generated; run `export_references.py` to create it.

| Fixture | TIFF sidecar | PNG sidecar | Tool | Version | Status |
|---------|-------------|-------------|------|---------|--------|
| `sample.cr3` | `sample.reference.tiff` | `sample.reference.png` | LibRaw | rawpy≥0.22 / LibRaw≥0.22 | VERIFIED |
| `sample-iso-high.cr3` | `sample-iso-high.reference.tiff` | `sample-iso-high.reference.png` | LibRaw | rawpy≥0.22 / LibRaw≥0.22 | VERIFIED |
| `sample-tele.cr3` | `sample-tele.reference.tiff` | `sample-tele.reference.png` | LibRaw | rawpy≥0.22 / LibRaw≥0.22 | VERIFIED |
| `sample-wide.cr3` | `sample-wide.reference.tiff` | `sample-wide.reference.png` | LibRaw | rawpy≥0.22 / LibRaw≥0.22 | VERIFIED |
| `sample-wb-tungsten.cr3` | `sample-wb-tungsten.reference.tiff` | `sample-wb-tungsten.reference.png` | LibRaw | rawpy≥0.22 / LibRaw≥0.22 | VERIFIED |
| `eosr-iso-low.cr3` | `eosr-iso-low.reference.tiff` | `eosr-iso-low.reference.png` | LibRaw | rawpy≥0.22 / LibRaw≥0.22 | VERIFIED |
| `eosr-iso-mid.cr3` | `eosr-iso-mid.reference.tiff` | `eosr-iso-mid.reference.png` | LibRaw | rawpy≥0.22 / LibRaw≥0.22 | VERIFIED |
| `eosr-iso-high.cr3` | `eosr-iso-high.reference.tiff` | `eosr-iso-high.reference.png` | LibRaw | rawpy≥0.22 / LibRaw≥0.22 | VERIFIED |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | `g5xm2-asis-iso200-ss130-ff2p5-01.reference.tiff` | `g5xm2-asis-iso200-ss130-ff2p5-01.reference.png` | LibRaw | rawpy≥0.22 / LibRaw≥0.22 | VERIFIED |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | `g5xm2-dark-…-02.reference.tiff` | `g5xm2-dark-…-02.reference.png` | LibRaw | rawpy≥0.22 / LibRaw≥0.22 | VERIFIED |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | `g5xm2-flat-…-02.reference.tiff` | `g5xm2-flat-…-02.reference.png` | LibRaw | rawpy≥0.22 / LibRaw≥0.22 | VERIFIED |

## CR3 fixtures without reference sidecars

The following fixtures are present in the test resource tree but do **not** yet
have reference sidecars.  Run `export_references.py` with the fixture name to
generate them and then run `generate_manifest.py` to update the manifest.

| Fixture | Notes |
|---------|-------|
| `research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3` | Detail fixture, no sidecars yet |
| `research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-03.cr3` | Detail fixture, no sidecars yet |

## Updating the inventory

This file is maintained manually.  After adding new fixtures and running the
export + manifest-generation scripts, update the table above and set the
**Status** to `VERIFIED`.
