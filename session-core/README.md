# session-core

Session orchestration for HDR Timelapse and Face Orbit features.

## Purpose

This module provides the service layer that orchestrates capture, processing,
and result generation for both features.

## Components

- `HdrTimelapseService`: Manages HDR timelapse sessions (bracket capture, HDR merge, video generation)
- `FaceOrbitService`: Manages face orbit sessions (sequential capture, quality checks, 3D profile generation)
- `SessionException`: Runtime exception for session errors

## Build

```bash
mvn -B clean verify -pl common-api,capture-core,storage,hdr-core,video-core,face-analysis-core,face-profile-core,session-core -am -Djava.awt.headless=true
```
