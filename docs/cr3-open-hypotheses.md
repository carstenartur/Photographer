# CR3 Open Hypotheses

This file tracks active CR3 research directions.

## Active CR3 hypotheses

| ID | Hypothesis | Status | Evidence | Next Experiment |
|---|---|---|---|---|
| B1 | Predictor row context is shifted or otherwise incorrect | **PARTIAL** | Phase-B NorthOnly reduced OB artefacts on the dark fixture, but Phase-C residual evidence shows **MED** wins 44/44 leftOB cells, 43/44 topOB cells, and 44/44 image-area cells. Simple N/W/NW family replacement is unsupported. See [2026-06-14-B1-predictor-state-isolation.md](cr3-results/2026-06-14-B1-predictor-state-isolation.md), [2026-06-14-predictor-evidence-matrix.md](cr3-results/2026-06-14-predictor-evidence-matrix.md), and [reference-comparison.md](cr3-results/reference-comparison.md). | Only revisit after a concrete decoder change reduces reference error |
| B2 | Predictor state reset at tile boundaries is wrong | **REJECTED** | b2-tile-continued doubles OB stdDev on every fixture; current tile-local reset is correct. See [2026-06-14-B1-predictor-state-isolation.md](cr3-results/2026-06-14-B1-predictor-state-isolation.md) | — |
| B3 | Predictor state reset at plane boundaries is wrong | **REJECTED** | b3-plane-seeded identical to baseline on every fixture; plane-independent reset is correct. See [2026-06-14-B1-predictor-state-isolation.md](cr3-results/2026-06-14-B1-predictor-state-isolation.md) | — |
| B4 | Per-plane neighbour model differs from current implementation | **REJECTED** | Phase-C evidence matrix found no systematic per-plane family split: MED wins all leftOB/image-area cells and all but one topOB cell. See [2026-06-14-predictor-evidence-matrix.md](cr3-results/2026-06-14-predictor-evidence-matrix.md). | — |
| D5 | Conventional zig-zag unsigned-to-signed residual mapping should replace bit-0 sign mapping while all other candidate-decoder semantics remain fixed | **REJECTED** | The complete 11-fixture discriminator produced 0/3 successful discriminator fixtures and 0/3 required Pearson flips. Median global MAE regressed from 1833.9213 to 2315.9968, median boundary error regressed from 2365.6625 to 3007.1074, and completion worsened on incomplete fixtures. See [2026-07-19-D5-sign-mapping-discriminator.md](cr3-results/2026-07-19-D5-sign-mapping-discriminator.md). | — |
| D6 | The remaining Stage-6 error is in a different isolated CRX entropy semantic, such as K adaptation or escape handling | **OPEN** | The 2026-07-03 stage audit identifies Stage 6 as the earliest failing/unverified point; MED neighbourhood, reset behavior and isolated zig-zag replacement are now constrained or rejected. | Select one concrete K-update or escape-rule alternative from structural evidence, predeclare acceptance criteria, and execute it alone on the same 11-fixture panel |

## Deferred hypotheses

These are not current priorities and require a novelty argument before reactivation.

| ID | Hypothesis | Status | Reason |
|---|---|---|---|
| D1 | quantValue is the primary lossless decoding problem | DEFERRED | low variability in current lossless corpus |
| D2 | PRVW geometry is the primary decoding problem | DEFERRED | Phase-A investigations already performed |
| D3 | CFA layout discovery is the primary decoding problem | DEFERRED | already investigated sufficiently |
| D4 | generic alignment sweep should be repeated | DEFERRED | alignment already studied extensively |

## Status values

- OPEN
- SUPPORTED
- REJECTED
- BLOCKED
- PARTIAL
- DEFERRED

Every completed experiment must update at least one status and link to a durable result document.
