# canon-cr3-codec

Experimental clean-room CRX RAW codec and linear Bayer data model.

## Responsibilities

- CRX header parsing
- bitstream and Rice-Golomb primitives
- lossless CRX decoding
- lossy/C-RAW decoder scaffolding
- inverse wavelet transform
- tile/plane reconstruction
- `Cr3RawImage` Bayer mosaic representation
- subband extraction and byte/bit statistics for CLI and research diagnostics

## Dependency rule

This module may depend on `canon-cr3-container` and `canon-cr3-metadata`.
It must not depend on post-codec processing, the compatibility facade, ImageIO,
CLI, application, or `canon-cr3-research`.

`SubbandStatistics` is a codec diagnostic used by the production CLI. During the
mechanical module split it intentionally keeps the legacy
`com.photographer.focusstack.cr3.research` package to avoid a source-breaking
rename and a forbidden CLI dependency on `canon-cr3-research`. The class is owned
and published by this codec module; #102 may move it to a diagnostics package when
downstream dependencies are rewired.

## Maturity

The API and decoder implementation are experimental. Moving them into a dedicated
module isolates that instability from the stable container and metadata layers; it
does not claim that CRX decoding is complete.

## Build

```bash
mvn -B verify -pl canon-cr3-codec -am -Djava.awt.headless=true
```
