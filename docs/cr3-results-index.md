# CR3 Results Index

Durable entry point for CR3 research. Every result records its fixed inputs, one changed variable, evidence, verdict and next step.

| Date | Hypothesis | Status | Document |
|---|---|---|---|
| 2026-06-14 | B1/B2/B3 predictor state | B1 PARTIAL; B2/B3 REJECTED | [Predictor state](cr3-results/2026-06-14-B1-predictor-state-isolation.md) |
| 2026-06-14 | B1/B4 predictor families | B1 narrowed; B4 REJECTED | [Predictor matrix](cr3-results/2026-06-14-predictor-evidence-matrix.md) |
| 2026-06-14 | External-reference baseline | COMPLETE | [Reference comparison](cr3-results/reference-comparison.md) |
| 2026-07-03 | Pipeline audit | Stage 6 earliest failure | [Stage audit](cr3-results/2026-07-03-cr3-pipeline-stage-audit.md) |
| 2026-07-19 | D5 sign mapping | REJECTED | [D5](cr3-results/2026-07-19-D5-sign-mapping-discriminator.md) |
| 2026-07-20 | D6 symbol-bracket K update | REJECTED; completion regression | [D6](cr3-results/2026-07-20-D6-k-adaptation-discriminator.md) |
| 2026-07-20 | D7 first K/bit divergence | COMPLETE; zero-symbol cause found | [D7](cr3-results/2026-07-20-D7-first-k-divergence.md) |

## Current priority: D8

D7 found the same first divergence in EOS R and G5 X Mark II: after the first identical `S=0`, HYBRID changes `K 1→0`, while D6 keeps `K=1`. Different bit consumption starts at the next zero symbol.

D8 changes exactly one rule:

```text
if S == 0 and K > 0:
    K = K - 1
else:
    apply the D6 bracket unchanged
```

Reuse exactly the fixed eleven-fixture D6 panel and production gate. No production change without `SUPPORTED` and no completion regression.
