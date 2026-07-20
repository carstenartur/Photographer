# CR3 Current State

## Verified foundation

- CR3 container parsing, CMP1/IAD1/CRX extraction and subband inventory work.
- The fixed lossless panel has LibRaw-backed TIFF/PNG references and a validated manifest.
- Geometry, alignment, optical-black, per-plane and tile-boundary diagnostics exist.
- Stages 1–4 are verified; Stage 5 is partial; Stage 6 entropy decoding remains the earliest failure.

## Strong findings

- Optical-black regions remain the strongest falsification axis.
- MED is the best-supported predictor family. Pure N/W/NW swaps, tile-state continuation, cross-plane seeding and a general per-plane predictor split were rejected.
- D5 zig-zag sign mapping was rejected.
- D6 universal `floor(log2(S+1)) ± 1` K bracketing was rejected: median global MAE changed `1833.9213→3059.2298`, boundary error `2365.6625→3680.9709`, and completion regressed.

## D7 first-divergence result

D7 compared the unchanged D6 variants on `eosr-iso-mid.cr3` and `g5xm2-asis-iso200-ss130-ff2p5-01.cr3`.

Both diverge identically at tile 0, plane 0, pixel `(0,0)`, symbol 0:

- both read bits `0..2` with `K=1`;
- both decode `S=0`, residual 0 and pixel 0;
- HYBRID changes `K 1→0`;
- SYMBOL_BRACKET leaves `K=1`.

At the next zero symbol, HYBRID consumes only the unary terminator while D6 also consumes a remainder bit. The first evidenced cause is therefore **zero-symbol K contraction**, not a camera-specific rule.

Reports:

- [D5 sign mapping](cr3-results/2026-07-19-D5-sign-mapping-discriminator.md)
- [D6 K adaptation](cr3-results/2026-07-20-D6-k-adaptation-discriminator.md)
- [D7 first divergence](cr3-results/2026-07-20-D7-first-k-divergence.md)

## Current priority: D8

Change exactly one rule relative to D6:

```text
if S == 0 and K > 0:
    K = K - 1
else:
    apply the D6 symbol bracket unchanged
```

Reuse the exact fixed eleven-fixture panel and production gate. No production decoder change is allowed without a `SUPPORTED` result and no completion regression.

Do not repeat broad predictor, CFA, alignment, sign-mapping or universal bracket sweeps without new evidence.
