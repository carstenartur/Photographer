# astro-core Architecture

## Overview

The `astro-core` module implements astrophotography-specific image processing:
star detection, frame registration (alignment), and stacking. It is a pure
Java library with no Spring dependency.

## Package Structure

```
com.photographer.focusstack.astro
├── StarDetectionService.java       # Finds star positions via brightness peak-finding
├── FrameRegistrationService.java   # Aligns frames using matched star positions
├── AstroStackingService.java       # Mean/median stacking of registered frames
├── AstroCapturePipeline.java       # End-to-end pipeline: capture → stack
├── AstroFocusOptimizer.java        # Focus sweep to maximise star sharpness
├── StarTrailDeblurService.java     # Deblur interface (extension point)
└── NoOpStarTrailDeblurService.java # Default no-op implementation
```

## Pipeline

```
Capture frames ──► Star detection ──► Frame registration ──► Stacking ──► Output
                                                          ▲
                                               (optional deblur)
```

1. **Star detection**: Scans each frame for brightness peaks above a threshold.
2. **Frame registration**: Matches star positions between frames to compute
   translation offsets, then shifts frames to align them.
3. **Stacking**: Combines aligned frames (mean averaging) to improve
   signal-to-noise ratio.
4. **Focus optimization**: Captures a focus sweep and picks the position with
   the highest star sharpness.

## Dependencies

```
astro-core ──► common-api
```

## Test Strategy

All test data is generated synthetically via `SyntheticStarImageGenerator`:
- Standard frames: sharp Gaussian star profiles on dark background
- Shifted frames: sub-pixel translations simulating sky drift
- Blurry frames: larger, dimmer blobs simulating defocus

No external image files are required.
