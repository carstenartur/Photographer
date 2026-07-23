# Canon CR3 / CRX

This is the maintained entry point for Photographer's Canon CR3 libraries and clean-room CRX research.

Do not treat all CR3 functionality as one maturity level. Container inspection, metadata, embedded JPEGs and standard ImageIO integration have supported external surfaces. Full sensor-RAW reconstruction remains experimental and is currently blocked on genuinely new semantic evidence.

## Choose the path you need

| Need | Start here | Status |
|---|---|---|
| Inspect CR3 tracks or extract embedded preview/thumbnail JPEGs | [Container and metadata](decoder.md#stable-container-and-metadata-layers) | Stable public APIs |
| Use CR3 through Java ImageIO | [ImageIO integration](imageio.md) | Stable preview, thumbnail, discovery and metadata; RAW controls experimental |
| Use the command-line tool | [CLI](cli.md) | Supported command surface with experimental decode commands identified |
| Understand the decoder and processing pipeline | [Decoder and processing](decoder.md) | Mixed: stable facade subset, experimental RAW, internal processing |
| See the exact research boundary | [Current state](current-state.md) | Maintained truth boundary |
| See unresolved/rejected hypotheses | [Open hypotheses](open-hypotheses.md) | Dated research state |
| Conduct clean-room research | [Research process](research.md) | Mandatory process |
| Read durable experiment results | [Results index](reports/index.md) | Curated index to dated/generated evidence |
| Understand supported Java symbols | [API boundaries](../cr3-api-boundaries.md) | Compatibility source of truth |
| Reproduce reference sidecars | [Reference export pipeline](../reference-pipeline.md) | Maintained evidence pipeline |
| Follow legal/process safeguards | [Clean-room engineering record](../cleanroom/README.md) | Mandatory for semantic CRX work |

## Module map

### Supported runtime libraries

| Artifact | Purpose | Maturity |
|---|---|---|
| `io.github.carstenartur:canon-cr3-container` | ISO BMFF/CR3 parsing, tracks, embedded JPEG preview and thumbnail extraction | Stable external surface |
| `io.github.carstenartur:canon-cr3-metadata` | Structured EXIF, MakerNote, CTMD and sensor/active-area metadata | Stable external surface |
| `io.github.carstenartur:canon-cr3-codec` | CRX header, entropy, predictor, tile/plane reconstruction and Bayer transfer model | Experimental/internal except explicitly classified diagnostics/models |
| `io.github.carstenartur:canon-cr3-processing` | Pedestal, crop, demosaic, white balance and color processing | Internal pipeline, not a general processing API |
| `io.github.carstenartur:canon-cr3-core` | Unified `Cr3Decoder` / `Cr3DecodeOptions` facade | Preview/thumbnail subset reliable; full RAW experimental |
| `io.github.carstenartur:canon-cr3-imageio` | Java ImageIO SPI, reader, thumbnails and metadata | Stable standard integration; RAW-specific parameters experimental |
| `canon-cr3-cli` | Inspection, extraction and experimental decode commands | User-facing CLI; Java implementation is not library API |

### Internal evidence and development modules

| Artifact | Purpose | Runtime use |
|---|---|---|
| `canon-cr3-reference-testkit` | Reference resolution, TIFF loading, metrics and deterministic evidence writers | Test/research only |
| `canon-cr3-research` | Hypothesis tests and report generators | Research only |
| `canon-cr3-maven-smoke` | Empty-repository consumer and dependency-boundary validation | Build/test only |
| `canon-cr3-benchmark` | JMH performance measurements | Benchmark profile only |

No production module may depend on the reference testkit or research module at runtime.

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

### Experimental

The following depend on the incomplete CRX reconstruction matrix:

- `RAW_LINEAR` Bayer output;
- `RAW_PROCESSED` output;
- `readRaster()` with RAW decode options;
- full-RAW `decode` and `decode-compare` CLI results;
- entropy/predictor/subband implementation classes;
- reference-matching pedestal controls and evolving diagnostics.

A visually plausible render is not proof of correct sensor values. Experimental promotion requires fixed inputs, quantitative reference evidence, clean-room provenance and a separate production decision.

## Current research state

Stages 1–3 of the lossless pipeline are verified. Structural framing is partial, and entropy reconstruction is the earliest verified semantic failure. The fixed lossless panel and registered source material do not currently expose a causal `Sd`/`Kd` mapping or multi-subband role/lifecycle sufficient to justify a D14 entropy candidate.

Research is therefore **blocked on new evidence**, not waiting for another broad parameter sweep. See [Current state](current-state.md) for the durable D5–D13 findings and exact resumption conditions.

## Documentation authority

For implementation claims, use this order:

1. code and executable tests;
2. current module POMs and [API boundaries](../cr3-api-boundaries.md);
3. this maintained CR3 manual and module READMEs;
4. [Current state](current-state.md) and [Open hypotheses](open-hypotheses.md);
5. dated/generated reports indexed by [Results](reports/index.md);
6. historical experiment-local notes.

Generated evidence does not silently become current truth, and a research plan does not prove an implementation exists.
