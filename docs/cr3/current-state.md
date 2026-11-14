# CR3 Current State

This is the maintained implementation/research truth boundary. Module APIs and executable tests define behavior; the dated result documents linked below provide the evidence behind this summary.

## Verified foundation

- CR3 container parsing, CMP1/IAD1/CRX extraction and subband inventory work.
- The fixed lossless panel has manifest-bound external TIFF/PNG references.
- Geometry, alignment, optical-black, per-plane, boundary and tile/plane completion diagnostics exist through the shared reference testkit.
- Pipeline stages 1–3 are verified; Stage 4 structural framing is partial; Stage 6 entropy reconstruction is the earliest verified semantic failure.
- Inverse-wavelet reconstruction is not reached for the lossless panel because correct entropy output is still unavailable.
- Research follows the auditable process in [Clean-room engineering](../cleanroom/README.md) and [Research](research.md).

## Durable entropy findings

- Optical-black regions remain the strongest physical falsification axis.
- MED is the best-supported predictor family.
- Pure N/W/NW swaps, tile-state continuation, cross-plane seeding and a general per-plane predictor split were rejected.
- D5 rejected replacing the residual mapping throughout the previous decoder. RG3 separately measured signed folding for two opening values.
- D6 rejected a universal `floor(log2(S+1)) ± 1` K bracket.
- D7 located D6's first difference at an identical `S=0` and divergent K state.
- D8 showed zero contraction is useful but insufficient.
- D9 found no common first non-zero decision cause.
- D10 found no transition class that predicted failure without contradicting complete-improvement fixtures.
- D11 established the exact public-primary-source base bracket: minimise `L(S,K)=(S>>K)+1+K` over a finite K domain and move K by at most one toward the optimum range.
- D12 showed the exact range is not sufficient when driven by the currently decoded symbol.
- D13 found no source-supported and causally representable `Sd`/`Kd` mapping in the fixed corpus.
- RG1 showed that correcting only the first-sample seed does not repair the stream: the first content-bearing residual is already interpreted incorrectly in most records.

## D13 topology boundary

Final verdict: **PRIMARY_SOURCE_OR_MAPPING_INSUFFICIENT**.

The deterministic fixed-panel inventory found:

- 11 fixtures and 88 records;
- 220,347,400 extracted payload bytes;
- all 88 records with `waveletLevels=0` and `subband=0`;
- 77 byte-contiguous record transitions, with no gaps or overlaps;
- one internally uniform topology per camera family;
- two geometry/topology variants across the whole panel.

The fixed panel proves a stable lossless two-tile/four-plane/single-subband organization and real payload continuity. It cannot expose a deeper wavelet parent, LL/HL/LH/HH identity, a causal `Sd` source or a `Kd` lifecycle. File order is not treated as proof of decoder-time causality.

The durable report is [D13 context and topology](../cr3-results/2026-07-20-D13-context-topology.md).

## RG1 LibRaw-guided first divergence

Final verdict: **OBSERVATION COMPLETE; SEMANTICS STILL INSUFFICIENT**.

RG1 connected the unchanged research symbol trace to the hash-bound LibRaw `RAW_LINEAR` mosaics. It used two explicitly labelled modes:

- `CONFIGURED_BASELINE` retains the historical first-pixel seed `0`;
- `REFERENCE_FIRST_SAMPLE_ORACLE` supplies only the correct first reference sample to neutralize the known DC/pedestal mismatch. This is a diagnostic oracle, not a decoder proposal.

Across the same 11 fixtures and 88 tile/plane streams:

- the configured baseline diverges at symbol 0 in 88/88 streams;
- the oracle-seeded matched prefix is 1–6 samples, median 1;
- 71/88 streams diverge at symbol 1;
- at every oracle-seeded first divergence, the candidate predictor agrees with the reference-derived MED predictor;
- the candidate decodes signed residual `0`, while the reference requires a non-zero positive or negative residual.

A scalar pedestal is therefore not the sole missing semantic. The passive reference tells us what the correct sensor value is, but not which private state transition or symbol grammar produced it. More fitted K/predictor formulas against the same files would not add causal evidence.

