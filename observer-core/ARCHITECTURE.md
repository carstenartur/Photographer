# observer-core Architecture

## Overview

`observer-core` implements the Wildlife Observer domain pipeline as a pure Java library. Spring composition, persistence and concrete AI provider selection are handled by adapters in `app`.

The module depends on neutral contracts from `common-api`, but not on `ai-vision`.

## Layered architecture

```text
                 ┌─────────────────────────────────────────┐
                 │             ObserverPipeline            │
                 │  coordinates all components per frame  │
                 └────┬──────────┬───────────┬────────────┘
                      │          │           │
              ┌───────▼───┐ ┌────▼─────┐ ┌──▼──────────────┐
              │SceneMonitor│ │Capture   │ │VisionProviderPort│
              │ monitoring │ │Orchestr. │ │ observer port    │
              └─────┬──────┘ └──────────┘ └──────────────────┘
                    │
         ┌──────────▼──────────┐
         │     RulesEngine      │
         └──────────┬──────────┘
                    │
         ┌──────────▼──────────┐
         │ MotionDetector       │
         │ SharpnessEvaluator   │
         │ WindFilter           │
         └─────────────────────┘
```

## Dependency direction

```text
common-api shared contracts
        ↓
observer-core domain ports and events
        ↓
app provider adapter / persistence / controllers
```

`VisionProviderPort` is the observer-domain port. It is not implemented in `ai-vision`; the application adapter selects a neutral `VisionProvider` and maps its `VisionResult` to `VisionResultData`.

The shared `BoundingBox` FQN remains `com.photographer.focusstack.observer.events.BoundingBox` for compatibility, but its source is owned by `common-api`. This prevents `ai-vision -> observer-core` while keeping existing serialized/imported type names stable.

## Event lifecycle

```text
IDLE → MOTION_DETECTED → TRACKING → CAPTURING → SELECTING → ANALYZING → COMPLETED
  └──────────────────────────────────────────────────────────────────→ DISCARDED
```

- `IDLE`: no active event; monitoring continues.
- `MOTION_DETECTED`: motion exceeded the trigger threshold.
- `TRACKING`: subject confirmed and capture planned.
- `CAPTURING`: burst or bracket sequence in progress.
- `SELECTING`: best-shot selection.
- `ANALYZING`: `VisionProviderPort` classifies the selected image.
- `COMPLETED`: event has a final result.
- `DISCARDED`: false positive or rejected event.

## Rules engine

Rules are evaluated in descending priority order. All rules must accept a trigger. New rule behavior belongs in an `ObserverRule` implementation and focused tests.

## Extension points

- **Motion detection**: implement `MotionDetector`.
- **Profiles**: add reviewed `ObserverProfile` factories/configuration.
- **Rules**: implement `ObserverRule` and register it.
- **AI integration**: implement/adapt `VisionProviderPort` in the application composition layer.
- **Persistence**: implement `ObservationEventListener` in an outer module.

## Guard

```bash
python3 scripts/validate_vision_module_boundaries.py .
```

The validator rejects provider→observer and plugin→implementation dependency edges.
