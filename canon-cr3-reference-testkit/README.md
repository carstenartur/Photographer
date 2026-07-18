# canon-cr3-reference-testkit

Shared test infrastructure for reference-backed CR3 decoder validation.

## Responsibilities

- load RAW-linear reference TIFF sidecars
- resolve fixture sidecar paths
- compute MAE, RMSE and Pearson correlation consistently
- compare selected image regions, including optical-black regions
- compute tile-boundary error
- apply bounded diagnostic offsets without modifying source arrays

## Dependency rule

The testkit is not part of the runtime CR3 API. Production modules must not
depend on it. Research and integration-test modules may consume it in test
scope.

## Metric contract

- MAE and RMSE are calculated over equally sized integer sample arrays.
- Pearson is `1.0` for equal constant arrays, `0.0` when correlation is
  undefined for unequal/one-sided constant arrays, and the usual covariance
  coefficient otherwise.
- Region and boundary metrics use the same absolute-error definition as the
  global metric.

## Build

```bash
mvn -B verify -pl canon-cr3-reference-testkit -am -Djava.awt.headless=true
```
