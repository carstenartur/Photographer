# CR3 Current State

This document summarizes the current consensus after the Phase-A research work and the subsequent reference-backed discriminator experiments.

## Established facts

- CR3 container parsing works.
- CMP1, IAD1 and CRX header extraction work.
- Fixture inventory, subband extraction, geometry diagnostics and alignment diagnostics exist.
- The repository contains both lossless RAW and C-RAW research fixtures.
- The corpus is structurally saturated for many broad decoder sweeps.
- All current lossless CR3 fixtures have LibRaw-backed reference TIFF/PNG sidecars plus a manifest.
- `Cr3ReferenceComparisonIT` generates reference-vs-decoder metrics and visual reports in `docs/cr3-results/reference-comparison.md`.
- The complete 11-fixture D5 sign-mapping discriminator is recorded in [`2026-07-19-D5-sign-mapping-discriminator.md`](cr3-results/2026-07-19-D5-sign-mapping-discriminator.md).
- The complete 11-fixture D6 K-adaptation discriminator is recorded in [`2026-07-20-D6-k-adaptation-discriminator.md`](cr3-results/2026-07-20-D6-k-adaptation-discriminator.md).

## Strong findings

- Optical-black (IAD1 OB) regions are the strongest currently available falsification axis.
- The current decoder output is physically implausible in OB regions.
- CRX_SMALL and CRX_BIG use different CFA layouts and this has already been documented.
- Bitstream alignment has been investigated and is not the leading explanation for the current failures.
- Replacing bit-0 sign mapping with conventional zig-zag mapping, while holding every other candidate-decoder choice fixed, is rejected by the external-reference panel.
- Replacing the hybrid K update with a universal `floor(log2(S+1)) ± 1` symbol bracket, while holding the D5 candidate choices fixed, is also rejected by the external-reference panel.

## Working hypothesis

The decoded signal is locally most consistent with a MED-family predictor, so the remaining fault is more likely in residual/entropy semantics or in how those semantics are scoped to CRX structure than in a simple predictor-family swap.

Phase-B / Phase-C investigation (2026-06-14) established:

- **B1 PARTIAL / NARROWED**: Phase-B's north-only experiment reduced OB artefacts on the dark fixture, but Phase-C residual evidence shows the already decoded signal is best explained by **MED** in 44/44 leftOB cells, 43/44 topOB cells, and 44/44 image-area cells. `N`, `NorthOnly`, `W`, `WestOnly`, and `NW` are not statistically supported as the leading predictor family.
- **B2 REJECTED**: Tile-state continuation doubles OB stdDev in every fixture. Current tile-local reset with seed=0 is correct.
- **B3 REJECTED**: Cross-plane seeding has zero effect. Plane-independent reset is correct.
- **B4 REJECTED**: No systematic per-plane neighbour-family split was observed; MED dominates across all planes with only one Paeth topOB outlier.

Phase-C evidence matrix ranking:

- **MED** is the best-supported family across the corpus.
- **Paeth** is the consistent runner-up.
- **N + W - NW** is a distant third.
- Pure **N**, **W**, and **NW** families are strongly contradicted by the decoded signal statistics.

Phase-D5 sign-mapping discriminator (2026-07-19) established:

- **D5 sign-mapping replacement REJECTED**: zig-zag produced 0/3 successful discriminator fixtures and 0/3 required Pearson sign flips.
- Median global MAE regressed from **1833.9213** with BIT0 to **2315.9968** with ZIGZAG.
- Median tile-boundary error regressed from **2365.6625** to **3007.1074**.
- Decode completion regressed on already incomplete fixtures.
- This result does not prove that BIT0 mapping is correct; it falsifies only the isolated BIT0-to-ZIGZAG replacement under otherwise fixed candidate settings.

Phase-D6 K-adaptation discriminator (2026-07-20) established:

- **D6 simple symbol-bracket replacement REJECTED**: the predeclared gate produced 0/3 successful discriminator fixtures.
- Median global MAE regressed from **1833.9213** to **3059.2298**.
- Median tile-boundary error regressed from **2365.6625** to **3680.9709**.
- The candidate introduced or worsened stream exhaustion on several fixtures.
- All three EOS R fixtures improved global MAE without completion loss, while G5 X Mark II and several other fixtures regressed. This is a diagnostic structural split, not evidence for a model-specific decoder rule.
- The result falsifies only the universal `K*=floor(log2(S+1))`, bracket `K*−1..K*+1` rule under the fixed D5 candidate settings. It does not prove the historical hybrid accumulator or disprove the broader patent-described K-adaptation family.

## Rejected or deprioritized directions

- broad container archaeology,
- repeated quantValue sweeps on the current lossless corpus,
- repeated PRVW geometry investigations,
- repeated CFA-layout investigations,
- simple predictor-family swaps to pure N / W / NW,
- generic alignment sweeps,
- isolated BIT0-to-ZIGZAG sign-mapping replacement,
- universal current-symbol `floor(log2(S+1)) ± 1` K-bracket replacement under D5 settings,
- image rendering quality optimisation before decoder correctness.

## Current priority

Stage 6 remains the earliest failing or unverified point in the implementation pipeline. The next task is not another broad formula sweep. It is a bounded first-divergence trace between:

- an EOS R fixture where D6 improved without completion loss (`eosr-iso-mid.cr3`), and
- a G5 X Mark II fixture where D6 newly exhausted the stream (`g5xm2-asis-iso200-ss130-ff2p5-01.cr3`).

The trace must retain both decoder variants unchanged and record only the first K/bit-position divergence: tile, plane, pixel coordinate, input bit position, quotient, remainder, unsigned symbol, previous/current/next K, predictor, and reconstructed value.

The subsequent decoder experiment must change exactly one cause supported by that trace. Candidate causes include K-state scope, row reset, symbol estimate, or a structural tile/plane boundary. Do not infer camera-model-specific entropy rules from the current evidence.

Stage audit reference (2026-07-03, [cr3-pipeline-stage-audit](cr3-results/2026-07-03-cr3-pipeline-stage-audit.md)):

- earliest unverified/failing point in the implementation pipeline is **Stage 6 (entropy decoding / Rice-Golomb / K-update)**;
- stages 1–4 are currently verified by parser/header tests and fixture runs;
- stage 5 remains partial, but is not currently the first failure.
