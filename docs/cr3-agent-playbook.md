# CR3 Research Playbook

This is the mandatory entry point for CR3/CRX RAW import work.

The repository already contains a substantial Phase-A research history. Do not restart from container discovery or broad decoder sweeps.

## Mandatory reading order

1. `docs/cr3-fixture-audit.md`
2. `canon-cr3-core/SubbandFixtures.md`
3. `docs/cleanroom-fixture-plan.md`
4. `docs/cr3-current-state.md`
5. `docs/cr3-open-hypotheses.md`
6. `docs/cr3-results-index.md`

## Phase-A conclusions to preserve

Treat these as the current baseline unless a new experiment explicitly disproves them:

- CR3 container parsing, CMP1, IAD1 and CRX header extraction are established.
- CRX_SMALL / PRVW geometry and CFA mismatch have been investigated sufficiently for diagnosis.
- Bitstream alignment has been investigated; 4-byte alignment is plausible and broad alignment sweeps are low priority.
- Optical-black (IAD1 OB) regions are the strongest current falsification axis.
- The current decoder is physically implausible in OB regions.
- `predictor correct, entropy wrong` is rejected by the Phase-A decision matrix.
- `entropy mostly plausible, predictor/state machine wrong` was a leading Phase-A direction, but D5 residual/value semantics is now the current priority.
- The current corpus is structurally saturated for many broad sweeps; do not run them again without a novelty argument.

## Current priority

Phase D5: residual/value semantics against the lossless LibRaw reference baseline.

The predictor-state branch is no longer the default next step:

- `PredictorStateIsolationIT` narrowed B1 and rejected B2/B3.
- `PredictorEvidenceMatrixIT` kept MED as the best-supported local predictor and rejected B4.
- `PedestalHypothesisValidationIT` already established one reference-backed DC/pedestal result.

Future research must therefore start from the triage matrix below instead of reopening
predictor-family or alignment loops.

## Research triage matrix

| Test / Report | Hypothesis | Evidence type | Reference-backed? | Current status | Keep / merge / retire / blocked | Next action |
|---|---|---|---|---|---|---|
| `PredictorStateIsolationIT` + `docs/cr3-results/2026-06-14-B1-predictor-state-isolation.md` | Predictor row/tile/plane state is the primary lossless failure | OB stdDev, banding, PRVW sanity on 11 lossless fixtures | No | B1 **PARTIAL**, B2/B3 **REJECTED** | **KEEP** | Preserve as a falsifier set; do not rerun unless a concrete residual/value change first improves reference metrics. |
| `PredictorEvidenceMatrixIT` + `docs/cr3-results/2026-06-14-predictor-evidence-matrix.md` | MED-family structure is wrong / per-plane neighbour family differs | Reference-backed residual statistics and decoder residual diagnostics | Yes | B1 narrowed, B4 **REJECTED** | **KEEP** | Use only to prevent reopening predictor-family sweeps; MED stays fixed for the next experiment. |
| `PedestalHypothesisValidationIT` + `docs/cr3-results/2026-06-16-pedestal-hypothesis-validation.md` | A constant pedestal/DC correction explains a large share of lossless error | Reference TIFF MAE/RMSE/OB-MAE optimization | Yes | **SUPPORTED** | **KEEP** | Keep as the leading supported production-change candidate for black-level/DC handling. |
| `DcComponentIsolationIT` + the DC-offset subsection of `DecoderHypothesisD1D2IT` | Extra DC/seed probes beyond the committed pedestal result are needed first | Reference-backed diagnostics, seed sweeps, pixel traces | Yes | Diagnostic only; no durable status update | **MERGE** | Fold future DC-only follow-ups into `PedestalHypothesisValidationIT`; do not start a separate DC experiment next. |
| `CrxKUpdateRuleBakeoffIT` | PATENT_BRACKET vs JPEG_LS K-update is still the main open discriminator | Dark/flat burst metrics and per-plane mode buckets | No | PATENT_BRACKET already promoted; legacy path retained for A/B only | **KEEP** | Use only as a secondary regression panel after a reference-backed residual/value result nominates an entropy change. |
| `DecoderStageIsolationIT` | One normative entropy knob alone explains the remaining lock-loss bug | Non-reference per-plane saturation / exhaustion ablation | No | Diagnostic only; not promoted to a durable result | **MERGE** | Reuse only after a reference-backed D5 result points at a specific residual/value knob; do not run it standalone as the next task. |
| `BitstreamPlaneAlignmentIT` + `BitstreamAlignmentDeepAuditIT` | Bitstream alignment is the primary decoder failure | Alignment audits over subbands / planes | No | Alignment direction already deprioritized | **RETIRE** | Historical only unless a new fixture shows a header or offset anomaly. |
| `Iad1OpticalBlackProfileIT` + `Iad1OpticalBlackDeepDiagnosticIT` + `DarkFixtureLosslessDecoderIT` / `FlatFixtureLosslessDecoderIT` | Optical-black plausibility is the strongest physical falsifier | OB medians, stddev, profiles, saturation signatures | No | Active diagnostic axis | **KEEP** | Use as the physical secondary gate for any residual/value experiment, especially dark/flat regressions. |
| `Cr3ReferenceComparisonIT` + `docs/cr3-results/reference-comparison.md` | Lossless decoder quality should be judged against LibRaw sidecars | Global MAE/RMSE/Pearson, OB MAE/StdDev, boundary error | Yes | Baseline complete on 11 lossless fixtures | **KEEP** | This is the primary scorecard for the one selected next experiment. |
| `CrawLossyBringUpIT` + `CrxPlaneFollowupRound12IT` + `CrxPlaneFollowupRound13IT` | C-RAW correctness can still be advanced by more blind sweeps | Structure checks plus ground-truth-free Pearson sweeps | No | Harness exhausted; no lossy ground truth | **BLOCKED** | Do not run new C-RAW decoder sweeps until reference-backed lossy sidecars or another real ground truth source exists. |
| `FixtureIntelligenceReportIT` + `ReferenceManifestValidationIT` + `ReferenceRebuildValidationIT` | The current corpus / sidecars are incomplete or unstable | Fixture inventory, manifest validation, rebuild reproducibility | Partly | Corpus and reference inventory are active and useful | **KEEP** | Use to confirm fixture scope before running the selected experiment; no new inventory-only report is needed. |

