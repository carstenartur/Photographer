# common-api Architecture

## Overview

`common-api` is a pure-Java library module with no framework dependencies
(no Spring, no JPA). It defines the shared vocabulary for the Focus Stack
project: interfaces that camera backends implement, and model records that
flow between layers.

## Package Structure

```
com.photographer.focusstack.common
├── analysis/       # AnalysisResult, SharpnessMap
├── camera/         # CameraBackend, CameraInfo, CameraState,
│                   # CaptureResult, FocusCommand, LiveViewFrame, Operation
└── model/          # CameraProfile, StackingJob, StackingParameters,
                    # JobStatus, QualityMode
```

## Design Principles

- **No framework coupling**: Only Jackson annotations and Jakarta validation
  are used, keeping this module lightweight and reusable.
- **Immutable records**: All model types are Java records, ensuring thread
  safety and predictable equality.
- **Interface-driven backends**: `CameraBackend` is the single integration
  point. New camera protocols (PTP, CCAPI, future) implement this interface
  without modifying the app module.
