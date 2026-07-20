# D12 Exact Golomb-Rice code-length K bracket

## Final verdict

**`PARTIAL`**

The exact finite-domain Golomb-Rice K bracket is source-correct, but substituting
it into the D8 decoder while retaining the current decoded symbol as the
candidate `Sn` does not pass the fixed-panel quality, completion or camera-family
gates.

No production decoder change is authorized.

## Evidence binding

- executed commit: `e939da3c7df851454d69f5b120bc7fb59cc8faf1`;
- CR3 Research Integration run: `29779232886`;
- artifact digest: `sha256:10ecb3506ad3f5265939534210f832e3e75c8f0a5585ec29c5cd068e31575c51`;
- fixed panel: the eleven paths in `KAdaptationFixtureSet.PANEL`;
- permitted sources: `SRC-001`, `SRC-007`, `SRC-008`, `SRC-009`.

## One changed variable

D8's non-zero range:

```text
K* = floor(log2(S+1))
Kmin = max(0, K*-1)
Kmax = min(K_MAX, K*+1)
```

was replaced by:

```text
L(S,K) = (S >> K) + 1 + K
minimumLength = min L(S,K), K in [0, K_MAX]
Kmin = lowest  K attaining minimumLength
Kmax = highest K attaining minimumLength
```

D8's explicit zero contraction, parser, predictor, sign mapping, K seed, row
reset, K domain, exhaustion, padding, geometry and assembly were unchanged.

## Gate result

| Gate | Result | Evidence |
|---|:---:|---|
| No fixture completion regression | fail | several incomplete fixtures changed adversely |
| No tile/plane completion regression | fail | `9` regressed streams |
| No newly exhausted stream | pass | `0` newly exhausted streams |
| Median global MAE non-regression | fail | `2415.055583 -> 5065.604043` |
| Median optical-black MAE non-regression | pass | `1927.434494 -> 1824.424003` |
| Median boundary MAE non-regression | fail | `2850.193666 -> 5073.395553` |
| Median saturation non-regression | fail | `0.000001 -> 0.000216` |
| Every camera family non-regressing | fail | EOS R, G5 X Mark II and sample series all fail |
| Sufficient positive evidence | partial | `6` fixtures improve on at least one primary metric; no fixture becomes complete |

Additional completion evidence:

- fixtures becoming complete: `0`;
- recovered tile/plane streams: `7`;
- regressed tile/plane streams: `9`;
- newly exhausted tile/plane streams: `0`;
- K decisions differing from D8: `6,873,844`.

## Family medians

| Family | D8 MAE | Exact MAE | D8 OB | Exact OB | D8 boundary | Exact boundary | Gate |
|---|---:|---:|---:|---:|---:|---:|:---:|
| EOS R | 1409.105295 | 4683.295512 | 1918.389046 | 1767.623736 | 1651.634294 | 4329.510119 | fail |
| G5 X Mark II research | 2415.055583 | 7274.075489 | 1791.889887 | 1650.832217 | 2850.193666 | 6846.962129 | fail |
| Sample series | 3002.547114 | 5325.827844 | 2082.404382 | 2572.468291 | 4630.577224 | 5073.395553 | fail |

## Fixture metrics

