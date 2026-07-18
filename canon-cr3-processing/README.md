# canon-cr3-processing

Post-codec processing for linear CR3 RAW mosaics.

## Responsibilities

- optical-black / pedestal estimation and subtraction
- black-level models
- Bayer demosaicing
- white balance
- camera color-matrix lookup and application
- dual-illuminant interpolation
- linear-to-sRGB conversion
- EXIF orientation transforms
- shared pixel helpers

## Dependency rule

This module may depend on container, metadata and codec. It must not depend on
the decoder compatibility facade, ImageIO, CLI, application or research modules.

Separating this layer keeps CRX bitstream work independent from display rendering
and prevents image-quality changes from being mixed into codec experiments.

## Build

```bash
mvn -B verify -pl canon-cr3-processing -am -Djava.awt.headless=true
```
