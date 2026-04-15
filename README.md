# Focus Stack (Photographer)

[![CI](https://github.com/carstenartur/Photographer/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/carstenartur/Photographer/actions/workflows/ci.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Photographer/coverage/badge.json)](https://carstenartur.github.io/Photographer/coverage/)
[![Tests](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Photographer/tests/badge.json)](https://carstenartur.github.io/Photographer/tests/surefire-report.html)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![SBOM](https://img.shields.io/badge/SBOM-CycloneDX-informational?logo=owasp&style=flat)](https://github.com/carstenartur/Photographer/dependency-graph/sbom)
[![GitHub release](https://img.shields.io/github/v/release/carstenartur/Photographer?style=flat-square)](https://github.com/carstenartur/Photographer/releases)

Focus Stack is a modular Java 21 + Spring Boot 4 photography platform with a React frontend for:

- Canon CCAPI camera control and adaptive focus stacking
- HDR timelapse, face-orbit capture, and astro sessions
- Observer workflows with AI vision providers
- Runtime plugin loading (ServiceLoader-based, no PF4J)
- Canon CR3 decoding libraries and CLI/ImageIO integration

## Current Status

The repository is an active multi-module monorepo with:

- 18 Maven modules (core app + domain modules + plugin/CR3/frontend modules)
- CI running full `mvn clean verify` (unit + integration tests) and frontend lint/build
- Docker-first local setup with PostgreSQL and mock camera defaults

## Key Modules

| Module | Purpose |
|---|---|
| `app` | Main Spring Boot application (REST API, workflows, plugin management, serves frontend). |
| `frontend-web` | React/Vite UI (Dashboard, Cameras, Jobs, Gallery, HDR, Face Orbit, Observer, Astro, Plugins). |
| `common-api` | Shared records/contracts across modules. |
| `plugin-api` | Public plugin extension interfaces (`AnalysisPlugin`, `CameraBackendPlugin`, `VisionProviderPlugin`). |
| `capture-core`, `camera-ptp`, `storage`, `session-core` | Capture orchestration, camera backend integration, persistence, and session workflows. |
| `hdr-core`, `video-core`, `face-analysis-core`, `face-profile-core`, `observer-core`, `ai-vision`, `astro-core` | Domain-specific imaging/AI workflows. |
| `canon-cr3-core`, `canon-cr3-imageio`, `canon-cr3-cli` | Canon CR3 decoding stack, Java ImageIO support, and CLI tooling. |

## Quickstart (Docker)

```bash
git clone https://github.com/carstenartur/Photographer.git
cd Photographer
cp .env.example .env
docker compose up --build
```

Open: http://localhost:8080

Default login:

- Username: `admin`
- Password: value of `FOCUSSTACK_SECURITY_ADMIN_PASSWORD` in `.env` (default `focusstack`)

Optional demo seed:

```bash
./scripts/seed-demo-data.sh
```

## Local Development

Prerequisites:

- JDK 21
- Maven 3.9+
- Node.js 20.19+ (or 22.12+)
- Docker / Docker Compose

Run backend:

```bash
mvn spring-boot:run -pl app -am -Djava.awt.headless=true
```

`-am` builds required dependent modules automatically for local runs.

Run frontend dev server:

```bash
cd frontend-web
npm ci
npm run dev
```

## Build & Test

Backend full verify:

```bash
mvn -B clean verify -am -Djava.awt.headless=true
```

The CI workflow uses an explicit `-pl ...` module list; see `.github/workflows/ci.yml` for the exact pinned command.

Frontend:

```bash
cd frontend-web
npm ci
npm run lint
npm run build
```

## Supported Cameras

Tested CCAPI models:

- Canon PowerShot G5 X Mark II
- Canon PowerShot G7 X Mark III

Other Canon cameras with CCAPI may work but are not officially verified.

## Documentation

- [Quickstart](docs/quickstart.md)
- [Development Guide](docs/development.md)
- [Camera Setup](docs/camera-setup.md)

## License

MIT — see [LICENSE](LICENSE).
