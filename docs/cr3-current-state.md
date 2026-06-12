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

The most likely remaining fault class is the predictor state machine.

Candidate explanations:

- wrong row context,
- wrong tile reset behaviour,
- wrong plane reset behaviour,
- wrong neighbour model.

## Rejected or deprioritized directions

- broad container archaeology,
- repeated quantValue sweeps on the current lossless corpus,
- repeated PRVW geometry investigations,
- repeated CFA-layout investigations,
- image rendering quality optimisation before decoder correctness.

## Current priority

Phase B — Predictor State Isolation.

Primary metrics:

- OB stdDev,
- OB median stability,
- row/column flatness,
- banding score,
- autocorrelation.

Secondary metrics:

- PRVW Pearson correlation.

## Next expected deliverable

A predictor-state-isolation experiment that evaluates narrowly scoped predictor-state hypotheses and records durable results under `docs/cr3-results/`.
