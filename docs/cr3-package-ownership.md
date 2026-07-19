# CR3 Package Ownership and Dependency Direction

This document is the source map for the CR3 Maven module split. It records the
current package, planned module owner, allowed dependencies, and compatibility
role before source files are moved.

## Target dependency direction

```text
canon-cr3-container
        ↓
canon-cr3-metadata
        ↓
canon-cr3-codec
        ↓
canon-cr3-processing
        ↓
canon-cr3-core (compatibility facade / orchestration)
        ↓
canon-cr3-imageio       canon-cr3-cli

canon-cr3-reference-testkit → may depend on all production CR3 modules (test scope)
canon-cr3-research      → may depend on all production modules + reference testkit
```

Production modules must never depend on `canon-cr3-research`. Runtime production
artifacts must not depend on `canon-cr3-reference-testkit`.

## Ownership map

| Current package / symbol group | Current module | Target module | API role | Allowed target dependencies | Notes |
|---|---|---|---|---|---|
| `com.photographer.focusstack.cr3.Cr3Exception` | `canon-cr3-core` | `canon-cr3-container` | stable shared exception | none | Lowest shared CR3 type; placing it in the base module avoids a new common module. |
| `com.photographer.focusstack.cr3.bmff` low-level ISO BMFF types | `canon-cr3-core` | `canon-cr3-container` | internal | none | Includes `IsoBox`, `IsoBmffReader`, `FtypBox`, track-navigation boxes and CNCV structures. |
| `Cmp1Box`, `Iad1Box` | `canon-cr3-core` | `canon-cr3-container` | internal parsed box models | none | `Cr3Track` currently embeds these values. Moving them to metadata would invert the container dependency and create a cycle. Higher-level metadata may project them into consumer models. |
| `com.photographer.focusstack.cr3.container` | `canon-cr3-core` | `canon-cr3-container` | stable | none | Owns `Cr3Parser`, `Cr3Container`, `Cr3Track`, compression/track descriptors and `SensorCropGeometry`. |
| `com.photographer.focusstack.cr3.preview` | `canon-cr3-core` | `canon-cr3-container` | stable | container | Embedded JPEG extraction is container-level payload access, not RAW processing. |
| `com.photographer.focusstack.cr3.metadata` | `canon-cr3-core` | `canon-cr3-metadata` | stable model, internal parser | container | Owns `Cr3Metadata` and `Cr3MetadataReader`; must not depend on codec or processing. |
| `com.photographer.focusstack.cr3.raw` | `canon-cr3-core` | `canon-cr3-codec` | experimental RAW model | container, metadata | Owns `Cr3RawImage` and Bayer mosaic/CFA representation. |
| `com.photographer.focusstack.cr3.raw.codec` codec primitives | `canon-cr3-core` | `canon-cr3-codec` | internal | container, metadata | Includes CRX header, bit reader, entropy primitives, lossless/lossy decoder and tile/plane reconstruction. |
| `SubbandExtractor` | `canon-cr3-core` | `canon-cr3-codec` (`diagnostics` package later if desired) | experimental diagnostics | container, metadata, codec | Used by CLI and research. It must not depend on research. |
| `com.photographer.focusstack.cr3.processing` | `canon-cr3-core` | `canon-cr3-processing` | internal | metadata, codec | Black level, crop, demosaic, white balance, color matrix, sRGB and orientation. |
| `com.photographer.focusstack.cr3.decode` | `canon-cr3-core` | `canon-cr3-core` compatibility facade | experimental facade | container, metadata, codec, processing | Keep `Cr3Decoder` and `Cr3DecodeOptions` in a small aggregator/facade module so existing consumers have one migration path. |
| `com.photographer.focusstack.cr3.imageio` | `canon-cr3-imageio` | `canon-cr3-imageio` | stable ImageIO, experimental RAW controls | core facade (or explicit production modules after #102) | Must contain adapter code only; no CRX algorithm implementation. |
| `com.photographer.focusstack.cr3.cli` | `canon-cr3-cli` | `canon-cr3-cli` | CLI contract, internal Java API | production CR3 modules | Research commands may consume codec diagnostics; CLI must not become a runtime dependency of another module. |
| `com.photographer.focusstack.cr3.research` in main or test source | core/imageio | `canon-cr3-research` | research-only | all production CR3 modules + testkit | No production source may import this package. |
| Reference TIFF loaders, MAE/RMSE/Pearson, OB and boundary metrics, diff rendering | duplicated in research ITs | `canon-cr3-reference-testkit` | test-only | all production CR3 modules | Centralized by #99; never a production runtime dependency. |
| JMH CR3 benchmarks | `canon-cr3-benchmark` | `canon-cr3-benchmark` | benchmark-only | selected production CR3 modules | Remains behind the Maven `benchmarks` profile. |

## Enforced rules before physical split

The source-level architecture tests introduced with this document enforce the
rules that are meaningful in the current monolithic `canon-cr3-core` module:

1. Production main sources outside a `research` package must not import
   `com.photographer.focusstack.cr3.research`.
2. Container/BMFF sources must not depend on metadata, RAW codec, processing,
   decode facade, ImageIO, CLI, or research packages.
3. Metadata sources must not depend on RAW codec, processing, decode facade,
   ImageIO, CLI, or research packages.
4. RAW/codec sources must not depend on processing, decode facade, ImageIO, CLI,
   or research packages.
5. Processing sources must not depend on decode facade, ImageIO, CLI, or research
   packages.
6. `canon-cr3-imageio` production sources must not import CLI or research code.

The tests intentionally inspect imports rather than relying on an external
architecture-test dependency. When packages become independent Maven modules,
these rules should be converted into POM dependency constraints and retained as
regression guards.

## Split sequence

1. #95 — container and embedded preview extraction.
2. #96 — metadata model and reader.
3. #97 — RAW model and CRX codec.
4. #101 — explicit codec-stage seams, preferably after codec extraction or rebased onto it.
5. #98 — post-codec processing.
6. #99 — reference testkit.
7. #100 — research module.
8. #102 — downstream dependency rewiring.
9. #103 — external Maven consumption verification.

The split PRs must preserve behavior. Decoder hypothesis changes belong in
separate research/production PRs and must not be hidden inside package moves.
