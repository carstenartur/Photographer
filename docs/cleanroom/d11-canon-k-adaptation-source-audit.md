# D11 Canon K-adaptation primary-source audit

## Decision

**`EXACT_CANDIDATE_AVAILABLE`**, narrowly scoped to the base Golomb-Rice
code-length bracket and the one-step movement toward that bracket.

This decision does **not** claim that US 2016/0323602 A1 is the exact CRX
bitstream specification. It means only that one previously guessed component
can now be replaced by a deterministic rule stated by the public primary
source and independently derived from its disclosed code-length equation.

A later D12 experiment may change exactly this one component. It must not
combine the bracket with a new predictor, subband mapping, reset rule, escape
rule, initial K, `Sd` reconstruction or `Kd` correction.

## Primary source

| Field | Value |
|---|---|
| Publication | US 2016/0323602 A1 |
| Correct title | *Image encoding apparatus and control method of the same* |
| Assignee | Canon Inc. |
| Inventors | Hiroshi Oto; Masaki Kitago |
| Priority | JP 2015-092369, 2015-04-28 |
| Public description | Google Patents publication record and the published US application |
| Relevant figures | FIG. 10A, FIG. 10B, FIG. 11, FIG. 12, FIG. 13 |
| Relevant flow steps | S1201-S1207 |
| Relevant claims | Claims 1-6, especially claims 3-5 |

The publication is an **encoder** disclosure. Decoder use is an interoperability
inference: a decoder must reproduce the same symbol and K state in order to
consume the encoder's codewords, but the patent does not identify the Canon CRX
container or state that every CRX file uses this embodiment.

## Evidence classifications

- **PATENT_EXPLICIT** - directly stated in the published description, figure,
  flow step or claim.
- **PATENT_EMBODIMENT** - a disclosed implementation choice rather than a
  universal CRX requirement.
- **PUBLIC_STANDARD_BACKGROUND** - generic Golomb-Rice, JPEG-LS or wavelet
  terminology.
- **CRX_HYPOTHESIS** - project inference about the on-disk CRX format.
- **UNRESOLVED** - the public primary source does not establish the required
  concrete rule.

## Extraction table

| ID | Source location | Extracted rule | Classification | Consequence |
|---|---|---|---|---|
| K-01 | FIG. 10A and description of the Golomb-Rice procedure | For non-negative symbol `S` and K parameter `K`, the code contains `S >> K` unary digits, one stop digit and `K` remainder bits. | PATENT_EXPLICIT | The code length is `L(S,K) = (S >> K) + 1 + K` for the disclosed bit ordering. |
| K-02 | FIG. 10B and description immediately before the K-update section | A symbol can have more than one K value with the same minimum code length. The patent explicitly gives `S=6` with optimum `K=2..3`. | PATENT_EXPLICIT | The optimum is a range, not a single `floor(log2(S+1))` estimate. |
| K-03 | FIG. 12, step S1204; examples for `S=2` and `S=4` | `Kmin(S)` is the lowest K producing the minimum code length and `Kmax(S)` the highest. Examples: `Kmin(2)=0`, `Kmax(2)=2`; `Kmin(4)=1`, `Kmax(4)=3`. Values may be stored in a table. | PATENT_EXPLICIT | `Kmin`/`Kmax` are exactly the minimum and maximum elements of `argmin_K L(S,K)`. No guessed delta is required. |
| K-04 | FIG. 12, step S1204 | If `Kx < Kmin(Sn)`, use `Kn=Kx+1`; if `Kx` is in the optimum range, keep it; if `Kx > Kmax(Sn)`, use `Kn=Kx-1`. | PATENT_EXPLICIT | The base update changes K by at most one per symbol. |
| K-05 | FIG. 11; FIG. 12 steps S1201-S1204; claim 3 | For LL, `Sx` is the latest encoded symbol, `Sd` is the symbol at the disclosed causal/co-located reference position, and the tentative next symbol is `Sn=(Sx+Sd)/2`. | PATENT_EMBODIMENT | A full LL implementation needs the specified neighbouring symbol state. The integer rounding rule is not made explicit in the accessible text and remains unresolved. |
| K-06 | FIG. 12 steps S1205-S1206; claim 3 | For HL/LH/HH in the first embodiment, `Sn=Sx`, followed by the same code-length-bracket update as S1204. | PATENT_EMBODIMENT | The exact base bracket can be tested without inventing `K* +/- delta`. |
| K-07 | FIG. 12 step S1202; claim 4 | The embodiment reconstructs `Sd` without buffering its symbol, using a pseudo predictive value for coefficient `d`. | PATENT_EMBODIMENT | This is not evidence that CRX stores or reconstructs `Sd` in the same way. |
| K-08 | FIG. 12 step S1207; claims 5-6 | For non-LL paths the first embodiment can correct estimated `Kn` using a past `Kd`; claim 5 describes adding a predetermined value when `Kd` exceeds the estimate by the disclosed condition. | PATENT_EMBODIMENT | The base K bracket is exact, but the complete non-LL embodiment is not represented by the D6/D8 current-symbol rule alone. |
| K-09 | Description following FIG. 12/FIG. 13 | K is generally about `0..15`, so a small K buffer is sufficient. | PATENT_EMBODIMENT | `15` is a reasonable patent-model bound, not proof of the CRX field limit. |
| K-10 | Claims 1-3 | The claimed core is estimation of a tentative succeeding symbol and selection of the succeeding entropy parameter from the code length obtained with the current parameter. | PATENT_EXPLICIT | The patent's core is history/spatially informed next-symbol estimation, not a universal function of the current symbol only. |

