# cr3-codec

Experimental clean-room CRX RAW codec and linear Bayer data model.

## Responsibilities

- CRX header parsing
- bitstream and Rice-Golomb primitives
- lossless CRX decoding
- native three-level C-RAW entropy decoding, inverse quantization and integer 5/3 synthesis
- inverse wavelet transform
- tile/plane reconstruction
- `Cr3RawImage` Bayer mosaic representation
- subband extraction and byte/bit statistics for CLI and research diagnostics

## Dependency rule

This module may depend on `cr3-container` and `cr3-metadata`.
It must not depend on post-codec processing, the compatibility facade, ImageIO,
CLI, application, or `canon-cr3-research`.

`SubbandStatistics` is a codec diagnostic used by the production CLI. During the
mechanical module split it intentionally keeps the legacy
`com.photographer.focusstack.cr3.research` package to avoid a source-breaking
rename and a forbidden CLI dependency on `canon-cr3-research`. The class is owned
and published by this codec module; #102 may move it to a diagnostics package when
downstream dependencies are rewired.

## Maturity

The public decoder uses the measured 14-bit V1 lossless and three-level C-RAW paths. Codec implementation classes remain internal. [Profiles and full-image checks](../docs/cr3/decoder.md)

## Build

```bash
mvn -B verify -pl canon-cr3-codec -am -Djava.awt.headless=true
```

## Canonical documentation

The [CR3 / CRX Manual](../docs/cr3/index.md) owns cross-module maturity claims. Clean-room experiment, evidence and promotion rules are maintained in [CR3 research](../docs/cr3/research.md).
