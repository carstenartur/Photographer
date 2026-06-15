# CR3 Phase-C Predictor Evidence Matrix — Results

**Date:** 2026-06-14  
**Hypotheses touched:** B1 (predictor row context / family), B4 (per-plane neighbour model)  
**Method:** `PredictorEvidenceMatrixIT` integration test  
**Primary decision metrics:** residual stddev and MAD on the already decoded signal

---

## Hypothesis ID

- **B1** — Predictor row context is shifted or otherwise incorrect
- **B4** — Per-plane neighbour model differs from the current implementation

---

## Goal

Determine which predictor family is statistically supported by the **already decoded**
CR3 signal.

This phase does **not** implement a new decoder and does **not** run another broad
predictor sweep. It collects evidence by asking which local predictor best explains
the current decoded samples in optical-black and visible-image regions.

---

## Fixtures used

11 lossless RAW fixtures were analysed; the same 2 C-RAW fixtures from Phase B were
correctly skipped.

- EOS R: `eosr-iso-low`, `eosr-iso-mid`, `eosr-iso-high`
- G5 X II: `g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02`
- G5 X II: `g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02`
- G5 X II: `g5xm2-asis-iso200-ss130-ff2p5-01`
- Sample set: `sample`, `sample-iso-high`, `sample-tele`, `sample-wb-tungsten`, `sample-wide`

---

## Method

`PredictorEvidenceMatrixIT` decodes each lossless fixture with the current production
decoder, then treats that decoded Bayer signal as the observation to explain.

For every fixture, plane, and area, residuals were computed for these candidates:

- `N`
- `W`
- `NW`
- `MED`
- `Paeth`
- `N + W - NW`
- `NorthOnly`
- `WestOnly`

Analysis areas:

- IAD1 `leftOb`
- IAD1 `topOb`
- IAD1 active image area

All results were produced per plane (`0..3`) and written to:

- `target/predictor-evidence/results.tsv`
- `target/predictor-evidence/results.json`
- `target/predictor-evidence/results.md`
- `target/predictor-evidence/images/`

No production decoder code was changed.

---

## Metrics

For every candidate / fixture / plane / area, the test computes:

- mean residual
- median residual
- residual stddev
- MAD
- histogram
- skewness
- kurtosis

Residual histograms and per-plane stddev plots are generated for all fixtures.

---

## Conclusion

### Best-supported predictor family

The decoded signal is overwhelmingly **MED-like**.

- **MED wins 44/44 leftOB cells**
- **MED wins 43/44 topOB cells**
- **MED wins 44/44 image-area cells**
- The single non-MED outlier is `g5xm2-flat` / `plane 3` / `topOB`, where **Paeth**
  is marginally better than MED

Aggregate mean residual stddev ranking:

| Area | 1st | 2nd | 3rd |
|---|---|---|---|
| leftOB | MED (0.9799) | Paeth (1.0805) | N+W-NW (1.1535) |
| topOB | MED (0.8994) | Paeth (0.9601) | N+W-NW (1.0519) |
| image area | MED (1.8340) | Paeth (1.9774) | N+W-NW (1.9895) |

### Rejected families

The following families are **not** supported by the decoded signal:

- **N / NorthOnly**
- **W / WestOnly**
- **NW**

Examples from the aggregate residual stddev means:

| Area | MED | N | W | NW |
|---|---|---|---|---|
| leftOB | 0.9799 | 3.0353 | 4.1468 | 5.0511 |
| topOB | 0.8994 | 17.6897 | 2.6522 | 18.3908 |
| image area | 1.8340 | 21.7326 | 48.6567 | 56.8063 |

So the current decoded samples are **far less self-consistent** under pure-N than
under MED, even on the dark fixture where Phase B made NorthOnly look visually less
wrong.

### Interpretation of the Phase-B / Phase-C tension

Phase B asked: “what happens if we change the decoder predictor?”

Phase C asked: “which predictor best explains the signal the decoder already emits?”

Those are not the same question.

NorthOnly can flatten some optical-black artefacts by changing the decoded signal,
yet still be a poor statistical explanation for that signal once it exists. The
evidence matrix therefore says:

- Phase-B NorthOnly improvement was a useful falsifier,
- but it does **not** mean the true local predictor family is NorthOnly,
- and it does **not** justify replacing MED with a pure-N predictor.

The more plausible reading is that the decoded signal retains a MED-like local
structure while some other part of the decode path still injects physically
implausible values.

---

## Status update

| Hypothesis | Previous status | New status | Reason |
|---|---|---|---|
| B1 (predictor row context) | PARTIAL | **PARTIAL / narrowed** | Simple N/W/NW family replacement is unsupported; MED remains the best explanation of the decoded signal |
| B4 (per-plane neighbour model) | OPEN | **REJECTED** | No systematic per-plane family split; MED dominates every plane except one marginal Paeth topOB outlier |

---

## Next step

### Phase D should investigate

Keep the MED neighbourhood model fixed and investigate why a MED-consistent signal
is still physically implausible in optical-black regions.

Recommended next directions:

1. inspect residual/value semantics rather than swapping predictor families
2. compare emitted residual distributions against what MED would imply on the dark fixture
3. audit sign mapping, amplitude scaling, or adaptive-state updates that could preserve
   local MED structure while still producing wrong absolute pixel values
4. focus on dark-fixture OB regions first, since they remain the strongest falsification axis

The new result is: **the current decoder output behaves like a broken MED-style
signal, not like a NorthOnly signal.**
