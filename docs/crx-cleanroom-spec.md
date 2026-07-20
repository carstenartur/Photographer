# CRX lossless decoder — clean-room engineering specification

> **Status:** research specification, not a normative Canon CRX format document.
>
> This specification separates verified container behavior, public algorithm-family disclosures, project observations and unresolved CRX semantics. Only behavior promoted through the clean-room experiment ledger may enter the production decoder.

## 1. Sources and evidence classes

Decision-bearing sources are registered in [`cleanroom/source-register.json`](cleanroom/source-register.json).

The relevant classes are:

- `PATENT_EXPLICIT` — directly stated by US 2016/0323602 A1;
- `PATENT_EMBODIMENT` — one implementation disclosed by the patent;
- `PUBLIC_STANDARD_BACKGROUND` — public coding terminology or generic rules;
- `FIRST_PARTY_OBSERVATION` — evidence produced from project-controlled cameras;
- `BLACK_BOX_REFERENCE` — decoded pixels used only as expected output;
- `CRX_HYPOTHESIS` — an inference about Canon's on-disk CRX data;
- `UNRESOLVED` — insufficient evidence to define a concrete rule.

No third-party CR3/CRX decoder source or decompiled binary is an allowed implementation input.

## 2. Verified project foundation

The following are established by repository code and tests:

- ISO-BMFF/CR3 container parsing and Canon box extraction;
- fixed tile/plane/subband byte ranges for the reference fixtures;
- a manifest-bound eleven-fixture lossless panel from EOS R and PowerShot G5 X Mark II captures;
- external TIFF/PNG references generated as black-box expected output;
- global, optical-black, boundary, saturation and completion metrics;
- deterministic research reports and clean-room evidence bundles.

The earliest unresolved image failure remains the entropy/symbol reconstruction stage. Production decoding must not claim full CRX correctness.

## 3. Logical decoder stages

```text
CR3 container
  -> CRX payload ranges
  -> entropy symbols
  -> signed residuals
  -> predicted coefficients
  -> optional dequantisation / inverse transform
  -> channel or Bayer assembly
  -> image output
```

The stages remain separable so an improvement in one area cannot hide a guessed rule in another.

## 4. Golomb-Rice symbol decoding

### 4.1 Publicly disclosed base code

`PATENT_EXPLICIT`

For non-negative symbol `S` and K parameter `K`, the disclosed code length is:

```text
L(S,K) = (S >> K) + 1 + K
```

The corresponding generic decoder shape is:

```text
q = read unary quotient
r = read K-bit remainder
S = (q << K) | r
```

The concrete unary digit convention and any CRX escape/run-length mode still require payload validation. Generic Golomb-Rice background alone does not establish CRX bit ordering.

### 4.2 Exact optimal K range

`PATENT_EXPLICIT`

For a predeclared finite K domain `0..KmaxGlobal`:

```text
minimumLength(S) = min L(S,K)
                   K in [0, KmaxGlobal]

Kmin(S) = min { K | L(S,K) = minimumLength(S) }
Kmax(S) = max { K | L(S,K) = minimumLength(S) }
```

The patent examples include:

| S | Exact optimum K range |
|---:|---:|
| 2 | `0..2` |
| 4 | `1..3` |
| 6 | `2..3` |

The previous D6/D8 approximation `floor(log2(S+1)) +/- 1` is not equivalent. It is retired as a source claim and retained only as a rejected/partial research candidate.

### 4.3 Base one-step update

`PATENT_EXPLICIT`

Given current K `Kx` and tentative next symbol `Sn`:

```text
if Kx < Kmin(Sn): Kn = Kx + 1
else if Kx > Kmax(Sn): Kn = Kx - 1
else: Kn = Kx
```

The package-private research model `PatentGolombRiceKRange` implements only this finite-domain bracket and update. It has no production dependency.

## 5. Tentative next symbol

### 5.1 Disclosed LL embodiment

`PATENT_EMBODIMENT`

```text
Sn = (Sx + Sd) / 2
```

Unresolved for CRX:

- integer rounding of the mean;
- mapping of `Sd` state to CRX tile/plane records;
- whether the current lossless panel uses the embodiment;
- boundary behavior when the reference is unavailable.

