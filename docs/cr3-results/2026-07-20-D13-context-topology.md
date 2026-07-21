# D13 Tentative-symbol context and CRX topology

## Status

**PENDING** — corrected source and topology boundaries are fixed before execution.

No decoder or production semantic changes are permitted in D13.

## Motivation

D11 established the exact finite-domain Golomb-Rice K range. D12 showed that this range is not sufficient when the currently decoded symbol is used directly as the tentative next symbol:

- optical-black median improved;
- global, boundary, saturation and every camera-family gate failed;
- nine tile/plane streams regressed and seven recovered partially;
- no incomplete fixture became complete.

The unresolved variables are the patent embodiment's `Sx`, `Sd`, `Sn` and `Kd` context and their possible relationship to CRX records.

## Fixed inputs

Exactly these eleven reference-backed lossless fixtures, enforced by `KAdaptationFixtureSet.PANEL`:

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

Primary-source input remains the registered Canon publication and the completed D11 extraction. No missing equation is filled by inference in D13.

## Corrected extractor boundary

The current `SubbandRef` exposes:

- original record order;
- tile index and tile sequence;
- plane and subband index;
- declared CMP1 wavelet-level count;
- plane grid and bit depth;
- absolute payload offset and payload size.

The report must preserve the actual half-open range:

```text
[payloadOffset, payloadOffset + payloadSize)
```

Two values must not be overinterpreted:

1. `waveletLevels` is the image-wide CMP1 decomposition count, not a per-record level.
2. For lossy files, the current plane width and height are the undecomposed grid, not per-subband coefficient dimensions.

The detailed boundary is recorded in `docs/cleanroom/d13-extractor-topology-boundary.md`.

## Fixed-panel assertions

D13 requires every record in the fixed panel to satisfy:

```text
declaredWaveletLevels = 0
subbandIndex = 0
```

This panel can measure:

- tile/plane record order;
- absolute payload ranges;
- continuity, gaps and overlaps;
- topology variants by fixture and camera family.

It cannot measure:

- a deeper-level parent;
- LL/HL/LH/HH identity;
- a causal `Sd` source;
- a `Kd` lifecycle;
- decoder causality merely from earlier file order.

## Source-analysis boundary

Every statement about `Sd`, `Sn` or `Kd` remains labelled as one of:

- `PATENT_EXPLICIT`;
- `PATENT_EMBODIMENT`;
- `CRX_HYPOTHESIS`;
- `UNRESOLVED`.

The source-backed boundary remains:

- LL embodiment: `Sn=(Sx+Sd)/2`;
- first high-pass embodiment: base estimate `Sn=Sx`;
- a later `Kd` correction exists;
- exact `Kd` arithmetic, state origin, initialization and CRX mapping remain unresolved.

## Prohibited selection criteria

D13 must not:

- decode a new symbol sequence;
- render or score candidate images;
- select topology with image metrics;
- search neighbour coordinates or subband permutations;
- combine `Sd`, `Kd`, reset, predictor or sign changes;
- promote D12 behavior to production.

## Decision gate

`EXACT_CONTEXT_CANDIDATE_AVAILABLE` requires one context mapping that:

1. is supported by an explicit or clearly delimited public-source embodiment;
2. is available causally from already decoded state;
3. is structurally consistent across the evidence;
4. does not depend on output-quality selection;
5. can be isolated as exactly one later semantic change.

The fixed lossless panel cannot by itself satisfy the deeper-level or high-pass mapping requirements. Unless an independently registered primary source closes those gaps, the final verdict is:

```text
PRIMARY_SOURCE_OR_MAPPING_INSUFFICIENT
```

and another decoder candidate remains blocked.

## Required outputs

- source matrix for `Sx`, `Sd`, `Sn` and `Kd`;
- deterministic topology Markdown, TSV and JSON;
- actual payload ranges;
- continuity, gap and overlap counts;
- camera-family topology variants;
- explicit lossless-panel limitation;
- final verdict and production decision;
- clean-room ledger binding to commit, workflow run, artifact digest and durable report.
