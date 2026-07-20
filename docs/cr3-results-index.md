# CR3 Results Index

Durable entry point for CR3 research. Every result records fixed inputs, one changed variable, evidence, verdict and next step.

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

## Current priority: D9

D8 shows that zero-symbol contraction is necessary but the current non-zero symbol bracket is still not universal.

Trace HYBRID and D8 without changing either decoder until the first non-zero K-state or bit-position divergence on:

- `eosr-iso-low.cr3` — complete improvement;
- `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` — complete but global regression;
- `sample-iso-high.cr3` — completion regression.

Record the aligned zero-run length, first non-zero symbol, byte:bit positions, K before/after, HYBRID accumulator/sample count, D8 bracket bounds, predictor and value. The next semantic experiment must change exactly one cause established by that trace.
