# face-profile-core

3D face profile generation interface with mock adapter.

## Purpose

This module defines the `FaceProfileEngine` interface for generating
an approximated 3D face profile by fusing multiple face views.
This is a face-specific approach, not a general photogrammetry system.

## Components

- `FaceProfileEngine`: Interface for 3D profile generation
- `MockFaceProfileEngine`: Test adapter with placeholder profile data

## Build

```bash
mvn -B clean verify -pl common-api,face-analysis-core,face-profile-core -am -Djava.awt.headless=true
```
