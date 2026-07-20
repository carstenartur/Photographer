# D13 Tentative-symbol context and CRX topology

## Status

**PENDING** — source extraction and fixed-panel topology observation are preregistered before execution.

No decoder or production semantic changes are permitted in D13.

## Motivation

D11 established the exact finite-domain Golomb-Rice K range. D12 showed that this range is not sufficient when the currently decoded symbol is used directly as the tentative next symbol:

- optical-black median improved;
- global, boundary, saturation and every camera-family gate failed;
- nine tile/plane streams regressed and seven recovered partially;
- no incomplete fixture became complete.

The next unresolved variables are the public patent embodiment's context values `Sx`, `Sd`, `Sn` and `Kd`, plus their possible mapping to current CRX subband records.

## Fixed inputs

Exactly these eleven fixtures, enforced by `KAdaptationFixtureSet.PANEL`:

- `eosr-iso-high.cr3`
- `eosr-iso-low.cr3`
- `eosr-iso-mid.cr3`
- `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3`
- `research/g5xm2-dark-iso125-ss160-ff7p1-20260504-192532-02.cr3`
- `research/g5xm2-flat-iso1600-ss0p4s-ff1p6-20260504-193022-02.cr3`
- `sample-iso-high.cr3`
- `sample-tele.cr3`
- `sample-wb-tungsten.cr3`
- `sample-wide.cr3`
- `sample.cr3`

Primary-source input is Canon patent application US 2016/0323602 A1 and any directly related public family document registered before use.

## Source-analysis boundary

Every statement about `Sd`, `Sn` or `Kd` must be labelled:

- `PATENT_EXPLICIT`;
- `PATENT_EMBODIMENT`;
- `CRX_HYPOTHESIS`; or
- `UNRESOLVED`.

The currently source-backed boundary is:

- LL embodiment: `Sn=(Sx+Sd)/2`;
- first high-pass embodiment: base estimate `Sn=Sx`;
- an additional `Kd` correction exists in step S1207/claims 5–6;
- the complete implementable `Kd` equation, initialization and CRX mapping are not yet established.

No missing equation may be reconstructed from third-party decoder code, decompiled binaries or output-driven parameter fitting.

## Topology observation

For every subband record exposed by the existing clean-room extractor, report deterministically:

- source record order;
- fixture and camera family;
- tile sequence and plane index;
- wavelet-level count and subband index;
- plane width, height and bit depth;
- extracted payload byte length;
- payload offset as `NOT_EXPOSED` when the current API does not expose it;
- dimension-compatible deeper-level candidates in the same tile/plane;
- the subset already available earlier in record order;
- first/last tile and first-plane/subband boundary flags.

A dimension candidate is reported only when:

```text
candidate.levels = current.levels + 1
abs(current.width  - 2*candidate.width)  <= 1
abs(current.height - 2*candidate.height) <= 1
```

This is a structural candidate, not a semantic conclusion.

## Prohibited selection criteria

D13 must not:

- decode a new symbol sequence;
- render or compare candidate images;
- use MAE, optical-black or boundary metrics to select topology;
- brute-force neighbour coordinates or subband permutations;
- combine `Sd`, `Kd`, reset, predictor or sign changes;
- promote D12 behavior to production.

## Decision gate

`EXACT_CONTEXT_CANDIDATE_AVAILABLE` requires one context mapping that:

1. is supported by an explicit or clearly delimited patent embodiment;
2. is available causally from already decoded state;
3. is structurally consistent across the fixed panel;
4. does not depend on output-quality selection;
5. can be isolated as exactly one later semantic change.

Otherwise the verdict is:

```text
PRIMARY_SOURCE_OR_MAPPING_INSUFFICIENT
```

and another decoder candidate remains blocked.

## Required outputs

- paragraph/claim/figure source matrix;
- deterministic topology Markdown, TSV and JSON;
- camera-family signature comparison;
- explicit causal/non-causal candidate counts;
- contradictions and missing extractor fields;
- one final verdict and production decision;
- clean-room ledger binding to commit, workflow run, artifact digest and durable report.
