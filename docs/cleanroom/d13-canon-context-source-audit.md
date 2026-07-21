# D13 Canon tentative-symbol and Kd context audit

## Final status

**`PRIMARY_SOURCE_OR_MAPPING_INSUFFICIENT` — no decoder candidate authorized.**

D13 follows the PARTIAL D12 result. It separates the remaining public-source statements from CRX mapping hypotheses and combines them with the executed lossless topology inventory. No production or test decoder semantics are introduced here.

## Clean-room boundary

Permitted inputs:

- registered Canon patent application US 2016/0323602 A1 and directly related registered public family publications;
- public bibliographic and figure records for that family;
- project-owned D5–D13 reports and fixed-fixture structure observations;
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

| Topic | Registered primary-source location | Final classification |
|---|---|---|
| Latest/current symbol `Sx` | FIG. 11; FIG. 12 steps S1201–S1206; claim 3 | `PATENT_EXPLICIT` variable; CRX state mapping `UNRESOLVED` |
| Reference symbol `Sd` | FIG. 11; FIG. 12 step S1202; claims 3–4 | `PATENT_EXPLICIT` variable; coordinate, lifecycle and CRX mapping `UNRESOLVED` |
| LL tentative symbol `Sn` | FIG. 12 steps S1203–S1204; claim 3 | `PATENT_EMBODIMENT`: `Sn=(Sx+Sd)/2` |
| High-pass tentative symbol | FIG. 12 steps S1205–S1206; claim 3 | `PATENT_EMBODIMENT`: base estimate uses `Sx` |
| Past parameter `Kd` correction | FIG. 12 step S1207; claims 5–6 | correction existence `PATENT_EMBODIMENT`; complete executable rule `UNRESOLVED` |
| Exact base K range | FIG. 10A/10B; FIG. 12 S1204/S1206 | `PATENT_EXPLICIT`, completed by D11 |

No new patent equation or CRX relationship is inferred by D13.

## Source-backed boundary

### Tentative next-symbol principle

`PATENT_EXPLICIT`

The succeeding entropy parameter is selected from an estimated or tentative succeeding symbol. This does not support a universal rule driven only by the current decoded symbol.

### LL embodiment

`PATENT_EMBODIMENT`

The disclosed LL path forms:

```text
Sn = (Sx + Sd) / 2
```

The following remain `UNRESOLVED`:

- exact integer rounding;
- the exact coordinate or state represented by `d` in each boundary case;
- initialization when the reference is unavailable;
- row, tile, plane and subband reset behavior;
- correspondence to current CRX records.

### First high-pass embodiment

`PATENT_EMBODIMENT`

The disclosed base estimate is:

```text
Sn = Sx
```

D12 tested a current-symbol base range without proving that any tested lossless CRX record represents a high-pass subband or that the later correction is absent.

### Kd correction

`PATENT_EMBODIMENT` / `UNRESOLVED`

Step S1207 and claims 5–6 disclose an additional correction involving a past entropy parameter `Kd`. The registered source evidence does not provide a complete implementation-safe rule for:

- comparison operands and direction;
- state origin and lifecycle;
- predetermined increment across embodiments;
- clamp and tie behavior;
- initialization and unavailable-history behavior;
- ordering relative to the base update;
- exact subband applicability.

No `Kd` Java model or decoder candidate is permitted from this evidence.

## Executed first-party topology result

D13 executed the fixed eleven-fixture lossless inventory and found:

- 88 records;
- all 88 with `waveletLevels=0` and `subband=0`;
- two tile sequences and four planes per tile for every fixture;
- 77 byte-contiguous transitions, no gaps and no overlaps;
- one stable topology per camera family;
- two geometry/topology variants across the full panel.

`waveletLevels` is the image-wide CMP1 decomposition count, not a per-record level. For lossy data the current extractor's plane dimensions are the undecomposed grid, not per-subband coefficient dimensions.

The fixed lossless panel therefore exposes no deeper decomposition relation and no LL/HL/LH/HH identity. Earlier file order does not establish decoder-time causality.

## D12/D13 combined interpretation

D12 showed a lower optical-black median but failed global, boundary, saturation, family and stream-completion gates. D13 now establishes that the fixed lossless topology cannot identify the missing tentative-symbol context.

The combined evidence is compatible with a correct base K range driven by the wrong or incomplete context. It is not proof of any particular `Sd`, `Sn` or `Kd` mapping, and it does not justify choosing a neighbour or role through image metrics.

## Final decision gate

`EXACT_CONTEXT_CANDIDATE_AVAILABLE` required one mapping that:

1. followed an explicit or clearly delimited source embodiment;
2. was representable from already decoded causal state;
3. was structurally consistent across the evidence;
4. required exactly one later semantic change;
5. did not depend on output metrics.

No mapping satisfies the first three requirements. The final verdict is:

```text
PRIMARY_SOURCE_OR_MAPPING_INSUFFICIENT
```

Semantic decoder work remains blocked until genuinely new registered primary-source or multi-subband structural evidence exists. Production remains unchanged.
