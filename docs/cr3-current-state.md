# CR3 Current State

## Verified foundation

- CR3 container parsing, CMP1/IAD1/CRX extraction and subband inventory work.
- The fixed lossless panel has manifest-bound LibRaw TIFF/PNG references.
- Geometry, alignment, optical-black, per-plane, boundary and tile/plane completion diagnostics exist.
- Stages 1–4 are verified; Stage 5 is partial; Stage 6 entropy reconstruction remains the earliest failure.
- Research follows the auditable process in [`docs/cleanroom/`](cleanroom/README.md).

## Durable findings

- Optical-black regions remain the strongest falsification axis.
- MED is the best-supported predictor family. Pure N/W/NW swaps, tile-state continuation, cross-plane seeding and a general per-plane predictor split were rejected.
- D5 rejected conventional zig-zag residual mapping.
- D6 rejected a universal `floor(log2(S+1)) ± 1` K bracket.
- D7 located D6's first difference at an identical `S=0` and divergent K state.
- D8 showed that zero contraction is useful but insufficient.
- D9 found no common first non-zero decision cause.
- D10 found no transition class that predicted failure without contradicting complete-improvement fixtures.
- D11 established the exact public-primary-source base bracket: minimise `L(S,K)=(S>>K)+1+K` over a finite K domain and move K by at most one toward the optimum range.
- D11 did not establish CRX subband mapping, tentative-symbol context, reset state or the complete `Kd` correction.

## D12 result

D12 changed exactly one test-decoder variable: D8's approximate non-zero bracket was replaced by D11's exact finite-domain code-length range. Zero contraction, parser, sign mapping, predictor, K seed/reset, geometry, padding and production code were unchanged.

Final verdict: **PARTIAL**.

| Metric | D8 | D12 exact bracket | Gate |
|---|---:|---:|:---:|
| Median global MAE | 2415.055583 | 5065.604043 | fail |
| Median optical-black MAE | 1927.434494 | 1824.424003 | pass |
| Median boundary MAE | 2850.193666 | 5073.395553 | fail |
| Median saturation | 0.000001 | 0.000216 | fail |

Additional evidence:

- six fixtures improve on at least one primary quality metric;
- no previously incomplete fixture becomes complete;
- nine tile/plane streams regress in completion;
- zero streams become newly exhausted;
- seven streams recover partially;
- all three camera-family median gates fail;
- 6,873,844 K decisions differ from D8.

The exact bracket is therefore not a universal fix when driven by the currently decoded symbol. The lower optical-black median is useful positive evidence, but it is contradicted by global, boundary, saturation, family and completion failures.

The durable report is [D12 exact K bracket](cr3-results/2026-07-20-D12-exact-k-bracket.md), with full per-plane evidence in the adjacent TSV. Production remains unchanged.

## Current priority: D13 context resolution

Before another semantic candidate, resolve the source and structural meaning of the patent's tentative-symbol inputs:

- `Sx` and `Sd` for the LL embodiment;
- arithmetic/rounding and causal boundary behavior of `(Sx+Sd)/2`;
- complete `Kd` correction and clamp;
- mapping of current CRX records to LL/HL/LH/HH and decomposition levels;
- whether the required context is causal and consistent across EOS R, G5 X Mark II and sample fixtures.

D13 is source analysis and deterministic topology observation only. It must not select a mapping by image MAE, brute-force neighbours or combine `Sd`, `Kd`, resets and predictor changes.

## Durable results

- [D5 sign mapping](cr3-results/2026-07-19-D5-sign-mapping-discriminator.md)
- [D6 K adaptation](cr3-results/2026-07-20-D6-k-adaptation-discriminator.md)
- [D7 first divergence](cr3-results/2026-07-20-D7-first-k-divergence.md)
- [D8 zero-contracted K](cr3-results/2026-07-20-D8-zero-contracted-k.md)
- [D9 first non-zero K divergence](cr3-results/2026-07-20-D9-first-nonzero-k-divergence.md)
- [D10 transition/outcome matrix](cr3-results/2026-07-20-D10-k-transition-outcome-matrix.md)
- [D11 Canon K source audit](cr3-results/2026-07-20-D11-canon-k-source-audit.md)
- [D12 exact K bracket](cr3-results/2026-07-20-D12-exact-k-bracket.md)

Do not repeat broad predictor, CFA, alignment, sign-mapping, transition-frequency or current-symbol K-bracket sweeps without new structural evidence.
