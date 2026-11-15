# CR3 / CRX

This is the maintained entry point for Photographer’s independent Java libraries for the Canon CR3 format and its clean-room CRX research.

The native Java RAW import supports the measured 14-bit, four-plane V1 configurations for the Canon EOS R, EOS RP, EOS M50 and PowerShot G5 X Mark II. The full-frame acceptance panel covers 15 lossless RAW files and six three-level C-RAW files, including two project-owned PowerShot captures. [Lossless measurements](../cr3-results/2026-11-14-crx-native/README.md) · [C-RAW measurements](../cr3-results/2026-11-14-crx-wavelet/README.md)

## Choose the path you need

| Need | Start here | Status |
|---|---|---|
| Inspect CR3 tracks or extract embedded preview/thumbnail JPEGs | [Container and metadata](decoder.md#stable-container-and-metadata-layers) | Stable public APIs |
| Import a file with the Java decoder or export camera frames from Photographer | [CR3 file import](import.md) | Includes native sensor and developed RGB examples |
| Use CR3 through Java ImageIO | [ImageIO integration](imageio.md) | Stable preview, thumbnail, discovery and metadata; RAW controls experimental |
| Write a lossless CR3 from native sensor data | [CR3 camera-reference writer](imageio.md#write-a-lossless-cr3-using-a-camera-reference) | Requires a supported camera reference and the matching Bayer mosaic |
| Export a native sensor image and reopen it as a DNG | [Bayer DNG round trip](imageio.md#write-and-read-a-bayer-dng) | Uncompressed 16-bit Bayer mosaic with calibration |
| Inspect sensor values in Fiji or ImageJ | [Fiji update site](fiji-update-site.md) · [Import and macro examples](../../cr3-imagej/README.md) | One unsigned 16-bit plane, full sensor coordinates |
| Use the command-line tool | [CLI](cli.md) | Supported command surface with experimental decode commands identified |
| Understand the decoder and processing pipeline | [Decoder and processing](decoder.md) | Mixed: stable container/metadata, experimental RAW, internal processing |
| See the exact research boundary | [Current state](current-state.md) | Maintained truth boundary |
| See unresolved, rejected and evidence-acquisition work | [Open hypotheses](open-hypotheses.md) | Maintained research state |
| Conduct clean-room research | [Research process](research.md) | Mandatory process |
| Read durable experiment results | [Results index](reports/index.md) | Curated index to dated/generated evidence |
| Understand supported Java symbols | [API boundaries](../cr3-api-boundaries.md) | Compatibility source of truth |
| Reproduce reference sidecars | [Reference export pipeline](../reference-pipeline.md) | Maintained evidence pipeline |
| Follow legal/process safeguards | [Clean-room engineering record](../cleanroom/README.md) | Mandatory for semantic CRX work |

## Module map

### Runtime and user modules

| Artifact | Purpose | Maturity |
|---|---|---|
| `io.github.carstenartur:cr3-container` | ISO BMFF/CR3 parsing, tracks, embedded JPEG preview and thumbnail extraction | Stable external surface |
| `io.github.carstenartur:cr3-metadata` | Structured EXIF, MakerNote, CTMD and sensor/active-area metadata | Stable external surface |
| `io.github.carstenartur:cr3-codec` | CRX header, entropy, predictor, tile/plane reconstruction and Bayer transfer model | Experimental/internal except explicitly classified diagnostics/models |
| `io.github.carstenartur:cr3-processing` | Pedestal, crop, demosaic, white balance and color processing | Internal pipeline, not a general processing API |
| `io.github.carstenartur:cr3-core` | Unified `Cr3Decoder` / `Cr3DecodeOptions` facade | Preview/thumbnail subset reliable; verified V1 lossless and three-level C-RAW |
| `io.github.carstenartur:cr3-imageio` | Java ImageIO CR3 and Bayer DNG readers and writers | Stable preview and metadata access, profile-specific RAW import |
| `io.github.carstenartur:cr3-imagej` | Full-sensor Bayer importer for ImageJ 1.x and Fiji | Distributed as a Fiji plugin |
| `com.photographer.focusstack:canon-cr3-cli` | Inspection, extraction and experimental decode commands | Repository CLI; excluded from the public Maven Central library bundle |

### Internal evidence and development modules

| Artifact | Purpose | Runtime use |
|---|---|---|
| `canon-cr3-reference-testkit` | Reference resolution, TIFF loading, metrics and deterministic evidence writers | Test/research only |
| `canon-cr3-research` | Hypothesis tests, observation harnesses and report generators | Research only |
| `canon-cr3-maven-smoke` | Empty-repository consumer and dependency-boundary validation | Build/test only |
| `canon-cr3-benchmark` | JMH performance measurements | Benchmark profile only |

No production module may depend on the reference testkit or research module at runtime. The public Maven bundle and release rules are defined in [Maven Central release contract](../cr3-maven-central.md).

## Maturity boundary

### Supported without full RAW decoding

The following do not depend on a correct sensor-RAW reconstruction:

- CR3 file/container detection and high-level parsing;
- track enumeration and structural metadata;
- embedded preview and thumbnail extraction;
- structured CR3 metadata;
- ImageIO SPI discovery;
- default `ImageIO.read(file)` embedded-preview behavior;
- CLI inspection and embedded-JPEG extraction commands.

### Native sensor decoding

Use `Cr3Decoder` with `Cr3DecodeOptions.rawLinear()` for the Bayer image. Canon's three-level C-RAW mode uses per-subband inverse quantization and inverse 5/3 lifting. The default ImageIO read still selects the embedded JPEG. Processed color, other codec versions, RAW bursts and other sensor layouts have separate support and verification boundaries.

## Documentation authority

For implementation claims, use this order:

1. code and executable tests;
2. current module POMs and [API boundaries](../cr3-api-boundaries.md);
3. this maintained CR3 manual and module READMEs;
4. [Current state](current-state.md) and [Open hypotheses](open-hypotheses.md);
5. dated/generated reports indexed by [Results](reports/index.md);
6. historical experiment-local notes.

Generated evidence does not silently become current truth, and a research plan does not prove an implementation exists.