## Exact base bracket

For an allowed K domain `0..KmaxGlobal`:

```text
L(S, K) = (S >> K) + 1 + K

minimumLength(S) = min L(S, K)
                   K in [0, KmaxGlobal]

Kmin(S) = min { K | L(S,K) = minimumLength(S) }
Kmax(S) = max { K | L(S,K) = minimumLength(S) }
```

The next K in the base S1204/S1206 update is:

```text
if Kx < Kmin(Sn): Kn = Kx + 1
else if Kx > Kmax(Sn): Kn = Kx - 1
else: Kn = Kx
```

This is exact for the disclosed base update and avoids an unnecessary closed
form. A lookup table and an on-demand code-length calculation are equivalent
provided they use the same finite K domain.

### Small-symbol discriminator table

| S | Exact optimum K range | D6/D8 `floor(log2(S+1)) +/- 1` range | Difference |
|---:|---:|---:|---|
| 0 | `0..0` | `0..1` | Approximation incorrectly permits holding K=1. |
| 1 | `0..1` | `0..2` | Approximation has an extra upper value. |
| 2 | `0..2` | `0..2` | Same. |
| 3 | `1..2` | `1..3` | Approximation has an extra upper value. |
| 4 | `1..3` | `1..3` | Same; matches the patent example. |
| 6 | `2..3` | `1..3` | Approximation has an extra lower value. |
| 7 | `2..3` | `2..4` | Approximation has an extra upper value. |
| 8 | `2..4` | `2..4` | Same. |

The D7/D8 zero-symbol finding is therefore explained by the exact code-length
rule: for `S=0`, only `K=0` is optimal. D8's explicit zero contraction repaired
that special case, but D10 showed that transition-class presence alone still
cannot select a universal current-symbol rule.

## Discrepancy against existing candidates

| Candidate | State input | Optimum range | Spatial/history input | Source status |
|---|---|---|---|---|
| HYBRID | accumulated absolute residuals and sample count | implicit thresholds | temporal history only | project hypothesis |
| D6 | current `S` | `floor(log2(S+1)) +/- 1` | none | rejected approximation |
| D8 | current `S`, with explicit zero contraction | same approximate non-zero bracket | none | partial, not production |
| Patent base S1204/S1206 | tentative next symbol `Sn` | exact `argmin L(Sn,K)` range | `Sn` depends on subband embodiment | primary-source rule |

## What is still unresolved

1. **CRX applicability.** The patent does not identify the CRX box/payload
   syntax and does not prove that the fixed research panel uses this embodiment.
2. **Subband identity.** Mapping CRX tile/plane/subband records to LL/HL/LH/HH
   remains empirical.
3. **Initial K and reset boundaries.** The accessible disclosure does not provide
   a CRX-specific initial K, tile reset or row reset rule.
4. **LL mean rounding.** `(Sx+Sd)/2` is stated, but the exact integer rounding
   convention is not explicit in the accessible text.
5. **`Sd` reconstruction.** The disclosed pseudo-prediction is an embodiment,
   not a proven CRX requirement.
6. **S1207 `Kd` correction.** The structure is disclosed, but the image equation
   and its exact boundary must be preserved independently before implementing it.
7. **Run-length and escape coupling.** The third embodiment adds run-length
   coding; no evidence yet proves which mode is present in the current CRX panel.
8. **Global K limit.** `0..15` is described as general/practical, not as a CRX
   syntax constraint.

## D12 gate

A D12 research candidate is justified only for this one change:

> Replace the D6/D8 approximate non-zero K bracket with the exact finite-domain
> Golomb-Rice code-length argmin bracket, while keeping all other D8 test-decoder
> semantics and the fixed eleven-fixture panel unchanged.

Predeclared requirements:

- exact same eleven fixtures and D8 reference outcomes;
- production decoder unchanged;
- no change to symbol parsing, sign mapping, predictor, reset, padding, geometry,
  completion semantics, `Sn` source or K seed;
- compare exact bracket against D8 using global, optical-black, boundary,
  saturation and completion gates;
- retain negative results and bind the run in the clean-room ledger.

A full patent-embodiment candidate remains blocked until the unresolved items
above have primary-source or first-party evidence.

## Clean-room statement

This audit used the public Canon patent publication, public patent metadata and
project-owned D5-D10 results. No source code from LibRaw, dcraw, RawTherapee,
darktable or any other CR3/CRX decoder, and no decompiled proprietary binary,
was consulted or translated.
