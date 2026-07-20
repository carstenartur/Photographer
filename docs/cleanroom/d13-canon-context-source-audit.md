# D13 Canon tentative-symbol and Kd context audit

## Status

**IN PROGRESS — no decoder candidate authorized.**

D13 follows the PARTIAL D12 result. D12 established that the exact public-primary-source Golomb-Rice K range is not sufficient when the currently decoded symbol is used directly as the tentative next symbol. This audit isolates the still-missing context before any further semantic experiment.

## Clean-room boundary

Permitted inputs:

- Canon patent application US 2016/0323602 A1 and directly related public family publications;
- public bibliographic and figure records for that family;
- project-owned D5–D12 reports and fixed-fixture structure observations;
- registered public standards for terminology only.

Prohibited inputs:

- source code from LibRaw, dcraw, RawTherapee, darktable or another CR3/CRX decoder;
- decompiled proprietary binaries;
- output-quality-driven neighbour or subband searches;
- unattributed pseudocode, equations or constants.

## Evidence labels

| Label | Meaning |
|---|---|
| `PATENT_EXPLICIT` | Direct text, claim, numbered flow step or unambiguous figure relation. |
| `PATENT_EMBODIMENT` | One disclosed implementation choice, not proof of the unique CRX rule. |
| `FIRST_PARTY_OBSERVATION` | Deterministic structure observed from project-controlled fixtures. |
| `CRX_HYPOTHESIS` | Proposed mapping from the public disclosure to CRX records. |
| `UNRESOLVED` | Insufficient source or structural evidence for implementation. |

## Fixed source locations

| Topic | Primary-source location | Current classification |
|---|---|---|
| Latest/current symbol `Sx` | FIG. 11; FIG. 12 steps S1201–S1206; claim 3 | `PATENT_EXPLICIT` variable, `PATENT_EMBODIMENT` use in the disclosed coding path |
| Reference symbol `Sd` | FIG. 11; FIG. 12 step S1202; claims 3–4 | `PATENT_EXPLICIT` variable; exact CRX coordinate and lifecycle `UNRESOLVED` |
| LL tentative symbol `Sn` | FIG. 12 steps S1203–S1204; claim 3 | `PATENT_EMBODIMENT`: `Sn=(Sx+Sd)/2` |
| High-pass tentative symbol | FIG. 12 steps S1205–S1206; claim 3 | `PATENT_EMBODIMENT`: base estimate uses `Sx` |
| Past parameter `Kd` correction | FIG. 12 step S1207; claims 5–6 | correction existence `PATENT_EMBODIMENT`; full implementable equation `UNRESOLVED` |
| Exact base K range | FIG. 10A/10B; FIG. 12 S1204/S1206 | `PATENT_EXPLICIT`, completed by D11 |

## What is source-backed

### 1. Tentative next-symbol principle

`PATENT_EXPLICIT`

The succeeding entropy parameter is selected from an estimated or tentative succeeding symbol. The core disclosure is therefore not a universal rule driven only by the current symbol value.

### 2. LL embodiment

`PATENT_EMBODIMENT`

The disclosed LL path forms:

```text
Sn = (Sx + Sd) / 2
```

where `Sx` is the latest symbol and `Sd` is a disclosed causal/spatial or deeper-level reference symbol. The publication describes a mechanism for deriving a pseudo predictive value so that every underlying symbol need not be buffered.

The following are not yet implementation-safe:

- exact integer rounding for the division;
- the exact coordinate represented by `d` in every boundary case;
- whether the reference comes from a deeper decomposition level, another subband, or a reconstructed causal value in the current CRX organisation;
- initialization when the reference is unavailable;
- reset at row, tile, plane or subband boundaries.

### 3. First high-pass embodiment

`PATENT_EMBODIMENT`

For the first disclosed HL/LH/HH path, the tentative value is based on the latest symbol:

```text
Sn = Sx
```

The exact D11 code-length range is then applied. D12 tested only this current-symbol base bracket without proving that the tested CRX records are high-pass subbands or that the later correction is absent.

### 4. Kd correction

`PATENT_EMBODIMENT` / `UNRESOLVED`

Step S1207 and claims 5–6 disclose an additional correction involving a past entropy parameter `Kd`. The public text establishes that the correction exists and that one claimed form adds a predetermined value under a stated relationship.

D13 does not yet have enough paragraph/figure-equation detail to implement the correction without guessing:

- the exact comparison operands and direction;
- whether `Kd` is from the same spatial position, preceding symbol, deeper level or another subband;
- the predetermined increment value in every embodiment;
- clamp and tie behavior;
- availability and initialization rules;
- whether the correction applies before or after the base one-step update;
- whether it applies to all high-pass subbands or only a subset.

Until these are resolved from the primary publication or an equivalent public family figure, no `Kd` Java model or decoder candidate is permitted.

## D12 implications

D12 produced these stable signals:

- corpus optical-black median improved;
- global and boundary medians regressed substantially;
- all camera-family median gates failed;
- nine tile/plane streams lost completion and seven recovered partially;
- no fixture became complete;
- no stream became newly exhausted;
- millions of K decisions differed from D8.

This is compatible with a correct base bracket being driven by the wrong context, but it is not proof of a particular `Sd`, `Sn` or `Kd` mapping.

## Required topology evidence

The fixed eleven-fixture topology report must list, in deterministic order:

- fixture;
- tile sequence;
- plane index;
- subband index;
- wavelet level;
- dimensions and bit depth;
- payload location/range where exposed by the clean-room parser;
- possible same-position parent/deeper-level relationships based only on dimensions and record ordering;
- whether each possible relationship is causal at decoder time;
- row/tile/plane boundary discontinuities;
- camera-family contradictions.

No topology candidate may be selected by rendered-image quality.

## Decision gate

D13 can conclude `EXACT_CONTEXT_CANDIDATE_AVAILABLE` only if one mapping:

1. follows an explicit or clearly delimited patent embodiment;
2. is representable from already decoded causal state;
3. is structurally consistent across the fixed panel;
4. requires exactly one later semantic change;
5. does not depend on output metrics to choose coordinates or subbands.

Otherwise the required verdict is:

```text
PRIMARY_SOURCE_OR_MAPPING_INSUFFICIENT
```

and semantic decoder work remains blocked.
