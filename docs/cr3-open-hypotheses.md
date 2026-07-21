# CR3 Open Hypotheses

| ID | Hypothesis | Status | Evidence / next step |
|---|---|---|---|
| B1 | Predictor row context is incorrect | PARTIAL | MED remains best supported; revisit only after entropy evidence points back to predictor context. |
| B2 | Tile predictor state should continue | REJECTED | Continuation doubles OB variation. |
| B3 | Plane predictor state should continue | REJECTED | Cross-plane seeding has no useful effect. |
| B4 | Predictor family differs by plane | REJECTED | No systematic plane split. |
| D5 | Replace BIT0 sign mapping with zig-zag | REJECTED | Fixed panel regressed. |
| D6 | Use one universal logarithmic K bracket | REJECTED | Corpus medians and completion regressed. See [D6](cr3-results/2026-07-20-D6-k-adaptation-discriminator.md). |
| D7 | Locate the first D6 K divergence | COMPLETE | First cause is zero-symbol K state. See [D7](cr3-results/2026-07-20-D7-first-k-divergence.md). |
| D8 | Contract K after zero symbols | PARTIAL | Helps selected files; corpus and completion gates fail. See [D8](cr3-results/2026-07-20-D8-zero-contracted-k.md). |
| D9 | Find one common first non-zero cause | COMPLETE: NO COMMON CAUSE | Three representatives produce different decision classes. See [D9](cr3-results/2026-07-20-D9-first-nonzero-k-divergence.md). |
| D10 | One transition class predicts failure | COMPLETE: NO PREDICTIVE CLASS | Candidate classes also occur in improved files. See [D10](cr3-results/2026-07-20-D10-k-transition-outcome-matrix.md). |
| D11 | Primary source defines an exact base K range | COMPLETE | The range is the finite-domain minimum of the disclosed code length. CRX context remains unresolved. See [D11](cr3-results/2026-07-20-D11-canon-k-source-audit.md). |
| D12 | The exact range alone fixes D8 | PARTIAL | OB median improves, but global, boundary, saturation, family and stream gates fail. See [D12](cr3-results/2026-07-20-D12-exact-k-bracket.md). |
| D13 | Tentative-symbol context can be mapped from the fixed lossless CRX topology | COMPLETE: INSUFFICIENT | All 88 records are lossless single-subband records. The panel exposes no deeper `Sd`, LL/HL/LH/HH role or `Kd` lifecycle. D14 is blocked. See [D13](cr3-results/2026-07-20-D13-context-topology.md). |

Blocked without genuinely new evidence: `Sd`/`Kd` semantics, multi-subband role mapping and any D14 entropy candidate.

Deferred without new evidence: quantisation, PRVW geometry, CFA layout, generic alignment, predictor-family, sign-mapping, transition-frequency and current-symbol K-bracket sweeps.

Status values: OPEN, SUPPORTED, REJECTED, BLOCKED, PARTIAL, DEFERRED, COMPLETE.
