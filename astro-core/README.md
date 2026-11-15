# astro-core

Domain module for astrophotography workflows: star detection, frame registration and image stacking for astro sessions.

## Services

| Class | Purpose |
|---|---|
| `StarDetectionService` | Detects star positions in frames using peak-finding on brightness data. |
| `FrameRegistrationService` | Aligns frames by matching star positions across shifted exposures. |
| `AstroStackingService` | Combines registered frames with mean, median or sigma-clipped mean. |
| `AstroCapturePipeline` | Orchestrates the full capture → detect → register → stack pipeline. |
| `AstroFocusOptimizer` | Finds an optimal focus position by maximising star sharpness. |
| `StarTrailDeblurService` | Interface for star-trail deblurring (no-op default). |

## Stacking contract

Stacking reads packed 8-bit RGB values for display images. A series of matching unsigned-short gray or RGB component images retains its native raster values, transfer type and color model through the output. Mixed precisions, alpha and incompatible high-precision color models are rejected. Low-depth non-packed display images use the existing 8-bit conversion. Registration estimates positions from a luminance preview; combination reads the original frames.

`AstroStackingMode` makes the algorithm choice explicit:

- `MEAN` preserves the historical per-channel arithmetic mean and integer-floor behavior;
- `MEDIAN` selects the per-channel middle sample; even counts use integer-floor division of the middle pair;
- `SIGMA_CLIPPED_MEAN` iteratively rejects a complete RGB sample when any channel lies strictly beyond `sigmaThreshold × population standard deviation`, then averages the survivors.

Sigma decisions within one iteration use the same pre-rejection statistics. A rejection set that would leave fewer than `minimumSamples` is not applied. When the initial count is below that minimum, the pixel falls back to the historical mean rather than silently inventing a robust estimate.

Median and sigma-clipped modes process one output row at a time and reuse temporary arrays proportional to input frame count. They do not allocate a width × height × frames sample cube. `StackingControl` provides cooperative cancellation and row progress.

For the five unsigned-short samples 40001, 40002, 40003, 40004 and 65535, Mean returns 45109, Median returns 40003, and sigma clipping at 1.5 with three minimum samples returns 40002. The deterministic test checks the stored raster samples directly. With few frames a median suppresses real, short-lived sources as readily as artifacts. Sigma clipping cannot distinguish an outlier below its threshold; use Mean for a repeatable baseline.

## Design

- **Plain Java** — no Spring dependency; wired into the app through the `app` module.
- **Depends on** `common-api` only.
- **Test data** is generated in memory through `SyntheticStarImageGenerator`.
- **No hidden quality choice** — callers pass an explicit `AstroStackingConfig`; the compatibility overload always uses Mean.

## Verification

Exact unit fixtures cover unchanged mean output, odd/even median, saturated values, isolated and multi-stage outlier rejection, insufficient-sample fallback, invalid configuration and cancellation/progress.

The application integration additionally verifies that:

- missing JSON/session configuration retains historical `MEAN`;
- selected mode and parameters round-trip through persistence;
- the exact persisted configuration is supplied to `AstroCapturePipeline`;
- user abort reaches `StackingControl`;
- active polling receives fine row progress while durable checkpoints advance in 5-percentage-point increments rather than per-row writes;
- terminal summaries identify the executed mode.

The [combination benchmark](../astro-core-benchmark/stacking.md) reports execution time and allocated bytes for both precisions, three algorithms and two frame counts. The historical Mean benchmark remains available for comparison with its existing series.

## Application integration

`AstroCaptureConfig.stackingConfig` is optional for backward compatibility and normalizes to `AstroStackingConfig.defaults()`.

New sessions persist mode, sigma threshold, maximum iterations and minimum samples. Historical rows without those columns normalize to Mean. The service executes exactly the resolved persisted configuration; the REST response and Astro Sessions UI expose the same provenance.

During `STACKING`, active polling receives the latest in-memory percentage. The application stores durable checkpoints every 5 percentage points and exact terminal progress, avoiding a write per image row while preserving useful recovery/status data after restart. Completed sessions report 100%; aborted or failed sessions retain their last exact terminal progress without fabricating completion.

## Build

```bash
mvn -B test -pl astro-core -am -Djava.awt.headless=true
```

## Canonical documentation

Cross-module role and support boundaries are maintained in [Modules and public surfaces](../docs/developer/modules.md). This README owns only astro-core algorithms and numerical semantics.
