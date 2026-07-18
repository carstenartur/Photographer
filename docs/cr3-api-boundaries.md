# CR3 API Boundaries

This document classifies the current CR3 Java API before the planned Maven module split.

The classification is about **compatibility guarantees**, not implementation quality. A type can be useful and well tested while still being experimental because its contract may change as the CRX decoder is completed.

## Status categories

| Status | Meaning |
|---|---|
| `PUBLIC_STABLE` | Intended for external consumers. Compatibility should be preserved within a release line. |
| `PUBLIC_EXPERIMENTAL` | External use is possible, but the contract may change while CR3 RAW support is completed. |
| `INTERNAL` | Implementation detail shared between project modules. It must not be presented as supported external API. |
| `RESEARCH_ONLY` | Exists to investigate or validate the format. It must never become a production dependency. |

## Package defaults

The package default applies unless a symbol is listed as an exception below.

| Current package | Default status | Planned owner | Notes |
|---|---|---|---|
| `com.photographer.focusstack.cr3.container` | `PUBLIC_STABLE` | `canon-cr3-container` | High-level parsed CR3 container and track model. |
| `com.photographer.focusstack.cr3.bmff` | `INTERNAL` | `canon-cr3-container` or `canon-cr3-metadata` | Low-level BMFF and Canon-box implementation details; selected value objects may later be promoted. |
| `com.photographer.focusstack.cr3.metadata` | `PUBLIC_STABLE` | `canon-cr3-metadata` | Structured metadata model is consumer-facing; parser helpers are internal unless explicitly listed. |
| `com.photographer.focusstack.cr3.preview` | `PUBLIC_STABLE` | `canon-cr3-container` or a facade module | Embedded preview/thumbnail extraction is a supported path. |
| `com.photographer.focusstack.cr3.decode` | `PUBLIC_EXPERIMENTAL` | facade / processing integration module | Preview use is reliable, but the full RAW contract depends on the experimental codec and processing pipeline. |
| `com.photographer.focusstack.cr3.raw` | `PUBLIC_EXPERIMENTAL` | `canon-cr3-codec` | RAW mosaic model may change while plane/CFA reconstruction is completed. |
| `com.photographer.focusstack.cr3.raw.codec` | `INTERNAL` | `canon-cr3-codec` | Codec implementation details are not a stable external API. |
| `com.photographer.focusstack.cr3.processing` | `INTERNAL` | `canon-cr3-processing` | Pipeline components are currently implementation details, not a supported image-processing toolkit. |
| `com.photographer.focusstack.cr3.imageio` | `PUBLIC_STABLE` | `canon-cr3-imageio` | Standard ImageIO integration. CR3-specific RAW options remain experimental as noted below. |
| `com.photographer.focusstack.cr3.cli` | `INTERNAL` | `canon-cr3-cli` | Command-line application, not a Java library API. |
| `com.photographer.focusstack.cr3.research` | `RESEARCH_ONLY` | `canon-cr3-research` | Must not be referenced by production modules. |

## Symbol classification

