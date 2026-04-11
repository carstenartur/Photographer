# face-profile-core Architecture

## Overview

3D face profile generation is abstracted behind the `FaceProfileEngine` interface.
The approach is face-specific: it uses facial landmarks from multiple views to
construct an approximated 3D mesh, rather than general photogrammetry.

## Interface

- **FaceProfileEngine.generate()**: Takes images, angles, and landmarks to produce a 3D profile
- **FaceProfileResult**: Contains profile data, vertex count, confidence, and format

## Mock Adapter

- `MockFaceProfileEngine`: Returns JSON placeholder with metadata

## Design Decisions

- Face-specific: Uses landmark positions to guide reconstruction
- Confidence increases with number of views
- Profile data format is implementation-dependent (JSON, OBJ, PLY)
- Depends on face-analysis-core for landmark data
