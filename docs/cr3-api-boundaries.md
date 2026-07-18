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

## Public API boundary table

The `Module` column records the current Maven owner before extraction. The target owner is stated in `Required action` where a move is planned.

| Symbol | Module | Current package | Classification | Reason | Required action |
|---|---|---|---|---|---|
| `Cr3Parser` | `canon-cr3-core` | `com.photographer.focusstack.cr3.container` | `PUBLIC_STABLE` | High-level entry point for parsing CR3 containers without exposing decoder internals. | Move to `canon-cr3-container`; preserve the parse contract. |
| `Cr3Container` | `canon-cr3-core` | `com.photographer.focusstack.cr3.container` | `PUBLIC_STABLE` | Immutable high-level file structure used by metadata, preview and diagnostics consumers. | Move to `canon-cr3-container`; preserve accessors. |
| `Cr3Track` | `canon-cr3-core` | `com.photographer.focusstack.cr3.container` | `PUBLIC_STABLE` | Consumer-facing descriptor for preview, thumbnail and RAW tracks. | Move to `canon-cr3-container`; avoid exposing mutable codec state. |
| `IsoBmffReader`, `IsoBox`, `FtypBox` | `canon-cr3-core` | `com.photographer.focusstack.cr3.bmff` | `INTERNAL` | Low-level box traversal and parsing are implementation details beneath `Cr3Parser`. | Move behind the container API; remove from general examples. |
| `Cmp1Box`, `Iad1Box` | `canon-cr3-core` | `com.photographer.focusstack.cr3.bmff` | `INTERNAL` | Parsed Canon box layouts couple container, metadata and codec implementation details. | Assign one module owner and expose higher-level projections where practical. |
| `Cr3Metadata` | `canon-cr3-core` | `com.photographer.focusstack.cr3.metadata` | `PUBLIC_STABLE` | Structured camera, sensor, EXIF and GPS data is a supported consumer model. | Move to `canon-cr3-metadata`; preserve accessors. |
| `Cr3MetadataReader` | `canon-cr3-core` | `com.photographer.focusstack.cr3.metadata` | `INTERNAL` | Parsing offsets and Canon box interpretation are implementation details. | Move to `canon-cr3-metadata`; prefer facade/ImageIO access in public examples. |
| `Cr3PreviewExtractor` | `canon-cr3-core` | `com.photographer.focusstack.cr3.preview` | `PUBLIC_STABLE` | Embedded JPEG extraction is independent of incomplete RAW decoding and is already reliable. | Move with the container layer or retain a stable facade. |
| `Cr3Decoder` | `canon-cr3-core` | `com.photographer.focusstack.cr3.decode` | `PUBLIC_EXPERIMENTAL` | The unified facade is useful externally, but full RAW results depend on an unverified codec. | Keep as compatibility facade; label full RAW behavior experimental. |
| `Cr3DecodeOptions` | `canon-cr3-core` | `com.photographer.focusstack.cr3.decode` | `PUBLIC_EXPERIMENTAL` | Preview selection is mature, while RAW and processing controls may change before 1.0. | Preserve common modes where practical; document unstable controls. |
| `Cr3DecodeOptions.experimentalPedestal(int)` and `EXPERIMENTAL_REFERENCE_MATCH` | `canon-cr3-core` | `com.photographer.focusstack.cr3.decode` | `RESEARCH_ONLY` | The explicit pedestal exists only to compare decoder output with reference fixtures. | Remove from stable examples and move behind an internal/research seam when practical. |
| `Cr3Decoder.pedestalReport()` and related records/enums | `canon-cr3-core` | `com.photographer.focusstack.cr3.decode` | `PUBLIC_EXPERIMENTAL` | Useful diagnostic surface, but its schema follows active decoder investigation. | Consider a diagnostics API/module; do not promise binary compatibility yet. |
| `Cr3RawImage` | `canon-cr3-core` | `com.photographer.focusstack.cr3.raw` | `PUBLIC_EXPERIMENTAL` | The Bayer mosaic transfer model may change with plane reconstruction and ownership decisions. | Move to `canon-cr3-codec`; define ownership and lifetime semantics. |
| `CrxLosslessRawDecoder` | `canon-cr3-core` | `com.photographer.focusstack.cr3.raw.codec` | `INTERNAL` | Entropy and predictor implementation should be reached through the facade, not called directly. | Move to `canon-cr3-codec`; keep external examples on `Cr3Decoder` or ImageIO. |
| `CrxLossyDecoder` | `canon-cr3-core` | `com.photographer.focusstack.cr3.raw.codec` | `INTERNAL` | C-RAW support is incomplete and not a functional public contract. | Move to `canon-cr3-codec`; do not advertise as supported API. |
| `CrxHeader`, `BitReader`, `RiceGolombDecoder` | `canon-cr3-core` | `com.photographer.focusstack.cr3.raw.codec` | `INTERNAL` | Bitstream primitives are tightly coupled to codec implementation and experiments. | Move to `canon-cr3-codec`; reduce visibility where possible. |
| `SubbandExtractor` | `canon-cr3-core` | `com.photographer.focusstack.cr3.raw.codec` | `PUBLIC_EXPERIMENTAL` | CLI and advanced diagnostics use it, but it is not part of normal image decoding. | Move to codec diagnostics; document its diagnostic scope. |
| Processing classes (`BlackLevelProcessor`, `BayerDemosaicer`, `WhiteBalanceProcessor`, `ColorMatrixProcessor`, `SrgbConverter`) | `canon-cr3-core` | `com.photographer.focusstack.cr3.processing` | `INTERNAL` | They implement the current CR3 rendering pipeline rather than a designed general-purpose processing API. | Move to `canon-cr3-processing`; promote only through a separate API design. |
| `CanonCr3ImageReaderSpi` | `canon-cr3-imageio` | `com.photographer.focusstack.cr3.imageio` | `PUBLIC_STABLE` | Standard ImageIO service discovery is the primary external integration contract. | Preserve service registration, suffixes and MIME behavior. |
| `CanonCr3ImageReader` | `canon-cr3-imageio` | `com.photographer.focusstack.cr3.imageio` | `PUBLIC_STABLE` | Standard preview, thumbnail and metadata reading is stable even though RAW controls are experimental. | Preserve ImageIO behavior; keep codec implementation outside the adapter. |
| `Cr3ImageMetadata` | `canon-cr3-imageio` | `com.photographer.focusstack.cr3.imageio` | `PUBLIC_STABLE` | It exposes CR3 information through the standard `IIOMetadata` contract. | Preserve the native metadata format or version changes explicitly. |
| `CanonCr3ImageReadParam` | `canon-cr3-imageio` | `com.photographer.focusstack.cr3.imageio` | `PUBLIC_EXPERIMENTAL` | Stable preview selection and experimental RAW options share one parameter type. | Keep preview behavior; allow RAW-specific controls to evolve before 1.0. |
| `Cr3CliApp` | `canon-cr3-cli` | `com.photographer.focusstack.cr3.cli` | `INTERNAL` | Commands are user-facing, but the Java entry-point class is not a library API. | Treat command syntax as the CLI contract; do not advertise Java calls. |
| `SubbandBatchStatistics`, `SubbandHypothesisHarness`, `SubbandStatistics` | `canon-cr3-core` | `com.photographer.focusstack.cr3.research` | `RESEARCH_ONLY` | These types exist to compare CRX hypotheses and produce research evidence. | Move to `canon-cr3-research`; prohibit production dependencies. |
| All test classes under `com.photographer.focusstack.cr3.research` | `canon-cr3-imageio` / `canon-cr3-core` test scope | `com.photographer.focusstack.cr3.research` | `RESEARCH_ONLY` | They are reproducible clean-room experiments rather than supported runtime APIs. | Move to `canon-cr3-research`; retain evidence and intentional execution paths. |

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
