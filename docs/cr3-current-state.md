# CR3 Current State

## Verified foundation

- CR3 container parsing, CMP1/IAD1/CRX extraction and subband inventory work.
- The fixed lossless panel has LibRaw-backed TIFF/PNG references and a validated manifest.
- Geometry, alignment, optical-black, per-plane and tile-boundary diagnostics exist.
- Stages 1–4 are verified; Stage 5 is partial; Stage 6 entropy decoding remains the earliest failure.
- CR3/CRX research now follows the auditable clean-room process in [`docs/cleanroom/`](cleanroom/README.md).

## Strong findings

- Optical-black regions remain the strongest falsification axis.
- MED is the best-supported predictor family. Pure N/W/NW swaps, tile-state continuation, cross-plane seeding and a general per-plane predictor split were rejected.
- D5 conventional zig-zag residual mapping was rejected.
- D6 universal `floor(log2(S+1)) ± 1` K bracketing was rejected.
- D7 located the first D6 divergence: after the same initial `S=0`, HYBRID contracts `K 1→0` while D6 keeps `K=1`.
- D8 confirms zero-symbol contraction is necessary under the current candidate boundary, but not sufficient for a correct non-zero K update.
- D9 found no common first non-zero K-decision cause across one EOS-R improvement, one G5-X-II regression and one completion-regressing sample.

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

Zero contraction removes D6's new exhaustion of the G5 X II `asis` fixture and improves several complete fixtures, but the current non-zero bracket remains invalid as a universal replacement.

## D9 result

D9 observes unchanged HYBRID and D8 rules through one shared parser and stops at the first non-zero divergence.

| Fixture | First non-zero divergence |
|---|---|
| `eosr-iso-low.cr3` | HYBRID decreases `K 1→0`; D8 holds `K=1` for `S=1` |
| `g5xm2-asis…` | HYBRID decreases `K 1→0`; D8 increases `K 1→2` for `S=8` |
| `sample-iso-high.cr3` | HYBRID holds `K=0`; D8 increases `K 0→1` for `S=3` |

Verdict: **NO_COMMON_CAUSE**.

No concrete predeclared classification occurs in at least two fixtures. D9 therefore does not authorize a semantic decoder change. The history-dependent HYBRID rule and current-symbol D8 bracket disagree for different reasons in different fixtures.

The durable report is [D9 first non-zero K divergence](cr3-results/2026-07-20-D9-first-nonzero-k-divergence.md). Production remains unchanged.

## Current priority

The next work must remain observation-only: build a fixed-panel K-transition matrix and correlate transition classes with D8's global, optical-black, boundary and completion deltas.

Record at least:

- HYBRID decrease / D8 hold;
- HYBRID decrease / D8 increase;
- HYBRID hold / D8 increase;
- opposite-direction transitions;
- first subsequent bit-position split;
- fixture outcome class.

A new semantic experiment is allowed only if one transition class consistently predicts the same failure mode across multiple fixtures. Do not combine accumulator thresholds, bracket width, symbol estimate or reset scope in one candidate.

## Durable results

- [D5 sign mapping](cr3-results/2026-07-19-D5-sign-mapping-discriminator.md)
- [D6 K adaptation](cr3-results/2026-07-20-D6-k-adaptation-discriminator.md)
- [D7 first divergence](cr3-results/2026-07-20-D7-first-k-divergence.md)
- [D8 zero-contracted K](cr3-results/2026-07-20-D8-zero-contracted-k.md)
- [D9 first non-zero K divergence](cr3-results/2026-07-20-D9-first-nonzero-k-divergence.md)

Do not repeat broad predictor, CFA, alignment, sign-mapping or universal bracket sweeps without new structural evidence.
