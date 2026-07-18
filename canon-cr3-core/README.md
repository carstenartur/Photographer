# canon-cr3-core

Core library for parsing, decoding, and processing Canon CR3 RAW image files.

## Purpose

This module provides the complete pipeline for reading Canon CR3 files, from
low-level ISO BMFF box parsing to high-level image processing. It has no
dependency on `javax.imageio` — the ImageIO adapter is in `canon-cr3-imageio`.

## API stability

The current module combines several layers that will be separated into dedicated
Maven modules. API compatibility expectations are defined in
[`docs/cr3-api-boundaries.md`](../docs/cr3-api-boundaries.md).

In particular:

- container and metadata models are intended as stable external APIs;
- the unified decoder and RAW data model are experimental while CRX decoding is completed;
- codec, processing and subband implementation classes are internal unless the API boundary document explicitly says otherwise;
- classes under a `research` package are research-only and must not be production dependencies.

## Key Components

### ISO BMFF Parser (`com.photographer.focusstack.cr3.bmff`)

| Type | Description |
|---|---|
| `IsoBox` | Record representing a single ISO BMFF box |
| `IsoBmffReader` | Reads box structures from an `ImageInputStream` |
| `FtypBox` | Parsed file type box with CR3 detection |

### Container (`com.photographer.focusstack.cr3.container`)

| Type | Description |
|---|---|
| `Cr3Container` | Parsed top-level structure of a CR3 file |
| `Cr3Track` | Single track (thumbnail, preview, RAW) |
| `Cr3Parser` | Parses CR3 files into container structures |

### Metadata (`com.photographer.focusstack.cr3.metadata`)

| Type | Description |
|---|---|
| `Cr3Metadata` | Camera model, sensor geometry, EXIF data |

### Preview (`com.photographer.focusstack.cr3.preview`)

| Type | Description |
|---|---|
| `Cr3PreviewExtractor` | Extracts JPEG preview and thumbnail images |

### RAW Decoding (`com.photographer.focusstack.cr3.raw`)

| Type | Description |
|---|---|
| `Cr3RawImage` | Experimental decoded Bayer-pattern RAW data model |
| `BitReader` | Internal bitwise reader for compressed streams |
| `RiceGolombDecoder` | Internal entropy decoder for CRX data |
| `CrxLosslessRawDecoder` | Internal CRX lossless RAW decoder — cleanroom adaptive Rice-Golomb implementation |

### Image Processing (`com.photographer.focusstack.cr3.processing`)

| Type | Description |
|---|---|
| `BlackLevelProcessor` | Subtracts optical black level |
| `BayerDemosaicer` | Bilinear Bayer demosaicing |
| `WhiteBalanceProcessor` | Applies WB multipliers |
| `ColorMatrixProcessor` | Camera-to-sRGB color matrix |
| `SrgbConverter` | Linear-to-sRGB gamma correction |

These processing types are currently implementation details of the CR3 pipeline,
not a separately supported image-processing API.

### Decoder (`com.photographer.focusstack.cr3.decode`)

| Type | Description |
|---|---|
| `Cr3Decoder` | Experimental facade for preview, metadata and RAW decoding |
| `Cr3DecodeOptions` | Experimental options controlling decode behavior |

#### `Cr3DecodeOptions` output modes

| Mode | Description |
|---|---|
| `OutputMode.PREVIEW` | Returns the embedded JPEG preview (default; fastest path). |
| `OutputMode.THUMBNAIL` | Returns the embedded JPEG thumbnail. |
| `OutputMode.RAW_PROCESSED` | Decodes the full RAW image with demosaicing and color processing. |
| `OutputMode.RAW_LINEAR` | Decodes the RAW image as a linear Bayer pattern (no demosaicing). |

Factory shortcuts: `Cr3DecodeOptions.defaults()`, `Cr3DecodeOptions.rawProcessed()`, `Cr3DecodeOptions.rawLinear()`.

**Experimental options:** `experimentalPedestal(int level)` and `PedestalStrategy.EXPERIMENTAL_REFERENCE_MATCH` are
research-only controls, not stable public API; they may change or be removed without notice.

## Dependencies

- `slf4j-api` — Logging

## Build

```bash
mvn -B clean verify -pl canon-cr3-core
```
