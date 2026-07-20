# CR3 / CRX Decoder Contribution Attestation

Use this text in a pull-request description when a change introduces or alters CR3/CRX container, entropy, predictor, wavelet, dequantisation or assembly semantics.

```text
Clean-room contribution attestation

Affected semantic area:

Permitted sources consulted:
- source-register ID(s):
- additional public sources proposed for registration:

Project-owned evidence used:
- fixtures:
- reference manifests:
- experiment/report IDs:

I confirm that I did not derive this contribution from source code or decompiled binaries of LibRaw, dcraw, RawTherapee, darktable or another third-party CR3/CRX decoder.

I did not copy unattributed pseudocode, constants or generated patches whose origin and licence cannot be established.

Reference-only tools were used only as black boxes to generate expected output; their implementation was not consulted.

The change is either:
- research/test scope only; or
- a production promotion backed by a SUPPORTED experiment and an explicit production-decision record.

Known prior exposure or possible contamination:
- none / describe:

Contributor:
Date:
```

## Reviewer checks

A reviewer should verify that:

- all cited source IDs exist in `source-register.json`;
- the change does not silently mix several semantic hypotheses;
- fixed fixtures and acceptance gates are declared before execution;
- test output is deterministic;
- production changes cite a supported experiment;
- new sources and possible contamination are handled explicitly.
