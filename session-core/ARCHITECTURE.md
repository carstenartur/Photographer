# session-core Architecture

## Overview

The session-core module orchestrates the two main features, coordinating
capture, processing, and result generation through the interfaces defined
in the other core modules.

## HDR Timelapse Flow

1. Create session with HdrSettings
2. Execute capture loop: bracket capture → HDR merge → tonemapping
3. Generate timelapse video from HDR results
4. Session state tracked in ConcurrentHashMap (in-memory)

## Face Orbit Flow

1. Create session with FaceOrbitSettings
2. Manual mode: step-by-step capture with quality checks
3. Auto mode: automated capture loop
4. Generate 3D face profile from captured views

## Design Decisions

- No Spring dependency — plain Java services with constructor injection
- ConcurrentHashMap for thread-safe session state
- Async execution delegated to caller (controller/executor)
- Quality checks run on every captured frame in face orbit mode
- HDR and video generation are pluggable via interfaces
