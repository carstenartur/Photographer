# canon-cr3-reference-testkit

Shared test-only support for reference-backed CR3 validation.

## Responsibilities

- deterministic discovery of CR3 fixtures and their TIFF/PNG sidecars
- typed, path-safe diagnostics for missing reference sidecars
- loading linear RAW reference TIFFs and PNG sidecars into a consistent first-band `int[]` representation
- MAE, RMSE and Pearson correlation
- optimal constant-offset estimation
- clamped offset application
- image-level MAE/RMSE and normalized histogram-CDF delta
- clipped rectangular-region metrics for optical-black diagnostics
- regular-grid metrics for Bayer/CFA plane comparisons
- tile-boundary MAE with explicitly documented seam counting
- deterministic absolute-difference heat maps for diagnostic reports
- deterministic UTF-8 TSV, JSON and Markdown output for result structures shared by multiple active consumers

## Dependency rule

Production CR3 modules must not depend on this module. Consumers such as
`canon-cr3-imageio` and `canon-cr3-research` use it only as test or research
infrastructure.

The module is deliberately not a general-purpose research utility library. A helper
belongs here only when at least two active consumers share the same semantics and the
helper can remain independent of production decoding and report-specific rendering.

## Current consumers

- `DecoderHypothesisD1D2IT` uses the shared TIFF loader and sample metrics.
- `ReferenceRebuildValidationIT` uses the shared image and histogram metrics.
- `SignMappingDiscriminatorIT` uses shared TIFF loading, global metrics, optical-black regions and tile-seam metrics.
- `Cr3ReferenceComparisonIT` uses `ReferenceFixtureResolver` for deterministic complete-fixture discovery and typed TIFF resolution, plus shared difference-image rendering and deterministic output scaling.
- `PedestalHypothesisValidationIT` uses the same resolver and sidecar contract while retaining its report-specific plots and experimental metrics locally.
- `DcComponentIsolationIT` uses resolver-based TIFF availability filtering and typed TIFF resolution; its decoder experiment and report structure remain local.
- `KTransitionMatrixIT` and `CrxSubbandTopologyIT` use `DeterministicResultWriter` for shared stable tabular/structured output semantics.

## Intentionally local or frozen reporters

Historical experiment series, including the `CrxPlaneFollowupRound*` reports, retain
their local writers. Their committed reports document a specific investigation state;
rewriting them merely for uniformity would create churn without improving active
validation. Reporters that combine unique diagrams, image generation or one-off
hypothesis structures also stay local until a second active consumer demonstrates the
same data contract.

This boundary means that fixture discovery, sidecar validation and genuinely shared
serialization are centralized, while report-specific scientific meaning remains with
the experiment that owns it.

## Fixture and sidecar resolution

`ReferenceFixtureResolver` normalizes the configured sample root, walks only real
regular `.cr3` files, and returns results in stable slash-separated relative order.
Each `ResolvedFixture` contains the CR3 path, expected TIFF and PNG paths, and a typed
set of missing sidecars.

`requiredTiff()` and `requiredPng()` fail with
`MissingReferenceSidecarException`. The exception exposes only the relative fixture
name and sidecar type; it does not retain an absolute host path. Symlink escapes and
fixtures outside the configured sample root are rejected.

## Reference-image loading

`ReferenceImageLoader.load(Path)` decodes TIFF and PNG sidecars through ImageIO and
copies the first decoded raster band into the canonical row-major sample array.

- unsigned 16-bit grayscale TIFF samples retain the complete `0..65535` range;
- grayscale PNG samples retain their stored sample values;
- a multi-band PNG contributes raster band 0 from the concrete image returned by
  the registered ImageIO reader; the semantic channel depends on that decoded
  image layout and is not assumed to be red, green, blue or luminance.

No implicit luminance or color conversion is performed. Color-aware comparisons
require a different representation and are intentionally outside this single-band
testkit.

## Difference-image semantics

`ReferenceDifferenceImage.absoluteHeatmap(...)` preserves the convention shared by
the active reference-comparison and pedestal reports:

```text
difference = abs(actual + actualOffset - reference)
intensity  = clamp(floor(difference / displayDivisor), 0, 255)
RGB        = (intensity, floor(intensity / 2), 0)
```

The default divisor is `8`, so every eight RAW sample levels produce one display
level. Identical samples are black; large differences saturate at orange
`RGB(255,127,0)`. The heat map is diagnostic only. Scientific conclusions must use
the unscaled metric APIs, not image pixels. Region rendering uses the same exclusive
right/bottom rectangle semantics as `SpatialReferenceMetrics.Rect`. Optional output
scaling uses deterministic bicubic interpolation and never changes quantitative
inputs.

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

## Deterministic output

`DeterministicResultWriter` writes UTF-8 with `\n` newlines, stable column/key order
and locale-independent number formatting. Callers remain responsible for defining
the scientific schema; the writer does not infer fields, reorder experiment meaning
or serialize arbitrary object graphs.

## Build

```bash
mvn -B verify -pl canon-cr3-reference-testkit -am -Djava.awt.headless=true
```
