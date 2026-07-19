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
- The complete 11-fixture D5 sign-mapping discriminator has been executed and recorded in [`2026-07-19-D5-sign-mapping-discriminator.md`](cr3-results/2026-07-19-D5-sign-mapping-discriminator.md).

## Strong findings

- Optical-black (IAD1 OB) regions are the strongest currently available falsification axis.
- The current decoder output is physically implausible in OB regions.
- CRX_SMALL and CRX_BIG use different CFA layouts and this has already been documented.
- Bitstream alignment has been investigated and is not the leading explanation for the current failures.
- Replacing bit-0 sign mapping with conventional zig-zag mapping, while holding every other candidate-decoder choice fixed, is rejected by the external-reference panel.

## Working hypothesis

The decoded signal is locally most consistent with a MED-family predictor, so the
remaining fault is more likely in residual/entropy semantics than in a simple
predictor-family swap.

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

## Rejected or deprioritized directions

- broad container archaeology,
- repeated quantValue sweeps on the current lossless corpus,
- repeated PRVW geometry investigations,
- repeated CFA-layout investigations,
- simple predictor-family swaps to pure N / W / NW,
- generic alignment sweeps,
- isolated BIT0-to-ZIGZAG sign-mapping replacement,
- image rendering quality optimisation before decoder correctness.

## Current priority

Stage 6 remains the earliest failing or unverified point in the implementation pipeline. The next experiment must vary a different, precisely defined entropy/residual semantic while preserving:

- the MED neighbourhood model;
- tile-local and plane-local reset behavior;
- the complete 11-fixture external-reference panel;
- predeclared global, optical-black, boundary, completion and discriminator criteria.

Candidate next experiments should be derived from concrete CRX entropy semantics, such as K adaptation or escape handling. They must not combine several changes in one run and must not reopen predictor, alignment, CFA or sign-mapping sweeps without new structural evidence.

Stage audit reference (2026-07-03, [cr3-pipeline-stage-audit](cr3-results/2026-07-03-cr3-pipeline-stage-audit.md)):

- earliest unverified/failing point in the implementation pipeline is **Stage 6 (entropy decoding / Rice-Golomb / K-update)**;
- stages 1–4 are currently verified by parser/header tests and fixture runs;
- stage 5 remains partial, but is not currently the first failure.
