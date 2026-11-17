# Roadmap and Open Work

For current software status and acceptance criteria, see the [open GitHub issues](https://github.com/carstenartur/Photographer/issues?q=is%3Aissue%20is%3Aopen). Camera measurements, capture acquisition and publication decisions are tracked in the [manual follow-up checklist](docs/manual-todo.md).

This file records cross-project follow-up and preserves pointers for old links. Git history preserves the former TODO list, including completed refactoring notes and superseded CRX hypotheses.

## Active areas

### Camera and CCAPI

The [capability guide](docs/ccapi-capabilities.md) describes endpoint and method selection. The [native focus-bracketing guide](docs/native-focus-bracketing.md) covers transport preflight and strategy selection. The remaining [model and firmware checks](docs/manual-todo.md#ccapi-hardware-evidence) and [native sequence acceptance](docs/manual-todo.md#native-focus-bracketing-on-hardware) require a connected camera.


### CR3 / CRX

The [current decoder status](docs/cr3/current-state.md) links the measured lossless and C-RAW results. The [Maven release guide](docs/cr3-maven-central.md) describes distribution and independent consumer verification.

The [D14 capture checklist](docs/manual-todo.md#d14-capture-series) records the inputs needed to run the controlled spatial-code experiment.

### Capture integrations

The [paired capture guide](docs/fresnel-capture.md) describes the implemented mock-camera sequence, polling, restart recovery and verified source transfer. The shared [Fresnel protocol work](https://github.com/carstenartur/Fresnel/issues/139) owns the wire contract.

- Agree on explicit online pause, resume and operator reconciliation for an uncertain shutter with the Fresnel maintainers. Specify idempotency, lease reacquisition, setting restoration and event transitions, then exercise them across both services. Keep automatic retries fenced while the outcome is uncertain.
- Specify and test optional SSE reconnection and RAW/preview negotiation in both products. Verify a reconnect from a durable event ID and independent source and preview hashes before advertising those capabilities.
- Qualify a real-camera adapter for single-dispatch capture. Require a verified file/step association and safe exposure and focus restoration under lease. Gate formats and camera models on the [physical acceptance checklist](docs/manual-todo.md#fresnel-hardware-acceptance).
- In the [display photometric stereo client](https://github.com/carstenartur/Fresnel/issues/141), bind each real display presentation to the exact raster digest, acknowledge that presentation before the shutter and cancel on presentation loss. The adapter already accepts and verifies the committed pattern identity.
- Connect final experiment bundles and analysis in the [BOS](https://github.com/carstenartur/Fresnel/issues/140) and [display photometric stereo](https://github.com/carstenartur/Fresnel/issues/141) clients. Compare imported files with the manual-upload path using the same observations, roles and pattern hashes.

## Adding work

When creating or updating an issue, include the observed problem, supporting evidence, an implementation or investigation plan, checkout-runnable verification and acceptance criteria. Record work that requires hardware access or an owner decision in the [manual follow-up checklist](docs/manual-todo.md).
