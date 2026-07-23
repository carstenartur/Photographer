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

The current public image contract is packed 8-bit RGB `BufferedImage` input and output. Non-int-packed inputs are converted once by the existing pixel helper; robust modes do not add another color-space or bit-depth conversion.

`AstroStackingMode` is explicit session provenance:

- `MEAN` preserves the historical per-channel arithmetic mean and integer-floor behavior;
- `MEDIAN` selects the per-channel middle sample; even counts use the floor of the two middle values;
- `SIGMA_CLIPPED_MEAN` iteratively rejects a complete RGB sample when any channel lies strictly beyond `sigmaThreshold × population standard deviation`, then averages the survivors.

Sigma decisions within one iteration use the same pre-rejection statistics. A rejection set that would leave fewer than `minimumSamples` is not applied. When the initial count is below that minimum, the pixel falls back to the historical mean rather than silently inventing a robust estimate.

Median and sigma-clipped modes process one output row at a time and reuse temporary arrays proportional to input frame count. They do not allocate a width × height × frames sample cube. `StackingControl` provides cooperative cancellation and row progress.

## Design

- **Plain Java** — no Spring dependency; wired into the app through the `app` module.
- **Depends on** `common-api` only.
- **Test data** is generated in memory through `SyntheticStarImageGenerator`.
- **No hidden quality choice** — callers select and persist an algorithm explicitly.

## Verification

Exact unit fixtures cover unchanged mean output, odd/even median, saturated values, isolated and multi-stage outlier rejection, insufficient-sample fallback and cancellation/progress.

The optional JMH benchmark measures one- and eight-frame stacks for all three algorithms at 1024×768 and 5632×3710:

```bash
mvn -B -P benchmarks -pl astro-core-benchmark -am package -DskipTests
```

Benchmark results are environment-sensitive and must record runner/JVM metadata before performance conclusions are promoted.

## Current integration boundary

`AstroCaptureConfig` and persisted session entities carry `AstroStackingConfig`. The core pipeline accepts that configuration, but the current application execution path still invokes the backward-compatible Mean overload. Service/API/frontend selection and visible progress remain the next #286 slice after this core is accepted.

## Build

```bash
mvn -B test -pl astro-core -am -Djava.awt.headless=true
```

## Canonical documentation

Cross-module role and support boundaries are maintained in [Modules and public surfaces](../docs/developer/modules.md). This README owns only astro-core algorithms and numerical semantics.
