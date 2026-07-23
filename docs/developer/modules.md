# Modules and Public Surfaces

The root Maven reactor is the authoritative module list. This chapter classifies modules by role and support boundary; volatile class/test counts are intentionally omitted.

## Default reactor

| Module | Role | Support boundary |
|---|---|---|
| `common-api` | Shared records, camera contracts, capabilities, media, policy, events and transport-neutral interfaces | Foundation library; keep framework dependencies minimal. |
| `plugin-api` | ServiceLoader SPI for analysis, camera and vision-provider plugins | Public extension surface; currently depends on `ai-vision` for vision contracts. |
| `capture-core` | Bracket and sequence capture abstractions | Framework-light domain library. |
| `storage` | Session storage abstraction and file implementation | Framework-light library; not the relational JPA layer. |
| `camera-ptp` | Clean-room PTP/USB protocol, Canon extensions, session/backend adapters | Reusable camera transport library; hardware compatibility remains model-dependent. |
| `hdr-core` | HDR processing and tonemapping ports/implementations | Domain library. |
| `video-core` | Video encoder contract and test/development implementations | Domain/port library; external encoder support must be documented per implementation. |
| `face-analysis-core` | Face-analysis contract and implementations | Domain library. |
| `face-profile-core` | Multi-view face-profile generation | Domain library composed with face analysis. |
| `session-core` | HDR timelapse and face-orbit orchestration | Framework-light workflow library. |
| `observer-core` | Wildlife-observer state machine, rules and capture orchestration | Framework-light workflow library. |
| `ai-vision` | Vision-provider abstraction, Gemini/mock implementations and AI permission/privacy gates | Provider library; live Gemini use requires explicit configuration and credentials. |
| `astro-core` | Star detection, registration, stacking and focus-related astro logic | Domain library. |
| `canon-cr3-container` | CR3 ISO BMFF parsing and embedded JPEG access | Candidate public runtime library under `io.github.carstenartur`. |
| `canon-cr3-metadata` | EXIF, MakerNote, CTMD and sensor/active-area metadata | Candidate public runtime library. |
| `canon-cr3-codec` | Clean-room CRX RAW codec and linear Bayer model | Publicly packaged but explicitly experimental before 1.0. |
| `canon-cr3-processing` | Pedestal, crop, demosaic, white balance and color processing | Candidate public runtime library; maturity follows the individual APIs. |
| `canon-cr3-core` | CR3 decode orchestration and compatibility facade | Candidate public runtime facade. |
| `canon-cr3-reference-testkit` | Shared reference loading, metrics and deterministic evidence writers | Internal test/research infrastructure; not a supported runtime dependency. |
| `canon-cr3-research` | Research experiments and report generators | Research-only; not a product library. |
| `canon-cr3-imageio` | Java ImageIO SPI and reader | Candidate public runtime library. |
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

`common-api` is the lowest shared project module. It contains both the compact `CameraBackend` contract and the richer connection-scoped `GenericCameraBackend` model.

No module should move Spring application wiring into `common-api` merely to avoid writing an adapter.

### Extension and domain libraries

`plugin-api`, `capture-core`, `storage`, `camera-ptp`, `hdr-core`, `video-core`, `face-analysis-core`, `face-profile-core`, `observer-core`, `ai-vision` and `astro-core` provide contracts and focused implementations.

`session-core` is an orchestration layer over several of these modules. It should coordinate work but not become a second application container.

### Application

`app` depends on almost every product library because it is the composition root. This is expected for a modular monolith, but new domain logic should still be placed in focused modules when it can remain independent of Spring, HTTP and JPA.

The application currently also owns relational entities/repositories and Hibernate Search composition. The `storage` module should therefore not be described as owning all persistence.

## CR3 runtime graph

The supported artifact graph is intentionally split:

```text
canon-cr3-container
  └─ canon-cr3-metadata
       └─ canon-cr3-codec (experimental)
            └─ canon-cr3-processing

container + metadata + codec + processing
  └─ canon-cr3-core
       ├─ canon-cr3-imageio
       └─ canon-cr3-cli
```

The actual POM graph has some direct dependencies in addition to this simplified path. `canon-cr3-core` is the facade over all four lower-level runtime modules, and `canon-cr3-imageio` directly declares the runtime modules it needs.

### Public Maven coordinates

The six supported CR3 library artifacts use `io.github.carstenartur` coordinates in the release reactor:

- `canon-cr3-container`;
- `canon-cr3-metadata`;
- `canon-cr3-codec`;
- `canon-cr3-processing`;
- `canon-cr3-core`;
- `canon-cr3-imageio`.

The repository contains a credential-free Central bundle preflight, but documentation must not claim Maven Central availability until an immutable version is visibly published and resolves from an empty external repository.

## Camera modules and adapters

### `camera-ptp`

`camera-ptp` contains the reusable clean-room PTP/USB implementation. It includes protocol primitives, Canon vendor extensions and backend/session adapters.

Real-camera behavior is limited to models and operations covered by hardware-backed evidence. A class implementing an operation is not proof that every Canon model supports it.

### CCAPI in `app`

The production CCAPI implementation currently remains in `app`. It owns discovery/catalog parsing, Digest authentication, retries, typed errors, media/event operations, native focus-bracketing support, session ownership and live-view lifecycle.

The exact camera-advertised endpoint catalogue is the source for path and HTTP-method selection.

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

Use Maven from a normal checkout:

```bash
mvn -B help:effective-pom -pl app
mvn -B dependency:tree -pl app
mvn -B dependency:tree -pl canon-cr3-imageio
```

The root `pom.xml` and each module POM take precedence over diagrams and dated inventories.
