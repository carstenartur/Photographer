# Modules and Public Surfaces

The root Maven reactor is the authoritative module list. This chapter classifies modules by role and support boundary; volatile class/test counts are intentionally omitted.

## Default reactor

| Module | Role | Support boundary |
|---|---|---|
| `common-api` | Shared camera, analysis and neutral vision contracts, capabilities, media, policy, events and transport-independent value types | Foundation library; framework and implementation dependencies are prohibited. |
| `plugin-api` | ServiceLoader SPI for analysis, camera and vision-provider plugins | Public extension surface; depends only on neutral contracts from `common-api`. |
| `capture-core` | Bracket and sequence capture abstractions | Framework-light domain library. |
| `storage` | Session storage abstraction and file implementation | Framework-light library; not the relational JPA layer. |
| `camera-ptp` | Clean-room PTP/USB protocol, Canon extensions, session/backend adapters | Reusable camera transport library; hardware compatibility remains model-dependent. |
| `hdr-core` | HDR processing and tonemapping ports/implementations | Domain library. |
| `video-core` | Video encoder contract and test/development implementations | Domain/port library; external encoder support must be documented per implementation. |
| `face-analysis-core` | Face-analysis contract and implementations | Domain library. |
| `face-profile-core` | Multi-view face-profile generation | Domain library composed with face analysis. |
| `session-core` | HDR timelapse and face-orbit orchestration | Framework-light workflow library. |
| `observer-core` | Wildlife-observer state machine, rules, bird visit/repeat policy, domain vision port and capture orchestration | Framework-light workflow library; no concrete AI provider dependency. |
| `ai-vision` | Local ONNX bird detector, Gemini/mock providers, prompts, parsing and AI permission/privacy gates | Provider implementation library using neutral `common-api` contracts; no `observer-core` dependency. |
| `astro-core` | Star detection, registration, stacking and focus-related astro logic | Domain library. |
| `cr3-container` | CR3 ISO BMFF parsing and embedded JPEG access | Candidate public runtime library under `io.github.carstenartur`. |
| `cr3-metadata` | EXIF, MakerNote, CTMD and sensor/active-area metadata | Candidate public runtime library. |
| `cr3-codec` | Clean-room CRX RAW codec and linear Bayer model | Publicly packaged but explicitly experimental before 1.0. |
| `cr3-processing` | Pedestal, crop, demosaic, white balance and color processing | Candidate public runtime library; maturity follows the individual APIs. |
| `cr3-core` | CR3 decoder facade and lossless writer with camera profiles | Runtime facade with experimental native RAW controls. |
| `canon-cr3-reference-testkit` | Shared reference loading, metrics and deterministic evidence writers | Internal test/research infrastructure; not a supported runtime dependency. |
| `canon-cr3-research` | Research experiments and report generators | Research-only; not a product library. |
| `cr3-imageio` | Java ImageIO services for CR3 reading, lossless CR3 writing and uncompressed Bayer DNG reading and writing | Supported and experimental entry points are listed in the [ImageIO guide](../cr3/imageio.md). |
| `cr3-imagej` | Native Bayer and 16-bit color importers for ImageJ and Fiji | [Fiji update site, version availability and offline installation](../cr3/fiji-update-site.md). |
| `canon-cr3-maven-smoke` | Isolated Maven consumer and architecture-boundary validation | Build/test infrastructure only. |
| `canon-cr3-cli` | CR3 inspection and experimental decode command line | User-facing tool/distribution; not required as an application library dependency. |
| `frontend-web` | React/Vite frontend | Product UI; packaged into `app` for production. |
| `app` | Spring Boot composition, REST, security, persistence, CCAPI transport and production wiring | Deployable backend/application module. |

## Benchmark profile

The `benchmarks` profile adds:

