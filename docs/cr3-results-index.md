# CR3 Results Index

Durable entry point for CR3 research. Every result records fixed inputs, one changed variable or observation boundary, evidence, verdict and next step.

| Date | Hypothesis | Status | Document |
|---|---|---|---|
| 2026-06-14 | B1/B2/B3 predictor state | B1 PARTIAL; B2/B3 REJECTED | [Predictor state](cr3-results/2026-06-14-B1-predictor-state-isolation.md) |
| 2026-06-14 | B1/B4 predictor families | B1 narrowed; B4 REJECTED | [Predictor matrix](cr3-results/2026-06-14-predictor-evidence-matrix.md) |
| 2026-06-14 | External-reference baseline | COMPLETE | [Reference comparison](cr3-results/reference-comparison.md) |
| 2026-07-03 | Pipeline audit | Stage 6 earliest failure | [Stage audit](cr3-results/2026-07-03-cr3-pipeline-stage-audit.md) |
| 2026-07-19 | D5 sign mapping | REJECTED | [D5](cr3-results/2026-07-19-D5-sign-mapping-discriminator.md) |
| 2026-07-20 | D6 approximate K bracket | REJECTED | [D6](cr3-results/2026-07-20-D6-k-adaptation-discriminator.md) |
| 2026-07-20 | D7 first K/bit divergence | COMPLETE; zero-symbol cause | [D7](cr3-results/2026-07-20-D7-first-k-divergence.md) |
| 2026-07-20 | D8 zero-contracted K bracket | PARTIAL | [D8](cr3-results/2026-07-20-D8-zero-contracted-k.md) |
| 2026-07-20 | D9 first non-zero K divergence | COMPLETE; NO COMMON CAUSE | [D9](cr3-results/2026-07-20-D9-first-nonzero-k-divergence.md) |
| 2026-07-20 | D10 transition/outcome matrix | COMPLETE; NO PREDICTIVE CLASS | [D10](cr3-results/2026-07-20-D10-k-transition-outcome-matrix.md) |
| 2026-07-20 | D11 Canon K primary-source audit | COMPLETE; EXACT BASE CANDIDATE | [D11](cr3-results/2026-07-20-D11-canon-k-source-audit.md) |
| 2026-07-20 | D12 exact code-length K bracket | PARTIAL | [D12](cr3-results/2026-07-20-D12-exact-k-bracket.md) |
| 2026-07-20 | D13 tentative-symbol context and lossless topology | COMPLETE; PRIMARY SOURCE OR MAPPING INSUFFICIENT | [D13](cr3-results/2026-07-20-D13-context-topology.md) |

## Current state: semantic work blocked

D13 executed a deterministic topology inventory over the fixed panel:

- 11 fixtures;
- 88 lossless single-subband records;
- 220,347,400 payload bytes;
- 77 contiguous transitions, no gaps and no overlaps;
- one stable topology per camera family and two variants across the full panel.

Because every record has `waveletLevels=0` and `subband=0`, the panel exposes no deeper parent, LL/HL/LH/HH identity, causal `Sd` source or `Kd` lifecycle. The registered public source also does not yet provide enough implementable detail to close those gaps.

No D14 entropy candidate is justified. RAW research may resume only with genuinely new public-primary-source or multi-subband structural evidence. D12 behavior remains research-only and production is unchanged.
