# Architecture

This document describes the high-level design of the Canon Focus Stack system.

---

## Overview

Canon Focus Stack is organized as a **multi-service monorepo**. Each service
runs in its own container and communicates over well-defined HTTP (REST)
interfaces. The repository layout is:

```
/
├── frontend-web/        # React single-page application
├── backend/
│   ├── common-api/      # Shared interfaces and models
│   ├── camera-service/  # Spring Boot service — camera orchestration
│   └── analysis-service/# Java service — image analysis & stacking
├── docker-compose.yml   # Full-stack composition
├── docs/                # Documentation
├── scripts/             # Developer & CI helper scripts
└── fixtures/            # Sample images for testing
```

---

## Services

### frontend-web — Browser UI

| | |
|---|---|
| **Language** | JavaScript (JSX) |
| **Framework** | React 19, Vite |
| **Port** | 3000 (Docker) / 5173 (dev) |

Responsibilities:
- Display the Live View MJPEG stream proxied through camera-service.
- Provide controls for starting, pausing, and stopping a focus-stack capture.
- Render a gallery of completed stacks with per-frame sharpness overlays.
- Communicate with camera-service via REST and WebSocket (STOMP/SockJS).

### camera-service — Camera Orchestration

| | |
|---|---|
| **Language** | Java 21 |
| **Framework** | Spring Boot 3, Spring Web (REST + WebSocket) |
| **Port** | 8080 |

Responsibilities:
- Wrap Canon CCAPI calls behind a stable internal REST API.
- Proxy the Live View MJPEG stream to the browser.
- Execute focus-stack sequences: iterate focus positions, trigger shutter, collect frames.
- Delegate per-frame analysis to analysis-service via HTTP (`RestClient`).
- Apply the **adaptive step strategy** — narrows focus steps near the sharpness peak.
- Persist capture metadata to PostgreSQL.
- Expose a WebSocket endpoint (STOMP/SockJS) for real-time capture progress and logs.

### analysis-service — Image Analysis

| | |
|---|---|
| **Language** | Java 21 |
| **Framework** | Spring Boot 3, ImageJ 1.x (headless) |
| **Port** | 8081 |

Responsibilities:
- Compute per-frame sharpness metrics (Laplacian variance via ImageJ).
- Detect movement between consecutive frames using a pluggable **motion detection strategy**
  (`optical-flow` using ImageJ pixel comparison, or `pixel-diff` for raw byte comparison).
- Advise camera-service whether to continue capturing or stop.
- Merge a completed focus stack into a single all-in-focus image using
  ImageJ-based **Extended Depth of Field** (EDF) algorithm.

### Storage — PostgreSQL + Local Volumes

| | |
|---|---|
| **Engine** | PostgreSQL 16 |
| **Port** | 5432 |

- **PostgreSQL** stores structured metadata: sessions, captures, frames, analysis results.
- **Docker volumes** hold raw image files (JPEG/CR3) and generated stack outputs.

---

## Adaptive Focus Stacking Cycle

The core loop runs inside camera-service and proceeds as follows:

```
 ┌─────────────────────────────────────────────────────────────────┐
 │                     CAPTURE SEQUENCE                            │
 │                                                                 │
 │  1. User sets near-focus and far-focus limits in the UI.        │
 │  2. camera-service positions the lens at the near limit.        │
 │  3. LOOP:                                                       │
 │     a. Trigger shutter via CCAPI → capture frame with imageData.│
 │     b. Send frame to analysis-service via HTTP multipart POST.  │
 │     c. Receive AnalysisResult (sharpness, motion, recommendCont.│
 │     d. IF motion detected OR gain below threshold → STOP.       │
 │     e. Compute next step via FocusStepStrategy:                 │
 │        - Rising sharpness → keep full step (far from peak).     │
 │        - Plateau → halve step (approaching peak).               │
 │        - Falling sharpness → quarter step (past peak).          │
 │     f. Advance focus by computed step size via CCAPI.           │
 │  4. Broadcast analysis result per frame via WebSocket.          │
 │  5. Persist completed stack metadata; notify UI via WebSocket.  │
 └─────────────────────────────────────────────────────────────────┘
```

### FocusStepStrategy (camera-service)

Configured via `camera.focus.step-strategy` (default: `adaptive`):

| Strategy | Behaviour |
|---|---|
| `adaptive` | Gradient-based narrowing around sharpness peak (default) |
| `linear` | Fixed step size for every move (original behaviour, safe fallback) |

### MotionDetectionStrategy (analysis-service)

