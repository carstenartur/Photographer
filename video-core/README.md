# video-core

Video encoding interface with mock adapter.

## Purpose

This module defines the `VideoEncoder` interface for creating videos from
image sequences. No external tools (ffmpeg, etc.) are used.

## Components

- `VideoEncoder`: Interface for video encoding
- `MockVideoEncoder`: Test adapter that returns placeholder data

## Build

```bash
mvn -B clean verify -pl common-api,video-core -am -Djava.awt.headless=true
```
