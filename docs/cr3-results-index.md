# CR3 Results Index

This document is the durable entry point for future CR3 research sessions.

## Naming convention

`YYYY-MM-DD-<hypothesis>-<short-name>.md`

Examples:

- `2026-06-14-B1-predictor-state-isolation.md`
- `2026-06-14-predictor-evidence-matrix.md`
- `2026-07-19-D5-sign-mapping-discriminator.md`

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
| 2026-06-14 | D1 (reference sidecars), D2 (reference comparison baseline) | D1 COMPLETE · D2 COMPLETE | [reference-comparison.md](cr3-results/reference-comparison.md) |
| 2026-07-03 | Stage audit (pipeline verification order) | Stage 6 FAILING · earliest unverified/failing stage identified | [2026-07-03-cr3-pipeline-stage-audit.md](cr3-results/2026-07-03-cr3-pipeline-stage-audit.md) |
| 2026-07-19 | D5 (isolated residual sign mapping) | D5 REJECTED · 0/3 required discriminator successes | [2026-07-19-D5-sign-mapping-discriminator.md](cr3-results/2026-07-19-D5-sign-mapping-discriminator.md) |

## Current priority

Phase D6: select one structurally motivated Stage-6 entropy semantic—preferably a precise K-adaptation or escape-handling rule—and execute it alone against the same external-reference panel.

The next result document must predeclare:

- the one variable that changes;
- the 11 reference-backed fixtures;
- discriminator and full-corpus thresholds;
- completion and exhaustion constraints;
- the condition that would justify a production decoder change.

Do not repeat generic predictor, alignment, CFA or BIT0-versus-ZIGZAG sweeps without new structural evidence.

## Goal

Future agents should be able to determine the current research state by reading:

1. `docs/cr3-current-state.md`
2. `docs/cr3-open-hypotheses.md`
3. `docs/cr3-results-index.md`

without reconstructing months of commit history.
