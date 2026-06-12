# CR3 Open Hypotheses

This file tracks active CR3 research directions.

## Active Phase-B hypotheses

| ID | Hypothesis | Status | Next Experiment | Success Criterion |
|---|---|---|---|---|
| B1 | Predictor row context is shifted or otherwise incorrect | OPEN | PredictorStateIsolationIT | lower OB stdDev and banding |
| B2 | Predictor state reset at tile boundaries is wrong | OPEN | tile-boundary isolation | improved OB and boundary behaviour |
| B3 | Predictor state reset at plane boundaries is wrong | OPEN | plane-boundary isolation | improved OB and per-plane stability |
| B4 | Per-plane neighbour model differs from current implementation | OPEN | per-plane predictor variants | measurable OB improvement |

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
