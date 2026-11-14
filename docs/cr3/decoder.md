# CR3 Decoder and Processing

The native Java RAW import supports the measured 14-bit, four-plane V1 configurations for the Canon EOS R, EOS RP, EOS M50 and PowerShot G5 X Mark II. The full-frame acceptance panel covers 15 lossless RAW files and six three-level C-RAW files, including two project-owned PowerShot captures. [Lossless measurements](../cr3-results/2026-11-14-crx-native/README.md) · [C-RAW measurements](../cr3-results/2026-11-14-crx-wavelet/README.md)

## Stable container and metadata layers

### `canon-cr3-container`

Supported responsibilities:

- high-level CR3 detection and parsing;
- immutable container and track models;
- CMP1/IAD1 facts projected into the high-level model;
- embedded preview and thumbnail payload extraction.

External entry points include `Cr3Parser`, `Cr3Container`, `Cr3Track` and `Cr3PreviewExtractor`. Low-level BMFF box classes are internal.

### `canon-cr3-metadata`

Supported responsibilities:

- `Cr3Metadata` consumer model;
- EXIF/TIFF, Canon MakerNote and CTMD-derived information;
- sensor dimensions, bit depth and active-area projection.

Parser helpers and offset/layout interpretation are internal. Consumers should depend on `Cr3Metadata`, the facade or ImageIO metadata rather than low-level parsing classes.

## Native codec layer

`canon-cr3-codec` owns:

- CRX header parsing;
- bit readers and Rice-Golomb primitives;
- entropy/predictor state;
- tile, plane and subband reconstruction;
- inverse wavelet scaffolding;
- the `Cr3RawImage` linear Bayer transfer model.

The public decoder selects the native entropy and wavelet paths using the compression parameters in the sensor track.

Most classes under `com.photographer.focusstack.cr3.raw.codec` are internal even when Java visibility is public for module integration and tests. `Cr3RawImage` is `PUBLIC_EXPERIMENTAL`.

## Internal processing layer

`canon-cr3-processing` applies post-codec operations:

- pedestal/optical-black handling;
- active-area crop;
- Bayer demosaic;
- white balance;
- camera color matrix and dual-illuminant interpolation;
- linear-to-sRGB conversion;
- orientation transforms.

These classes implement the current CR3 pipeline and are not a general-purpose public image-processing toolkit. Image-quality changes must not be mixed into entropy/predictor experiments.

## Unified facade

`canon-cr3-core` composes container, metadata, codec and processing into:

- `Cr3Decoder`;
- `Cr3DecodeOptions`;
- evolving pedestal/diagnostic reports.

### Output modes

| Mode | Status | Behavior |
|---|---|---|
| `PREVIEW` | Stable subset | Embedded JPEG preview; default and most reliable path |
| `THUMBNAIL` | Stable subset | Embedded JPEG thumbnail |
| `RAW_LINEAR` | Verified for the RG4 lossless and RG5 C-RAW layouts | Linear Bayer mosaic without demosaic |
| `RAW_PROCESSED` | Experimental | RAW decode plus crop/demosaic/color processing |

Factory shortcuts include `Cr3DecodeOptions.defaults()`, `rawLinear()` and `rawProcessed()`.

`experimentalPedestal(int)` and `EXPERIMENTAL_REFERENCE_MATCH` are research-only controls. They must not appear in production examples or compatibility promises.

## Full-sensor verification

For a reference comparison, select the 16-bit linear output and disable the active-area crop and black-level processing. The public raster scales 14-bit samples to 16-bit grayscale. The test checks that this scaling is exactly reversible at every site.

```java
try (var decoder = new Cr3Decoder()) {
    decoder.setInput(rawFile);
    var options = Cr3DecodeOptions.rawLinear()
            .bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN)
            .cropToActiveArea(false)
            .applyBlackLevel(false);
    BufferedImage sensor = decoder.decode(options);
}
```

The C-RAW gate checks equality with the native reconstruction of the compressed capture, including optical black, both sides of the tile seam and saturated sites. V2 CRX, RAW bursts, additional sensor depths, and camera-specific color rendering have separate qualification criteria.

## Research promotion gate

A production semantic change requires:

1. a pre-stated hypothesis based on a permissible source or project-owned observation;
2. fixed inputs and manifest-bound references;
3. quantitative improvement on the specified primary metrics;
4. no regression across camera-family and physical plausibility gates;
5. a durable result document with accepted/rejected status;
6. clean-room contributor evidence;
7. a separate production change and regression test.

Broad sweeps selected by rendered appearance are not an acceptable promotion method.

## Build and verification

```bash
mvn -B verify -pl canon-cr3-container -am -Djava.awt.headless=true
mvn -B verify -pl canon-cr3-metadata -am -Djava.awt.headless=true
mvn -B verify -pl canon-cr3-codec -am -Djava.awt.headless=true
mvn -B verify -pl canon-cr3-processing -am -Djava.awt.headless=true
mvn -B clean verify -pl canon-cr3-core -am -Djava.awt.headless=true
```

The experimental codec gate proves current regression contracts, not universal correctness.

## Related documentation

- [CR3 overview](index.md)
- [ImageIO](imageio.md)
- [Current state](current-state.md)
- [Research process](research.md)
- [API boundaries](../cr3-api-boundaries.md)
- [Clean-room record](../cleanroom/README.md)
- [Container README](../../canon-cr3-container/README.md)
- [Metadata README](../../canon-cr3-metadata/README.md)
- [Codec README](../../canon-cr3-codec/README.md)
- [Processing README](../../canon-cr3-processing/README.md)
- [Core README](../../canon-cr3-core/README.md)
