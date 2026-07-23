# canon-cr3-metadata

CR3 metadata model and parsers, independent of RAW decoding and rendering.

## Responsibilities

- public `Cr3Metadata` model
- CMT1/CMT2/CMT3/CMT4 metadata extraction
- TIFF/EXIF IFD parsing
- Canon MakerNote parsing
- CTMD parsing
- projection of container CMP1/IAD1 data into sensor, bit-depth and active-area metadata

## Dependency rule

This module depends only on `canon-cr3-container` among CR3 production modules.
It must not depend on the RAW codec, processing, ImageIO, CLI, or research modules.

## Build

```bash
mvn -B verify -pl canon-cr3-metadata -am -Djava.awt.headless=true
```

## Canonical documentation

Cross-module CR3 maturity, supported surfaces and navigation are maintained in the [CR3 / CRX Manual](../docs/cr3/index.md).
