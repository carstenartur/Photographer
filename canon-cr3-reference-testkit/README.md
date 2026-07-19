# canon-cr3-reference-testkit

Shared test-only support for reference-backed CR3 validation.

## Responsibilities

- loading linear RAW reference TIFFs into a consistent `int[]` representation
- MAE, RMSE and Pearson correlation
- optimal constant-offset estimation
- clamped offset application
- image-level MAE/RMSE and normalized histogram delta
- clipped rectangular-region metrics for optical-black diagnostics
- regular-grid metrics for Bayer/CFA plane comparisons
- tile-boundary MAE with explicitly documented seam counting

## Dependency rule

Production CR3 modules must not depend on this module. Consumers such as
`canon-cr3-imageio` and `canon-cr3-research` use it only as test or research
infrastructure.

## Current consumers

- `DecoderHypothesisD1D2IT` uses the shared TIFF loader and sample metrics.
- `ReferenceRebuildValidationIT` uses the shared image and histogram metrics.

`Cr3ReferenceComparisonIT`, pedestal/DC experiments and the sign-mapping
discriminator still contain local spatial metric code. Issue #99 tracks their
migration; the testkit should gain a helper only when shared semantics are clear.

## Metric semantics

- MAE and RMSE are calculated over all selected samples.
- Pearson is calculated over the same samples.
- If both inputs have zero variance, Pearson is `1.0` only when the inputs are
  identical and `0.0` otherwise; if only one input has zero variance, it is `0.0`.
- The optimal constant delta is the median of `reference[i] - actual[i]`, which
  minimizes L1 error.
- Spatial signed error is `actual - reference`.
- Rectangles use exclusive right and bottom edges; overlapping regions count
  overlapping pixels once per supplied region.
- Tile-boundary MAE includes both sides of every internal seam. Intersections of
  horizontal and vertical seams are counted once for each seam direction.
- Population standard deviation is used for spatial signed-error dispersion.

## Build

```bash
mvn -B verify -pl canon-cr3-reference-testkit -am -Djava.awt.headless=true
```
