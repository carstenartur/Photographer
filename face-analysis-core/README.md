# face-analysis-core

Face analysis engine interface with mock adapter.

## Purpose

This module defines the `FaceAnalysisEngine` interface for face detection,
landmark analysis, quality assessment, and face angle estimation.

## Components

- `FaceAnalysisEngine`: Interface for face analysis operations
- `MockFaceAnalysisEngine`: Test adapter with fixed plausible results

## Build

```bash
mvn -B clean verify -pl common-api,face-analysis-core -am -Djava.awt.headless=true
```
