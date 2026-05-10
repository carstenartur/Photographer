# Generic Camera Architecture

> Status: foundation introduced 2026‑05‑10. Existing `CanonCcapiBackend`,
> controllers and frontend continue to work unchanged. New feature work
> targets the **generic interfaces** in `common-api`; existing transports
> are migrated one workflow at a time via adapters.

## Why

The application has so far been organized around two implementations of
`CameraBackend` (Canon CCAPI, USB/PTP) plus several mock/folder backends.
Workflow code (capture, focus stack, HDR, …) and the React frontend
inevitably leaked vendor concepts into the upper layers — a CCAPI endpoint
key here, a PTP property there.

The goal is a **transport-independent application layer**. The GUI and
workflow logic are not allowed to know whether bytes arrive over WLAN/CCAPI,
USB/PTP or a folder watcher. Capability detection, media ingest, deletion,
events and (later) AI hooks are expressed once, in terms of a small set of
domain interfaces.

## Layered view

```
┌────────────────────────────────────────────────────────────┐
│  GUI / REST controllers / job system / AI providers        │  ← never imports backend.* / camera-ptp / ccapi.*
├────────────────────────────────────────────────────────────┤
│  Domain workflow services (MediaIngestService, …)          │  ← talks to GenericCameraBackend + CameraSession only
├────────────────────────────────────────────────────────────┤
│  GenericCameraBackend  (common-api, transport package)     │  ← single contract every transport must satisfy
├────────────────┬─────────────────────┬─────────────────────┤
│ CcapiCameraBackend │ UsbCameraBackend │ Folder / Mock      │  ← adapters that wrap legacy CameraBackend impls
├────────────────┴─────────────────────┴─────────────────────┤
│  Transports: HTTP/CCAPI, libusb/PTP, filesystem, …         │
└────────────────────────────────────────────────────────────┘
```

## New packages (common-api)

| Package | Purpose |
|---|---|
| `common.device`      | `DeviceInfo`, `StorageInfo`, `BatteryInfo`, `LensInfo` — vendor-neutral status snapshots. |
| `common.capability`  | `Capability` enum, `CapabilitySet`, `CapabilityResolver` — what a camera can / cannot do. |
| `common.media`       | `MediaItem`, `MediaType`, `TransferState`, `DeleteState`, `PreviewState`, `TransferResult`, `MediaRepository`. |
| `common.policy`      | `IngestPolicy`, `DeletePolicy` — the safe-by-default delete rules. |
| `common.event`       | `CameraEvent` sealed hierarchy: `ConnectionLost`, `NewMediaDetected`, `ImportCompleted`, `DeleteCompleted`, … |
| `common.ai`          | `SceneAnalysisInput/Result`, `CaptureRecommendation`, `QualityCheckResult` — AI seam, not yet wired. |
| `common.transport`   | `CameraConnection`, `CameraSession`, `CaptureService`, `GenericCameraBackend`. |

## New packages (app)

| Package | Purpose |
|---|---|
| `generic.backend.CcapiCameraBackend` | Adapts the existing `CanonCcapiBackend` to `GenericCameraBackend`. |
| `generic.backend.UsbCameraBackend`   | Skeleton USB/PTP adapter; rich operations marked `TODO`. |
| `generic.session.DefaultCameraSession` | Default `CameraSession` implementation. |
| `generic.ingest.MediaIngestService`    | Type-filter → download → verify → optional delete, with audit events. |
| `generic.ingest.InMemoryMediaRepository` | Default `MediaRepository` for tests / replay sessions. |

## Capability model

`CapabilitySet` is the *only* source of truth the UI may consult to decide
whether to render a control. Adding a feature is "extend the
`Capability` enum + report it from each backend that supports it"; no UI
code branches on CCAPI keys or PTP opcodes.

## Auto-delete safety net

`MediaIngestService` enforces this matrix:

| `DeletePolicy`         | Deletes when … |
|---|---|
| `NEVER`                | never |
| `MANUAL_CONFIRM`       | never automatically — requires a separate explicit call |
| `AUTO_AFTER_IMPORT`    | the download succeeded |
| `AUTO_IF_VERIFIED`     | the download succeeded **and** verification passed |
| `AUTO_JPEG_ONLY`       | as `AUTO_IF_VERIFIED`, restricted to JPEG/HEIF |

Verification means: byte count on disk matches the camera-reported size
*and*, if `IngestPolicy.verifyChecksum` is on, a SHA-256 has been computed.
Every decision is emitted as a `CameraEvent.ImportCompleted` /
`CameraEvent.DeleteCompleted` for audit logging — no silent data loss.

## Migration plan

1. **Today**: foundation only. New code targets `GenericCameraBackend`;
   existing controllers and `CanonCcapiBackend` are untouched.
2. Move CCAPI media-list / storage / battery probes from the legacy backend
   into `CcapiCameraBackend`, then drop the corresponding `TODO`s.
3. Switch the contents/preview UI to `MediaItem` via a thin REST mapper.
4. Flesh out `UsbCameraBackend` against `camera-ptp`.
5. Promote `MediaIngestService` to a Spring `@Service` and expose an
   `/api/cameras/{id}/ingest` endpoint that takes an `IngestPolicy`.
6. Plug the AI seam: a `SceneAnalysisProvider` consumes
   `SceneAnalysisInput` (live-view JPEG) and returns
   `CaptureRecommendation` for the capture controller.

The legacy `CameraBackend` interface is **not** scheduled for removal — it
remains the small extension-point contract for plugins. Adapters bridge
between the two.
