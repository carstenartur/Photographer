# CR3 Research Results

This is the curated entry point to durable CR3/CRX research evidence. Each result records fixed inputs, the changed variable or observation boundary, quantitative evidence, verdict and next step.

Generated and dated reports are evidence snapshots. The maintained interpretation of their current relevance is in [Current state](../current-state.md) and [Open hypotheses](../open-hypotheses.md).

| Date | Hypothesis / observation | Status | Durable report |
|---|---|---|---|
| 2026-06-14 | B1/B2/B3 predictor state | B1 PARTIAL; B2/B3 REJECTED | [Predictor state](../../cr3-results/2026-06-14-B1-predictor-state-isolation.md) |
| 2026-06-14 | B1/B4 predictor families | B1 narrowed; B4 REJECTED | [Predictor matrix](../../cr3-results/2026-06-14-predictor-evidence-matrix.md) |
| 2026-06-14 | External-reference baseline | COMPLETE | [Reference comparison](../../cr3-results/reference-comparison.md) |
| 2026-06-16 | Pedestal / DC hypothesis | SUPPORTED diagnostic result; not a blanket entropy fix | [Pedestal validation](../../cr3-results/2026-06-16-pedestal-hypothesis-validation.md) |
| 2026-07-03 | Pipeline audit | Stage 6 earliest verified semantic failure | [Stage audit](../../cr3-results/2026-07-03-cr3-pipeline-stage-audit.md) |
| 2026-07-19 | D5 sign mapping | REJECTED | [D5](../../cr3-results/2026-07-19-D5-sign-mapping-discriminator.md) |
| 2026-07-20 | D6 approximate K bracket | REJECTED | [D6](../../cr3-results/2026-07-20-D6-k-adaptation-discriminator.md) |
| 2026-07-20 | D7 first K/bit divergence | COMPLETE; zero-symbol cause | [D7](../../cr3-results/2026-07-20-D7-first-k-divergence.md) |
| 2026-07-20 | D8 zero-contracted K bracket | PARTIAL | [D8](../../cr3-results/2026-07-20-D8-zero-contracted-k.md) |
| 2026-07-20 | D9 first non-zero K divergence | COMPLETE; NO COMMON CAUSE | [D9](../../cr3-results/2026-07-20-D9-first-nonzero-k-divergence.md) |
| 2026-07-20 | D10 transition/outcome matrix | COMPLETE; NO PREDICTIVE CLASS | [D10](../../cr3-results/2026-07-20-D10-k-transition-outcome-matrix.md) |
| 2026-07-20 | D11 Canon K primary-source audit | COMPLETE; EXACT BASE CANDIDATE | [D11](../../cr3-results/2026-07-20-D11-canon-k-source-audit.md) |
| 2026-07-20 | D12 exact code-length K bracket | PARTIAL | [D12](../../cr3-results/2026-07-20-D12-exact-k-bracket.md) |
| 2026-07-20 | D13 tentative-symbol context and lossless topology | COMPLETE; PRIMARY SOURCE OR MAPPING INSUFFICIENT | [D13](../../cr3-results/2026-07-20-D13-context-topology.md) |

## Current verdict

D13 inventoried the fixed lossless panel:

- 11 fixtures;
- 88 lossless single-subband records;
- 220,347,400 payload bytes;
- 77 contiguous transitions with no gaps or overlaps;
- one stable topology per camera family and two variants across the full panel.

Every record has `waveletLevels=0` and `subband=0`. The panel therefore exposes no deeper parent, LL/HL/LH/HH identity, causal `Sd` source or `Kd` lifecycle. The registered public source also does not yet provide enough implementable detail to close these gaps.

No D14 entropy candidate is justified. RAW semantic research may resume only with genuinely new primary-source, multi-subband or first-party causal evidence. D12 remains research-only and production is unchanged.

## Evidence classes

### Maintained current-state documents

These are updated when the interpretation changes:

- [Current state](../current-state.md);
- [Open hypotheses](../open-hypotheses.md);
- this results index.

### Generated or dated evidence

Reports under `docs/cr3-results/` preserve the inputs, metrics and verdict at a specific revision. They are not manually rewritten to match later conclusions. A maintained current-state document explains whether a report still applies.

### Local fixture and diagnostic records

Module-local ledgers such as `canon-cr3-core/SubbandFixtures.md` and fixture-local READMEs preserve detailed provenance. They remain local and are linked when needed rather than copied into the curated index.

## Adding a result

A new result is accepted into this index only when it has:

1. a pre-stated hypothesis or observation boundary;
2. fixed inputs and manifest/provenance identifiers;
3. machine-readable metrics;
4. a deterministic human-readable report;
5. an explicit verdict (`SUPPORTED`, `REJECTED`, `PARTIAL`, `BLOCKED`, or equivalent);
6. an updated current-state/hypothesis entry when the durable interpretation changes;
7. a local regeneration or verify command.

See [Research process](../research.md) and the [clean-room record](../../cleanroom/README.md).
