# D9 First Non-zero K Divergence

## Hypothesis ID

D9 — observe the first non-zero K/bit-position divergence between the unchanged HYBRID and D8 zero-contracted bracket rules after the initial zero run has been aligned.

## Goal

D8 established that zero-symbol contraction is necessary under the current candidate boundary, but did not provide a universal non-zero K rule. D9 therefore changes no decoder semantics. It records the first non-zero divergence on three fixed externally reference-backed fixtures representing different D8 outcomes:

- `eosr-iso-low.cr3` — complete D8 improvement;
- `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` — complete decoding restored, but global/boundary regression;
- `sample-iso-high.cr3` — lower global/OB error with completion regression.

## Method

A single package-private test-scope parser executes both existing rules so symbol parsing cannot differ because of two independently implemented readers. Unit tests require full synthetic HYBRID and D8 output to match the existing candidate decoders.

The real-fixture observation is bounded to the first 65,536 symbols per plane and records:

- exact byte:bit positions;
- K before/after;
- quotient, remainder and unsigned symbol;
- signed residual, predictor and reconstructed value;
- preceding zero-run length;
- HYBRID accumulator and sample count before/after;
- D8 `K*`, `Kmin`, `Kmax` and decision;
- two preceding and up to sixteen following paired symbols.

Identically parsed zero symbols are retained only as context. A follow-up semantic cause is selected automatically only if the same concrete classification occurs in at least two fixtures. `OTHER_K_DECISION`, exhaustion-only and bounded no-divergence outcomes cannot select an experiment.

## Execution evidence

- code commit: `2b3a945ed41076cb37289bfec058eb1d696a00bf`;
- GitHub Actions run: `29771321438`;
- artifact: `cr3-research-evidence`;
- artifact digest:

```text
sha256:d1b44e42279219959b999ecdf296e1f0cb269d0fb1aeafe04b855370178d9249
```

The run completed successfully. The artifact contains deterministic Markdown/JSON and passing unit/integration reports.

## Results

### EOS R low ISO

First non-zero divergence: tile 0, plane 0, symbol/pixel 18, bits `4:1..4:3`.

Both paths read the same state:

- `K=1`;
- `q=0`, `r=1`, `S=1`;
- signed residual `0` under the fixed BIT0 candidate mapping;
- predictor/value `6/6`.

Their next K differs:

- HYBRID: accumulator `6`, sample count `20` → `K 1→0` (`HYBRID_DECREASE`);
- D8: `K*=1`, bracket `0..2` → `K 1→1` (`HOLD`).

Classification: `HYBRID_ACCUMULATOR_DECREASE_VS_D8_HOLD`.

### PowerShot G5 X Mark II `asis`

First non-zero divergence: tile 0, plane 0, symbol/pixel 18, bits `3:4..4:2`.

Both paths read:

- `K=1`;
- `q=4`, `r=0`, `S=8`;
- signed residual `4`;
- predictor/value `0/4`.

Their next K moves in opposite directions:

- HYBRID: accumulator `3→7`, sample count `19→20`; magnitude `4` does not satisfy the strict `> 1<<(K+1)` increase test, then the accumulator test decreases `K 1→0`;
- D8: `K*=3`, bracket `2..4` → increases `K 1→2`.

Classification: `OTHER_K_DECISION` because the predeclared taxonomy intentionally did not collapse opposite-direction decisions into one of the narrower categories.

### `sample-iso-high.cr3`

First non-zero divergence: tile 0, plane 0, symbol/pixel 22, bits `3:2..3:6`.

Both paths read:

- `K=0`;
- `q=3`, `r=0`, `S=3`;
- signed residual `-1`;
- predictor/value `1/0`;
- preceding zero run length `5`.

Their next K differs:

- HYBRID: accumulator `1→2`, sample count `23→24` → holds `K=0`;
- D8: `K*=2`, bracket `1..3` → increases `K 0→1`.

Classification: `HYBRID_HOLD_VS_D8_INCREASE`.

## Verdict: **NO_COMMON_CAUSE**

No concrete classification occurs in at least two fixtures. D9 therefore does not authorize a D10 semantic decoder change.

The evidence narrows the problem but does not unify it:

- EOS R success begins where the history-dependent accumulator decreases but D8 remains inside its current-symbol bracket;
- G5 X II regression begins with an opposite-direction decision (`1→0` versus `1→2`);
- the completion-regressing sample begins where the accumulator holds but D8 raises K.

This confirms that replacing history-dependent adaptation with one universal current-symbol bracket is too coarse. It does not establish that the historical hybrid rule is correct.

## Production decision

No production decoder change. D9 is observation-only and leaves the experimental CRX decoder unchanged.

## Next step

Because no common cause exists, the next work must remain observation-only. The highest-information follow-up is a fixed-panel **K-transition matrix** that counts and localizes:

- HYBRID decrease / D8 hold;
- HYBRID decrease / D8 increase;
- HYBRID hold / D8 increase;
- other opposite-direction transitions;
- the first subsequent bit-position split;
- correlation with global, optical-black, boundary and completion deltas already recorded by D8.

A new semantic experiment is allowed only if one transition class consistently predicts the same failure mode across multiple fixtures. Do not combine thresholds, accumulator policy, bracket width or symbol estimate in one candidate.