Configured via `analysis.motion.strategy` (default: `optical-flow`):

| Strategy | Behaviour |
|---|---|
| `optical-flow` | ImageJ-decoded per-pixel comparison (accurate for compressed images) |
| `pixel-diff` | Raw byte comparison (fast, works on any byte array) |

### Sharpness Analysis

Each frame is decoded and converted to an ImageJ FloatProcessor, then convolved
with a Laplacian kernel. The variance of the response (normalised to 0–1) serves
as the sharpness score.

### Stack Merging

After a job completes, the `/api/analysis/merge-stack` endpoint accepts all
captured frames and returns a single merged PNG using the **EDF (Extended Depth of
Field)** algorithm: for each pixel, the frame with the highest local Laplacian
response is selected.

---

## Communication

```
Browser ──HTTP/WS──► camera-service ──HTTP (RestClient)──► analysis-service
                          │
                          ▼
                      PostgreSQL
```

- **Browser ↔ camera-service**: REST for commands; STOMP/SockJS WebSocket for
  real-time progress events and logs; MJPEG for Live View.
- **camera-service → analysis-service**: REST (Spring `RestClient`), multipart
  POST per captured frame. Falls back to linear mode if the service is unreachable.
- **camera-service → Canon camera**: Canon CCAPI over Wi-Fi (HTTP).
- **Both services → PostgreSQL**: JDBC via HikariCP connection pool.

---

## Configuration

All services read configuration from environment variables. Defaults are defined
in each service's `application.yml` and overridden in `docker-compose.yml`.

| Variable | Service | Description |
|---|---|---|
| `CAMERA_CCAPI_URL` | camera-service | Base URL of the Canon CCAPI (e.g., `http://192.168.1.2:8080`) |
| `CAMERA_MOCK_ENABLED` | camera-service | `true` to use the built-in mock camera |
| `CAMERA_FOCUS_STEP_STRATEGY` | camera-service | `adaptive` or `linear` (default: `adaptive`) |
| `ANALYSIS_SERVICE_ENABLED` | camera-service | `true` to enable per-frame analysis calls |
| `ANALYSIS_SERVICE_URL` | camera-service | URL of the analysis-service (e.g., `http://analysis-service:8081`) |
| `ANALYSIS_MOTION_STRATEGY` | analysis-service | `optical-flow` or `pixel-diff` (default: `optical-flow`) |
| `SPRING_DATASOURCE_URL` | camera-service | JDBC URL for PostgreSQL |

---

## Security Considerations

- The system is designed for **local-network use only**. There is no built-in
  authentication. Do not expose the services to the public internet without
  adding an authentication layer.
- CCAPI communication is unencrypted HTTP on the local Wi-Fi network. Use a
  dedicated or isolated network for camera control.


---

## Plugin API (analysis-service)

The analysis-service exposes a lightweight plugin system for third-party analysis
algorithms.

### Interface

```java
public interface AnalysisPlugin {
    String name();                                                       // unique, URL-safe name
    AnalysisPluginResult analyze(byte[] imageData, AnalysisContext ctx); // returns score + metadata
}
```

### Registration

Any Spring bean implementing `AnalysisPlugin` is automatically collected by
`AnalysisPluginRegistry` via `@Autowired List<AnalysisPlugin>`. No manual
registration is required — add a `@Component` class and it will appear in the
plugin list on next startup.

### Endpoints

| Method | Path | Description |
|---|---|---|
| `GET`  | `/api/analysis/plugins` | Lists all registered plugins by name |
| `POST` | `/api/analysis/plugins/{name}/analyze` | Runs the named plugin on an uploaded image |

### Bundled Plugins

| Name | Class | Description |
|---|---|---|
| `sharpness` | `DefaultSharpnessPlugin` | Laplacian-variance sharpness score via ImageJ (with `SharpnessAnalyzer` fallback on raw grayscale bytes) |

### Mock Camera Failure Simulation

`MockCameraBackend` supports the following configuration flags for testing error
scenarios without real hardware:

| Property | Default | Description |
|---|---|---|
| `camera.mock.simulate-timeout-after-frames` | `0` | Block for 30 s after N captures (simulates network timeout) |
| `camera.mock.simulate-disconnect-after-frames` | `0` | Throw after N captures (simulates Wi-Fi disconnect) |
| `camera.mock.simulate-empty-frame-at` | `0` | Return empty image at capture N (simulates transmission error) |
| `camera.mock.simulate-movement-at` | `0` | Return noise-like image at capture N (simulates subject movement) |

Set any flag to `0` to disable it.
