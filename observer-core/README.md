# observer-core

Wildlife Observer core logic: event model, state machine, rules engine, scene monitor, capture orchestrator and observer pipeline.

## Purpose

This module provides the pure-Java business logic for detecting and recording wildlife observation events from a camera live-view stream. It owns observer-domain semantics but not concrete AI provider implementations.

## Components

| Package | Key types | Description |
|---|---|---|
| `events` | `ObservationEvent`, `EventState`, `EventStateMachine` | Event model and lifecycle state machine. |
| `events` | `CapturedImage`, `RegionOfInterest`, `VisionResultData` | Observer event value objects. |
| `rules` | `RulesEngine`, `ObserverRule`, `TriggerContext` | Pluggable trigger decision framework. |
| `rules` | `CooldownRule`, `TimeWindowRule`, `MotionThresholdRule`, `MinObjectSizeRule`, `MaxEventsPerHourRule` | Built-in rules. |
| `profile` | `ObserverProfile` | Predefined operating profiles. |
| `monitoring` | `SceneMonitor`, `MotionDetector`, `PixelDiffMotionDetector` | Live-view analysis and trigger detection. |
| `monitoring` | `SharpnessEvaluator`, `WindFilter` | Image-quality and false-positive filtering. |
| `capture` | `CaptureOrchestrator`, `CaptureSeriesResult` | Burst and focus-bracketing capture sequences. |
| `pipeline` | `ObserverPipeline`, `VisionProviderPort`, `ObservationEventListener` | End-to-end coordination and domain ports. |

`com.photographer.focusstack.observer.events.BoundingBox` remains the shared value type used by `VisionResultData`, but its source ownership is in `common-api` so provider implementations can use the same FQN without depending on `observer-core`.

## Design principles

- **No Spring dependency**: pure Java domain library.
- **No provider dependency**: AI integration uses the observer-owned `VisionProviderPort`; concrete provider selection/adaptation belongs in `app`.
- **Immutable value objects**: records are immutable; `ObservationEvent` uses a builder.
- **Pluggable rules**: new rules do not require engine modification.
- **Neutral shared values**: cross-layer contracts such as `BoundingBox` are supplied by `common-api`, not copied into provider modules.

## Quick start

```java
ObserverProfile profile = ObserverProfile.birdhouse();
RulesEngine rules = new RulesEngine(List.of(
    new MotionThresholdRule(profile.motionThreshold()),
    new CooldownRule(profile.cooldownSeconds()),
    new TimeWindowRule(profile.activeTimeStart(), profile.activeTimeEnd())
));
SceneMonitor monitor = new SceneMonitor(new PixelDiffMotionDetector(), rules, profile);
```

## Build

```bash
python3 scripts/validate_vision_module_boundaries.py .
mvn -B clean verify -pl observer-core -am -Djava.awt.headless=true
```

## Canonical documentation

Cross-module workflow and provider-boundary ownership is maintained in [Modules and public surfaces](../docs/developer/modules.md). Observer-domain behavior remains local to this README and the module tests.
