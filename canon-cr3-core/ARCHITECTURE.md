# canon-cr3-core Architecture

## Overview

`canon-cr3-core` is a pure-Java library for parsing and decoding Canon CR3 RAW
files. It has no dependency on `javax.imageio` — the ImageIO integration is a
separate adapter module (`canon-cr3-imageio`).

## Package Structure

```
com.photographer.focusstack.cr3
├── bmff/           # Generic ISO BMFF box parser (format-agnostic)
│   ├── IsoBox
│   ├── IsoBmffReader
│   └── FtypBox
├── container/      # CR3-specific container parsing
│   ├── Cr3Container
│   ├── Cr3Track
│   └── Cr3Parser
├── metadata/       # Metadata extraction
│   └── Cr3Metadata
├── preview/        # JPEG preview/thumbnail extraction
│   └── Cr3PreviewExtractor
├── raw/            # RAW image data structures
│   ├── Cr3RawImage
│   └── codec/     # CRX compression codec
│       ├── BitReader
│       ├── RiceGolombDecoder
│       └── CrxLosslessRawDecoder
├── processing/     # Image processing pipeline
│   ├── BlackLevelProcessor
│   ├── BayerDemosaicer
│   ├── WhiteBalanceProcessor
│   ├── ColorMatrixProcessor
│   └── SrgbConverter
├── decode/         # High-level decoder API
│   ├── Cr3Decoder
│   └── Cr3DecodeOptions
├── Cr3Exception
└── Cr3FormatException
```

## Design Principles

- **Separation of concerns**: Generic ISO BMFF parsing is separated from
  Canon CR3-specific logic, allowing reuse for other BMFF formats.
- **No ImageIO dependency**: The core decoder can be used standalone without
  `javax.imageio`. ImageIO is only an adapter layer.
- **Stream-based I/O**: Uses `ImageInputStream` with `long` offsets throughout.
  Files are never loaded entirely into memory.
- **Incremental implementation**: Preview extraction works first, then lossless
  RAW decoding, then demosaicing and color processing.
- **Extensibility**: C-RAW (lossy) support and additional camera models are
  prepared with TODO markers.

## Decoding Pipeline

```
CR3 File
  │
  ├─ IsoBmffReader ──→ IsoBox tree
  │
  ├─ Cr3Parser ──→ Cr3Container (tracks, metadata)
  │
  ├─ Cr3PreviewExtractor ──→ JPEG BufferedImage (fast path)
  │
  └─ CrxLosslessRawDecoder ──→ Cr3RawImage (Bayer)
       │
       ├─ BlackLevelProcessor
       ├─ WhiteBalanceProcessor
       ├─ BayerDemosaicer ──→ RGB BufferedImage
       ├─ ColorMatrixProcessor
       └─ SrgbConverter ──→ Final sRGB BufferedImage
```
