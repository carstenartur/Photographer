# Local Bird Observer Implementation Plan

> **For agentic workers:** Use the implementation and verification skills for each task; independent components have disjoint file ownership.

**Goal:** Detect birds locally in a camera preview and optionally capture full-quality photographs from the existing Observer UI.

**Architecture:** A bounded application worker owns one camera and calls a local vision provider before a pure-Java visit/repeat policy permits capture. The existing event history stores resulting photographs. A React panel exposes configuration, a matching analysed preview, arm and stop.

**Tech Stack:** Java 25, Maven 3.9+, ONNX Runtime CPU, Spring Boot, React/Vite, JUnit, Vitest.

**Spec:** [Accepted design](../specs/2026-11-19-local-bird-observer-design.md).

## Global Constraints

- Default 500 ms interval, confidence 0.5, ROI-relative minimum area 0.0025, repeat 5 s, observed absence 3 s.
- Generic birds only; no unsupported species claims or remote provider fallback.
- One active camera run, bounded frames, exclusive camera ownership and explicit arming.
- Reuse existing neutral vision contracts, Observer dashboard and event persistence.
- Real camera timing is not established by a mocked test or this environment.

## Review Focus

- A stop or changed configuration while inference is running prevents its stale decision from capturing.
- Blackout, duplicate frames, camera errors and provider errors never advance observed absence.
- A bird in a small off-centre ROI produces correctly mapped full-frame boxes and an uncropped photograph.
- Competing workflows cannot operate the observed CCAPI/USB camera; cleanup does not release a still-running owner.
- Missing/corrupt model or undecodable images remain explicit failures, never successful negative detections.

## Task 1: Bundled local detector

Files: `ai-vision/pom.xml`, new `ai-vision/src/main/java/com/photographer/focusstack/ai/local/LocalBirdVisionProvider.java` and focused preprocessing/decoding helpers; model/resources, attribution and matching tests.

Produces: `LocalBirdVisionProvider implements VisionProvider, AutoCloseable`; no-argument constructor uses the bundled pinned YOLOX-Nano model; optional `Path` constructor supports an explicit compatible local model. Existing `detectObjects(byte[], VisionContext)` returns normalized full-input boxes labelled `bird`, generic classification only and typed failures. Native session initialization and close are thread-safe and resources bounded.

- [ ] Test real bird recognition, empty input, invalid image and shape, coordinate mapping/NMS, repeated inference and close.
- [ ] Implement adapter and pinned model packaging with upstream provenance/license and fixture attribution.
- [ ] Run `mvn -B test -pl ai-vision -am -Djava.awt.headless=true`.

## Task 2: Trigger policy and application runtime

Files: new observer-core bird policy/settings classes/tests; app observer live service/controller/configuration and tests; existing camera identity/workflow ownership and ObserverService event image mapping as required.

Consumes: neutral `VisionProvider.detectObjects`; existing `CameraBackend`/`CaptureResult`, profile service and lease manager.
Produces: `/api/v1/observer/live` status, `/start`, `/settings`, `/stop`, atomic frame/box preview, validated configuration, persisted events. Exact frontend JSON contract is recorded in the task handoff before implementation.

- [ ] Test positive/repeat/absence sequences, invalid values, stale/duplicate frames, error gaps and stop during inference.
- [ ] Implement the bounded camera-owning runtime with separate preview-only and armed modes, ROI crop and full-frame mapping.
- [ ] Persist original capture plus JPEG event preview and expose truthful status/error data.
- [ ] Run focused core/application tests with `-am`; check module boundaries.

## Task 3: Observer user interface

Files: `frontend-web/src/components/observer/BirdTriggerPanel.jsx` and CSS/tests, `frontend-web/src/api/observerApi.js`, existing `ObserverDashboard.jsx`.

Consumes: live JSON contract from task 2; existing authenticated request functions and camera list.

- [ ] Test camera selection, settings/ROI validation, explicit arming, stop, errors and matching detections.
- [ ] Add a responsive pointer/keyboard accessible ROI selector and existing-dashboard integration.
- [ ] Run `npm test`, `npm run lint`, `npm run build`.

## Task 4: Product verification and delivery

- [ ] Document usage, model provenance/platform requirements, runtime ownership and measured software verification in canonical documentation.
- [ ] Run `bash scripts/verify-product.sh` and record any environment/baseline blockers without weakening the gate.
- [ ] Review the complete change independently and resolve concrete defects.
- [ ] Commit on the isolated feature branch, open a focused PR, inspect checks and report exact verification and hardware limits.
