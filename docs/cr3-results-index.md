# CR3 Results Index

Durable entry point for CR3 research. Every result records fixed inputs, one changed variable or observation boundary, evidence, verdict and next step.

| Date | Hypothesis | Status | Document |
|---|---|---|---|
| 2026-06-14 | B1/B2/B3 predictor state | B1 PARTIAL; B2/B3 REJECTED | [Predictor state](cr3-results/2026-06-14-B1-predictor-state-isolation.md) |
| 2026-06-14 | B1/B4 predictor families | B1 narrowed; B4 REJECTED | [Predictor matrix](cr3-results/2026-06-14-predictor-evidence-matrix.md) |
| 2026-06-14 | External-reference baseline | COMPLETE | [Reference comparison](cr3-results/reference-comparison.md) |
| 2026-07-03 | Pipeline audit | Stage 6 earliest failure | [Stage audit](cr3-results/2026-07-03-cr3-pipeline-stage-audit.md) |
| 2026-07-19 | D5 sign mapping | REJECTED | [D5](cr3-results/2026-07-19-D5-sign-mapping-discriminator.md) |
| 2026-07-20 | D6 symbol-bracket K update | REJECTED; completion regression | [D6](cr3-results/2026-07-20-D6-k-adaptation-discriminator.md) |
| 2026-07-20 | D7 first K/bit divergence | COMPLETE; zero-symbol cause found | [D7](cr3-results/2026-07-20-D7-first-k-divergence.md) |
| 2026-07-20 | D8 zero-contracted K bracket | PARTIAL; 1/3 discriminator successes; completion regression | [D8](cr3-results/2026-07-20-D8-zero-contracted-k.md) |
| 2026-07-20 | D9 first non-zero K divergence | COMPLETE; NO COMMON CAUSE | [D9](cr3-results/2026-07-20-D9-first-nonzero-k-divergence.md) |

## Current priority: D10 observation matrix

D9 found three distinct first non-zero decisions:

- EOS R: HYBRID decrease / D8 hold;
- G5 X II `asis`: HYBRID decrease / D8 increase;
- `sample-iso-high`: HYBRID hold / D8 increase.

No semantic decoder change is justified. The next task is an observation-only transition matrix over the fixed reference panel. It must count and localize direction disagreements, the first subsequent bit-position split and the associated D8 outcome class.

A semantic experiment may follow only when one transition class consistently predicts the same error or completion behavior across multiple fixtures. Otherwise retain `NO_COMMON_CAUSE` and narrow the observation again.
