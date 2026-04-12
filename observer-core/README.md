# observer-core

Wildlife Observer core logic: event model, state machine, rules engine, scene monitor,
capture orchestrator, and observer pipeline.

## Purpose

This module provides all the pure-Java business logic for detecting and recording
wildlife observation events from a camera live-view stream.

## Components

| Package | Key Types | Description |
|---|---|---|
| `events` | `ObservationEvent`, `EventState`, `EventStateMachine` | Event data model and lifecycle state machine |
| `events` | `CapturedImage`, `RegionOfInterest`, `VisionResultData`, `BoundingBox` | Supporting value objects |
| `rules` | `RulesEngine`, `ObserverRule`, `TriggerContext` | Pluggable trigger decision framework |
| `rules` | `CooldownRule`, `TimeWindowRule`, `MotionThresholdRule`, `MinObjectSizeRule`, `MaxEventsPerHourRule` | Built-in rule implementations |
| `profile` | `ObserverProfile` | Pre-defined operating mode profiles (birdhouse, birdbath, macro, etc.) |
| `monitoring` | `SceneMonitor`, `MotionDetector`, `PixelDiffMotionDetector` | Live-view frame analysis and trigger detection |
| `monitoring` | `SharpnessEvaluator`, `WindFilter` | Image quality and false-positive filtering |
| `capture` | `CaptureOrchestrator`, `CaptureSeriesResult` | Burst and focus-bracketing capture sequences |
| `pipeline` | `ObserverPipeline`, `VisionProviderPort`, `ObservationEventListener` | End-to-end pipeline coordination |

## Design Principles

- **No Spring dependency**: Pure Java library, usable in any context.
- **No AI dependency**: AI integration uses the `VisionProviderPort` interface; the implementation lives in `ai-vision` or `app`.
- **Immutable value objects**: All records are immutable; `ObservationEvent` uses a builder.
- **Pluggable rules**: Add new `ObserverRule` implementations without modifying the engine.

## Quick Start

```java
ObserverProfile profile = ObserverProfile.birdhouse();
RulesEngine rules = new RulesEngine(List.of(
    new MotionThresholdRule(profile.motionThreshold()),
    new CooldownRule(profile.cooldownSeconds()),
    new TimeWindowRule(profile.activeTimeStart(), profile.activeTimeEnd())
));
SceneMonitor monitor = new SceneMonitor(new PixelDiffMotionDetector(), rules, profile);
// Call monitor.analyzeFrame(liveViewFrame) in a periodic loop
```

## Build

```bash
mvn -B clean verify -pl common-api,observer-core -am -Djava.awt.headless=true
```
