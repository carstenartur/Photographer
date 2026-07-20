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
| 2026-07-20 | D11 Canon K primary-source audit | COMPLETE; EXACT CANDIDATE AVAILABLE | [D11](cr3-results/2026-07-20-D11-canon-k-source-audit.md) |
| 2026-07-20 | D12 exact code-length K bracket | PARTIAL | [D12](cr3-results/2026-07-20-D12-exact-k-bracket.md) |

## Current priority: D13 context and topology

D12 confirms that the source-backed optimum K range is not sufficient when the currently decoded symbol is used as the tentative next symbol. The optical-black median improves, but global, boundary, saturation, camera-family and stream-completion gates fail.

The next work is observation and source analysis only:

- transcribe the remaining `Sd`, `Sn` and `Kd` semantics with figure/claim references;
- map the fixed CRX subband topology without selecting by output quality;
- verify causal availability at row, tile, plane and decomposition boundaries;
- produce one exact context candidate or keep semantic work blocked.

Issue #238 defines the acceptance boundary. No D12 behavior may enter production.
