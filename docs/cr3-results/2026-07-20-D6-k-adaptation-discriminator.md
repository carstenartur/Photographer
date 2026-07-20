# D6 K-Adaptation Discriminator

## Hypothesis ID

D6 — replace the historical hybrid K-update rule with symbol-by-symbol movement toward a Golomb-Rice bracket derived from the current unsigned symbol.

## Goal

Test one Stage-6 entropy semantic while keeping every other candidate-decoder choice fixed. The experiment asks whether the following one-step rule improves the complete externally reference-backed lossless CR3 corpus:

```text
K*   = floor(log2(S + 1))
Kmin = max(0, K* - 1)
Kmax = min(12, K* + 1)

if K < Kmin: K = K + 1
if K > Kmax: K = K - 1
otherwise:   K unchanged
```

`S` is the current decoded unsigned Rice symbol.

## Fixtures used

The discriminator uses exactly the same eleven lossless CR3 fixtures and LibRaw-backed reference TIFFs as D5. The list is explicit in `KAdaptationDiscriminatorIT`, so later corpus growth cannot silently change medians or the verdict:

- three EOS R ISO variants;
- three PowerShot G5 X Mark II research fixtures (`asis`, `dark`, `flat`);
- `sample.cr3`, `sample-iso-high.cr3`, `sample-tele.cr3`, `sample-wide.cr3`, and `sample-wb-tungsten.cr3`.

The three predeclared discriminator fixtures were:

- `sample-tele.cr3`;
- `sample-wide.cr3`;
- `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3`.

## Method

`KAdaptationDiscriminatorIT` decoded every fixture twice:

1. **HYBRID** — the existing candidate rule: increase K from the current signed magnitude and decrease it from a running accumulator/sample-count threshold;
2. **SYMBOL_BRACKET** — the isolated D6 rule above.

Both variants retained exactly the same:

- `K_INIT = 1`;
- per-row K reset;
- unary direction `ones until zero`;
- BIT0 residual sign mapping;
- disabled unary escape;
- MED predictor and boundary handling;
- first-pixel seed `0`;
- K clamp `0..12`;
- tile/plane extraction and Bayer assembly.

The test generates deterministic Markdown, TSV and JSON evidence without a runtime timestamp. The final review-corrected execution was GitHub Actions run `29762696674` at commit `1d2d64e6c5ce25200ec3ac2d3ede475389c46fe5`. The uploaded artifact digest was:

```text
sha256:4d5301ae014e75175e88785b41969788baa2ae23cb14e49fb8e28ee71e829953
```

## Predeclared acceptance gate

`SUPPORTED` required all of the following:

- at least two of three discriminator fixtures improve both global MAE and optical-black MAE;
- median global MAE does not regress;
- median tile-boundary error does not regress;
- no fixture loses symbol completion or newly exhausts its input;
- median saturation does not increase.

## Verdict: **REJECTED**

- successful discriminator fixtures: **0 / 3**;
- median global MAE: **1833.9213 → 3059.2298**;
- median tile-boundary error: **2365.6625 → 3680.9709**;
- median saturation fraction: **0.0000 → 0.0000**;
- completion gate: **failed**.

The candidate is therefore not eligible for a production decoder change.

## Fixture-level metrics

| Fixture | Hybrid MAE | Bracket MAE | Hybrid OB MAE | Bracket OB MAE | Hybrid completion | Bracket completion | Result |
|---|---:|---:|---:|---:|---:|---:|---|
| `eosr-iso-high.cr3` | 1735.1697 | **1556.4496** | 1967.8177 | **1924.9936** | 1.0000 | 1.0000 | local improvement |
| `eosr-iso-low.cr3` | 1691.6085 | **1361.4614** | 1942.1394 | **1915.7542** | 1.0000 | 1.0000 | local improvement |
| `eosr-iso-mid.cr3` | 1733.2698 | **1344.2599** | 1966.0290 | **1896.5111** | 1.0000 | 1.0000 | local improvement |
| `g5xm2-asis…` | **1706.9891** | 4341.6692 | 1819.9539 | **1599.3876** | 1.0000 | 0.9991 | regression; newly exhausted |
| `g5xm2-dark…` | **1365.1349** | 2425.5717 | 1733.2005 | **1413.4515** | 1.0000 | 1.0000 | mixed, global regression |
| `g5xm2-flat…` | **9221.8301** | 14659.8654 | 5356.0063 | **4976.2903** | 0.0829 | 0.0486 | strong regression |
| `sample-iso-high.cr3` | 13296.6568 | **12223.7928** | 3430.9171 | **3331.0574** | 0.9319 | 0.7057 | error lower, completion worse |
| `sample-tele.cr3` | **2983.7212** | 3059.2298 | 2181.4054 | **1980.4199** | 1.0000 | 1.0000 | discriminator failure |
| `sample-wb-tungsten.cr3` | 13360.0214 | **11963.3773** | 3435.2810 | **3081.6247** | 0.9121 | 0.6861 | error lower, completion worse |
| `sample-wide.cr3` | **2987.3870** | 3254.9788 | 2167.5443 | **1954.3114** | 1.0000 | 1.0000 | discriminator failure |
| `sample.cr3` | **1833.9213** | 2835.5977 | 1936.2878 | **1725.4339** | 1.0000 | 1.0000 | global regression |

The generated artifact contains RMSE, Pearson, boundary, saturation and all per-plane metrics for both variants.

## Conclusion

The simple `floor(log2(S+1)) ± 1` K bracket is not a valid universal replacement under the current candidate pipeline. It reduces global and optical-black error on all three EOS R fixtures, but it strongly regresses the corpus and changes bit consumption enough to introduce or worsen stream exhaustion on several fixtures.

This result falsifies only the isolated rule under the fixed D5 candidate settings. It does **not** prove that the historical hybrid accumulator is correct, and it does not falsify the broader patent-described K-adaptation family. In particular, the current experiment still uses:

- one predictor family for all four planes;
- per-row K reset;
- current plane/channel interpretation;
- `Sn = Sx` without a deeper-level symbol.

Those were deliberately held constant to preserve isolation.

## Status update

- D6 simple symbol-bracket replacement: **REJECTED**;
- production decoder: unchanged;
- Stage 6 remains the earliest failing/unverified pipeline stage;
- the EOS-R-versus-G5-X-II split is a new diagnostic signal, not a support claim for model-specific entropy rules.

## Next step

Issue #224 adds a bounded K/bit-position divergence trace for one fixture where the candidate improves (`eosr-iso-mid.cr3`) and one where it fails (`g5xm2-asis-iso200-ss130-ff2p5-01.cr3`). It records, for the first divergence only:

- tile and plane identity;
- pixel coordinate;
- input bit position;
- decoded quotient, remainder and unsigned symbol;
- previous/current/next K;
- predictor and reconstructed value.

The trace compares HYBRID and SYMBOL_BRACKET without changing either decoder. Its purpose is to determine whether the split begins with different K state, different symbol interpretation, row reset, or a structural tile/plane boundary. The next semantic experiment must change exactly one evidenced cause rather than trying another broad K formula.
