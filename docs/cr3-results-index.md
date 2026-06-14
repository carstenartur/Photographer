# CR3 Results Index

This document is the durable entry point for future CR3 research sessions.

## Naming convention

`YYYY-MM-DD-<hypothesis>-<short-name>.md`

Examples:

- `2026-06-14-B1-predictor-state-isolation.md`
- `2026-06-14-predictor-evidence-matrix.md`
- `2026-06-20-B2-tile-reset-study.md`

## Required contents

Every result document must contain:

1. Hypothesis ID
2. Goal
3. Fixtures used
4. Method
5. Metrics
6. Conclusion
7. Status update
8. Next step

## Required companion updates

Whenever a result document is added, update:

- `docs/cr3-current-state.md`
- `docs/cr3-open-hypotheses.md`
- this file

## Completed result documents

| Date | Hypotheses | Status | Document |
|---|---|---|---|
| 2026-06-14 | B1 (row context), B2 (tile reset), B3 (plane reset) | B1 PARTIAL · B2 REJECTED · B3 REJECTED | [2026-06-14-B1-predictor-state-isolation.md](cr3-results/2026-06-14-B1-predictor-state-isolation.md) |
| 2026-06-14 | B1 (predictor family evidence), B4 (per-plane neighbour model) | B1 PARTIAL / narrowed · B4 REJECTED | [2026-06-14-predictor-evidence-matrix.md](cr3-results/2026-06-14-predictor-evidence-matrix.md) |

## Current priority

Phase D: residual/value semantics with MED-like neighbourhood structure.

Recommended first result document:

`<date>-D1-residual-semantics-study.md`

## Goal

Future agents should be able to determine the current research state by reading:

1. `docs/cr3-current-state.md`
2. `docs/cr3-open-hypotheses.md`
3. `docs/cr3-results-index.md`

without reconstructing months of commit history.
