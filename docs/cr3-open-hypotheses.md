# CR3 Open Hypotheses

This file tracks active CR3 research directions.

## Active Phase-B hypotheses

| ID | Hypothesis | Status | Evidence | Next Experiment |
|---|---|---|---|---|
| B1 | Predictor row context is shifted or otherwise incorrect | **PARTIAL** | N-only −72% leftOB dark; −31% topOB dark; W-only worsens in 9/11 fixtures. See [2026-06-14-B1-predictor-state-isolation.md](../cr3-results/2026-06-14-B1-predictor-state-isolation.md) | Phase C: pure-N or N+NW predictor variant in production |
| B2 | Predictor state reset at tile boundaries is wrong | **REJECTED** | b2-tile-continued doubles OB stdDev on every fixture; current tile-local reset is correct. See [2026-06-14-B1-predictor-state-isolation.md](../cr3-results/2026-06-14-B1-predictor-state-isolation.md) | — |
| B3 | Predictor state reset at plane boundaries is wrong | **REJECTED** | b3-plane-seeded identical to baseline on every fixture; plane-independent reset is correct. See [2026-06-14-B1-predictor-state-isolation.md](../cr3-results/2026-06-14-B1-predictor-state-isolation.md) | — |
| B4 | Per-plane neighbour model differs from current implementation | OPEN | Not yet tested; B1 PARTIAL result motivates per-plane N-only vs N+NW investigation | Phase C experiment |

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
