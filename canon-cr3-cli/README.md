# canon-cr3-cli

Command-line debug and analysis tool for Canon CR3 files.

## Purpose

`canon-cr3-cli` is the thin CLI wrapper around `canon-cr3-core`. It is useful for:

- inspecting ISO-BMFF / CR3 container structure
- dumping extracted CR3 metadata and crop geometry
- inspecting RAW/CRX track layout and subband structure
- extracting embedded JPEG previews and thumbnails
- running the current RAW decode pipeline for visual comparison

## Commands

| Command | What it does | Main information source |
|---|---|---|
| `info <file.cr3>` | Prints high-level file, track, and basic metadata summary. | `Cr3Parser`, `Cr3MetadataReader` |
| `boxes <file.cr3>` | Dumps the parsed CR3 box hierarchy. | `Cr3Parser` |
| `dump-box-tree <file.cr3>` | Dumps the raw ISO-BMFF box tree with offsets and sizes. | `IsoBmffReader` |
| `dump-metadata <file.cr3>` | Prints extracted camera / EXIF metadata and active area. | `Cr3MetadataReader` |
| `dump-crop <file.cr3>` | Prints sensor, active-area, and default output geometry. | `IAD1` → `Cr3Metadata` / `SensorCropGeometry` |
| `dump-raw-layout <file.cr3>` | Prints RAW-track codec, compression mode, dimensions, offsets, and sizes. | `Cr3Track`, `CMP1`-derived track state |
| `list-subbands <file.cr3>` | Lists every CRX tile/plane/subband with offsets, sizes, quantization, and codec version. | `CrxHeader`, `SubbandExtractor` |
| `dump-subband <file.cr3> <seq> [outBase]` | Writes one subband as `.bits` plus `.meta.json` sidecar. | `CrxHeader`, `SubbandExtractor` |
| `subband-stats <file.cr3> <seq>` | Computes byte/bit statistics for one CRX subband from a CR3 file. | `SubbandStatistics` |
| `subband-stats --bits <file.bits>` | Computes the same statistics from a previously dumped `.bits` file. | `SubbandStatistics` |
| `preview <file.cr3> [output.jpg]` | Extracts the embedded JPEG preview. | `Cr3PreviewExtractor` |
| `thumb <file.cr3> [output.jpg]` | Extracts the embedded JPEG thumbnail. | `Cr3PreviewExtractor` |
| `decode <file.cr3> [output.jpg]` | Runs the current RAW→sRGB pipeline and writes a JPEG. | `Cr3Decoder` |
| `decode-compare <file.cr3> [outBase]` | Writes decoded RAW, embedded preview, and thumbnail for side-by-side comparison. | `Cr3Decoder` |
| `pedestal-report <file.cr3>` | Prints where the decoder resolved pedestal/black-level from (OB, metadata, fallback) plus key values. | `Cr3Decoder.pedestalReport()` |

## Quick examples

```bash
# High-level file and metadata summary
java -jar canon-cr3-cli.jar info photo.cr3

# Parsed CR3 boxes
java -jar canon-cr3-cli.jar boxes photo.cr3

# Raw BMFF tree with offsets/sizes
java -jar canon-cr3-cli.jar dump-box-tree photo.cr3

# Extracted metadata and active area
java -jar canon-cr3-cli.jar dump-metadata photo.cr3

# Sensor / crop geometry from IAD1-derived metadata
java -jar canon-cr3-cli.jar dump-crop photo.cr3

# RAW track layout and compression summary
java -jar canon-cr3-cli.jar dump-raw-layout photo.cr3

# CRX header / subband inventory
java -jar canon-cr3-cli.jar list-subbands photo.cr3

# Dump one CRX subband plus JSON metadata
java -jar canon-cr3-cli.jar dump-subband photo.cr3 7 sample.sb7

# Subband statistics from CR3 or a dumped .bits file
java -jar canon-cr3-cli.jar subband-stats photo.cr3 7
java -jar canon-cr3-cli.jar subband-stats --bits sample.sb7.bits

# Extract embedded JPEGs
java -jar canon-cr3-cli.jar preview photo.cr3 output.jpg
java -jar canon-cr3-cli.jar thumb photo.cr3 thumbnail.jpg

# Decode RAW and compare against camera-rendered outputs
java -jar canon-cr3-cli.jar decode photo.cr3 output.jpg
java -jar canon-cr3-cli.jar decode-compare photo.cr3 compare

# Diagnose pedestal/black-level source and values
java -jar canon-cr3-cli.jar pedestal-report photo.cr3
```

## Analysis coverage relevant to CR3 research

### BlackLevel information

- **Available today in the repository:** `Cr3Decoder.resolveBlackLevel()`, `ObBlackLevelEstimator`, `BlackLevelProcessor`, and the `BlackLevel` model.
- **What the CLI currently exposes:** `decode` / `decode-compare` use the resolved black-level path during RAW decoding.
- **Dedicated diagnostics command:** `pedestal-report` prints the resolved source (`OB_PER_ROW`, `METADATA_PER_CHANNEL`, `DEFAULT_GLOBAL`) and resolved levels.
- **Current sources used by the decoder:**  
  1. OB-derived per-row estimation from the left optical-black region (`metadata.activeAreaLeft()`),  
  2. per-channel metadata if `Cr3Metadata.perChannelBlackLevels()` is populated,  
  3. global fallback default.

### IAD1 information

- `Iad1Box` parses crop, optical-black, and active-area rectangles.
- `Cr3Parser` attaches parsed `IAD1` data to the RAW track (`Cr3Track.iad1()`).
- `Cr3MetadataReader` copies the active area into `Cr3Metadata`.
- `dump-crop` is the main CLI command for IAD1-derived geometry.
- `dump-metadata` also prints the active area.

### CMP1 information

- `Cmp1Box` parses the CR3 compression parameters: version, full image size, tile size, bit depth, plane count, CFA layout, encoding type, wavelet levels, `mdatHeaderSize`, and extended-header details such as `medianBitDepth`.
- `Cr3Parser` attaches parsed `CMP1` data to the RAW track (`Cr3Track.cmp1()`).
- `dump-raw-layout` exposes the most useful track-level compression summary in the CLI.
- `dump-subband` / `list-subbands` expose additional CMP1-derived details such as bit depth, tile size, wavelet levels, encoding type, and codec version.
- There is **no dedicated CLI command yet** that prints every raw `CMP1` field verbatim.

### CRX header information

- `CrxHeader` parses the tile / plane / subband header tree from the CRX bitstream.
- `SubbandExtractor` converts that structure into CLI-usable descriptors with offsets, sizes, quantization, rounded bits, geometry, and codec version.
- `list-subbands` is the best CLI inventory view of CRX-header-derived data.
- `dump-subband` writes the selected subband payload plus a `.meta.json` sidecar containing the relevant CRX header facts.
- `subband-stats` analyzes the extracted payload content, not just the container metadata.

### Reference information

- **Not exposed as a direct CLI command today.**
- Repository-side reference tooling exists in:
  - `tools/reference-export/export_references.py`
  - `tools/reference-export/generate_manifest.py`
  - `docs/cr3-reference-manifest.md`
- Existing reference types:
  - `reference.tiff` → `RAW_LINEAR`
  - `reference.png` → `PREVIEW_RENDERED`
- There is also a test-only embedded-preview helper in `PreviewReferenceExtractor` for research comparisons.

## Dependencies

- `canon-cr3-core` — core CR3 parsing, metadata, and decoding logic
- `logback-classic` — logging implementation

## Build

```bash
mvn -B clean verify -pl canon-cr3-cli -am -Djava.awt.headless=true
```
