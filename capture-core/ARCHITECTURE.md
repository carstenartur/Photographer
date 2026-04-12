# capture-core Architecture

## Overview

The capture-core module provides camera capture abstractions used by
HDR Timelapse and Face Orbit sessions. It delegates actual image capture
to the `CameraBackend` interface from `common-api`.

## Components

- **BracketCaptureService**: Captures multiple exposures at different EV offsets,
  grouped into a `BracketSet`. Used by HDR Timelapse.
- **SequenceCaptureService**: Captures individual frames with angle/position metadata.
  Used by Face Orbit.

## Design Decisions

- No dependency on Spring — plain Java services that can be instantiated directly.
- CameraBackend is injected via constructor for testability.
- EV offsets are calculated symmetrically around center exposure.
