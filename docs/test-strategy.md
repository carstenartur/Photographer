# Test Strategy

This document outlines the testing approach for Canon Focus Stack, covering
unit tests, integration tests with the mock camera, and hardware-in-the-loop
tests.

---

## System Requirements for Testing

### Minimum Requirements

| Resource | Minimum | Recommended | Notes |
|---|---|---|---|
| JDK | 21 | 21 (Temurin) | Required for all Java tests |
| Docker | 24+ | 24+ | Required for Testcontainers integration tests |
| RAM | 4 GB | 8 GB | Integration tests run PostgreSQL + Spring Boot + ImageJ concurrently |
| Disk | 2 GB free | 5 GB free | Docker images (PostgreSQL, Ryuk) and Maven cache |
| CPU | 2 cores | 4 cores | Tests run sequentially but containers add overhead |

### JVM Memory Configuration

Memory settings are centralized in the parent POM (`backend/pom.xml`):

| Plugin | Heap (`-Xmx`) | Purpose |
|---|---|---|
| maven-surefire-plugin (unit tests) | 512 MB | Sufficient for mocked tests and ImageJ headless |
| maven-failsafe-plugin (integration tests) | 1 GB | Spring Boot context + Testcontainers + ImageJ |

Both plugins set `-Djava.awt.headless=true` for ImageJ compatibility.

To override memory settings from the command line:

```bash
# Increase failsafe heap for large test suites
cd backend && mvn verify -Dfailsafe.argLine="-Xmx2g -Djava.awt.headless=true"

# Set Maven process memory (affects the Maven JVM itself)
MAVEN_OPTS="-Xmx1g" mvn verify
```

### Docker Requirements

Testcontainers automatically manages Docker containers during tests:

| Container | Image | RAM | Purpose |
|---|---|---|---|
| PostgreSQL | `postgres:16-alpine` | ~100 MB | camera-service integration tests |
| Ryuk | `testcontainers/ryuk` | ~20 MB | Automatic container cleanup |
| analysis-service | Built from Dockerfile | ~300 MB | Docker-based IT (optional, `@Tag("docker")`) |
| fiji-worker | Built from Dockerfile | ~500 MB | Docker-based IT (optional, `@Tag("docker")`) |

---

## Testing Pyramid

```
        ┌───────────────────┐
        │   HIL Tests       │   ← Real camera on live network
        │   (manual gate)   │
        ├───────────────────┤
        │  Docker ITs       │   ← @Tag("docker"), opt-in only
        ├───────────────────┤
        │  Integration      │   ← Testcontainers (PostgreSQL)
        │  Tests            │      + Spring Boot context
        ├───────────────────┤
        │                   │
        │   Unit Tests      │   ← Isolated, fast, no I/O
        │                   │
        └───────────────────┘
```

The majority of tests live at the **unit** level. Integration and
hardware-in-the-loop (HIL) tests provide confidence in cross-service and
real-hardware behaviour.

---

## Unit Tests

### Scope

- Individual classes and methods tested in isolation.
- External dependencies (database, camera, analysis-service) are mocked or
  stubbed.
- No network calls, no Docker containers.

### Tools

| Service | Framework | Mocking |
|---|---|---|
| camera-service | JUnit 5, AssertJ | Mockito |
| analysis-service | JUnit 5, AssertJ | Mockito |
| frontend-web | ESLint | — |

### Conventions

- Test classes mirror the source package structure.
- One test class per production class.
- Test class names end with `Test` (e.g., `SharpnessAnalyzerTest`).
- Test method names follow `should_<expected>_when_<condition>` format.
- Tests must complete in under 5 seconds each.
- Run via `maven-surefire-plugin` in the `test` phase.

### Running

```bash
# Java (from repository root)
cd backend && mvn test

# Frontend
cd frontend-web && npm run lint
```

---

## Integration Tests

### Scope

- Verify interactions **between** components: service ↔ database, service ↔ mock
  camera, service ↔ analysis-service.
- Use real (containerized) infrastructure, not mocks.

### Tools

| Concern | Tool |
|---|---|
| Database | Testcontainers 1.20.4 (PostgreSQL 16-alpine) |
| Image processing | ImageJ 1.54j (headless) |
| HTTP | Spring TestRestTemplate |
| Test runner | maven-failsafe-plugin |

### Conventions

- Integration test classes are suffixed with `IT` (e.g., `CameraServiceIT`).
- They live in `src/test/java` under the same package as the code they test.
- Run via `maven-failsafe-plugin` in the `integration-test` / `verify` phase.
- camera-service ITs extend `AbstractPostgresIntegrationTest` which provides
  a shared (singleton) PostgreSQL container for all test classes.

### Shared PostgreSQL Container (Singleton Pattern)