| Symbol | Current module | Status | Supported use | Required action during split |
|---|---|---|---|---|
| `Cr3Parser` | `canon-cr3-core` | `PUBLIC_STABLE` | Parse a CR3 into the high-level container model. | Move to `canon-cr3-container`; preserve the high-level parse contract. |
| `Cr3Container` | `canon-cr3-core` | `PUBLIC_STABLE` | Inspect parsed tracks and CR3 file structure. | Move to `canon-cr3-container`. |
| `Cr3Track` | `canon-cr3-core` | `PUBLIC_STABLE` | Inspect preview, thumbnail and RAW track descriptors. | Move to `canon-cr3-container`; avoid exposing mutable codec internals. |
| `IsoBmffReader`, `IsoBox`, `FtypBox` | `canon-cr3-core` | `INTERNAL` | Container implementation and diagnostics. | Move behind the container API; do not use in general README examples. |
| `Cmp1Box`, `Iad1Box` | `canon-cr3-core` | `INTERNAL` | Parsed Canon box data used by metadata and codec layers. | Decide ownership in the package map; expose through higher-level models where practical. |
| `Cr3Metadata` | `canon-cr3-core` | `PUBLIC_STABLE` | Consumer-facing camera, sensor and EXIF metadata. | Move to `canon-cr3-metadata`; preserve accessors. |
| `Cr3MetadataReader` | `canon-cr3-core` | `INTERNAL` | Metadata parsing implementation. | Move to `canon-cr3-metadata`; consumers should normally use the facade/ImageIO metadata API. |
| `Cr3PreviewExtractor` | `canon-cr3-core` | `PUBLIC_STABLE` | Extract embedded JPEG preview and thumbnail. | Keep as a supported utility or expose through a container facade. |
| `Cr3Decoder` | `canon-cr3-core` | `PUBLIC_EXPERIMENTAL` | Unified preview/metadata/RAW facade. | Keep the facade, but document that full RAW output is experimental until the codec is verified. |
| `Cr3DecodeOptions` | `canon-cr3-core` | `PUBLIC_EXPERIMENTAL` | Select preview, thumbnail, processed RAW or linear RAW output. | Preserve common modes where possible; experimental pedestal controls may change or disappear. |
| `Cr3DecodeOptions.experimentalPedestal(int)` | `canon-cr3-core` | `RESEARCH_ONLY` | Reference-matching experiments only. | Remove from stable examples; move behind an internal/research seam when practical. |
| `Cr3Decoder.pedestalReport()` and related records/enums | `canon-cr3-core` | `PUBLIC_EXPERIMENTAL` | Diagnostics and CLI investigation. | Consider a diagnostics API/module; do not promise long-term binary compatibility yet. |
| `Cr3RawImage` | `canon-cr3-core` | `PUBLIC_EXPERIMENTAL` | Linear Bayer mosaic transfer object. | Move to `canon-cr3-codec` or a small RAW model package; define ownership/lifetime semantics. |
| `CrxLosslessRawDecoder` | `canon-cr3-core` | `INTERNAL` | CRX lossless implementation used by the facade and research tests. | Move to `canon-cr3-codec`; external callers should use `Cr3Decoder` or ImageIO. |
| `CrxLossyDecoder` | `canon-cr3-core` | `INTERNAL` | Incomplete C-RAW implementation scaffold. | Move to `canon-cr3-codec`; do not expose as functional public API. |
| `CrxHeader`, `BitReader`, `RiceGolombDecoder` | `canon-cr3-core` | `INTERNAL` | Codec stages and bitstream primitives. | Move to `canon-cr3-codec`; keep package-private where possible. |
| `SubbandExtractor` | `canon-cr3-core` | `PUBLIC_EXPERIMENTAL` | CLI/research inspection of CRX subbands. | Move to codec diagnostics or research support; not part of the normal decode API. |
| Processing classes (`BlackLevelProcessor`, `BayerDemosaicer`, `WhiteBalanceProcessor`, `ColorMatrixProcessor`, `SrgbConverter`) | `canon-cr3-core` | `INTERNAL` | Current RAW-to-display implementation. | Move to `canon-cr3-processing`; promote only if a separate processing API is intentionally designed. |
| `CanonCr3ImageReaderSpi` | `canon-cr3-imageio` | `PUBLIC_STABLE` | Automatic ImageIO discovery. | Preserve service-provider registration and suffix/MIME behavior. |
| `CanonCr3ImageReader` | `canon-cr3-imageio` | `PUBLIC_STABLE` | Standard `ImageReader` integration, preview, thumbnail and metadata access. | Preserve ImageIO behavior; keep codec implementation out of this module. |
| `Cr3ImageMetadata` | `canon-cr3-imageio` | `PUBLIC_STABLE` | CR3 metadata exposed through `IIOMetadata`. | Preserve native metadata format or version changes explicitly. |
| `CanonCr3ImageReadParam` | `canon-cr3-imageio` | `PUBLIC_EXPERIMENTAL` | CR3-specific output and processing selection. | Stable preview selection can remain; RAW-specific controls follow decoder maturity. |
| `Cr3CliApp` | `canon-cr3-cli` | `INTERNAL` | CLI entry point. | Treat commands as user-facing CLI contracts, not Java API. |
| `SubbandBatchStatistics`, `SubbandHypothesisHarness`, `SubbandStatistics` | `canon-cr3-core` | `RESEARCH_ONLY` | CRX hypothesis and subband research. | Move to `canon-cr3-research`; production modules must not depend on them. |
| All test classes under `com.photographer.focusstack.cr3.research` | `canon-cr3-imageio` / `canon-cr3-core` test scope | `RESEARCH_ONLY` | Reproducible clean-room experiments. | Move to `canon-cr3-research`; retain evidence and intentional execution paths. |

## Public usage rules

External examples and Maven-facing documentation should use only:

- `Cr3Parser`, `Cr3Container`, and `Cr3Track` for container inspection;
- `Cr3Metadata` for structured metadata;
- `Cr3PreviewExtractor` for direct embedded JPEG extraction;
- `CanonCr3ImageReaderSpi`, `CanonCr3ImageReader`, and standard ImageIO APIs;
- `Cr3Decoder` / `Cr3DecodeOptions` only with an explicit statement that full RAW decoding is experimental.

External examples must not directly use entropy, predictor, subband-research, pedestal-matching, or low-level BMFF implementation classes.

## Compatibility policy during the module split

1. Preserve `PUBLIC_STABLE` behavior and source compatibility where practical.
2. Record any unavoidable stable API break in the relevant split PR.
3. Experimental APIs may move or change, but migration must be documented.
4. Internal and research APIs may change freely as long as production behavior and reproducible research remain intact.
5. No production module may depend on `canon-cr3-research` or the reference testkit at runtime.

This classification is the input for the package ownership and dependency-guard work in issue #94.