| Module | Purpose |
|---|---|
| `canon-cr3-benchmark` | JMH benchmarks for CR3 parsing, metadata, preview and decode paths. |
| `astro-core-benchmark` | JMH benchmarks for astro and pixel-processing paths. |

Activate the profile explicitly:

```bash
mvn -B -P benchmarks -pl canon-cr3-benchmark -am package -DskipTests
```

Benchmark results are environment-sensitive. PR comparisons run base and head sequentially on the same runner; scheduled history records runner, CPU, JVM and Maven metadata.

## Dependency layers

### Foundation

`common-api` is the lowest shared project module. It contains the camera contracts, neutral vision-provider contract family and shared value records required by plugin, observer and provider modules.

The Java package of `BoundingBox` remains `com.photographer.focusstack.observer.events` as a compatibility seam even though its source owner is `common-api`. Package cleanup requires a separate versioned API decision.

No module should move Spring application wiring or concrete provider/domain behavior into `common-api` merely to avoid writing an adapter.

### Extension and domain libraries

`plugin-api`, `capture-core`, `storage`, `camera-ptp`, `hdr-core`, `video-core`, `face-analysis-core`, `face-profile-core`, `observer-core`, `ai-vision` and `astro-core` provide focused SPIs, domain logic and implementations.

The vision-specific direction is enforced as:

```text
common-api neutral contracts
        ↓
plugin-api SPI   observer-core domain   ai-vision providers
        └──────────────────┬────────────────────┘
                           ↓
                      app composition
```

Forbidden edges are `plugin-api -> ai-vision`, `plugin-api -> observer-core` and `ai-vision -> observer-core`. `scripts/validate_vision_module_boundaries.py` runs from a normal checkout and through `scripts/verify-product.sh`.

`session-core` is an orchestration layer over several domain modules. It should coordinate work but not become a second application container.

### Application

`app` depends on almost every product library because it is the composition root. This is expected for a modular monolith, but new domain logic should still be placed in focused modules when it can remain independent of Spring, HTTP and JPA.

The application currently also owns relational entities/repositories and Hibernate Search composition. The `storage` module should therefore not be described as owning all persistence.

## CR3 runtime graph

The supported artifact graph is intentionally split:

```text
cr3-container
  └─ cr3-metadata
       └─ cr3-codec (experimental)
            └─ cr3-processing

container + metadata + codec + processing
  └─ cr3-core
       ├─ cr3-imageio
       │    └─ cr3-imagej (ImageJ/Fiji host adapter)
       └─ canon-cr3-cli
```

The actual POM graph has some direct dependencies in addition to this simplified path. `cr3-core` is the facade over all four lower-level runtime modules, and `cr3-imageio` directly declares the runtime modules it needs.

### ImageJ adapter

`cr3-imagej` depends on `cr3-imageio`. Fiji supplies the ImageJ 1.x API and SLF4J. Each importer creates the Photographer reader through `CanonCr3ImageReaderSpi`, owns a `FileImageInputStream`, reads image index 0, closes the stream and disposes the reader. The caller owns the resulting image. The following contracts apply from version 0.1.8.

| Headless entry point | Decode options and result |
| --- | --- |
| `Cr3SensorImporter.open(Path)` | `Cr3DecodeOptions.rawSensor()` returns the entire Bayer mosaic. `ShortProcessor` adopts the packed unsigned-short grayscale raster. The `ImagePlus` carries available calibration as properties and ignores ImageJ global calibration. |
| `Cr3ColorImporter.open(Path)` | `Cr3DecodeOptions.rawProcessed().bitDepth(SIXTEEN)` develops the active area to oriented sRGB with supported lens correction and camera tone enabled by default. `CompositeImage` has three 16-bit channels in R, G, B order, one slice and one frame. Each channel's initial display range is 0–65535. |

