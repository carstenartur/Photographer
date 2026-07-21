# D13 extractor and topology boundary

## Purpose

This note corrects the executable boundary used by the D13 topology inventory. It does not define a new entropy rule and does not authorize a decoder candidate.

## Fields that are actually exposed

`SubbandExtractor.SubbandRef` provides the following first-party structural data:

- absolute `dataOffsetInFile`;
- `dataSize`;
- tile index and tile sequence;
- plane and subband index;
- declared CMP1 `waveletLevels`;
- plane width and height;
- bit depth and codec metadata.

The D13 report must therefore preserve the real half-open payload range:

```text
[dataOffsetInFile, dataOffsetInFile + dataSize)
```

It must not report the offset as unavailable.

## Two fields that must not be overinterpreted

### Declared wavelet levels

`waveletLevels` is the decomposition count declared by CMP1 for the image. It is not the level of one individual subband record.

### Plane dimensions

For a lossy image the current extractor reports the undecomposed plane grid. The values are not per-subband coefficient dimensions. They cannot identify a deeper-level parent until subband ordering and coefficient geometry are independently established.

## Fixed-panel consequence

The fixed D13 panel is the same reference-backed lossless panel used by D8 and D12. The integration test requires every record to have:

```text
declaredWaveletLevels = 0
subbandIndex = 0
```

The panel can establish:

- record order;
- tile and plane structure;
- absolute payload ranges;
- payload continuity, gaps or overlaps;
- cross-camera topology variants.

The panel cannot establish:

- a deeper decomposition-level relation;
- an LL/HL/LH/HH role assignment;
- a causal `Sd` source;
- a `Kd` lifecycle;
- decoder-time causality merely from earlier file-record order.

## Required classification

For the fixed panel, parent candidates are empty and causal assessment is:

```text
NOT_APPLICABLE_WAVELET_LEVELS_0
```

For a future multi-subband corpus using the current extractor, the classification remains:

```text
UNRESOLVED_NO_PER_SUBBAND_GEOMETRY_OR_ROLE
```

until per-subband geometry and ordering are independently supported.

## Decision impact

A uniform lossless topology would show that the test harness is structurally consistent across cameras. It would not provide the context mapping required for a new entropy candidate.

Unless a registered primary source supplies the missing `Sd`/`Kd` mapping and lifecycle independently, D13 must end with:

```text
PRIMARY_SOURCE_OR_MAPPING_INSUFFICIENT
```

Production and test decoder semantics remain unchanged.
