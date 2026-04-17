# astro-core

Domain module for astrophotography workflows: star detection, frame
registration, and image stacking for astro sessions.

## Services

| Class | Purpose |
|---|---|
| `StarDetectionService` | Detects star positions in frames using peak-finding on brightness data |
| `FrameRegistrationService` | Aligns frames by matching star positions across shifted exposures |
| `AstroStackingService` | Stacks registered frames into a single deep-sky image |
| `AstroCapturePipeline` | Orchestrates the full capture → detect → register → stack pipeline |
| `AstroFocusOptimizer` | Finds optimal focus position by maximising star sharpness |
| `StarTrailDeblurService` | Interface for star-trail deblurring (no-op default) |

## Design

- **Plain Java** — no Spring dependency; wired into the app via Spring
  configuration in the `app` module.
- **Depends on** `common-api` only.
- **Test data** is generated in-memory via `SyntheticStarImageGenerator`
  (configurable star count, dimensions, shifts, blur levels).

## Status

The module is functional with unit tests covering all services. Integration
into the UI and session workflow is available via the Astro Sessions page.

## Build

```bash
mvn -B test -pl astro-core -am -Djava.awt.headless=true
```
