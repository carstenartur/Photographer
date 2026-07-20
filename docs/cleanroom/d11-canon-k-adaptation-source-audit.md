# D11 Canon K-adaptation primary-source audit

## Decision

**`EXACT_CANDIDATE_AVAILABLE`**, narrowly scoped to the base Golomb-Rice code-length bracket and the one-step movement toward that bracket.

This decision does **not** claim that US 2016/0323602 A1 is the exact CRX bitstream specification. It means only that one previously guessed component can now be replaced by a deterministic rule stated by the public primary source and independently derived from its disclosed code-length equation.

A later D12 experiment may change exactly this component. It must not combine the bracket with a new predictor, subband mapping, reset rule, escape rule, initial K, `Sd` reconstruction or `Kd` correction.

## Primary source

| Field | Value |
|---|---|
| Publication | US 2016/0323602 A1 |
| Correct title | *Image encoding apparatus and control method of the same* |
| Assignee | Canon Inc. |
| Inventors | Hiroshi Oto; Masaki Kitago |
| Priority | JP 2015-092369, 2015-04-28 |
| Relevant figures | FIG. 10A, FIG. 10B, FIG. 11, FIG. 12, FIG. 13 |
| Relevant flow steps | S1201-S1207 |
| Relevant claims | Claims 1-6, especially claims 3-5 |

The publication is an **encoder** disclosure. Decoder use is an interoperability inference: a decoder must reproduce the same symbol and K state to consume the encoder's codewords, but the patent does not identify the Canon CRX container or state that every CRX file uses this embodiment.

## Evidence classifications

- **PATENT_EXPLICIT** — directly stated in the published description, figure, flow step or claim.
- **PATENT_EMBODIMENT** — a disclosed implementation choice rather than a universal CRX requirement.
- **PUBLIC_STANDARD_BACKGROUND** — generic Golomb-Rice, JPEG-LS or wavelet terminology.
- **CRX_HYPOTHESIS** — project inference about the on-disk format.
- **UNRESOLVED** — the primary source does not establish the required concrete rule.

## Extraction table

| ID | Source location | Extracted rule | Classification | Consequence |
|---|---|---|---|---|
| K-01 | FIG. 10A and Golomb-Rice procedure | For non-negative symbol `S` and K parameter `K`, the code contains `S >> K` unary digits, one stop digit and `K` remainder bits. | PATENT_EXPLICIT | `L(S,K) = (S >> K) + 1 + K`. |
| K-02 | FIG. 10B and text before the K-update section | A symbol can have more than one K with the same minimum code length; `S=6` has optimum `K=2..3`. | PATENT_EXPLICIT | The optimum is a range, not one logarithmic estimate. |
| K-03 | FIG. 12 step S1204; examples for `S=2` and `S=4` | `Kmin(S)` is the lowest K producing minimum length and `Kmax(S)` the highest. Examples: `Kmin(2)=0`, `Kmax(2)=2`; `Kmin(4)=1`, `Kmax(4)=3`. Values may be stored in a table. | PATENT_EXPLICIT | `Kmin`/`Kmax` are the minimum and maximum elements of `argmin_K L(S,K)`. |
| K-04 | FIG. 12 step S1204 | If `Kx < Kmin(Sn)`, use `Kn=Kx+1`; if K is in the optimum range, keep it; if `Kx > Kmax(Sn)`, use `Kn=Kx-1`. | PATENT_EXPLICIT | Base update changes K by at most one. |
| K-05 | FIG. 11; FIG. 12 S1201-S1204; claim 3 | For LL, `Sx` is the latest symbol, `Sd` a disclosed causal/co-located reference symbol, and `Sn=(Sx+Sd)/2`. | PATENT_EMBODIMENT | A full LL implementation needs spatial/deeper state; integer rounding remains unresolved. |
| K-06 | FIG. 12 S1205-S1206; claim 3 | For HL/LH/HH in the first embodiment, `Sn=Sx`, followed by the same bracket update. | PATENT_EMBODIMENT | The exact base bracket can be tested without invented deltas. |
| K-07 | FIG. 12 S1202; claim 4 | The embodiment reconstructs `Sd` without buffering every symbol, using a pseudo predictive value. | PATENT_EMBODIMENT | Not proof that CRX reconstructs `Sd` identically. |
| K-08 | FIG. 12 S1207; claims 5-6 | Non-LL paths can correct estimated `Kn` using a past `Kd`; claim 5 describes adding a predetermined value under the disclosed condition. | PATENT_EMBODIMENT | Complete non-LL embodiment is more than D6/D8's current-symbol rule. Exact image equation remains unresolved. |
| K-09 | Text following FIG. 12/13 | K is generally small, around `0..15`, so a small buffer suffices. | PATENT_EMBODIMENT | `15` is a reasonable model bound, not proof of a CRX syntax limit. |
| K-10 | Claims 1-3 | The core estimates a tentative succeeding symbol and selects the succeeding entropy parameter from resulting code length. | PATENT_EXPLICIT | Core rule is next-symbol estimation, not a universal current-symbol-only formula. |

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

This avoids an unnecessary closed form. A lookup table and on-demand code-length calculation are equivalent when they use the same finite K domain.

### Small-symbol discriminator table

| S | Exact optimum K range | D6/D8 `floor(log2(S+1)) +/- 1` | Difference |
|---:|---:|---:|---|
| 0 | `0..0` | `0..1` | Approximation incorrectly permits holding K=1. |
| 1 | `0..1` | `0..2` | Extra upper value. |
| 2 | `0..2` | `0..2` | Same. |
| 3 | `1..2` | `1..3` | Extra upper value. |
| 4 | `1..3` | `1..3` | Same; matches patent example. |
| 6 | `2..3` | `1..3` | Extra lower value. |
| 7 | `2..3` | `2..4` | Extra upper value. |
| 8 | `2..4` | `2..4` | Same. |

The D7/D8 zero-symbol finding follows from the exact length rule: for `S=0`, only `K=0` is optimal. D8 repaired that special case, while its non-zero bracket remained an approximation.

## Discrepancy against existing candidates

| Candidate | State input | Optimum range | Spatial/history input | Source status |
|---|---|---|---|---|
| HYBRID | accumulated absolute residuals and sample count | implicit thresholds | temporal history only | project hypothesis |
| D6 | current `S` | `floor(log2(S+1)) +/- 1` | none | rejected approximation |
| D8 | current `S`, explicit zero contraction | same approximate non-zero bracket | none | partial, not production |
| Patent base S1204/S1206 | tentative next symbol `Sn` | exact `argmin L(Sn,K)` range | depends on embodiment | primary-source rule |

## Unresolved items

1. CRX applicability of the patent embodiment.
2. Mapping of CRX records to LL/HL/LH/HH.
3. Initial K and reset boundaries.
4. Integer rounding for `(Sx+Sd)/2`.
5. Exact `Sd` reconstruction in CRX.
6. Complete S1207 `Kd` correction equation and boundaries.
7. Run-length/escape mode used by the current fixtures.
8. CRX-specific global K limit.

## D12 gate

A D12 candidate is justified only for this one change:

> Replace D8's approximate non-zero K bracket with the exact finite-domain Golomb-Rice code-length argmin bracket, while keeping every other D8 test-decoder semantic and the fixed eleven-fixture panel unchanged.

A full patent-embodiment candidate remains blocked until the unresolved items have primary-source or first-party evidence.

## Clean-room statement

This audit used the public Canon patent publication, public patent metadata and project-owned D5-D10 results. No source code from LibRaw, dcraw, RawTherapee, darktable or another CR3/CRX decoder, and no decompiled proprietary binary, was consulted or translated.
