# D7 First K / Bit-Position Divergence

## Goal

Explain the earliest state difference between the rejected D6 `HYBRID_ACCUMULATOR` and `SYMBOL_BRACKET` variants without changing either decoder.

Fixed fixtures:

- `eosr-iso-mid.cr3`, where D6 improved without completion loss;
- `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3`, where D6 regressed and newly exhausted the stream.

## Method

A bounded test-scope trace records tile, plane, pixel, byte:bit positions, K before/after, quotient, remainder, unsigned and signed symbol, predictor and reconstructed value. Neutrality tests prove tracing does not change output, completion or byte consumption.

Initial executed evidence: GitHub Actions run `29762827201`, commit `b8600c2c261b0ae5368a25c6d8bf0a0c6af8364a`, artifact digest:

```text
sha256:5c3b777934a8a3205bbe04885230acfa275fb92ab198c58f0083e76003428480
```

## Result

Both fixtures diverge at the same location:

- tile 0, plane 0, pixel `(0,0)`, symbol 0;
- bit range `0..2` (`0:0` through `0:2`);
- `K=1`, quotient/remainder `0/0`;
- unsigned symbol `S=0`;
- residual, predictor and reconstructed value `0`.

Only the next K differs:

| Variant | K before | S | K after |
|---|---:|---:|---:|
| HYBRID | 1 | 0 | 0 |
| SYMBOL_BRACKET | 1 | 0 | 1 |

Classification: **K_STATE_BEFORE_DIFFERENT_BITS**.

At the next zero symbol, HYBRID consumes only the unary terminator while D6 also consumes one remainder bit. The bit positions therefore separate immediately.

## Conclusion

The first D6 divergence is not camera-specific. The evidenced cause is the treatment of `S=0`: the universal bracket accepts K=1, while the historical rule contracts to K=0. Long zero runs amplify that one-bit-per-symbol difference and alter all later symbols.

D7 status: **COMPLETE**. Production decoder behavior remains unchanged.

## Next experiment: D8

Change exactly one rule relative to D6:

```text
if S == 0 and K > 0:
    K = K - 1
else:
    apply the D6 bracket unchanged
```

This preserves the observed zero-run contraction and isolates the bracket to non-zero symbols. Reuse the exact fixed eleven-fixture panel and production gate.
