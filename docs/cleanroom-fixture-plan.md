# Clean-room Fixture Planning

The current CR3/CRX research and fixture-acquisition process is maintained at:

- [CR3 Manual — Research process](cr3/research.md)
- [CR3 Manual — Current state](cr3/current-state.md)
- [CR3 Manual — Open hypotheses](cr3/open-hypotheses.md)
- [Reference export pipeline](reference-pipeline.md)
- [Clean-room engineering record](cleanroom/README.md)

## Historical status

The previous content was the Phase‑A continuation plan. It proposed dark, flat, detail, burst and paired RAW/C‑RAW captures to unlock then-undecidable predictor, pedestal, stability and codec-mode comparisons.

That plan is **not an active unconditional capture list** after the D5–D13 research sequence. D13 shows that the fixed lossless panel is structurally saturated for the current entropy-context questions: all 88 records are lossless single-subband records and expose neither a deeper parent/subband role nor a causal `Sd`/`Kd` lifecycle.

New captures are justified only when they add evidence that the existing corpus cannot provide. In particular, useful acquisition now requires a predeclared decision such as:

- a legally usable multi-subband CRX corpus with independently established geometry and ordering;
- controlled paired captures that isolate one codec or camera variable and have independent reference outputs;
- first-party structural observations that expose a causal context boundary;
- a model/firmware capability snapshot needed for a concrete CCAPI support claim.

Adding more ordinary dark, flat, detail or burst images without such a novelty argument does not authorize another entropy sweep.

## Requirements for a new fixture set

Before capture, document:

1. the exact hypothesis or support claim;
2. why current fixtures cannot decide it;
3. camera model, firmware, connection/capture mode and all controlled settings;
4. scene, tripod/focus/motion constraints and paired/burst ordering;
5. expected reference type and independent generator;
6. naming, manifest and checksum rules;
7. primary metrics and rejection criteria;
8. backup and redistribution status.

After capture:

- preserve the original fixture or a deterministic private-reference binding;
- generate and hash-lock permitted sidecars through the reference pipeline;
- update manifests and inventory;
- run the shared reference-testkit gates;
- record accepted, rejected or insufficient outcome in the maintained CR3 state documents.

Git history preserves the detailed Phase‑A capture matrix and its rationale as historical planning evidence. This page remains at the old path so existing links do not present that historical matrix as the current default task.
