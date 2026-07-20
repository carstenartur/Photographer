# D8 Zero-Contracted K Discriminator

## Hypothesis ID

D8 — preserve K contraction for zero symbols and apply the D6 symbol bracket only to non-zero unsigned symbols.

## Goal

D7 showed that HYBRID and D6 first diverged after decoding the same initial `S=0`: HYBRID changed `K 1→0`, while D6 retained `K=1`. D8 tests exactly one correction:

```text
if S == 0 and K > 0:
    K = K - 1
else:
    apply the unchanged D6 floor(log2(S+1)) ± 1 bracket
```

## Fixed inputs and decoder choices

The experiment uses exactly the eleven reference-backed lossless CR3 fixtures from D5/D6:

- three EOS R ISO fixtures;
- three PowerShot G5 X Mark II fixtures (`asis`, `dark`, `flat`);
- `sample.cr3`, `sample-iso-high.cr3`, `sample-tele.cr3`, `sample-wide.cr3`, and `sample-wb-tungsten.cr3`.

HYBRID and D8 keep identical:

- `K_INIT = 1`;
- per-row reset;
- unary direction `ones until zero`;
- BIT0 residual mapping;
- unary escape disabled;
- MED predictor and seed 0;
- K clamp `0..12`;
- tile/plane extraction and Bayer assembly.

The test-only D8 decoder delegates every non-zero K transition to the unchanged D6 bracket implementation.

## Execution evidence

The final run followed review correction of the exhaustion/padding path so D8 and the baseline use equivalent terminal semantics.

- commit: `c9dc1bcdf65fb92fa888550eea0bbf381b88a1de`;
- GitHub Actions run: `29767975996`;
- artifact: `cr3-research-evidence`;
- artifact digest:

```text
sha256:f101ebc2eb9f647844f3578a719a0c433e5b1fcd100dd7f17c2da403e6d6a90f
```

The earlier artifact from commit `dee2834...` is superseded because its D8 padding behavior differed from the baseline and could bias completion-sensitive metrics.

## Predeclared gate

`SUPPORTED` required:

- at least two of three discriminator fixtures improving global and optical-black MAE;
- non-regressing corpus median MAE;
- non-regressing median tile-boundary error;
- no lower completion or newly exhausted fixture;
- non-increasing median saturation.

## Verdict: **PARTIAL**

- successful discriminator fixtures: **1 / 3**;
- median global MAE: **1833.9213 → 2415.0556**;
- median tile-boundary error: **2365.6625 → 2850.1937**;
- median saturation: **0.0000 → 0.0000**;
- completion gate: **failed**.

D8 is not eligible for a production decoder change.

## Key fixture results

| Fixture group | Result |
|---|---|
| EOS R low/mid/high | Global and optical-black MAE improve on all three; completion remains 1.0 |
| G5 X II `asis` | D6's new exhaustion is removed and completion returns to 1.0, but global and boundary error regress |
| G5 X II `dark` | Global, optical-black and boundary errors improve at full completion |
| G5 X II `flat` | Strong global/boundary regression and completion drops from 0.0829 to 0.0687 |
| `sample-wide.cr3` | Global, optical-black and boundary errors improve at full completion |
| `sample.cr3` | Global and optical-black errors improve, boundary error regresses |
| `sample-tele.cr3` | Optical-black improves but global and boundary errors regress |
| `sample-iso-high.cr3` / `sample-wb-tungsten.cr3` | Global/OB errors improve but completion regresses substantially |

## Conclusion

D7 identified a real necessary correction: zero symbols must contract K under the current candidate boundary. D8 confirms that correction is useful because it restores complete bit consumption for the G5 X II `asis` fixture and improves several complete fixtures.

However, applying the current-symbol D6 bracket to every non-zero symbol remains invalid. The remaining failure begins after the aligned zero run and is therefore narrower than D6:

- zero-symbol handling is no longer the first problem;
- the next uncertainty is the first non-zero K transition and the symbol estimate used for it;
- the current evidence does not support a camera-model-specific rule.

## Next step

D9 must trace HYBRID versus D8 until the first **non-zero** symbol that produces a different K transition or subsequent bit position. Use fixed representatives for:

- a complete improvement: `eosr-iso-low.cr3`;
- complete but globally regressing behavior: `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3`;
- completion regression: `sample-iso-high.cr3`.

Record the zero-run length, first non-zero symbol, K before/after, accumulator state on HYBRID, bracket bounds on D8, and exact byte:bit positions. No production code changes.
