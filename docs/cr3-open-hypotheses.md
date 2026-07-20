# CR3 Open Hypotheses

| ID | Hypothesis | Status | Evidence / next step |
|---|---|---|---|
| B1 | Predictor row context is incorrect | PARTIAL | MED remains best supported; revisit only after entropy evidence points back to predictor context. |
| B2 | Tile predictor state should continue | REJECTED | Continuation doubles OB variation. |
| B3 | Plane predictor state should continue | REJECTED | Cross-plane seeding has no useful effect. |
| B4 | Predictor family differs by plane | REJECTED | No systematic plane split. |
| D5 | Replace BIT0 sign mapping with zig-zag | REJECTED | Fixed 11-fixture panel regressed. |
| D6 | Replace hybrid K update with universal `floor(log2(S+1)) ± 1` bracket | REJECTED | 0/3 discriminator successes, worse medians and completion. See [D6](cr3-results/2026-07-20-D6-k-adaptation-discriminator.md). |
| D7 | Locate the first D6 K/bit-position divergence | COMPLETE | Both fixed fixtures diverge after the first identical `S=0`: HYBRID changes `K 1→0`, D6 keeps `K=1`. See [D7](cr3-results/2026-07-20-D7-first-k-divergence.md). |
| D8 | Contract K after zero symbols, use D6 bracket only for non-zero symbols | PARTIAL | Zero contraction removes D6's new G5-X-II `asis` exhaustion and improves many fixtures, but median MAE/boundary and completion still fail. See [D8](cr3-results/2026-07-20-D8-zero-contracted-k.md). |
| D9 | Find one common first non-zero K divergence after zero-run alignment | COMPLETE: NO COMMON CAUSE | EOS R shows HYBRID decrease/D8 hold; G5 X II shows HYBRID decrease/D8 increase; `sample-iso-high` shows HYBRID hold/D8 increase. No semantic change is justified. See [D9](cr3-results/2026-07-20-D9-first-nonzero-k-divergence.md). |
| D10 | A transition class consistently predicts one D8 failure mode across the fixed panel | OPEN OBSERVATION | Build a fixed-panel K-transition matrix and correlate transition classes with D8 MAE/OB/boundary/completion deltas. Do not alter decoder semantics. |

Deferred without new evidence: repeated quantValue, PRVW geometry, CFA-layout, generic alignment, predictor-family and sign-mapping sweeps.

Status values: OPEN, SUPPORTED, REJECTED, BLOCKED, PARTIAL, DEFERRED, COMPLETE.