## Selected next decoder experiment

Run exactly one experiment next: **the D5 sign-mapping discriminator using the sign-mapping comparison subsection of `DecoderHypothesisD1D2IT` against the lossless LibRaw sidecars**.

### Single hypothesis to test

The remaining lossless error is primarily caused by the **PATENT_BRACKET bit-0 residual sign mapping** in `CrxLosslessRawDecoder.decodePlane`, while the MED neighbourhood model stays fixed.

### Why this is not already answered

- Predictor-state work answered neighbour / tile / plane-state questions, not residual sign semantics.
- Pedestal/DC work established a supported offset result, but it did not decide whether the residual sign convention is wrong.
- `DecoderStageIsolationIT` touches the sign-bit knob, but only with non-reference saturation metrics and no durable result document.

### Fixtures to use

Use the existing 11 lossless fixtures with LibRaw sidecars from `Cr3ReferenceComparisonIT`, with these discriminator fixtures called out explicitly:

- `sample-tele.cr3`
- `sample-wide.cr3`
- `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3`

Keep these as regression sentinels in the same run:

- `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3`
- `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3`

### Reference metrics that decide the outcome

Primary decision metrics:

- Pearson sign/delta on the three anti-correlated discriminator fixtures
- Global MAE from the reference TIFF comparison
- OB MAE from the reference TIFF comparison
- Boundary Error from `docs/cr3-results/reference-comparison.md`

Secondary guard metrics:

- dark/flat optical-black plausibility from the existing OB diagnostics

### `SUPPORTED` means

Promote the hypothesis only if the tested alternative sign convention:

1. flips Pearson from negative to positive on at least **2 of the 3** discriminator fixtures,
2. lowers **Global MAE** and **OB MAE** on those same fixtures versus the current PATENT_BRACKET path, and
3. does **not** worsen the full 11-fixture median Global MAE or Boundary Error.

### `REJECTED` means

Reject the hypothesis if:

- fewer than 2 discriminator fixtures flip to positive Pearson, or
- any Pearson gain comes with worse Global MAE / OB MAE, or
- the remaining 11-fixture median error regresses.

### Production code eligible for change if supported

Only the residual sign-mapping branch inside:

- `canon-cr3-core/src/main/java/com/photographer/focusstack/cr3/raw/codec/CrxLosslessRawDecoder.java`

would become eligible for change, plus any directly coupled helper used for that mapping. Predictor-family, tile-reset, plane-reset, alignment, and C-RAW code stay out of scope.

## Do not spend agent time on

- new container analysis,
- broad Rice/Golomb parameter sweeps,
- quantValue-first experiments on the lossless corpus,
- CFA-layout rediscovery,
- PRVW geometry rediscovery,
- colour pipeline, white balance, demosaic, or rendering quality.

## Required workflow

1. Choose exactly one active hypothesis from `docs/cr3-open-hypotheses.md`.
2. Explain why Phase-A updates (p), (q), (r), and (s) do not already settle it.
3. Implement only test/research-scope code unless the production gate is met.
4. Use the hypothesis-specific primary score. For the current D5 sign-mapping task, primary metrics are reference-backed: Pearson, Global MAE, OB MAE, and Boundary Error.
5. Use IAD1 optical-black plausibility as the physical secondary gate; use PRVW Pearson only as a tertiary sanity metric when no better reference exists.
6. Emit machine-readable and human-readable artifacts.
7. Update the durable research documents listed below.

## Required output for every CR3 research task

At task completion, update or create:

- `docs/cr3-current-state.md`
- `docs/cr3-open-hypotheses.md`
- `docs/cr3-results-index.md`
- `docs/cr3-results/<date>-<hypothesis>-<short-name>.md`

Also generate test artifacts under `target/<experiment>/`, preferably both `.tsv` or `.json` and `.md`.

Stdout-only results are not acceptable.

## Gate for production decoder changes

Production decoder changes require all of the following:

- a hypothesis status of `SUPPORTED`,
- a durable result document under `docs/cr3-results/`,
- numeric improvement on at least one Phase-A falsifier, preferably OB plausibility,
- no weakened regression tests,
- a separate production-code commit or PR after the research result is documented.

## Default next task

If no more specific task is provided, perform the **D5 sign-mapping discriminator via `DecoderHypothesisD1D2IT`** described above and document it as the next durable result.