### 5.2 Disclosed high-pass embodiment

`PATENT_EMBODIMENT`

For the first described HL/LH/HH path:

```text
Sn = Sx
```

A later correction can use a past `Kd` value. The existence and broad purpose of that correction are disclosed, but its complete equation and boundaries are not yet transcribed with implementation-level confidence. It is excluded from D11's Java model.

## 6. Symbol-to-residual mapping

D5 rejected a broad conventional-zigzag replacement on the fixed panel. Therefore:

- the production mapping remains unchanged;
- no mapping is labelled patent-exact for CRX merely because a public encoder description uses a familiar signed-symbol transform;
- any future mapping change requires its own fixed-panel experiment.

## 7. Predictor, subband and transform semantics

The patent discloses wavelet/subband and prediction embodiments, but their mapping to current CRX records is not established by the patent alone.

The following remain `CRX_HYPOTHESIS` or `UNRESOLVED`:

- whether each CRX plane represents a color plane, wavelet subband or another coding partition;
- mapping of indices to LL/HL/LH/HH;
- number and interpretation of transform levels;
- predictor family and boundary rules for each concrete partition;
- quantisation/dequantisation interpretation;
- inverse-transform normalization and edge handling.

Existing negative predictor and state-continuation experiments remain valid and must not be repeated without new structural evidence.

## 8. Initial state and reset boundaries

The public primary source does not define a CRX-specific:

- initial K;
- per-row reset;
- per-tile reset;
- per-plane/subband reset;
- stream exhaustion/padding behavior.

These are independent variables. A test candidate may change only one at a time and must retain exact input and completion accounting.

## 9. Global K domain

The patent discusses a practical K range around `0..15`. This is a `PATENT_EMBODIMENT`, not proof of a CRX syntax field.

Research code must:

- predeclare its finite `KmaxGlobal`;
- report the chosen value;
- reject invalid state rather than silently clamp to an undocumented constant;
- avoid promoting `15` to production until the CRX path supports it empirically.

## 10. D5-D10 evidence boundary

| Experiment | Durable conclusion |
|---|---|
| D5 | Broad sign-mapping replacement rejected. |
| D6 | Universal approximate symbol bracket rejected. |
| D7 | First D6 difference classified at zero-symbol K state. |
| D8 | Zero contraction useful but insufficient; result `PARTIAL`. |
| D9 | No common first non-zero decision cause. |
| D10 | No transition class predicts failure without improvement contradictions. |

Negative results remain part of the specification because they prohibit silent reintroduction of disproved candidates.

## 11. D11 exact candidate boundary

D11's decision is:

```text
EXACT_CANDIDATE_AVAILABLE
```

The decision applies only to:

1. calculating `Kmin(S)` and `Kmax(S)` by finite-domain minimisation of `L(S,K)`; and
2. moving K by at most one toward that range.

It does not establish the full CRX K algorithm. The source extraction and unresolved details are recorded in [`cleanroom/d11-canon-k-adaptation-source-audit.md`](cleanroom/d11-canon-k-adaptation-source-audit.md).

## 12. D12 experiment gate

A D12 candidate may change exactly one variable:

> Replace D8's approximate non-zero K bracket with the exact finite-domain Golomb-Rice code-length bracket.

It must keep unchanged:

- the explicit eleven-fixture panel;
- D8's parser, sign mapping, predictor, K seed and reset behavior;
- tentative-symbol source for the isolated discriminator;
- payload exhaustion and padding semantics;
- geometry and assembly;
- production code.

Required gates:

- global MAE;
- optical-black MAE;
- tile-boundary error;
- saturation;
- per-plane metrics;
- completion and newly exhausted/recovered streams;
- deterministic report and artifact digest.

A full patent-embodiment experiment remains blocked until `Sd`, `Kd`, subband identity and CRX state boundaries have additional evidence.

## 13. Promotion rule

Research code may enter production only through a separate PR that cites a `SUPPORTED` experiment in `docs/cleanroom/experiment-ledger.json` and passes the complete reference-backed and production test suite.

`PARTIAL`, `REJECTED`, `NO_COMMON_CAUSE` and `NO_PREDICTIVE_TRANSITION_CLASS` results do not authorize production changes.
