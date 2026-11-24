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
- profile-based correction of vignetting, lateral chromatic aberration and distortion
- camera tone curves for supported body and Picture Style combinations
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

## Optical calibration data

The JAR includes three sets of measured Lensfun coefficients for full-field recordings.
The PowerShot G5 X Mark II uses its fixed-lens profile. The EOS R requires an exact
match for the RF 24–105 mm F4 L IS USM or RF 85 mm F2 Macro IS STM.
The registry evaluates the calibration at the recorded focal length, aperture and
focus distance. It bounds lookups to the closest measured focus planes. For the
G5 X Mark II both published distance planes contain the same coefficients, so
near-focus results depend on a calibration measured at longer distances.

The Java processing code uses the project's MIT license. The three profile
tables are distributed under CC BY-SA 3.0, with their origin, modifications
and license in `META-INF/lensfun` inside the JAR. [Data attribution](src/main/resources/META-INF/lensfun/NOTICE.txt) · [Profile license](src/main/resources/META-INF/lensfun/LICENSE-CC-BY-SA-3.0.txt)

## Compatibility contract

The module split preserved the existing processing classes, their package names
and the eight-bit rendering path. ImageIO uses 16-bit sRGB by default in
0.1.9-SNAPSHOT. The 16-bit RGB development path added in 0.1.8 keeps fractional values through the enabled processing
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
