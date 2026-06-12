# CR3 Research Playbook

This is the mandatory entry point for CR3/CRX RAW import work.

## Read first

1. `docs/cr3-fixture-audit.md`
2. `canon-cr3-core/SubbandFixtures.md`
3. `docs/cr3-current-state.md`
4. `docs/cr3-open-hypotheses.md`
5. `docs/cr3-results/index.md`

Do not rediscover the CR3 container format. The repository already contains fixture inventory, parser output, CRX header data, IAD1/OB geometry, subband extraction, statistics, and hypothesis sweep infrastructure.

## Work on one layer only

| Layer | Meaning | Policy |
|---|---|---|
| A | CR3 container, tracks, boxes | Stable; do not rework unless broken |
| B | CMP1/IAD1/CRX header interpretation | Add invariants and tests |
| C | CRX entropy/residual codec | Main current research area |
| D | RAW image pipeline | Do not optimize until layer C improves |

## Rules

1. Select exactly one hypothesis from `docs/cr3-open-hypotheses.md`.
2. Keep speculative code in test or research scope.
3. Do not change production decoder code unless a hypothesis is marked `SUPPORTED`.
4. Every experiment must produce a durable result under `docs/cr3-results/` or `target/cr3-research/`.
5. Stdout-only reports are not sufficient.
6. Update the hypothesis status after the experiment.

## Required workflow

1. Pick one hypothesis.
2. State the success criterion.
3. Run or add a reproducible test/research harness.
4. Write the result to `docs/cr3-results/YYYY-MM-DD-<hypothesis-id>-<short-name>.md`.
5. Update `docs/cr3-results/index.md`.
6. Update `docs/cr3-open-hypotheses.md`.

## Gate for production decoder changes

Production decoder changes require:

- status `SUPPORTED` for the relevant hypothesis,
- a durable result document,
- numeric metrics,
- no weakened regression tests,
- a separate follow-up commit or PR for production code.
