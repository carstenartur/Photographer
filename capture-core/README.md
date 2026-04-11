# capture-core

Core capture abstractions for bracket and sequence capture modes.

## Purpose

This module provides services for capturing images using a CameraBackend:
- `BracketCaptureService`: Captures exposure brackets (e.g. -1, 0, +1 EV)
- `SequenceCaptureService`: Captures sequential frames with angle metadata

## Dependencies

- `common-api`: Shared model records and CameraBackend interface

## Build

```bash
mvn -B clean verify -pl common-api,capture-core -am -Djava.awt.headless=true
```
