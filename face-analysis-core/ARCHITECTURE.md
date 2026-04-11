# face-analysis-core Architecture

## Overview

Face analysis is abstracted behind the `FaceAnalysisEngine` interface.
This is not a general-purpose computer vision module but a face-specific
analysis engine for quality checking and landmark detection.

## Interface

- **analyzeQuality()**: Checks sharpness, exposure, and face detection
- **detectLandmarks()**: Returns facial landmark coordinates
- **estimateFaceAngle()**: Estimates yaw angle from landmarks

## Mock Adapter

- `MockFaceAnalysisEngine`: Returns fixed plausible results for testing

## Quality Criteria

- Sharpness score: 0.0 (blurry) to 1.0 (sharp)
- Exposure score: 0.0 (under/over) to 1.0 (correct)
- Face confidence: 0.0 (no face) to 1.0 (certain)