**Ownership.** The color adapter can give ImageJ the decoded banks because it exclusively owns the source raster. It adopts three distinct unsigned-short banks in raster band order when each array contains exactly one tightly packed plane with zero offsets. For a valid strided or interleaved raster it copies the samples into three new planes using one row of scratch. It rejects an incompatible band count, transfer type or sample depth. The arrays stay live with the ImageJ stack after the reader closes. Rendering a composite can also allocate a packed display image. The 6 bytes per output pixel for RGB16 cover only the three retained sample planes.

**Options and errors.** Both menu commands use `AUTO` and an automatic worker budget. Java clients that need `maxWorkers(int)`, explicit `LOW_MEMORY` or `FAST`, a region or a destination can select them through `CanonCr3ImageReadParam` and its decode options. The planner considers decoder buffers and known ImageIO destinations. Later ImageJ rendering and other host allocations consume additional heap. An `open(Path)` call propagates file and decode failures as `IOException`. The menu handler reports an `IOException` or `IllegalArgumentException` with the file path through `IJ.error`. Canceling the chooser returns without opening an image. [ImageIO parameters](../cr3/imageio.md#read-parameters) · [RAW heap policy](../cr3/import.md#limited-heap) · [ImageJ verification](testing.md#imagej-and-fiji-importers)

### Public Maven coordinates

The six supported CR3 library artifacts use `io.github.carstenartur` coordinates in the release reactor:

- `cr3-container`;
- `cr3-metadata`;
- `cr3-codec`;
- `cr3-processing`;
- `cr3-core`;
- `cr3-imageio`.

The [Maven release guide](../cr3-maven-central.md) covers Maven Central coordinates for 0.1.8 and the Git-backed repository. Verify the exact version in the selected channel with an empty Maven cache.

## Camera modules and adapters

### `camera-ptp`

`camera-ptp` contains the reusable clean-room PTP/USB implementation. It includes protocol primitives, Canon vendor extensions and backend/session adapters.

Real-camera behavior is limited to models and operations covered by hardware-backed evidence. A class implementing an operation is not proof that every Canon model supports it.

### CCAPI in `app`

The production CCAPI implementation currently remains in `app`. It owns discovery/catalog parsing, Digest authentication, retries, typed errors, media/event operations, native focus-bracketing support, session ownership and live-view lifecycle.

The camera-advertised catalogue supplies endpoint paths and HTTP method flags. The [CCAPI compatibility policy](../ccapi-capabilities.md#legacy-endpoint-compatibility) describes fixed v1.0 paths and remaining legacy suffix lookups.

### Generic adapters

`CcapiCameraBackend` and `UsbCameraBackend` adapt toward `GenericCameraBackend`:

- the CCAPI adapter is partially functional and accurately withholds unsupported media capabilities;
- the USB adapter is currently a skeleton and exposes only the capability it can honor;
- neither adapter permits the UI to assume support based solely on transport name.

## Public API rules

A type is not automatically a stable public API merely because it is `public` in Java. Public support requires all of the following:

- the artifact is classified as a supported runtime artifact;
- the behavior is covered by executable tests;
- module documentation describes its maturity and limitations;
- published POMs do not expose test/research dependencies;
- compatibility changes follow the pre-1.0 version policy.

Research classes, fixtures, benchmark harnesses and testkits are not application APIs.

## Module-local documentation

Each module may retain `README.md` and `ARCHITECTURE.md` for:

- module ownership;
- public contracts;
- independent build/test commands;
- local limitations and extension points.

Cross-module architecture belongs in this Developer Manual. Module files should link here rather than duplicate the full reactor or product architecture.

## How to verify the graph

Use Maven and the repository-owned architecture check from a normal checkout:

```bash
python3 scripts/test_validate_vision_module_boundaries.py
python3 scripts/validate_vision_module_boundaries.py .
mvn -B help:effective-pom -pl app
mvn -B dependency:tree -pl app
mvn -B dependency:tree -pl canon-cr3-imageio
```

The root `pom.xml` and each module POM take precedence over diagrams and dated inventories.
