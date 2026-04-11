# video-core Architecture

## Overview

Video encoding is abstracted behind the `VideoEncoder` interface.
Concrete implementations wrap Java-only video libraries in adapter classes.
No external command-line tools (ffmpeg, etc.) are used.

## Interface

- **VideoEncoder.encode()**: Takes ordered image frames + parameters, returns video bytes.
- **VideoEncoder.format()**: Reports the output format.

## Mock Adapter

- `MockVideoEncoder`: Returns placeholder metadata (for tests).

## Design Decisions

- No shell calls — all encoding is in-process via Java libraries.
- Format-agnostic interface supports multiple output formats.
- Frame data is passed as byte arrays for flexibility.
