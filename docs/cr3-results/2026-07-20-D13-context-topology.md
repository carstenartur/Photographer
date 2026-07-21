# D13 Tentative-symbol context and CRX topology

## Final verdict

**`PRIMARY_SOURCE_OR_MAPPING_INSUFFICIENT`**

D13 found no source-supported and causally representable `Sd`/`Kd` context mapping for the current CRX research structures. No further semantic decoder candidate is authorized from the present evidence.

No production decoder behavior changed.

## Evidence binding

- executed code commit: `5df1851f7da7642e3a55e7684fb5d04c71aa9edf`;
- D13 workflow run: `29799183124`;
- artifact digest: `sha256:a9bb5223a3c23de3712e328ec86261cc476ffac1711ce8fd4ab7fbdd0199ae94`;
- fixed inputs: the eleven paths in `KAdaptationFixtureSet.PANEL`;
- permitted registered inputs: `SRC-001`, `SRC-007`, `SRC-008`.

The generated Markdown, TSV and JSON are deterministic and include every exposed record and payload range.

## Executed topology summary

| Observation | Result |
|---|---:|
| Fixtures | 11 |
| Exposed records | 88 |
| Extracted payload bytes | 220,347,400 |
| Records with `waveletLevels=0`, `subband=0` | 88 |
| Byte-contiguous record transitions | 77 |
| Gaps | 0 |
| Overlaps | 0 |
| Distinct fixture topology variants | 2 |
| Families with internal topology variation | 0 |
| One topology shared by every fixture | no |

Every fixture contains two tile sequences, four planes per tile and one subband per plane. All payload records after the first record of a fixture start exactly where the preceding record ends.

## Camera-family result

| Family | Fixtures | Distinct topology variants |
|---|---:|---:|
| EOS R | 3 | 1 |
| G5 X Mark II research | 3 | 1 |
| Sample series | 5 | 1 |

The EOS R fixtures use a `1722×2273`, 14-bit plane grid. The G5 X Mark II and sample-series fixtures use the second observed plane-grid topology, while remaining internally uniform within their recorded families. Payload size is deliberately excluded from the structural signature because it is content-dependent.

The two variants do not reveal different entropy context rules. They establish only that the fixture inventory contains two stable geometry/topology families.

## Corrected extractor boundary

`SubbandRef` exposes actual absolute payload offsets and sizes. D13 records each half-open range as:

```text
[payloadOffset, payloadOffset + payloadSize)
```

D13 also corrects two earlier overinterpretations:

1. `waveletLevels` is the image-wide CMP1 decomposition count, not a per-record wavelet level.
2. For lossy data, the exposed plane width and height describe the undecomposed plane grid, not per-subband coefficient dimensions.

Therefore dimensions and file order cannot be used to manufacture a deeper-level parent or claim decoder-time causal availability.

## Fixed-panel limitation established by execution

All 88 records satisfy:

```text
declaredWaveletLevels = 0
subbandIndex = 0
causalAssessment = NOT_APPLICABLE_WAVELET_LEVELS_0
```

Consequently, the fixed reference-backed lossless panel can establish:

- record ordering;
- tile and plane structure;
- absolute payload ranges;
- byte continuity;
- fixture and camera-family topology variants.

It cannot establish:

- a deeper decomposition-level parent;
- LL/HL/LH/HH identity;
- a causal `Sd` source;
- `Kd` state origin or lifecycle;
- row, tile, plane or subband reset behavior for the patent embodiment;
- causal availability merely because another record occurs earlier in the file.

This is a completed negative result, not a missing test implementation.

## Source-analysis result

The primary-source boundary remains:

- the LL embodiment discloses `Sn=(Sx+Sd)/2`;
- the first disclosed high-pass path uses `Sn=Sx` before a possible later correction;
- a `Kd`-related correction is disclosed;
- the complete implementable `Kd` arithmetic, comparison, state origin, initialization, clamp and CRX role mapping remain unresolved.

D13 found no structural information in the fixed lossless panel that closes those source gaps. D12's mixed quality result cannot be used to choose a neighbour, subband role or state lifecycle because that would be output-driven fitting.

## Decision against a D14 semantic candidate

The predeclared `EXACT_CONTEXT_CANDIDATE_AVAILABLE` gate required one mapping that was:

1. source-supported;
2. causally available from already decoded state;
3. structurally consistent across the evidence;
4. selected without image-quality fitting;
5. isolatable as exactly one semantic change.

No candidate satisfies the first three requirements. D13 therefore concludes:

```text
PRIMARY_SOURCE_OR_MAPPING_INSUFFICIENT
```

A D14 decoder experiment is blocked. The next useful work must obtain genuinely new structural or public-primary-source evidence, such as a legally usable multi-subband corpus with independently established subband geometry or a public source that fully defines `Sd` and `Kd`. Repeating current-symbol K rules, neighbour searches or subband permutations is not justified.

## Additional defect fixed during D13

The focused verification exposed a D12 test/implementation contradiction: a fixture with full completion was still classified as a completion regression solely because its decoder result carried an exhaustion flag. The verdict logic now treats exhaustion as a regression only when completion is actually below one. The existing regression test now passes. This change affects research-gate classification only and does not modify production decoding.

## Production decision

**No production decoder change.** Keep the current RAW decoder behavior unchanged and retain D13 as a durable blocking result.
