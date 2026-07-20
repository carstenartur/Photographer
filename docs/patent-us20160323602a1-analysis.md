# Clean-room analysis of Canon patent application US 2016/0323602 A1

> **Status:** primary-source engineering analysis, corrected by D11.
>
> **Publication title:** *Image encoding apparatus and control method of the same*.
>
> This document distinguishes statements made by the public patent publication
> from hypotheses about Canon CRX files. It is not legal advice and does not
> claim that the patent is a normative CRX file-format specification.

The detailed paragraph/figure extraction, evidence classifications and D12 gate
are maintained in
[`cleanroom/d11-canon-k-adaptation-source-audit.md`](cleanroom/d11-canon-k-adaptation-source-audit.md).

## 1. Clean-room boundary

Permitted inputs for this analysis:

- the public US patent publication and its public bibliographic record;
- public standards for terminology and general coding background;
- fixtures, measurements and negative experiments produced by this project.

Not used as implementation inputs:

- source code from LibRaw, dcraw, RawTherapee, darktable or another CR3/CRX decoder;
- decompiled proprietary binaries;
- unattributed constants, pseudocode or generated patches.

Black-box reference images remain expected-output data. They are not an algorithm specification.

## 2. Evidence labels

| Label | Meaning |
|---|---|
| `PATENT_EXPLICIT` | Directly stated in text, a claim, a figure or a numbered flow step. |
| `PATENT_EMBODIMENT` | A disclosed implementation choice that is not established as the unique CRX rule. |
| `PUBLIC_STANDARD_BACKGROUND` | General public coding terminology or background. |
| `CRX_HYPOTHESIS` | A project inference about Canon's on-disk CRX representation. |
| `UNRESOLVED` | The available primary source is insufficient to define the concrete rule. |

No embodiment or project inference may be presented as an exact CRX semantic without fixture-backed validation.

## 3. What the patent establishes for Golomb-Rice coding

### 3.1 Code length

`PATENT_EXPLICIT`

For a non-negative symbol `S` and Rice parameter `K`, the disclosed code has a unary quotient derived from `S >> K`, one stop digit and a `K`-bit remainder. The resulting length is:

```text
L(S, K) = (S >> K) + 1 + K
```

### 3.2 Optimal K is a range

`PATENT_EXPLICIT`

A symbol may have several K values with the same minimum length. The patent therefore defines:

```text
minimumLength(S) = min L(S,K)
Kmin(S) = lowest  K attaining minimumLength(S)
Kmax(S) = highest K attaining minimumLength(S)
```

The minimisation is over the allowed finite K domain. A lookup table and direct evaluation of the formula are equivalent.

Examples disclosed by the patent include:

| Symbol | Exact optimum range |
|---:|---:|
| `2` | `K=0..2` |
| `4` | `K=1..3` |
| `6` | `K=2..3` |

These examples disprove the earlier project assumption that the optimum bracket can generally be represented as `floor(log2(S+1)) +/- 1`.

### 3.3 Base K update

`PATENT_EXPLICIT`

Given current parameter `Kx` and tentative next symbol `Sn`:

```text
if Kx < Kmin(Sn): Kn = Kx + 1
else if Kx > Kmax(Sn): Kn = Kx - 1
else: Kn = Kx
```

The base update changes K by no more than one per symbol. The test-scope model `PatentGolombRiceKRange` implements only this exact finite-domain calculation; it does not claim to implement the complete patent embodiment or CRX decoder.

## 4. Tentative next-symbol estimation

### 4.1 LL embodiment

`PATENT_EMBODIMENT`

For the disclosed LL path:

```text
Sn = (Sx + Sd) / 2
```

The publication also describes reconstructing `Sd` without retaining every symbol. The precise integer rounding convention and mapping of this state to CRX records remain unresolved.

### 4.2 HL, LH and HH embodiment

`PATENT_EMBODIMENT`

For the first disclosed high-pass embodiment:

```text
Sn = Sx
```

The same exact code-length bracket is then used for the base K update.

### 4.3 Additional Kd correction

`PATENT_EMBODIMENT` / `UNRESOLVED`

The publication describes a further correction involving a past `Kd` value for non-LL paths. The accessible text establishes that such a correction exists, but its complete image equation and all boundary details have not yet been transcribed with sufficient confidence for implementation. D11 therefore does not implement it.

## 5. Exact bracket versus previous project candidates

| S | Exact `argmin L(S,K)` range | Former D6/D8 approximation |
|---:|---:|---:|
| 0 | `0..0` | `0..1` |
| 1 | `0..1` | `0..2` |
| 2 | `0..2` | `0..2` |
| 3 | `1..2` | `1..3` |
| 4 | `1..3` | `1..3` |
| 6 | `2..3` | `1..3` |
| 7 | `2..3` | `2..4` |
| 8 | `2..4` | `2..4` |

The exact range explains the D7/D8 zero-symbol observation: for `S=0`, only `K=0` is optimal. D8's explicit zero contraction repaired that case but retained an inaccurate non-zero bracket.

## 6. Relationship to D5-D10

| Experiment | Result relevant to this analysis |
|---|---|
| D5 | A broad replacement of the existing sign mapping was rejected. |
| D6 | The universal `floor(log2(S+1)) +/- 1` bracket was rejected. |
| D7 | The first D6 divergence was associated with an identical zero symbol and different K transition. |
| D8 | Zero contraction was useful but insufficient; corpus and completion gates failed. |
| D9 | Three representative fixtures produced different first non-zero decision classes. |
| D10 | No transition class predicted a failure without contradicting complete-improvement fixtures. |

D10 means that a frequent transition disagreement is not, by itself, a causal rule. D11 changes the basis of the next experiment: the candidate comes from a primary-source code-length definition, not from transition-frequency fitting.

## 7. What the patent does not prove about CRX

The publication does not establish:

- that current CRX payloads use this exact embodiment;
- how CRX tile/plane/subband records map to LL, HL, LH and HH;
- the initial K used by a CRX subband;
- row, tile or subband reset boundaries in the concrete format;
- the CRX escape or run-length mode;
- the exact integer rounding used for the LL mean;
- the complete `Kd` correction equation and all boundaries;
- that a practical K range mentioned by the patent is a CRX syntax limit;
- that one encoder embodiment is sufficient to decode every CRX variant.

These remain `CRX_HYPOTHESIS` or `UNRESOLVED` and must be tested independently.

## 8. D11 decision and D12 boundary

D11 concludes:

```text
EXACT_CANDIDATE_AVAILABLE
```

This decision is deliberately narrow. A D12 test may replace only the D6/D8 approximate non-zero bracket with the exact finite-domain `argmin L(S,K)` bracket while preserving the fixed corpus, parser, sign handling, predictor, K seed, reset state, geometry, padding, completion semantics and production decoder.

A full patent-embodiment candidate remains blocked until `Sd`, `Kd`, subband identity and CRX state boundaries have independent evidence.

## 9. Reproducibility record

The source identity, permitted use and repository evidence are registered as `SRC-001` in `docs/cleanroom/source-register.json`. The D11 extraction is preserved in the clean-room evidence bundle, and the pure Java model has fixture-independent unit tests for the patent examples, small-symbol ranges and one-step update.
