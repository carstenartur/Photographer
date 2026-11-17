# session-core

Session orchestration for the experimental timed capture and face-orbit workflows.

## Purpose

This module coordinates capture and delegates processing to injected adapters.
The packaged application supplies mock HDR, tonemapping, video and face-profile
adapters. Intended exposure offsets are recorded in the bracket metadata.

## Components

- `HdrTimelapseService`: Schedules repeated captures and delegates optional HDR processing and video encoding.
- `FaceOrbitService`: Manages face orbit sessions (sequential capture, quality checks, 3D profile generation)
- `SessionException`: Runtime exception for session errors

## Build

```bash
mvn -B clean verify -pl common-api,capture-core,storage,hdr-core,video-core,face-analysis-core,face-profile-core,session-core -am -Djava.awt.headless=true
```
