# CR3 Current State

This document summarizes the current consensus after the Phase-A research work.

## Established facts

- CR3 container parsing works.
- CMP1, IAD1 and CRX header extraction work.
- Fixture inventory, subband extraction, geometry diagnostics and alignment diagnostics exist.
- The repository contains both lossless RAW and C-RAW research fixtures.
- The corpus is structurally saturated for many broad decoder sweeps.

## Strong findings

- Optical-black (IAD1 OB) regions are the strongest currently available falsification axis.
- The current decoder output is physically implausible in OB regions.
- CRX_SMALL and CRX_BIG use different CFA layouts and this has already been documented.
- Bitstream alignment has been investigated and is not the leading explanation for the current failures.

## Working hypothesis

The most likely remaining fault class is the predictor row context model.

Phase-B investigation (2026-06-14) established:

- **B1 PARTIAL**: The north-only (N) predictor reduces leftOB stdDev by 72% on dark fixtures (240→67) and topOB stdDev by 31%. The W component of MED is actively harmful. Row-offset reduces banding by 40% on the dark topOB.
- **B2 REJECTED**: Tile-state continuation doubles OB stdDev in every fixture. Current tile-local reset with seed=0 is correct.
- **B3 REJECTED**: Cross-plane seeding has zero effect. Plane-independent reset is correct.

The remaining stdDev of 67 on the dark fixture's leftOB (vs ~2–5 physical floor) means the row context model is partially but not wholly wrong. The pure-N predictor is the leading candidate for Phase C.

Candidate explanations:

- wrong row context (specifically the W component of MED),
- possibly a modified LOCO-I variant (N+NW, no W) rather than full MED,
- per-plane neighbour model variation (not yet tested).

## Rejected or deprioritized directions

- broad container archaeology,
- repeated quantValue sweeps on the current lossless corpus,
- repeated PRVW geometry investigations,
- repeated CFA-layout investigations,
- image rendering quality optimisation before decoder correctness.

## Current priority

Phase C — Predictor Model Correction.

Following Phase-B results:
- B1 PARTIAL: N-only predictor reduces OB stdDev significantly; W context is harmful
- B2 REJECTED: tile-local reset is correct
- B3 REJECTED: plane-independent reset is correct

Recommended next experiment: test pure-N and N+NW (no W) predictor variants in the production decoder path, with leftOB dark-fixture stdDev as the primary falsification metric.
