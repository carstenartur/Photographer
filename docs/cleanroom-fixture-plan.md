# Clean-room Fixture Planning

The current CR3/CRX research and fixture-acquisition process is maintained at:

- [CR3 Manual — Research process](cr3/research.md)
- [CR3 Manual — Current state](cr3/current-state.md)
- [CR3 Manual — Open hypotheses](cr3/open-hypotheses.md)
- [Controlled diagnostic pattern pack](cr3-patterns/README.md)
- [Reference export pipeline](reference-pipeline.md)
- [Clean-room engineering record](cleanroom/README.md)

## Historical status

The previous content was the Phase-A continuation plan. It proposed ordinary dark, flat, detail, burst and paired RAW/C-RAW captures for questions that have since been narrowed by D5–D13 and RG1.

That plan is **not an active unconditional capture list**. D13 shows that the fixed lossless panel is structurally saturated for the current context questions. RG1 shows that correcting the first sample still leaves the first non-zero residual incorrectly decoded, but passive reference values do not reveal the missing private state transition.

The current useful acquisition is issue #249's controlled first-party corpus:

- deterministic display pattern and exact presentation state;
- fixed camera settings and recorded model/firmware/connection mode;
- JPEG or embedded-preview observation as the camera-visible calibration channel;
- matching CR3 from the same exposure;
- duplicate or ABBA captures to establish noise;
- machine-readable stable payload differences;
- independent reproduction with another stimulus family.

No direct monitor-pixel-to-sensor-pixel assumption is required. New ordinary photographs without a predeclared causal question do not authorize another entropy sweep.

## Requirements for a new fixture set

Before capture, document the exact hypothesis or support claim, novelty over the existing corpus, all controlled hardware/display/camera settings, pairing order, naming/manifest/checksum rules, calibration/reference channel, metrics, rejection criteria, backup and redistribution status.

After capture, preserve originals or deterministic private bindings, generate permitted sidecars, validate manifests, run shared research gates and record accepted, rejected or insufficient outcomes in the maintained CR3 state documents.

Git history preserves the detailed Phase-A matrix. This page remains at the old path so existing links do not present it as current default work.
