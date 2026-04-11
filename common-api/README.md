# common-api

Shared interfaces and model records used across all Focus Stack modules.

## Purpose

This module defines the contracts that camera backends (CCAPI, PTP, mock) and the
main application depend on. By keeping these types in a separate module, backend
implementations can be developed and tested independently of the Spring Boot app.

## Key Types

### Camera interfaces (`com.photographer.focusstack.common.camera`)

| Type | Description |
|---|---|
| `CameraBackend` | Main interface every camera backend must implement. |
| `CameraInfo` | Camera name, model, serial number. |
| `CameraState` | Current camera state (battery, mode, settings). |
| `CaptureResult` | Result of a capture operation (JPEG bytes, metadata). |
| `LiveViewFrame` | A single live-view frame (image bytes, dimensions). |
| `FocusCommand` | Focus adjustment command (direction, magnitude). |
| `Operation` | Enumeration of camera capabilities. |

### Model records (`com.photographer.focusstack.common.model`)

| Type | Description |
|---|---|
| `CameraProfile` | Persisted camera profile (name, connection URL). |
| `StackingJob` | A focus-stacking job with parameters and status. |
| `StackingParameters` | Focus range, step size, quality mode. |
| `JobStatus` | Stacking job lifecycle states. |
| `QualityMode` | Preset quality modes (Fast, Balanced, Maximum). |

### Analysis records (`com.photographer.focusstack.common.analysis`)

| Type | Description |
|---|---|
| `AnalysisResult` | Sharpness and motion analysis for a captured frame. |
| `SharpnessMap` | Per-region sharpness scores for a frame. |

## Dependencies

- `jackson-annotations` — JSON serialization annotations
- `jakarta.validation-api` — Bean validation constraints

## Build

```bash
mvn -B clean verify -pl common-api
```
