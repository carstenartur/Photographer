# Test Strategy

This document outlines the testing approach for Canon Focus Stack, covering
unit tests, integration tests with the mock camera, and hardware-in-the-loop
tests.

---

## Testing Pyramid

```
        ┌───────────────────┐
        │   HIL Tests       │   ← Real camera on live network
        │   (manual gate)   │
        ├───────────────────┤
        │  Integration      │   ← Mock camera + Testcontainers
        │  Tests            │
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
- Test method names follow `should_<expected>_when_<condition>` format.
- Tests must complete in under 5 seconds each.

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
| Database | Testcontainers (PostgreSQL) |
| Mock camera | Custom mock CCAPI server (Docker image) |
| HTTP | Spring WebTestClient, RestAssured |
| Frontend E2E | Playwright |

### Mock Camera

The project includes a lightweight **mock CCAPI server** that simulates Canon
camera responses:

- `/ccapi` — version discovery
- `/ccapi/ver110/shooting/liveview` — returns a static or looping MJPEG stream
  from `fixtures/sample-liveview/`
- `/ccapi/ver110/shooting/control/focus` — simulates focus movement
- `/ccapi/ver110/shooting/control/shutter` — returns a sample JPEG from
  `fixtures/sample-stacks/`

The mock is configured via environment variables and runs as a Docker container
alongside the services during integration tests.

### Conventions

- Integration test classes are suffixed with `IT` (e.g., `CaptureSequenceIT`).
- They live in `src/test/java` under the same package as the code they test.
- Activated by the Maven `integration` profile (`-Pintegration`).

### Running

```bash
cd backend && mvn verify
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

1. **Lint** — ESLint (frontend), Checkstyle (Java).
2. **Unit tests** — All services.
3. **Integration tests** — With Testcontainers and mock camera.
4. **Build** — Docker images for all services.

HIL tests are triggered manually via a workflow dispatch and are not part of the
default CI pipeline.
