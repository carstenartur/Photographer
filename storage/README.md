# storage

Session storage abstraction and file-based implementation.

## Purpose

This module provides a `SessionStorage` interface for persisting session data
(frames, brackets, results, metadata) to the file system under `data/sessions/`.

## Components

- `SessionStorage`: Interface for session data persistence
- `FileSessionStorage`: File-system based implementation

## Build

```bash
mvn -B clean verify -pl common-api,storage -am -Djava.awt.headless=true
```
