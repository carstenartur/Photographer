# Focus Stack

**Adaptive Focus Stacking over Wi-Fi for Canon cameras with live analysis and browser-based control**

Canon Focus Stack automates focus-stacking workflows for Canon cameras equipped
with CCAPI (Camera Control API). A browser-based UI lets you frame the shot with
Live View, configure a focus sweep, and launch an adaptive capture sequence that
analyses each frame in real time—stopping automatically when it detects the
subject has moved or the entire depth of field has been covered.

---

## Features

| Feature | Description |
|---|---|
| **Canon CCAPI support** | Control compatible Canon cameras over Wi-Fi using the official Camera Control API. |
| **Browser UI** | Responsive React front-end for live preview, capture control, and stack review. |
| **Live View preview** | Real-time MJPEG stream from the camera displayed in the browser. |
| **Adaptive focus stacking** | Automatically steps through the focus range, analysing sharpness to decide the next position. |
| **Movement-aware capture stop** | Detects subject or camera movement and halts the sequence to avoid blurred frames. |
| **Dockerized local deployment** | Single `docker compose up` to run the full stack on any machine. |
| **Mock camera for CI/testing** | Built-in mock CCAPI server so the entire pipeline can be tested without hardware. |
| **Optional headless ImageJ/Fiji integration** | Post-capture stack merging via headless Fiji for maximum quality. |

## Supported Cameras

The following Canon cameras expose CCAPI and have been tested:

- **Canon PowerShot G5 X Mark II**
- **Canon PowerShot G7 X Mark III**

> Other Canon cameras with CCAPI support may work but are not officially tested.

---

## Quickstart

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) ≥ 24
- [Docker Compose](https://docs.docker.com/compose/) ≥ 2.20

### Run with Docker Compose

```bash
# Clone the repository
git clone https://github.com/carstenartur/Photographer.git
cd Photographer

# Start all services (camera-service, analysis-service, frontend-web, PostgreSQL)
docker compose up --build

# Open the UI
open http://localhost:3000
```

The default profile uses the **mock camera** so you can explore the UI without
hardware. To connect a real camera, see [Camera Setup](docs/camera-setup.md).

### Seed demo data (optional)

```bash
./scripts/seed-demo-data.sh
```

For the full setup guide, including development mode, see
[docs/quickstart.md](docs/quickstart.md).

---

## Architecture

Canon Focus Stack follows a **multi-service** architecture:

```
┌────────────────┐     HTTP/WS      ┌──────────────────┐     CCAPI     ┌───────────┐
│  frontend-web  │ ◄──────────────► │  camera-service   │ ◄───────────► │  Canon    │
│  (React/Vite)  │                  │  (Spring Boot)    │               │  Camera   │
└────────────────┘                  └────────┬─────────┘               └───────────┘
                                             │
                                             │ REST (HTTP)
                                             ▼
                                   ┌──────────────────┐
                                   │ analysis-service  │
                                   │ (Java / ImageJ headless) │
                                   └────────┬─────────┘
                                             │
                                             ▼
                                   ┌──────────────────┐
                                   │   PostgreSQL +    │
                                   │   Local Volumes   │
                                   └──────────────────┘
```

| Service | Tech | Role |
|---|---|---|
| **frontend-web** | React 19, Vite | Browser UI: Live View, capture controls, stack gallery. |
| **camera-service** | Java 21, Spring Boot 3 | Orchestrates CCAPI calls, manages capture sequences, streams Live View. |
| **analysis-service** | Java 21, ImageJ headless | Sharpness analysis, movement detection, optional Fiji stack merging. |
| **storage** | PostgreSQL 16, local Docker volumes | Metadata in Postgres; raw frames on a shared volume. |

For a deeper dive, see [docs/architecture.md](docs/architecture.md).

---

## Development

```bash
# Start infrastructure (Postgres) in Docker
./scripts/dev-up.sh

# Run camera-service locally
cd backend/camera-service && mvn spring-boot:run

# Run analysis-service locally
cd backend/analysis-service && mvn spring-boot:run

# Run frontend-web locally
cd frontend-web && npm install && npm run dev
```

See [docs/development.md](docs/development.md) for IDE setup, coding standards,
and contribution guidelines.

---

## Roadmap

### ✅ Milestone 1 — Foundation
- Project scaffolding (monorepo, CI, Docker Compose)
- Mock CCAPI camera server
- Basic Spring Boot camera-service with Live View proxy

### ✅ Milestone 2 — Core Capture
- Focus-step sweep with configurable range and step size
- Per-frame sharpness analysis (Laplacian variance)
- Adaptive step algorithm that narrows around peak sharpness

### ✅ Milestone 3 — Intelligence
- Movement detection (frame-diff + optical flow via ImageJ)
- Auto-stop on movement or full-range coverage
- Headless ImageJ integration for final stack merge

### Milestone 4 — Polish & Extensibility
- ✅ Stack gallery with before/after comparison
- ✅ Export & sharing workflows (ZIP download, merged PNG download)
- ✅ Plugin API for third-party analysis modules
- ✅ MockCameraBackend failure simulation (timeout, disconnect, empty frame, movement)
- ✅ Preset quality modes (Fast / Balanced / Maximum Quality)

### Milestone 5 — Future Work
- Real Canon CCAPI integration with hardware-in-the-loop tests
- GPU-accelerated stack analysis (CUDA/OpenCL via JVM bindings)
- Multi-camera support (parallel capture from multiple bodies)
- RAW image support (Canon CR3 / CR2 via LibRaw)

---

## License

This project is licensed under the terms of the [MIT License](LICENSE).
