# Architecture

This document describes the high-level design of the Canon Focus Stack system.

---

## Overview

Canon Focus Stack is organized as a **multi-service monorepo**. Each service
runs in its own container and communicates over well-defined HTTP (or gRPC)
interfaces. The repository layout is:

```
/
├── frontend-web/        # React single-page application
├── camera-service/      # Spring Boot service — camera orchestration
├── analysis-service/    # Java service — image analysis & stacking
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
| **Language** | TypeScript |
| **Framework** | React 18, Vite, TailwindCSS |
| **Port** | 3000 |

Responsibilities:
- Display the Live View MJPEG stream proxied through camera-service.
- Provide controls for starting, pausing, and stopping a focus-stack capture.
- Render a gallery of completed stacks with per-frame sharpness overlays.
- Communicate with camera-service via REST and WebSocket.

### camera-service — Camera Orchestration

| | |
|---|---|
| **Language** | Java 21 |
| **Framework** | Spring Boot 3, Spring WebFlux |
| **Port** | 8080 |

Responsibilities:
- Wrap Canon CCAPI calls behind a stable internal REST API.
- Proxy the Live View MJPEG stream to the browser.
- Execute focus-stack sequences: iterate focus positions, trigger shutter, collect frames.
- Delegate per-frame analysis to analysis-service.
- Persist capture metadata to PostgreSQL.
- Expose a WebSocket endpoint for real-time capture progress.

### analysis-service — Image Analysis

| | |
|---|---|
| **Language** | Java 21 |
| **Framework** | Spring Boot 3, OpenCV 4 |
| **Port** | 8081 |

Responsibilities:
- Compute per-frame sharpness metrics (Laplacian variance, Tenengrad).
- Detect movement between consecutive frames (frame differencing, optical flow).
- Advise camera-service whether to continue, adjust step size, or stop.
- Optionally merge a completed stack using headless ImageJ/Fiji.

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
 │     a. Trigger shutter via CCAPI → download frame.              │
 │     b. Send frame to analysis-service.                          │
 │     c. Receive sharpness score + movement flag.                 │
 │     d. IF movement detected → STOP sequence.                   │
 │     e. IF sharpness is rising → keep current step size.        │
 │     f. IF sharpness peaked & falling → halve step size or STOP.│
 │     g. Advance focus by step size via CCAPI.                    │
 │  4. Persist completed stack metadata; notify UI via WebSocket.  │
 └─────────────────────────────────────────────────────────────────┘
```

### Sharpness Analysis

Each frame is converted to greyscale and convolved with a Laplacian kernel. The
variance of the result serves as the primary sharpness metric. A secondary
Tenengrad (Sobel gradient magnitude) score is computed for cross-validation.

### Movement Detection

Two consecutive frames are aligned and a pixel-wise absolute difference is
computed. If the mean difference exceeds a configurable threshold, or if sparse
optical flow vectors show significant displacement, the system flags movement
and halts the sequence.

---

## Communication

```
Browser ──HTTP/WS──► camera-service ──HTTP──► analysis-service
                          │                         │
                          ▼                         ▼
                      PostgreSQL             (shared volume)
```

- **Browser ↔ camera-service**: REST for commands, WebSocket for progress events, MJPEG for Live View.
- **camera-service → analysis-service**: REST (synchronous per-frame analysis during capture).
- **camera-service → Canon camera**: Canon CCAPI over Wi-Fi (HTTP).
- **Both services → PostgreSQL**: JDBC via HikariCP connection pool.
- **Both services → shared volume**: Raw frames written by camera-service, read by analysis-service.

---

## Configuration

All services read configuration from environment variables. Defaults are defined
in each service's `application.yml` and overridden in `docker-compose.yml`.

| Variable | Service | Description |
|---|---|---|
| `CAMERA_CCAPI_URL` | camera-service | Base URL of the Canon CCAPI (e.g., `http://192.168.1.2:8080`) |
| `CAMERA_MOCK_ENABLED` | camera-service | `true` to use the built-in mock camera |
| `ANALYSIS_SERVICE_URL` | camera-service | URL of the analysis-service |
| `SPRING_DATASOURCE_URL` | both | JDBC URL for PostgreSQL |
| `FIJI_ENABLED` | analysis-service | `true` to enable headless Fiji stack merging |
| `FIJI_PATH` | analysis-service | Path to the Fiji executable |

---

## Security Considerations

- The system is designed for **local-network use only**. There is no built-in
  authentication. Do not expose the services to the public internet without
  adding an authentication layer.
- CCAPI communication is unencrypted HTTP on the local Wi-Fi network. Use a
  dedicated or isolated network for camera control.
