# CR3 Open Hypotheses

This file tracks active CR3 research directions.

## Active CR3 hypotheses

| ID | Hypothesis | Status | Evidence | Next Experiment |
|---|---|---|---|---|
| B1 | Predictor row context is shifted or otherwise incorrect | **PARTIAL** | Phase-B NorthOnly reduced OB artefacts on the dark fixture, but Phase-C residual evidence shows **MED** wins 44/44 leftOB cells, 43/44 topOB cells, and 44/44 image-area cells. Simple N/W/NW family replacement is unsupported. See [2026-06-14-B1-predictor-state-isolation.md](cr3-results/2026-06-14-B1-predictor-state-isolation.md) and [2026-06-14-predictor-evidence-matrix.md](cr3-results/2026-06-14-predictor-evidence-matrix.md). | Phase D: inspect residual/value semantics while keeping the MED neighbourhood fixed |
| B2 | Predictor state reset at tile boundaries is wrong | **REJECTED** | b2-tile-continued doubles OB stdDev on every fixture; current tile-local reset is correct. See [2026-06-14-B1-predictor-state-isolation.md](cr3-results/2026-06-14-B1-predictor-state-isolation.md) | — |
| B3 | Predictor state reset at plane boundaries is wrong | **REJECTED** | b3-plane-seeded identical to baseline on every fixture; plane-independent reset is correct. See [2026-06-14-B1-predictor-state-isolation.md](cr3-results/2026-06-14-B1-predictor-state-isolation.md) | — |
| B4 | Per-plane neighbour model differs from current implementation | **REJECTED** | Phase-C evidence matrix found no systematic per-plane family split: MED wins all leftOB/image-area cells and all but one topOB cell. See [2026-06-14-predictor-evidence-matrix.md](cr3-results/2026-06-14-predictor-evidence-matrix.md). | — |

## Deferred hypotheses

These are not current priorities and require a novelty argument before reactivation.

| ID | Hypothesis | Status | Reason |
|---|---|---|---|
| D1 | quantValue is the primary lossless decoding problem | DEFERRED | low variability in current lossless corpus |
| D2 | PRVW geometry is the primary decoding problem | DEFERRED | Phase-A investigations already performed |
| D3 | CFA layout discovery is the primary decoding problem | DEFERRED | already investigated sufficiently |
| D4 | generic alignment sweep should be repeated | DEFERRED | alignment already studied extensively |
| D5 | residual/value semantics are wrong while the neighbourhood family is roughly MED-like | OPEN | Phase-C evidence matrix says MED best matches the decoded signal, yet OB remains physically implausible |

## Status values

- OPEN
- SUPPORTED
- REJECTED
- BLOCKED
- PARTIAL
- DEFERRED

Every completed experiment must update at least one status and link to a durable result document.