All camera-service integration tests share a single PostgreSQL container
through the `AbstractPostgresIntegrationTest` base class. This uses the
[singleton container pattern](https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/#singleton-containers):

```
AbstractPostgresIntegrationTest  ← starts PostgreSQL once in static {}
  ├── CameraServiceIT
  ├── CameraProfileRepositoryIT
  ├── StackingJobRepositoryIT
  ├── HibernateSearchIT
  └── ValidationIT
```

The container starts once when the first test class loads and stays alive
for the entire JVM lifetime. Testcontainers' Ryuk reaper cleans it up
after the JVM exits.

### Running

```bash
# Unit tests + integration tests (default)
cd backend && mvn verify

# Unit tests only (skip integration tests)
cd backend && mvn verify -DskipITs=true

# Integration tests only
cd backend && mvn verify -DskipTests=true
```

### Docker-Based Integration Tests

Heavy integration tests that build Docker images (analysis-service container,
Fiji worker container) are tagged with `@Tag("docker")` and **excluded from
the default build**. These tests:

- Build Docker images from the project Dockerfiles using Testcontainers'
  `ImageFromDockerfile`
- Require network access (Fiji tests download ~500 MB)
- Take 5–15 minutes to complete

To run them explicitly:

```bash
# Run only Docker-based ITs
cd backend && mvn verify -pl analysis-service \
  -Dfailsafe.groups=docker \
  -Dfailsafe.excludedGroups=

# Run all tests including Docker ITs
cd backend && mvn verify \
  -Dfailsafe.excludedGroups= \
  -Dsurefire.excludedGroups=
```

### End-to-End Tests

Full-stack E2E tests exercise the entire system through the browser.

```bash
./scripts/test-e2e.sh
```

This script:

1. Starts all services via `docker compose up`.
2. Waits for health checks to pass.
3. Runs API smoke tests against `http://localhost:8080`.
4. Tears down the stack and reports results.

---

## Hardware-in-the-Loop (HIL) Tests

### Scope

- Validate behaviour against a **real Canon camera** on a live Wi-Fi network.
- Verify CCAPI compatibility, timing, and image quality.
- These tests are **not** run in CI—they require physical hardware.

### When to Run

- Before any release.
- After changes to CCAPI interaction code in camera-service.
- After updating the mock camera to ensure it still matches real behaviour.

### Setup

1. Connect a supported Canon camera (see [camera-setup.md](camera-setup.md)).
2. Set `CAMERA_CCAPI_URL` to the camera's address.
3. Set `CAMERA_MOCK_ENABLED=false`.

### Running

```bash
cd camera-service && ./mvnw verify -Phil -DCAMERA_CCAPI_URL=http://192.168.1.42:8080
```

### Test Cases

| Test | Validates |
|---|---|
| Connect and read status | CCAPI discovery and status endpoint. |
| Start and stop Live View | MJPEG stream starts and stops cleanly. |
| Move focus near and far | Focus position changes as expected. |
| Capture single frame | Shutter fires and image is downloaded. |
| Full stack sequence | Adaptive loop runs end-to-end with real optics. |
| Movement detection | Physically moving the camera triggers stop. |

### Results

HIL test results are recorded manually in a test log and attached to the
release notes. Automated reporting is a future goal.

---

## Coverage

### Targets

| Layer | Target |
|---|---|
| Unit tests | ≥ 80% line coverage |
| Integration tests | Critical paths covered (capture sequence, analysis pipeline) |
| E2E tests | Happy-path workflows (connect, capture, review) |

### Reporting

- Java: JaCoCo generates reports during `mvn verify`.
- Frontend: ESLint for code quality.

```bash
# View Java coverage
open backend/camera-service/target/site/jacoco/index.html
```

---

## CI Integration

The GitHub Actions CI pipeline runs the following on every pull request:

1. **Unit tests** — All services (maven-surefire-plugin).
2. **Integration tests** — With Testcontainers and PostgreSQL (maven-failsafe-plugin).
3. **Docker build** — Smoke-test Docker images for all services.

Docker-based ITs (`@Tag("docker")`) and HIL tests are **not** run in the
default CI pipeline due to resource and time constraints.

### CI Memory Settings

The CI workflow sets `MAVEN_OPTS="-Xmx1g"` for the Maven process. Plugin-level
JVM memory is configured in the parent POM:

- Surefire (unit tests): `-Xmx512m`
- Failsafe (integration tests): `-Xmx1g`

### Test Summary

| Service | Unit Tests | Integration Tests | Docker ITs |
|---|---|---|---|
| camera-service | 19 (surefire) | 25 (failsafe, Testcontainers+PostgreSQL) | — |
| analysis-service | 29 (surefire) | 13 (failsafe, ImageJ headless) | 8 (opt-in, `@Tag("docker")`) |
