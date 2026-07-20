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
| D8 | Contract K after zero symbols, use D6 bracket only for non-zero symbols | OPEN | Implement `S==0 && K>0 ? K-1 : bracket(S)`, then rerun the exact fixed panel and gate. |

Deferred without new evidence: repeated quantValue, PRVW geometry, CFA-layout, generic alignment, predictor-family and sign-mapping sweeps.

Status values: OPEN, SUPPORTED, REJECTED, BLOCKED, PARTIAL, DEFERRED, COMPLETE.
