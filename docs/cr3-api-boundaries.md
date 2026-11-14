# CR3 API boundaries

This document defines the compatibility status of the Java APIs exposed by the
split Canon CR3 Maven modules.

The classification describes **support and compatibility guarantees**, not whether
a class is technically public or useful inside the repository. Java visibility is
sometimes wider than the supported external API because modules, CLI tools, and
research tests share implementation types.

## Status categories

| Status | Meaning |
|---|---|
| `PUBLIC_STABLE` | Intended for external consumers. Compatibility should be preserved within a release line. |
| `PUBLIC_EXPERIMENTAL` | External use is possible, but the contract may change while CRX RAW support is completed. |
| `INTERNAL` | Implementation detail shared between project modules. It must not be presented as supported external API. |
| `RESEARCH_ONLY` | Exists to investigate or validate the format. It must never become a production runtime dependency. |

## Package defaults and actual module ownership

The package default applies unless a symbol is listed as an exception below.

| Package | Default status | Maven owner | Notes |
|---|---|---|---|
| `com.photographer.focusstack.cr3.container` | `PUBLIC_STABLE` | `cr3-container` | High-level parsed container and track model. |
| `com.photographer.focusstack.cr3.preview` | `PUBLIC_STABLE` | `cr3-container` | Embedded preview and thumbnail extraction. |
| `com.photographer.focusstack.cr3.bmff` | `INTERNAL` | `cr3-container` | Low-level ISO-BMFF and Canon-box implementation details. |
| `com.photographer.focusstack.cr3.metadata` | `PUBLIC_STABLE` | `cr3-metadata` | Structured metadata models are consumer-facing; parser helpers are internal. |
| `com.photographer.focusstack.cr3.decode` | `PUBLIC_EXPERIMENTAL` | `cr3-core` | Compatibility facade; preview is mature, full RAW remains experimental. |
| `com.photographer.focusstack.cr3.raw` | `PUBLIC_EXPERIMENTAL` | `cr3-codec` | RAW mosaic transfer model may evolve with decoder completion. |
| `com.photographer.focusstack.cr3.raw.codec` | `INTERNAL` | `cr3-codec` | CRX entropy, predictor, tiling, and reconstruction implementation. |
| `com.photographer.focusstack.cr3.processing` | `INTERNAL` | `cr3-processing` | CR3 RAW-to-image pipeline, not a general processing toolkit. |
| `com.photographer.focusstack.cr3.imageio` | `PUBLIC_STABLE` | `cr3-imageio` | Standard ImageIO integration; RAW-specific parameters are exceptions below. |
| `com.photographer.focusstack.dng.imageio` | `PUBLIC_EXPERIMENTAL` | `cr3-imageio` | ImageIO reader and writer for uncompressed 16-bit Bayer DNGs and their sensor calibration. |
| `com.photographer.focusstack.cr3.cli` | `INTERNAL` | `canon-cr3-cli` | The command syntax is user-facing; Java implementation classes are not library API. |
| `com.photographer.focusstack.cr3.reference` | `RESEARCH_ONLY` | `canon-cr3-reference-testkit` | Shared test infrastructure, never a production runtime dependency. |
| `com.photographer.focusstack.cr3.research` | `RESEARCH_ONLY` | `canon-cr3-research` | Reverse-engineering, diagnostics, and hypothesis tests. |

## Public API boundary table

