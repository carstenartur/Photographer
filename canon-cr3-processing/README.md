# cr3-processing

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

This module may depend on `cr3-metadata` and `cr3-codec`. It must
not depend on the decoder compatibility facade, ImageIO, CLI, application or
research modules. A direct dependency on `cr3-container` is intentionally
avoided; container facts should reach this layer through metadata or codec
models rather than low-level box structures.

Separating this layer keeps CRX bitstream work independent from display rendering
and prevents image-quality changes from being mixed into codec experiments.

## Compatibility contract

The extraction is structural only. Existing processing classes retain their package
names and behavior. The module split must not change pedestal selection, pixel
values, demosaicing, color conversion, crop geometry or orientation results.

## Build

```bash
mvn -B verify -pl canon-cr3-processing -am -Djava.awt.headless=true
```

## Canonical documentation

Cross-module CR3 processing maturity and supported output claims are maintained in the [CR3 / CRX Manual](../docs/cr3/index.md).
