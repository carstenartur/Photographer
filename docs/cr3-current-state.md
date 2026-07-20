# CR3 Current State

## Verified foundation

- CR3 container parsing, CMP1/IAD1/CRX extraction and subband inventory work.
- The fixed lossless panel has LibRaw-backed TIFF/PNG references and a validated manifest.
- Geometry, alignment, optical-black, per-plane and tile-boundary diagnostics exist.
- Stages 1–4 are verified; Stage 5 is partial; Stage 6 entropy decoding remains the earliest failure.

## Strong findings

- Optical-black regions remain the strongest falsification axis.
- MED is the best-supported predictor family. Pure N/W/NW swaps, tile-state continuation, cross-plane seeding and a general per-plane predictor split were rejected.
- D5 conventional zig-zag residual mapping was rejected.
- D6 universal `floor(log2(S+1)) ± 1` K bracketing was rejected.
- D7 located the first D6 divergence: after the same initial `S=0`, HYBRID contracts `K 1→0` while D6 keeps `K=1`.
- D8 confirms zero-symbol contraction is necessary under the current candidate boundary, but not sufficient for a correct non-zero K update.

## D8 result

D8 uses:

```text
if S == 0 and K > 0:
    K = K - 1
else:
    apply the unchanged D6 symbol bracket
```

Final fixed-panel verdict: **PARTIAL**.

- discriminator successes: `1/3`;
- median global MAE: `1833.9213→2415.0556`;
- median boundary error: `2365.6625→2850.1937`;
- median saturation unchanged at `0.0000`;
- completion gate failed.

Important positive evidence:

- all three EOS R fixtures improve global and optical-black MAE at complete decoding;
- G5 X II `dark` improves globally, in optical black and at boundaries;
- `sample-wide.cr3` improves globally, in optical black and at boundaries;
- the G5 X II `asis` fixture no longer newly exhausts as it did under D6.

Remaining failures:

- G5 X II `asis` is complete but global and boundary errors regress;
- G5 X II `flat`, `sample-iso-high` and `sample-wb-tungsten` lose completion;
- `sample-tele` and several boundary metrics regress.

The final evidence is recorded in [D8 zero-contracted K](cr3-results/2026-07-20-D8-zero-contracted-k.md). Production remains unchanged.

## Current priority: D9

Trace HYBRID versus D8 through the now-aligned zero run and stop at the first **non-zero** symbol that changes K or subsequent bit consumption.

Use:

- `eosr-iso-low.cr3` — complete improvement;
- `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` — complete but global regression;
- `sample-iso-high.cr3` — completion regression.

Record zero-run length, first non-zero symbol, exact byte:bit positions, K before/after, HYBRID accumulator/sample count, D8 bracket bounds, predictor and reconstructed value. Do not change production or combine another semantic hypothesis with the trace.

## Durable results

- [D5 sign mapping](cr3-results/2026-07-19-D5-sign-mapping-discriminator.md)
- [D6 K adaptation](cr3-results/2026-07-20-D6-k-adaptation-discriminator.md)
- [D7 first divergence](cr3-results/2026-07-20-D7-first-k-divergence.md)
- [D8 zero-contracted K](cr3-results/2026-07-20-D8-zero-contracted-k.md)

Do not repeat broad predictor, CFA, alignment, sign-mapping or universal bracket sweeps without new structural evidence.
