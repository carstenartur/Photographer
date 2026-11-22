# CR3 / CRX

This is the maintained entry point for Photographer’s independent Java libraries for the Canon CR3 format and its clean-room CRX research.

The native Java RAW import reconstructs supported 14-bit, four-plane CRX layouts.
See [camera compatibility](camera-coverage.md) for RAW and C-RAW coverage,
and [decoder and processing](decoder.md) for format and memory limits.

The [0.1.8 release notes](../releases/0.1.8.md) cover RGB16 import in Java and Fiji, the active-area crop correction, and RAW heap planning.

## Choose the path you need

| Need | Start here | Status |
|---|---|---|
| Install the CR3 libraries with Maven | [Maven release repositories](../cr3-maven-central.md#resolve-a-released-version) | Version 0.1.8 on Maven Central, with a separate Git-backed distribution. |
| Inspect CR3 tracks or extract embedded preview/thumbnail JPEGs | [Container and metadata](decoder.md#stable-container-and-metadata-layers) | Stable public APIs |
| Import a file with the Java decoder or export camera frames from Photographer | [CR3 file import](import.md) | Includes native sensor and developed RGB examples |
| Use CR3 through Java ImageIO | [ImageIO integration](imageio.md) | Default RAW color development on supported layouts, explicit preview, thumbnail and metadata |
| Review import speed and heap requirements | [Complete-import performance](optimization-2026-11-19/README.md) | Fresh-read Java 21/25 comparisons, exact-output checks and constrained-heap examples |
| Write a lossless CR3 from native sensor data | [CR3 camera-profile writer](imageio.md#write-a-lossless-cr3-using-a-camera-profile) | Four built-in profiles and their full Bayer mosaics |
| Export a native sensor image and reopen it as a DNG | [Bayer DNG round trip](imageio.md#write-and-read-a-bayer-dng) | Uncompressed 16-bit Bayer mosaic with calibration |
| Inspect sensor values in Fiji or ImageJ | [Fiji update site](fiji-update-site.md) · [Import and macro examples](../../cr3-imagej/README.md) | One unsigned 16-bit plane, full sensor coordinates |
| Develop a 16-bit color image in Fiji or Java | [Fiji color import](fiji-update-site.md) · [ImageIO options](imageio.md) | Three 16-bit RGB channels from 0.1.8, with experimental color rendering |
| Use the command-line tool | [CLI](cli.md) | Supported command surface with experimental decode commands identified |
| Follow the decoder from CRX sample to Bayer rows and RGB | [Native codec](decoder.md#native-codec-layer) · [Output modes](decoder.md#output-modes) | Stable container and metadata APIs, experimental RAW controls, internal processing |
| Implement or verify a CR3 reader or writer | [CR3 and CRX format reference](format.md) | Measured byte and bit layouts, fixtures and implementation links |
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
| `io.github.carstenartur:cr3-core` | Decoder facade and camera-profile writer | Stable preview and thumbnail paths. Native decoding and lossless writing cover the measured profiles. |
| `io.github.carstenartur:cr3-imageio` | Java ImageIO CR3 and Bayer DNG readers and writers | Stable preview and metadata access, profile-specific RAW import |
| `io.github.carstenartur:cr3-imagej` | Full-sensor Bayer import and RGB16 composite import | Distributed as a Fiji plugin |
| `com.photographer.focusstack:canon-cr3-cli` | Inspection, preview export and experimental decode commands | Executable built from a source checkout |

### Internal evidence and development modules

| Artifact | Purpose | Runtime use |
|---|---|---|
| `canon-cr3-reference-testkit` | Reference resolution, TIFF loading, metrics and deterministic evidence writers | Test/research only |
| `canon-cr3-research` | Hypothesis tests, observation harnesses and report generators | Research only |
| `canon-cr3-maven-smoke` | Empty-repository consumer and dependency-boundary validation | Build/test only |
| `canon-cr3-benchmark` | JMH performance measurements | Benchmark profile only |

No production module may depend on the reference testkit or research module at runtime. The [Maven release guide](../cr3-maven-central.md) lists the six distributed libraries and explains both release channels.

## Maturity boundary

### Supported without full RAW decoding

The following do not depend on a correct sensor-RAW reconstruction:

- CR3 file/container detection and high-level parsing;
- track enumeration and structural metadata;
- embedded preview and thumbnail extraction;
- structured CR3 metadata;
- ImageIO SPI discovery;
- CLI inspection and embedded-JPEG extraction commands.

### Native sensor decoding

Use `Cr3DecodeOptions.rawSensor()` for the native Bayer mosaic, including the optical-black margins. Use `rawLinear()` for the scaled Bayer output. `ImageIO.read(file)` develops the full-resolution color image on supported RAW layouts. See the [ImageIO output modes](imageio.md) and [verified layouts](current-state.md).

## Documentation authority

For implementation claims, use this order:

1. code and executable tests;
2. current module POMs and [API boundaries](../cr3-api-boundaries.md);
3. this maintained CR3 manual and module READMEs;
4. [Current state](current-state.md) and [Open hypotheses](open-hypotheses.md);
5. dated/generated reports indexed by [Results](reports/index.md);
6. historical experiment-local notes.

Generated evidence does not silently become current truth, and a research plan does not prove an implementation exists.
