# CR3 Current State

This document summarizes the current consensus after the Phase-A research work.

## Established facts

- CR3 container parsing works.
- CMP1, IAD1 and CRX header extraction work.
- Fixture inventory, subband extraction, geometry diagnostics and alignment diagnostics exist.
- The repository contains both lossless RAW and C-RAW research fixtures.
- The corpus is structurally saturated for many broad decoder sweeps.
- All current lossless CR3 fixtures now have LibRaw-backed reference TIFF/PNG sidecars plus a manifest.
- `Cr3ReferenceComparisonIT` generates reference-vs-decoder metrics and visual reports in `docs/cr3-results/reference-comparison.md`.

## Strong findings

- Optical-black (IAD1 OB) regions are the strongest currently available falsification axis.
- The current decoder output is physically implausible in OB regions.
- CRX_SMALL and CRX_BIG use different CFA layouts and this has already been documented.
- Bitstream alignment has been investigated and is not the leading explanation for the current failures.

## Working hypothesis

The decoded signal is locally most consistent with a MED-family predictor, so the
remaining fault is more likely in residual/value semantics than in a simple
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

## Rejected or deprioritized directions

- broad container archaeology,
- repeated quantValue sweeps on the current lossless corpus,
- repeated PRVW geometry investigations,
- repeated CFA-layout investigations,
- simple predictor-family swaps to pure N / W / NW,
- image rendering quality optimisation before decoder correctness.

## Current priority

Phase D5 — residual/value semantics against the external reference baseline.

Following the predictor-state triage:
- B1 PARTIAL / narrowed: MED best matches the decoded signal; NorthOnly looked less wrong visually but is not statistically supported
- B2 REJECTED: tile-local reset is correct
- B3 REJECTED: plane-independent reset is correct
- B4 REJECTED: no per-plane neighbour-family split
- pedestal/DC now has a committed **SUPPORTED** result
- bitstream-alignment loops are historical, not active

Stage audit update (2026-07-03, [cr3-pipeline-stage-audit](cr3-results/2026-07-03-cr3-pipeline-stage-audit.md)):
- earliest unverified/failing point in the implementation pipeline is **Stage 6 (entropy decoding / Rice-Golomb / K-update)**
- stages 1–4 are currently verified by parser/header tests and fixture runs
- stage 5 remains partial, but is not currently the first failure

Recommended next experiment: keep the MED neighbourhood model fixed and run the
**sign-mapping discriminator** in `DecoderHypothesisD1D2IT` on the 11
reference-backed lossless fixtures. The decision is reference-backed: the change
must flip Pearson from negative to positive on at least 2 of these 3 discriminator
fixtures — `sample-tele`, `sample-wide`, and
`research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` — while also reducing Global
MAE and OB MAE there and not worsening the median full-corpus error.
