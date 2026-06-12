# CR3 Research Playbook

This is the mandatory entry point for CR3/CRX RAW import work.

The repository already contains a substantial Phase-A research history. Do not restart from container discovery or broad decoder sweeps.

## Mandatory reading order

1. `docs/cr3-fixture-audit.md`
2. `canon-cr3-core/SubbandFixtures.md`
3. `docs/cleanroom-fixture-plan.md`
4. `docs/cr3-current-state.md`
5. `docs/cr3-open-hypotheses.md`
6. `docs/cr3-results-index.md`

## Phase-A conclusions to preserve

Treat these as the current baseline unless a new experiment explicitly disproves them:

- CR3 container parsing, CMP1, IAD1 and CRX header extraction are established.
- CRX_SMALL / PRVW geometry and CFA mismatch have been investigated sufficiently for diagnosis.
- Bitstream alignment has been investigated; 4-byte alignment is plausible and broad alignment sweeps are low priority.
- Optical-black (IAD1 OB) regions are the strongest current falsification axis.
- The current decoder is physically implausible in OB regions.
- `predictor correct, entropy wrong` is rejected by the Phase-A decision matrix.
- `entropy mostly plausible, predictor/state machine wrong` is the current leading direction.
- The current corpus is structurally saturated for many broad sweeps; do not run them again without a novelty argument.

## Current priority

Phase B: isolate the predictor state machine.

Useful Phase-B hypotheses are narrow and falsifiable:

1. previous-row context is off by one row,
2. predictor state reset is wrong at tile boundaries,
3. predictor state reset is wrong at plane boundaries,
4. per-plane neighbour context differs from the current implementation.

## Do not spend agent time on

- new container analysis,
- broad Rice/Golomb parameter sweeps,
- quantValue-first experiments on the lossless corpus,
- CFA-layout rediscovery,
- PRVW geometry rediscovery,
- colour pipeline, white balance, demosaic, or rendering quality.

## Required workflow

1. Choose exactly one active hypothesis from `docs/cr3-open-hypotheses.md`.
2. Explain why Phase-A updates (p), (q), (r), and (s) do not already settle it.
3. Implement only test/research-scope code unless the production gate is met.
4. Use IAD1 OB metrics as the primary score: OB stdDev, median stability, row/column profile, autocorrelation, banding score.
5. Use PRVW Pearson only as a secondary sanity metric.
6. Emit machine-readable and human-readable artifacts.
7. Update the durable research documents listed below.

## Required output for every CR3 research task

At task completion, update or create:

- `docs/cr3-current-state.md`
- `docs/cr3-open-hypotheses.md`
- `docs/cr3-results-index.md`
- `docs/cr3-results/<date>-<hypothesis>-<short-name>.md`

Also generate test artifacts under `target/<experiment>/`, preferably both `.tsv` or `.json` and `.md`.

Stdout-only results are not acceptable.

## Gate for production decoder changes

Production decoder changes require all of the following:

- a hypothesis status of `SUPPORTED`,
- a durable result document under `docs/cr3-results/`,
- numeric improvement on at least one Phase-A falsifier, preferably OB plausibility,
- no weakened regression tests,
- a separate production-code commit or PR after the research result is documented.

## Default next task

If no more specific task is provided, perform `B1 Predictor State Isolation` from `docs/cr3-open-hypotheses.md`.
