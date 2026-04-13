# canon-cr3-core

Core library for parsing, decoding, and processing Canon CR3 RAW image files.

## Purpose

This module provides the complete pipeline for reading Canon CR3 files, from
low-level ISO BMFF box parsing to high-level image processing. It has no
dependency on `javax.imageio` — the ImageIO adapter is in `canon-cr3-imageio`.

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
| `Cr3RawImage` | Decoded Bayer-pattern RAW data |
| `BitReader` | Bitwise reader for compressed streams |
| `RiceGolombDecoder` | Entropy decoder for CRX data |
| `CrxLosslessRawDecoder` | CRX lossless RAW decoder (stub) |

### Image Processing (`com.photographer.focusstack.cr3.processing`)

| Type | Description |
|---|---|
| `BlackLevelProcessor` | Subtracts optical black level |
| `BayerDemosaicer` | Bilinear Bayer demosaicing |
| `WhiteBalanceProcessor` | Applies WB multipliers |
| `ColorMatrixProcessor` | Camera-to-sRGB color matrix |
| `SrgbConverter` | Linear-to-sRGB gamma correction |

### Decoder (`com.photographer.focusstack.cr3.decode`)

| Type | Description |
|---|---|
| `Cr3Decoder` | Main entry point for decoding |
| `Cr3DecodeOptions` | Options controlling decode behavior |

## Dependencies

- `slf4j-api` — Logging

## Build

```bash
mvn -B clean verify -pl canon-cr3-core
```