The durable report is [RG1 LibRaw-guided first divergence](../cr3-results/2026-07-23-RG1-libraw-guided-first-divergence.md).

## Measured stream prefix and C-RAW reference values

RG2 reproduced RG1 on 88 streams. Consuming the patent-derived conditional flag at offset zero shifted the residual reader by one bit and left all 88 first errors unchanged. Every payload had its first one at offset 10. Flipping bit zero made the external reference reject each of two controlled inputs. The meaning of the ten-zero prefix remains open.

LightCraft and DNGLab agreed on every Bayer value of the public EOS R and PowerShot G5 X Mark II C-RAW fixtures. Photographer currently validates their headers and returns its C-RAW unsupported result. See the [RG2 experiment](../cr3-results/2026-11-14-crx/README.md).

## First two native lossless samples

The RG3 research reader derives both values in a plane directly from the payload. It matched all 88 discovery streams and all 32 prospective streams from four cameras. Changing each of bits 18–31 on two cameras and swapping that range between them gave the exact predicted first values. The fixed prefix conditions this result. Changes to earlier bits can also affect the first value. All four second-code remainder controls were rejected. See the [RG3 measurements](../cr3-results/2026-11-14-crx-opening/README.md).

## Current priority

Start the next lossless experiment at RG3's recorded end-bit cursor and the third same-plane site. General entropy adaptation, complete rows and later boundaries remain open. An additional evidence-acquisition path is the controlled spatial-code corpus in issue #249:

- the deterministic screen/print pattern pack and manifests exist;
- duplicate captures, camera/display state records and payload observations are still required;
- the JPEG or embedded preview is the observable calibration channel for whether and where the camera detected the controlled stimulus;
- CRX changes must be separated from duplicate noise and correlated with the known stimulus;
- an adaptive closed loop may choose the next pattern or intensity, but it must preserve the same manifests, provenance and predeclared decision boundaries.

A completed capture corpus can justify a later semantic hypothesis. The pattern assets alone, ordinary additional photographs, or another passive LibRaw metric sweep cannot.

Do not repeat current-symbol K rules, neighbor-coordinate searches, subband permutations, broad predictor sweeps, CFA/alignment sweeps or combined `Sd`/`Kd`/reset experiments on the same fixed panel.

## Stable product boundary

The research block does not invalidate supported CR3 functionality:

- container/track parsing and embedded JPEG extraction remain supported;
- structured metadata remains supported;
- standard ImageIO preview, thumbnail, discovery and metadata paths remain supported;
- full RAW/CRX modes remain experimental;
- production has not adopted D12, RG1 oracle initialization or another unsupported entropy variant.

See [CR3 overview](index.md), [ImageIO](imageio.md) and [Decoder](decoder.md).

## Durable results

- [Reference comparison baseline](../cr3-results/reference-comparison.md)
- [Pipeline stage audit](../cr3-results/2026-07-03-cr3-pipeline-stage-audit.md)
- [D5 sign mapping](../cr3-results/2026-07-19-D5-sign-mapping-discriminator.md)
- [D6 K adaptation](../cr3-results/2026-07-20-D6-k-adaptation-discriminator.md)
- [D7 first divergence](../cr3-results/2026-07-20-D7-first-k-divergence.md)
- [D8 zero-contracted K](../cr3-results/2026-07-20-D8-zero-contracted-k.md)
- [D9 first non-zero K divergence](../cr3-results/2026-07-20-D9-first-nonzero-k-divergence.md)
- [D10 transition/outcome matrix](../cr3-results/2026-07-20-D10-k-transition-outcome-matrix.md)
- [D11 Canon K source audit](../cr3-results/2026-07-20-D11-canon-k-source-audit.md)
- [D12 exact K bracket](../cr3-results/2026-07-20-D12-exact-k-bracket.md)
- [D13 context and topology](../cr3-results/2026-07-20-D13-context-topology.md)
- [RG1 LibRaw-guided first divergence](../cr3-results/2026-07-23-RG1-libraw-guided-first-divergence.md)

The curated chronology is in [Results](reports/index.md).
