# D11 Canon K-adaptation primary-source result

## Verdict

**`EXACT_CANDIDATE_AVAILABLE`**, limited to the finite-domain Golomb-Rice code-length bracket and the one-step movement toward that bracket.

No production decoder change is authorized by D11.

## Fixed source input

- Canon patent application US 2016/0323602 A1, *Image encoding apparatus and control method of the same*;
- project-owned D5-D10 reports used only to identify which patent ambiguities affect the current research path;
- no third-party CR3/CRX decoder source and no decompiled binary.

The detailed figure, flow-step, claim and evidence-class extraction is recorded in [`../cleanroom/d11-canon-k-adaptation-source-audit.md`](../cleanroom/d11-canon-k-adaptation-source-audit.md).

## Exact source-backed component

For non-negative symbol `S` and K parameter `K`:

```text
L(S,K) = (S >> K) + 1 + K
```

For a predeclared finite K domain:

```text
Kmin(S) = lowest  K attaining min L(S,K)
Kmax(S) = highest K attaining min L(S,K)
```

The disclosed base update is:

```text
if Kx < Kmin(Sn): Kn = Kx + 1
else if Kx > Kmax(Sn): Kn = Kx - 1
else: Kn = Kx
```

A package-private, fixture-independent test model reproduces the patent examples:

| S | Exact optimum K range |
|---:|---:|
| 2 | `0..2` |
| 4 | `1..3` |
| 6 | `2..3` |

It also pins the small-symbol differences that made the former D6/D8 approximation inaccurate.

## What D11 does not establish

D11 does not prove:

- that current CRX payloads use the disclosed embodiment;
- CRX mapping to LL/HL/LH/HH;
- initial K or reset boundaries;
- integer rounding for the disclosed LL average;
- exact `Sd` reconstruction;
- the complete non-LL `Kd` correction;
- run-length/escape mode;
- a CRX-specific global K limit.

## D12 gate

A D12 fixed-panel experiment may change exactly one test-decoder variable:

> Replace D8's approximate non-zero bracket with the exact finite-domain code-length bracket.

All parser, sign, predictor, K seed, reset, geometry, padding, completion and production behavior must remain unchanged.

A complete patent-embodiment decoder remains blocked pending additional primary-source or first-party evidence.