| Fixture | Rule | MAE | OB | Boundary | Saturation | Completion | Exhausted | K differences | Stream regressions | New exhaustions | Recoveries |
|---|---|---:|---:|---:|---:|---:|:---:|---:|---:|---:|---:|
| `eosr-iso-high.cr3` | D8 | 1409.105295 | 1918.389046 | 1651.634294 | 0.000000 | 1.000000 | no | 0 | 0 | 0 | 0 |
| `eosr-iso-high.cr3` | D12 | 4683.295512 | 1733.941762 | 5073.499010 | 0.000121 | 1.000000 | no | 946593 | 0 | 0 | 0 |
| `eosr-iso-low.cr3` | D8 | 1256.095477 | 1915.538102 | 1510.518258 | 0.000000 | 1.000000 | no | 0 | 0 | 0 | 0 |
| `eosr-iso-low.cr3` | D12 | 4803.035284 | 1767.623736 | 4208.387813 | 0.000216 | 1.000000 | no | 944194 | 0 | 0 | 0 |
| `eosr-iso-mid.cr3` | D8 | 1450.263363 | 1927.434494 | 2008.164760 | 0.000000 | 1.000000 | no | 0 | 0 | 0 | 0 |
| `eosr-iso-mid.cr3` | D12 | 3867.158034 | 1824.424003 | 4329.510119 | 0.000117 | 1.000000 | no | 944205 | 0 | 0 | 0 |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | D8 | 2415.055583 | 1791.889887 | 2850.193666 | 0.000060 | 1.000000 | no | 0 | 0 | 0 | 0 |
| `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | D12 | 7274.075489 | 1650.832217 | 6846.962129 | 0.002513 | 1.000000 | no | 544384 | 0 | 0 | 0 |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | D8 | 1186.195090 | 1635.103659 | 1195.980323 | 0.000000 | 1.000000 | no | 0 | 0 | 0 | 0 |
| `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3` | D12 | 3423.805157 | 1638.608138 | 2468.928167 | 0.001356 | 1.000000 | no | 484845 | 0 | 0 | 0 |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | D8 | 15243.076973 | 5314.496759 | 15328.944070 | 0.000001 | 0.068705 | yes | 0 | 0 | 0 | 0 |
| `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3` | D12 | 8837.273754 | 5973.256995 | 10454.205391 | 0.000014 | 0.068153 | yes | 49319 | 6 | 0 | 2 |
| `sample-iso-high.cr3` | D8 | 12313.287089 | 3249.157354 | 13583.209838 | 0.000013 | 0.836487 | yes | 0 | 0 | 0 | 0 |
| `sample-iso-high.cr3` | D12 | 8597.467299 | 4889.636142 | 10271.061456 | 0.000148 | 0.836642 | yes | 520477 | 1 | 0 | 3 |
| `sample-tele.cr3` | D8 | 3002.547114 | 2082.404382 | 4630.577224 | 0.000011 | 1.000000 | no | 0 | 0 | 0 | 0 |
| `sample-tele.cr3` | D12 | 5325.827844 | 1870.534809 | 5073.395553 | 0.000884 | 1.000000 | no | 666036 | 0 | 0 | 0 |
| `sample-wb-tungsten.cr3` | D8 | 12331.460625 | 3189.431891 | 13898.082480 | 0.000006 | 0.819850 | yes | 0 | 0 | 0 | 0 |
| `sample-wb-tungsten.cr3` | D12 | 9781.365881 | 3092.525067 | 11552.727358 | 0.000077 | 0.819787 | yes | 510230 | 2 | 0 | 2 |
| `sample-wide.cr3` | D8 | 2911.059296 | 2080.202748 | 3008.323315 | 0.000014 | 1.000000 | no | 0 | 0 | 0 | 0 |
| `sample-wide.cr3` | D12 | 5065.604043 | 2572.468291 | 4992.519272 | 0.000735 | 1.000000 | no | 664021 | 0 | 0 | 0 |
| `sample.cr3` | D8 | 1456.046075 | 1802.005192 | 2405.503235 | 0.000000 | 1.000000 | no | 0 | 0 | 0 | 0 |
| `sample.cr3` | D12 | 3945.474232 | 1746.328835 | 3095.080728 | 0.000437 | 1.000000 | no | 599540 | 0 | 0 | 0 |

## Interpretation

The source-backed optimum range is not itself a universal fix when driven by
the currently decoded symbol. The most consistent positive signal is lower
optical-black error for the EOS R family and selected other fixtures. At the
same time, global and boundary errors grow sharply and completion becomes mixed
inside incomplete fixtures.

This pattern is consistent with D11's distinction between the exact bracket and
the still-unresolved tentative-symbol estimator. The patent updates K from a
tentative succeeding symbol `Sn`, which can depend on `Sx`, `Sd`, subband class
and a later `Kd` correction. D12 deliberately did not implement those pieces.

The next work must therefore be observation/source analysis of `Sn`, `Sd`,
`Kd` and subband identity. It must not promote the D12 current-symbol candidate
or tune the K domain after the fact.

## Durable machine-readable evidence

The full generated TSV and JSON are bound by the workflow artifact digest above.
The TSV is committed alongside this report so per-plane and completion metrics
remain reviewable after normal Actions artifacts expire.

## Production decision

**No production decoder change.** D12 remains a test-scope `PARTIAL` result.
