# CR3 Current State

## Verified foundation

- CR3 container parsing, CMP1/IAD1/CRX extraction and subband inventory work.
- The fixed lossless panel has manifest-bound external TIFF/PNG references.
- Geometry, alignment, optical-black, per-plane, boundary and tile/plane completion diagnostics exist.
- Stages 1–3 are verified; Stage 4 structural framing is partial; Stage 6 entropy reconstruction is the earliest verified semantic failure. Inverse wavelet reconstruction is not reached for the lossless panel because correct entropy output is still unavailable.
- Research follows the auditable process in [`docs/cleanroom/`](cleanroom/README.md).

## Durable entropy findings

- Optical-black regions remain the strongest falsification axis.
- MED is the best-supported predictor family. Pure N/W/NW swaps, tile-state continuation, cross-plane seeding and a general per-plane predictor split were rejected.
- D5 rejected conventional zig-zag residual mapping.
- D6 rejected a universal `floor(log2(S+1)) ± 1` K bracket.
- D7 located D6's first difference at an identical `S=0` and divergent K state.
- D8 showed that zero contraction is useful but insufficient.
- D9 found no common first non-zero decision cause.
- D10 found no transition class that predicted failure without contradicting complete-improvement fixtures.
- D11 established the exact public-primary-source base bracket: minimise `L(S,K)=(S>>K)+1+K` over a finite K domain and move K by at most one toward the optimum range.
- D12 showed that the exact range is not sufficient when driven by the currently decoded symbol.
- D13 found no source-supported and causally representable `Sd`/`Kd` mapping in the current evidence.

## D12 result

D12 changed exactly one test-decoder variable: D8's approximate non-zero bracket was replaced by D11's exact finite-domain code-length range. Zero contraction, parser, sign mapping, predictor, K seed/reset, geometry, padding and production code were unchanged.

Final verdict: **PARTIAL**.

| Metric | D8 | D12 exact bracket | Gate |
|---|---:|---:|:---:|
| Median global MAE | 2415.055583 | 5065.604043 | fail |
| Median optical-black MAE | 1927.434494 | 1824.424003 | pass |
| Median boundary MAE | 2850.193666 | 5073.395553 | fail |
| Median saturation | 0.000001 | 0.000216 | fail |

Six fixtures improve on at least one primary metric, but nine tile/plane streams regress, no incomplete fixture becomes complete and every camera-family gate fails. Production remains unchanged.

## D13 result

Final verdict: **PRIMARY_SOURCE_OR_MAPPING_INSUFFICIENT**.

The deterministic fixed-panel inventory found:

- 11 fixtures and 88 records;
- 220,347,400 extracted payload bytes;
- all 88 records with `waveletLevels=0` and `subband=0`;
- 77 byte-contiguous record transitions, with no gaps or overlaps;
- one internally uniform topology per camera family;
- two geometry/topology variants across the whole panel.

The fixed panel therefore proves a stable lossless two-tile/four-plane/single-subband organization and real payload continuity. It cannot expose a deeper wavelet parent, LL/HL/LH/HH identity, a causal `Sd` source or a `Kd` lifecycle. File order is not treated as proof of decoder-time causality.

The durable report is [D13 context and topology](cr3-results/2026-07-20-D13-context-topology.md). No D14 semantic experiment is authorized.

## Current priority

RAW work is blocked on genuinely new evidence, not on another fitted formula. Useful next inputs would be one of:

- a legally usable multi-subband CRX corpus with independently established per-subband geometry and ordering;
- a public primary source that fully specifies `Sd`, `Kd`, initialization, rounding, clamps and boundaries;
- new first-party structural observations that identify causal context without selecting by image quality.

Do not repeat current-symbol K rules, neighbour-coordinate searches, subband permutations, broad predictor sweeps, CFA/alignment sweeps or combined `Sd`/`Kd`/reset experiments.

## Durable results

- [D5 sign mapping](cr3-results/2026-07-19-D5-sign-mapping-discriminator.md)
- [D6 K adaptation](cr3-results/2026-07-20-D6-k-adaptation-discriminator.md)
- [D7 first divergence](cr3-results/2026-07-20-D7-first-k-divergence.md)
- [D8 zero-contracted K](cr3-results/2026-07-20-D8-zero-contracted-k.md)
- [D9 first non-zero K divergence](cr3-results/2026-07-20-D9-first-nonzero-k-divergence.md)
- [D10 transition/outcome matrix](cr3-results/2026-07-20-D10-k-transition-outcome-matrix.md)
- [D11 Canon K source audit](cr3-results/2026-07-20-D11-canon-k-source-audit.md)
- [D12 exact K bracket](cr3-results/2026-07-20-D12-exact-k-bracket.md)
- [D13 context and topology](cr3-results/2026-07-20-D13-context-topology.md)
