# Development Guide

This guide covers building, testing, and contributing to Canon Focus Stack.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21+ | [Eclipse Temurin](https://adoptium.net/) recommended |
| Maven | 3.9+ | Or use the included `mvnw` wrapper |
| Node.js | 20 LTS | For the frontend |
| npm | 10+ | Bundled with Node.js 20 |
| Docker | 24+ | For infrastructure and integration tests |
| Docker Compose | 2.20+ | Multi-service orchestration |
| RAM | 4 GB minimum | 8 GB recommended for integration tests |

---

## Repository Layout

```
/
├── frontend-web/          # React / Vite (JSX)
├── backend/
│   ├── common-api/        # Shared interfaces and models
│   ├── camera-service/    # Spring Boot – camera orchestration
│   └── analysis-service/  # Spring Boot – image analysis
├── docs/                  # Documentation
├── scripts/               # Helper scripts
├── fixtures/              # Sample images for tests & demos
├── docker-compose.yml     # Full-stack composition
└── .github/               # CI workflows
```

---

## Building

### Java services

```bash
# From repository root
cd backend
mvn clean verify
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

This starts PostgreSQL and the mock camera container.

### 2. Start services

In separate terminals:

```bash
# Terminal 1
cd backend/camera-service && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Terminal 2
cd backend/analysis-service && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Terminal 3
cd frontend-web && npm run dev
```

The frontend dev server runs at [http://localhost:5173](http://localhost:5173).

---

## Testing

### Unit Tests

```bash
# Java (from repository root)
cd backend && mvn test

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
cd backend && mvn verify

# Skip integration tests
cd backend && mvn verify -DskipITs=true
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
cd backend && mvn verify -pl analysis-service \
  -Dfailsafe.groups=docker -Dfailsafe.excludedGroups=
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
2. Import each Maven module (`camera-service`, `analysis-service`).
3. Set the Project SDK to JDK 21.
4. Install the **Lombok** plugin if prompted.
5. Enable annotation processing: **Settings → Build → Compiler → Annotation Processors**.

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
| `ANALYSIS_SERVICE_URL` | `http://localhost:8081` |

---

## Contributing

1. Fork the repository and create a feature branch from `main`.
2. Follow the code style guidelines above.
3. Add or update tests for your changes.
4. Run the full test suite before opening a pull request.
5. Open a pull request with a clear description of the change and its motivation.

All pull requests require at least one approving review before merge.
