# CR3 / CRX Clean-room Research

This is the maintained process entry for CRX semantic research. Read [Current state](current-state.md) before proposing an experiment.

## Present decision

[RG3](../cr3-results/2026-11-14-crx-opening/README.md) derives the first two native same-plane values directly from an ordinary 14-bit lossless CRX subband. The reader passed the original 88 streams and 32 streams from four held-out files. Preregistered bit flips and transplants independently tested the first-value formula. The next testable site is the third, at the bit cursor recorded for each stream.

The D13 panel has no evidence of a deeper subband parent or `Sd`/`Kd` lifecycle. RG1 diagnosed the earlier decoder's first failure, and [RG2](../cr3-results/2026-11-14-crx/README.md) located the common ten-zero prefix. RG3 remains scoped to those opening sites and the measured layout. Full-row prediction, code adaptation and the grammar of the opening prefix remain open.

### Active evidence path

Issue #249 defines a controlled first-party spatial-stimulus corpus. Its deterministic pattern assets exist; hardware acquisition and differential analysis remain open. The intended evidence chain is:

```text
known display stimulus
    -> camera exposure
    -> JPEG/embedded-preview observation
    -> CR3/CRX payload observation
```

The JPEG is the calibration channel that shows whether, where and with what visible strength the camera detected the stimulus. No direct monitor-pixel-to-sensor-pixel assumption is required. Duplicate captures establish the noise baseline, and an adaptive loop may select the next position, shape or intensity from the previous machine-readable observation.

This work is evidence acquisition. It does not by itself authorize an entropy rule or production decoder change.

Research may also resume with:

- a legally usable multi-subband CRX corpus with independently established subband geometry and ordering;
- a permissible public primary source that fully specifies `Sd`, `Kd`, initialization, rounding, clamps and boundaries;
- another first-party structural observation that identifies causal context without selecting a rule by image quality.

## Mandatory clean-room boundary

All semantic CRX work follows the [clean-room engineering record](../cleanroom/README.md).

Researchers must not derive implementation behavior from third-party decoder source code. Black-box tools may produce output evidence, but their source is not an implementation specification. Record tool version, command, input hash and output hash.

## Before an experiment

A research proposal must state:

1. **Novel evidence** — what new source, fixture or observation is unavailable in D13/RG1?
2. **One hypothesis or observation boundary** — one causal statement that can be rejected.
3. **Fixed inputs** — exact fixture, pattern and manifest set selected before execution.
4. **Changed variable** — one implementation or stimulus dimension, not a broad sweep.
5. **Primary metrics** — quantitative decision metrics selected before results.
6. **Physical/noise guards** — duplicate baseline, optical black, completion, saturation, boundary and camera-family gates as applicable.
7. **Runtime and output** — checkout-runnable command, resource bound and durable artifact paths.
8. **Promotion rule** — explicit supported/rejected/partial criteria and what production code could later become eligible.

If novelty cannot be explained, do not run the experiment.

## Controlled capture contract

For a camera/display closed loop:

- disable adaptive display behavior such as HDR, automatic brightness, True Tone/Night Shift and dynamic contrast;
- record display geometry, scale factor, pattern ID, exact RGB values and presentation acknowledgment;
- fix camera model, firmware, exposure, ISO, aperture, focus, white balance, framing and connection mode;
- acquire duplicate or ABBA-ordered captures to estimate temporal noise;
- bind the CR3 and its JPEG observation to the same exposure and manifest row;
- compare decoded JPEG image content, not JPEG file bytes;
- separate duplicate-stable CRX payload changes from container/metadata variation;
- use larger cells before single display pixels when optical blur or aliasing makes the stimulus unobservable;
- preserve both accepted and rejected observations.

Long adaptive campaigns belong in a resumable local runner with JSON request/result files and an evidence ledger. A JUnit `real-camera` integration test may expose the hardware operation and smoke-test orchestration, but it should not be the only persistence mechanism.

## Evidence hierarchy

Use the strongest available evidence:

1. controlled first-party paired observations with duplicate baselines;
2. manifest-bound `RAW_LINEAR` TIFF references;
3. structural/container facts and independently known geometry;
4. physical sensor falsifiers, especially optical-black regions and completion;
5. rendered-preview metrics as a secondary sanity signal;
6. visual inspection for debugging, never as the sole promotion gate.

The shared `canon-cr3-reference-testkit` provides canonical reference loading, geometry/alignment validation, metrics and deterministic evidence writers. Do not reimplement equivalent local loops in each experiment.

## Fixture acquisition

New captures are justified by the decision they enable, not by corpus size. Record deterministic names, checksums, provenance, settings, pairing order, redistribution status and an independent reference/calibration channel.

Adding ordinary dark, flat, detail or burst photographs without a novelty argument does not authorize another entropy sweep.

## Implementation scope

Initial work belongs in tests, `canon-cr3-research` or the reference testkit. Production modules remain unchanged until a result passes its predeclared gate.

Production modules must not acquire runtime dependencies on research, reference-testkit, benchmark or private-reference modules/files.

## Required outputs

Every completed experiment produces:

- machine-readable metrics (`.tsv`, `.csv` or `.json`);
- a deterministic human-readable report;
- exact command and source revision;
- fixture/pattern/manifest identifiers and hashes;
- implementation or stimulus variant identifier;
- verdict and next step;
- updated [Current state](current-state.md), [Open hypotheses](open-hypotheses.md) and [Results index](reports/index.md) when durable interpretation changes.

Stdout-only conclusions are not durable evidence.

## Verify and update modes

Evidence generators distinguish:

- **verify** — recompute/check and fail on missing or stale committed evidence without overwriting it;
- **update** — intentionally regenerate evidence after review of fixed inputs and expected changes.

CI uses verify mode. It never commits generated scientific output automatically.

## Production promotion

A semantic production change requires all of:

- hypothesis status `SUPPORTED` under its predeclared gate;
- durable indexed result;
- quantitative improvement on primary metrics;
- no prohibited camera-family, optical-black, completion, saturation or boundary regression;
- clean-room source and contributor records;
- regression tests using shared contracts;
- a separate focused production PR.

`PARTIAL` and an oracle-assisted diagnosis are not sufficient for production promotion.

## Do not repeat without new evidence

The current record already rejects or saturates:

- pure N/W/NW predictor swaps and broad predictor-family sweeps;
- tile/plane state continuation and general per-plane predictor splits;
- conventional zig-zag sign mapping;
- universal/current-symbol logarithmic K brackets;
- broad alignment and CFA rediscovery;
- transition-frequency fishing;
- combined `Sd`/`Kd`/reset searches on the same lossless single-subband panel;
- passive LibRaw metric fitting that adds no new causal observation;
- blind C-RAW sweeps without lossy ground truth.

## Historical plans

`docs/cr3-agent-playbook.md` and `docs/cleanroom-fixture-plan.md` now point here. Their D5/Phase-A defaults are superseded, while Git history preserves their rationale.

## Related documentation

- [CR3 overview](index.md)
- [Current state](current-state.md)
- [Open hypotheses](open-hypotheses.md)
- [Results index](reports/index.md)
- [Clean-room engineering record](../cleanroom/README.md)
- [Reference export pipeline](../reference-pipeline.md)
- [Controlled pattern pack](../cr3-patterns/README.md)
- [Reference-testkit README](../../canon-cr3-reference-testkit/README.md)
- [Research module README](../../canon-cr3-research/README.md)
