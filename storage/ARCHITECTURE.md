# storage Architecture

## Overview

The storage module provides file-system based persistence for capture sessions.

## Directory Structure

```
data/sessions/{sessionId}/
  frames/      — captured image files
  brackets/    — HDR bracket set data
  results/     — processed results (HDR, video, 3D profile)
  metadata/    — session and frame metadata JSON files
```

## Design Decisions

- Interface-based for testability (can be mocked or replaced with cloud storage).
- No Spring dependency — plain Java with constructor injection.
- Automatic directory creation on first file store.
- Thread-safe file operations via `java.nio.file`.
