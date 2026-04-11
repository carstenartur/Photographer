# app Architecture

## Overview

The `app` module is a Spring Boot application that serves as the central
orchestrator for focus-stacking workflows. It exposes a REST API, drives
camera backends, performs real-time image analysis, and serves the React
frontend as static resources.

## Package Structure

```
com.photographer.focusstack
├── FocusStackApplication          # Spring Boot entry point
├── analysis/
│   ├── controller/                # REST: AnalysisController, PluginController, StackMergeController
│   ├── dto/                       # AnalyzeRequest, SharpnessResponse, MotionResponse
│   ├── plugin/                    # AnalysisPlugin SPI, AnalysisPluginRegistry
│   ├── service/                   # SharpnessAnalyzer, MotionDetector, ImageJProcessor,
│   │                              # ImageJStackMerger, InformationGainEstimator, StackingAdvisor
│   └── strategy/                  # MotionDetectionStrategy implementations
├── app/
│   ├── config/                    # CameraConfig, StorageConfig
│   └── security/                  # SecurityConfig, UserEntity, UserRepository
├── camera/
│   ├── backend/                   # CameraBackendFactory, CanonCcapiBackend, MockCameraBackend
│   ├── config/                    # CameraServiceConfig, WebSocketConfig
│   ├── controller/                # CameraController, JobController
│   ├── entity/                    # CameraProfileEntity, StackingJobEntity (JPA)
│   ├── exception/                 # CameraException, GlobalExceptionHandler
│   ├── repository/                # JPA repositories
│   ├── search/                    # HibernateSearchInitializer, SearchService
│   ├── service/                   # CameraProfileService, StackingJobService, FrameDataStore
│   └── strategy/                  # FocusStepStrategy, AdaptiveStepStrategy, LinearStepStrategy
```

## Key Components

### Camera Backends

`CameraBackendFactory` selects the active backend based on configuration:
- `CanonCcapiBackend` — communicates with Canon cameras over Wi-Fi via CCAPI
- `MockCameraBackend` — generates synthetic frames for CI and demos

### Analysis Pipeline

Each captured frame is analysed for sharpness (Laplacian variance) and motion
(configurable strategy: pixel diff, optical flow, structural similarity).
`StackingAdvisor` uses analysis results to recommend the next focus step.
`AnalysisPluginRegistry` supports third-party analysis plugins via the SPI.

### Persistence

JPA entities (`CameraProfileEntity`, `StackingJobEntity`) are stored in
PostgreSQL (production) or H2 (development). Hibernate Search indexes
profiles and jobs for full-text search.

### Security

Spring Security with form login. `DefaultAdminInitializer` creates the admin
user on first start using the configured password.

### Frontend Integration

The React frontend (built by `frontend-web`) is copied into
`src/main/resources/static` at package time and served by Spring Boot.
