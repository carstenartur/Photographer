# Development Guide

This guide covers building, testing, and contributing to Focus Stack.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 25 | [Eclipse Temurin](https://adoptium.net/) recommended |
| Maven | 3.9+ | Or use the included `mvnw` wrapper |
| Node.js | 20.19+ (or 22.12+) | For the frontend |
| npm | 10+ | Bundled with Node.js |
| Docker | 24+ | For infrastructure and integration tests |
| Docker Compose | 2.20+ | Service orchestration |
| RAM | 4 GB minimum | 8 GB recommended for integration tests |

---

## Repository Layout

```
/
├── common-api/            # Shared records and interfaces
├── plugin-api/            # Public plugin SPI
├── capture-core/          # Bracket and sequence capture
├── storage/               # Session file-system persistence
├── camera-ptp/            # PTP/USB Canon backend
├── hdr-core/              # HDR merge and tonemapping
├── video-core/            # Video encoding
├── face-analysis-core/    # Face detection and quality
├── face-profile-core/     # 3D face profiles
├── session-core/          # HDR timelapse and face orbit
├── observer-core/         # Wildlife observer rules engine
├── ai-vision/             # AI vision providers
├── astro-core/            # Astrophotography
├── canon-cr3-core/        # CR3 RAW parser
├── canon-cr3-imageio/     # ImageIO adapter for CR3
├── canon-cr3-cli/         # CR3 debug CLI
├── frontend-web/          # React / Vite SPA
├── app/                   # Spring Boot application (REST API, workflows)
├── docs/                  # Documentation
├── scripts/               # Helper scripts
├── fixtures/              # Sample images for tests & demos
└── .github/               # CI workflows
```

---

## Building

### Java backend

```bash
# From repository root — builds all modules including integration tests
mvn -B clean verify \
  -pl common-api,plugin-api,capture-core,storage,camera-ptp,hdr-core,video-core,face-analysis-core,face-profile-core,session-core,observer-core,ai-vision,astro-core,canon-cr3-core,canon-cr3-imageio,canon-cr3-cli,app \
  -am -Djava.awt.headless=true

# Quick unit tests only
mvn -B test -pl common-api,app -am -DskipITs=true -Djava.awt.headless=true
```

### Frontend

```bash
cd frontend-web
npm install
npm run build      # Production build → dist/
npm run lint       # ESLint
```

---

## Running Locally

### 1. Start infrastructure

```bash
./scripts/dev-up.sh
```

This starts PostgreSQL in Docker.

### 2. Start the application

In separate terminals:

```bash
# Terminal 1 — Spring Boot app
mvn spring-boot:run -pl app -am -Djava.awt.headless=true

# Terminal 2 — Frontend dev server (hot-reload)
cd frontend-web && npm run dev
```

The frontend dev server runs at [http://localhost:5173](http://localhost:5173).

---

## Testing

### Unit Tests

```bash
# Java (from repository root)
mvn test -pl common-api,app -am -Djava.awt.headless=true

# Frontend
cd frontend-web && npm run lint
```

### Integration Tests

Integration tests use **Testcontainers** to manage PostgreSQL containers and
run via the **maven-failsafe-plugin** in the `verify` phase. Test class names
end with `IT` (e.g., `CameraServiceIT`).

**Requirements:** Docker must be running. Minimum 4 GB RAM available.

```bash
# Unit tests + integration tests (default)
mvn verify -pl common-api,app -am -Djava.awt.headless=true

# Skip integration tests
mvn verify -pl common-api,app -am -DskipITs=true -Djava.awt.headless=true
```

#### Memory Settings

JVM memory is pre-configured in the parent POM:
- **Surefire** (unit tests): `-Xmx512m`
- **Failsafe** (integration tests): `-Xmx1g`

To override for resource-constrained environments:

```bash
MAVEN_OPTS="-Xmx512m" mvn verify -Dfailsafe.argLine="-Xmx512m -Djava.awt.headless=true"
```

#### Docker-Based ITs (Optional)

Tests tagged `@Tag("docker")` build Docker images from the project Dockerfiles
and are **excluded by default** (they require network access and take 5–15 min):

```bash
# Run only Docker-based ITs
mvn verify -pl app -am \
  -Dfailsafe.groups=docker -Dfailsafe.excludedGroups= -Djava.awt.headless=true
```

### End-to-End Tests

```bash
./scripts/test-e2e.sh
```

This starts the full Docker Compose stack, runs Playwright tests against the
frontend, and tears everything down.

See [test-strategy.md](test-strategy.md) for the full testing philosophy.

---

## Code Style

### Java

- Follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).
- Use `final` for local variables where possible.
- Prefer records for DTOs.
- Maximum line length: 120 characters.

### TypeScript / React

- Follow the project ESLint + Prettier configuration.
- Use functional components with hooks.
- Use named exports (no default exports).

### Commits

- Write clear, imperative-mood commit messages (e.g., "Add focus sweep endpoint").
- Keep commits focused on a single logical change.

---

## IDE Setup

### IntelliJ IDEA

1. Open the repository root as a project.
2. Import the Maven modules (IntelliJ detects them automatically from `pom.xml`).
3. Set the Project SDK to JDK 25.
4. Enable annotation processing: **Settings → Build → Compiler → Annotation Processors**.

### VS Code

1. Install the **Extension Pack for Java** and **Spring Boot Extension Pack**.
2. Install the **ESLint** and **Prettier** extensions for frontend work.
3. Open the repository root as a workspace.

---

## Environment Variables

See [architecture.md](architecture.md) for the full list. For local development,
the `dev` Spring profile sets sensible defaults:

| Variable | Default (dev) |
|---|---|
| `CAMERA_MOCK_ENABLED` | `true` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/focusstack` |
| `SPRING_DATASOURCE_USERNAME` | `focusstack` |
| `SPRING_DATASOURCE_PASSWORD` | `focusstack` |
| `FOCUSSTACK_SECURITY_ADMIN_PASSWORD` | `focusstack` |

---

## Contributing

1. Fork the repository and create a feature branch from `main`.
2. Follow the code style guidelines above.
3. Add or update tests for your changes.
4. Run the full test suite before opening a pull request.
5. Open a pull request with a clear description of the change and its motivation.

All pull requests require at least one approving review before merge.