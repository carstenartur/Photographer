# D12 Exact Golomb-Rice code-length K bracket

## Status

**PENDING** — preregistered before execution of the fixed-panel experiment.

No production decoder change is part of D12.

## Source-backed hypothesis

D11 established that the base K range disclosed by Canon patent application
US 2016/0323602 A1 is the finite-domain set of K values that minimise:

```text
L(S,K) = (S >> K) + 1 + K
```

D12 tests whether replacing D8's approximate non-zero bracket with this exact
range improves the existing CRX candidate while every other test-decoder
semantic remains unchanged.

## One changed variable

Baseline D8 non-zero range:

```text
K* = floor(log2(S+1))
Kmin = max(0, K*-1)
Kmax = min(K_MAX, K*+1)
```

D12 non-zero range:

```text
minimumLength = min L(S,K), K in [0, K_MAX]
Kmin = lowest  K attaining minimumLength
Kmax = highest K attaining minimumLength
```

Both use the same one-step movement toward the range. D8 zero contraction for
`S=0` is retained unchanged.

## Fixed inputs

Exactly the eleven reference-backed lossless fixtures used by D8 and D10:

- `eosr-iso-low.cr3`
- `eosr-iso-mid.cr3`
- `eosr-iso-high.cr3`
- `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3`
- `research/g5xm2-dark.cr3`
- `research/g5xm2-flat.cr3`
- `sample-iso-high.cr3`
- `sample-tele.cr3`
- `sample-wb-tungsten.cr3`
- `sample-wide.cr3`
- the remaining explicit fixture in `KAdaptationFixtureSet.PANEL`

`KAdaptationFixtureSet.PANEL` is the executable source of truth. Later corpus
growth must not change this experiment's inputs or verdict.

## Unchanged semantics

- bit and unary parsing;
- sign/residual mapping;
- MED predictor and reconstruction;
- K initial value and reset boundaries;
- K maximum/domain;
- explicit zero contraction;
- exhaustion and padding behavior;
- tile, plane and subband geometry;
- reference alignment and image assembly;
- production decoder.

## Required evidence

For D8 and D12, report deterministically:

- global MAE, RMSE and Pearson correlation;
- optical-black MAE;
- tile-boundary MAE;
- per-plane MAE, RMSE and Pearson correlation;
- saturation rate;
- fixture completion and exhaustion;
- per-tile/per-plane completion regressions;
- newly exhausted streams;
- recovered streams;
- count of K decisions where exact and D8 ranges differ;
- camera-family medians.

## Predeclared gate

`SUPPORTED` requires all of the following:

1. no fixture completion regression;
2. no tile/plane completion regression;
3. no newly exhausted tile/plane stream;
4. median global MAE does not regress;
5. median optical-black MAE does not regress;
6. median boundary MAE does not regress;
7. median saturation does not regress;
8. every camera-family group is non-regressing on global, optical-black and
   boundary medians;
9. either at least two fixtures improve on a primary quality metric, or one
   previously incomplete fixture becomes fully complete and non-exhausted;
10. deterministic Markdown, TSV and JSON evidence is produced.

A recovered individual stream that does not make a fixture complete is only
`PARTIAL` evidence. Failure of any mandatory gate prevents `SUPPORTED`.

## Clean-room boundary

Permitted inputs: `SRC-001`, `SRC-007`, `SRC-008`, `SRC-009`.
No source from a third-party CR3/CRX decoder and no decompiled binary may be
consulted. Negative and partial results remain durable.
