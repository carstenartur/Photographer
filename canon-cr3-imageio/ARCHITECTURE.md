# canon-cr3-imageio Architecture

## Overview

`canon-cr3-imageio` is a thin adapter layer that exposes the CR3 decoder from
`canon-cr3-core` through the standard `javax.imageio` API.

## Package Structure

```
com.photographer.focusstack.cr3.imageio
├── CanonCr3ImageReaderSpi   # SPI: format detection, reader factory
├── CanonCr3ImageReader      # ImageReader: delegates to Cr3Decoder
└── CanonCr3ImageReadParam   # Extended params with Cr3DecodeOptions
```

## Design Principles

- **Adapter pattern**: This module contains no decoding logic. All actual
  work is delegated to `canon-cr3-core`.
- **Standard API**: Users interact via `ImageIO.read()` without knowing
  about CR3-specific classes.
- **SPI service loading**: Automatic registration via
  `META-INF/services/javax.imageio.spi.ImageReaderSpi`.
