# common-api

Shared interfaces and immutable model records used across Focus Stack modules.

## Purpose

This module owns contracts that must be usable without Spring, provider implementations or observer-domain dependencies. Camera backends, plugins, AI providers and the application can therefore be developed and tested against the same neutral types without reversing dependency direction.

## Key types

### Camera interfaces (`com.photographer.focusstack.common.camera`)

| Type | Description |
|---|---|
| `CameraBackend` | Main interface every camera backend must implement. |
| `CameraInfo` | Camera name, model and serial number. |
| `CameraState` | Current camera state such as battery, mode and settings. |
| `CaptureResult` | Result of a capture operation. |
| `LiveViewFrame` | One live-view frame with image bytes and dimensions. |
| `FocusCommand` | Focus adjustment command. |
| `Operation` | Camera capability enumeration. |

### Vision contracts (`com.photographer.focusstack.ai`)

| Type | Description |
|---|---|
| `VisionProvider` | Provider-neutral image classification, description, detection and ranking port. |
| `VisionContext` | Provider-neutral analysis context. |
| `VisionResult` | Unified provider result. |
| `ProviderCapabilities` | Advertised provider features and input limits. |

`com.photographer.focusstack.observer.events.BoundingBox` is also owned here as a compatibility-preserving shared value type used by both `VisionResult` and observer event data. Its package remains unchanged so the ownership move does not break existing imports. A package rename would require a separately reviewed compatibility change.

Concrete providers remain in `ai-vision`; external provider plugins implement the SPI in `plugin-api`; observer workflow ports and event semantics remain in `observer-core`.

### Model records (`com.photographer.focusstack.common.model`)

| Type | Description |
|---|---|
| `CameraProfile` | Persisted camera profile. |
| `StackingJob` | Focus-stacking job with parameters and status. |
| `StackingParameters` | Focus range, step size and quality mode. |
| `JobStatus` | Stacking job lifecycle states. |
| `QualityMode` | Preset quality modes. |

### Analysis records (`com.photographer.focusstack.common.analysis`)

| Type | Description |
|---|---|
| `AnalysisResult` | Sharpness and motion analysis for a captured frame. |
| `SharpnessMap` | Per-region sharpness scores for a frame. |

## Dependency boundary

`common-api` must not depend on application, provider, observer, plugin or camera implementation modules. The checkout-runnable validator enforces that neutral vision contracts remain here and rejects the forbidden edges:

- `plugin-api -> ai-vision`;
- `plugin-api -> observer-core`;
- `ai-vision -> observer-core`.

```bash
python3 scripts/test_validate_vision_module_boundaries.py
python3 scripts/validate_vision_module_boundaries.py .
```

The same checks run through `scripts/verify-product.sh`.

## External dependencies

- `jackson-annotations` — JSON serialization annotations;
- `jakarta.validation-api` — Bean Validation constraints.

## Build

```bash
mvn -B clean verify -pl common-api -am -Djava.awt.headless=true
```

## Canonical documentation

Cross-module ownership and support classification are maintained in [Modules and public surfaces](../docs/developer/modules.md). This README remains authoritative only for `common-api`-local contracts and verification.
