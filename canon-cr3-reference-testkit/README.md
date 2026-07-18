# canon-cr3-reference-testkit

Shared test-only support for reference-backed CR3 validation.

## Responsibilities

- loading linear RAW reference TIFFs into a consistent `int[]` representation
- MAE, RMSE and Pearson correlation
- optimal constant-offset estimation
- clamped offset application
- image-level MAE/RMSE and normalized histogram delta

## Dependency rule

Production CR3 modules must not depend on this module. Consumers such as
`canon-cr3-imageio` and the future `canon-cr3-research` module use it with Maven
`test` scope only.

## Metric semantics

- MAE and RMSE are calculated over all compared samples.
- Pearson is calculated over the same samples.
- If both inputs have zero variance, Pearson is `1.0` only when the inputs are
  identical and `0.0` otherwise; if only one input has zero variance, it is `0.0`.
- The optimal constant delta is the median of `reference[i] - actual[i]`, which
  minimizes L1 error.

## Build

```bash
mvn -B verify -pl canon-cr3-reference-testkit -am -Djava.awt.headless=true
```
