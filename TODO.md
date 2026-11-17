# Roadmap and Open Work

For current software status and acceptance criteria, see the [open GitHub issues](https://github.com/carstenartur/Photographer/issues?q=is%3Aissue%20is%3Aopen). Camera measurements, capture acquisition and publication decisions are tracked in the [manual follow-up checklist](docs/manual-todo.md).

This file is retained as a stable pointer for old links. Git history preserves the former TODO list, including completed refactoring notes and superseded CRX hypotheses.

## Active areas

### Camera and CCAPI

The [capability guide](docs/ccapi-capabilities.md) describes endpoint and method selection. The [native focus-bracketing guide](docs/native-focus-bracketing.md) covers transport preflight and strategy selection. The remaining [model and firmware checks](docs/manual-todo.md#ccapi-hardware-evidence) and [native sequence acceptance](docs/manual-todo.md#native-focus-bracketing-on-hardware) require a connected camera.


### CR3 / CRX

The [current decoder status](docs/cr3/current-state.md) links the measured lossless and C-RAW results. The [Maven release guide](docs/cr3-maven-central.md) describes distribution and independent consumer verification.

The [D14 capture checklist](docs/manual-todo.md#d14-capture-series) records the inputs needed to run the controlled spatial-code experiment.

### Capture integrations

- [#314](https://github.com/carstenartur/Photographer/issues/314) tracks paired capture sessions for Fresnel.

Fresnel supplies a capture-provider interface and a mock provider. Pairing and durable capture-session recovery depend on the [shared capture protocol](https://github.com/carstenartur/Fresnel/issues/139).

## Adding work

When creating or updating an issue, include the observed problem, supporting evidence, an implementation or investigation plan, checkout-runnable verification and acceptance criteria. Record work that requires hardware access or an owner decision in the [manual follow-up checklist](docs/manual-todo.md).