| Symbol | Module | Current package | Classification | Reason | Required action |
|---|---|---|---|---|---|
| `Cr3Parser` | `cr3-container` | `com.photographer.focusstack.cr3.container` | `PUBLIC_STABLE` | High-level entry point for container inspection without codec internals. | Preserve parse behavior and source compatibility within the release line. |
| `Cr3Container` | `cr3-container` | `com.photographer.focusstack.cr3.container` | `PUBLIC_STABLE` | Immutable high-level CR3 structure used by metadata and preview consumers. | Preserve accessors; add new data compatibly where practical. |
| `Cr3Track` | `cr3-container` | `com.photographer.focusstack.cr3.container` | `PUBLIC_STABLE` | Consumer-facing descriptor for thumbnail, preview, and RAW tracks. | Do not expose mutable decoder state through this model. |
| `Cr3PreviewExtractor` | `cr3-container` | `com.photographer.focusstack.cr3.preview` | `PUBLIC_STABLE` | Embedded JPEG extraction is independent of incomplete full-RAW decoding. | Keep direct preview extraction documented as a stable path. |
| `IsoBmffReader`, `IsoBox`, `FtypBox` | `cr3-container` | `com.photographer.focusstack.cr3.bmff` | `INTERNAL` | Low-level traversal and box parsing sit beneath the stable container API. | Exclude from general external examples. |
| `Cmp1Box`, `Iad1Box`, `SensorCropGeometry` | `cr3-container` | `com.photographer.focusstack.cr3.bmff` / `container` | `INTERNAL` | These values couple Canon box layout, sensor geometry, metadata, and codec behavior. | Expose higher-level projections rather than promising their binary layout as API. |
| `Cr3Metadata` | `cr3-metadata` | `com.photographer.focusstack.cr3.metadata` | `PUBLIC_STABLE` | Structured camera, sensor, EXIF, and GPS data is a supported consumer model. | Preserve accessors and document newly supported fields. |
| `Cr3MetadataReader` and parser helpers | `cr3-metadata` | `com.photographer.focusstack.cr3.metadata` | `INTERNAL` | Offset handling and Canon-box interpretation are implementation details. | Prefer `Cr3Metadata`, the decoder facade, or ImageIO metadata in examples. |
| `Cr3Decoder` | `cr3-core` | `com.photographer.focusstack.cr3.decode` | `PUBLIC_EXPERIMENTAL` | Useful unified facade, but full RAW results still depend on an incomplete codec matrix. | Preserve preview behavior; version or document unavoidable RAW contract changes. |
| `Cr3DecodeOptions` | `cr3-core` | `com.photographer.focusstack.cr3.decode` | `PUBLIC_EXPERIMENTAL` | Preview/thumbnail selection is mature while RAW and processing controls may evolve. | Label RAW-specific modes and controls experimental before 1.0. |
| `Cr3DecodeOptions.experimentalPedestal(int)` and `EXPERIMENTAL_REFERENCE_MATCH` | `cr3-core` | `com.photographer.focusstack.cr3.decode` | `RESEARCH_ONLY` | Explicit pedestal injection exists only for fixture/reference experiments. | Keep out of production examples and allow removal without compatibility promise. |
| `Cr3Decoder.pedestalReport()` and related diagnostics | `cr3-core` | `com.photographer.focusstack.cr3.decode` | `PUBLIC_EXPERIMENTAL` | Diagnostic schemas follow active decoder investigation. | Do not promise binary compatibility until a diagnostics API is designed. |
| `Cr3DecodeOptions.rawSensor()` | `cr3-core` | `com.photographer.focusstack.cr3.decode` | `PUBLIC_EXPERIMENTAL` | Selects original Bayer codes in an unsigned-short raster for the verified 14-bit V1 profiles. | Preserve sensor coordinates and sample units across Core and ImageIO. |
| `CrxSensorDecoder` and `RowConsumer` | `cr3-codec` | `com.photographer.focusstack.cr3.raw.codec` | `INTERNAL` | Connects the entropy decoder to the Core raster writer. Row buffers are reused. | Applications select sensor output through the Core or ImageIO API. |
| `Cr3RawImage` | `cr3-codec` | `com.photographer.focusstack.cr3.raw` | `PUBLIC_EXPERIMENTAL` | The Bayer mosaic transfer model may change with plane/CFA reconstruction. | Document ownership, dimensions, bit depth, and sample lifetime when the model stabilizes. |
| `CrxLosslessRawDecoder` | `cr3-codec` | `com.photographer.focusstack.cr3.raw.codec` | `INTERNAL` | Entropy and predictor implementation should be reached through the facade or ImageIO. | Do not use as the primary external example. |
| `CrxLossyDecoder` | `cr3-codec` | `com.photographer.focusstack.cr3.raw.codec` | `INTERNAL` | C-RAW support is incomplete and not a supported external contract. | Keep unsupported variants explicit; do not advertise blanket C-RAW support. |
| `CrxHeader`, `BitReader`, `RiceGolombDecoder`, predictor/assembly stages | `cr3-codec` | `com.photographer.focusstack.cr3.raw.codec` | `INTERNAL` | Bitstream primitives and decoder stages are tightly coupled implementation seams. | Keep stage-level tests; reduce visibility when module integration permits. |
| `SubbandExtractor` | `cr3-codec` | `com.photographer.focusstack.cr3.raw.codec` | `PUBLIC_EXPERIMENTAL` | CLI and advanced diagnostics use it, but it is not normal image-decoding API. | Document it only as a diagnostic surface; allow contract changes before 1.0. |
| `SubbandStatistics` | `cr3-codec` | `com.photographer.focusstack.cr3.research` | `RESEARCH_ONLY` | Retained in the codec artifact as a package-compatible diagnostic value used by CLI/research consumers. | Treat this as an explicit exception; never make production behavior depend on it. |
| `BlackLevelProcessor`, `BayerDemosaicer`, `WhiteBalanceProcessor`, `ColorMatrixProcessor`, `SrgbConverter` | `cr3-processing` | `com.photographer.focusstack.cr3.processing` | `INTERNAL` | They implement the current CR3 rendering pipeline rather than a designed general API. | Promote only through a separate processing-API design. |
| `CanonCr3ImageReaderSpi` | `cr3-imageio` | `com.photographer.focusstack.cr3.imageio` | `PUBLIC_STABLE` | Standard service discovery is the primary packaged integration contract. | Preserve service registration, suffixes, MIME type, and `canDecodeInput` behavior. |
| `CanonCr3ImageReader` | `cr3-imageio` | `com.photographer.focusstack.cr3.imageio` | `PUBLIC_STABLE` | Preview, thumbnail, and metadata behavior is stable through standard ImageIO APIs. | Keep codec implementation outside the adapter; document unsupported RAW variants. |
| `Cr3ImageMetadata` | `cr3-imageio` | `com.photographer.focusstack.cr3.imageio` | `PUBLIC_STABLE` | Exposes CR3 information through the standard `IIOMetadata` contract. | Preserve or explicitly version the native metadata format. |
| `CanonCr3ImageReadParam` | `cr3-imageio` | `com.photographer.focusstack.cr3.imageio` | `PUBLIC_EXPERIMENTAL` | Stable preview selection and experimental RAW controls share the same type. | Preserve preview semantics; allow RAW-specific options to evolve before 1.0. |
| `Cr3CliApp` | `canon-cr3-cli` | `com.photographer.focusstack.cr3.cli` | `INTERNAL` | The CLI is a product surface, but its Java entry class is not a library API. | Treat documented command syntax, exit codes, and output formats as the CLI contract. |
| `SubbandBatchStatistics`, hypothesis harnesses, generated-report helpers | `canon-cr3-research` | `com.photographer.focusstack.cr3.research` | `RESEARCH_ONLY` | These types produce reproducible clean-room evidence, not runtime functionality. | Keep the research module outside all production dependency graphs. |
| `ReferenceImage`, `SpatialReferenceMetrics`, sidecar helpers | `canon-cr3-reference-testkit` | `com.photographer.focusstack.cr3.reference` | `RESEARCH_ONLY` | Shared validation infrastructure is not a user-facing image API. | Consume only from tests or the research module. |

## Public usage rules

External Maven-facing examples should use only:

- `Cr3Parser`, `Cr3Container`, `Cr3Track`, and `Cr3PreviewExtractor` from
  `cr3-container`;
- `Cr3Metadata` from `cr3-metadata`;
- standard `ImageIO`, `CanonCr3ImageReaderSpi`, `CanonCr3ImageReader`, and
  `Cr3ImageMetadata` from `cr3-imageio`;
- `Cr3Decoder` and `Cr3DecodeOptions` only with an explicit statement that full
  RAW decoding is experimental.

External examples must not directly use entropy, predictor, internal processing,
reference-matching pedestal, or research classes.

## Compatibility policy

1. Preserve `PUBLIC_STABLE` behavior and source compatibility within a release line.
2. Record unavoidable stable API breaks in release notes and migration documentation.
3. Experimental APIs may evolve before 1.0, but changes should remain explicit and tested.
4. Internal and research APIs may change while production behavior and reproducible evidence remain intact.
5. No production module may depend on `canon-cr3-research` or
   `canon-cr3-reference-testkit` at runtime.
6. Java `public` visibility alone does not imply external support; this table is the
   compatibility source of truth until package visibility can be narrowed safely.
