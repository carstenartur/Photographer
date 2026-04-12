# observer-core Architecture

## Overview

The observer-core module implements the full Wildlife Observer pipeline as a
dependency-free Java library. All Spring and AI integrations are handled by
adapter classes in the `app` module.

## Layered Architecture

```
                 ┌─────────────────────────────────────────┐
                 │             ObserverPipeline              │
                 │  (coordinates all components per frame)  │
                 └────┬──────────┬───────────┬──────────────┘
                      │          │           │
              ┌───────▼───┐ ┌────▼─────┐ ┌──▼──────────────┐
              │SceneMonitor│ │Capture   │ │VisionProviderPort│
              │(monitoring)│ │Orchestr. │ │(ai-vision port) │
              └─────┬──────┘ └──────────┘ └──────────────────┘
                    │
         ┌──────────▼──────────┐
         │    RulesEngine       │
         │  (all ObserverRules) │
         └──────────┬──────────┘
                    │
         ┌──────────▼──────────┐
         │   MotionDetector     │
         │   SharpnessEval.     │
         │   WindFilter         │
         └─────────────────────┘
```

## Event Lifecycle (State Machine)

```
IDLE ──► MOTION_DETECTED ──► TRACKING ──► CAPTURING ──► SELECTING ──► ANALYZING ──► COMPLETED
  │              │              │              │              │              │
  └──────────────┴──────────────┴──────────────┴──────────────┴──────────────┴──► DISCARDED
```

- `IDLE`: No active event. `SceneMonitor` is polling.
- `MOTION_DETECTED`: Motion exceeded threshold, rules evaluation in progress.
- `TRACKING`: Object confirmed, capture will begin.
- `CAPTURING`: `CaptureOrchestrator` is executing the burst/bracket sequence.
- `SELECTING`: Best-shot selection by sharpness score.
- `ANALYZING`: `VisionProviderPort` is classifying the best image.
- `COMPLETED`: Event stored with AI result.
- `DISCARDED`: False positive, wind, or no subject detected.

## Rules Engine

Rules are evaluated in descending priority order. All rules must return `true`
for a trigger to occur. Adding a new rule requires only implementing `ObserverRule`
and passing it to `RulesEngine`.

## Extension Points

- **New motion detectors**: Implement `MotionDetector` (e.g. optical-flow based).
- **New profiles**: Add static factory methods to `ObserverProfile`.
- **New rules**: Implement `ObserverRule` and register in `RulesEngine`.
- **AI providers**: Implement `VisionProviderPort` (done in `ai-vision` module).
- **Event persistence**: Implement `ObservationEventListener` (done in `app` module).
