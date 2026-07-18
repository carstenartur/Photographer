# canon-cr3-imageio Architecture

## Overview

`canon-cr3-imageio` is a thin adapter that exposes CR3 reading through the
standard `javax.imageio` API. Decode orchestration remains in the
`canon-cr3-core` compatibility facade; the adapter declares the split modules
whose public types it uses instead of relying on accidental transitive
classpath visibility.

## Package Structure

```text
com.photographer.focusstack.cr3.imageio
├── CanonCr3ImageReaderSpi   # SPI: format detection and reader factory
├── CanonCr3ImageReader      # ImageReader adapter around Cr3Decoder
├── CanonCr3ImageReadParam   # Extended parameters backed by Cr3DecodeOptions
└── Cr3ImageMetadata         # IIOMetadata projection of Cr3Metadata
```

## Module dependencies

- `canon-cr3-core` — decode facade and options
- `canon-cr3-container` — CR3 detection, preview/thumbnail access and track data
- `canon-cr3-metadata` — consumer metadata model
- `canon-cr3-codec` — RAW types used by retained ImageIO diagnostics/tests
- `canon-cr3-processing` — orientation and output geometry helpers

The module has no dependency on `canon-cr3-research` or
`canon-cr3-reference-testkit`. Reference-backed research tests live in the
separate research module.

## Design Principles

- **Adapter only**: no entropy, predictor, demosaic or colour algorithm is
  implemented here.
- **Standard API**: consumers can use `ImageIO.read()` and service discovery.
- **Explicit dependencies**: direct imports are represented by direct Maven
  dependencies rather than inherited through the facade.
- **SPI service loading**: registration uses
  `META-INF/services/javax.imageio.spi.ImageReaderSpi`.
