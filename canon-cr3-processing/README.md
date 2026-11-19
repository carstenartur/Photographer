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

The module split preserved the existing processing classes, their package names
and the default eight-bit rendering behavior. The opt-in 16-bit RGB development
path added in 0.1.8-SNAPSHOT keeps fractional values through the enabled processing
stages and quantizes once into three unsigned-short planes. Its row buffers are
bounded by image width and worker count, and it writes directly into the requested
oriented output region. The color model describes sRGB or linear RGB according to
the selected transfer function. Native Bayer decoding is unaffected.

## Build

```bash
mvn -B verify -pl canon-cr3-processing -am -Djava.awt.headless=true
```

## Canonical documentation

Cross-module CR3 processing maturity and supported output claims are maintained in the [CR3 / CRX Manual](../docs/cr3/index.md).
