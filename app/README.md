# app

Main Spring Boot application for Focus Stack. Combines camera control, image analysis,
and the browser-based UI into a single deployable JAR.

## Features

- Canon CCAPI camera backend (Wi-Fi control)
- Mock camera backend for testing and demos
- Sharpness analysis (Laplacian variance via ImageJ)
- Motion detection (pixel diff, optical flow, structural similarity)
- Adaptive and linear focus-step strategies
- REST API with OpenAPI/Swagger documentation
- WebSocket support for real-time updates
- Spring Security with admin user management
- Hibernate Search (Lucene) for full-text search
- PostgreSQL persistence with Testcontainers integration tests

## Dependencies

| Dependency | Purpose |
|---|---|
| `common-api` | Shared interfaces and models |
| Spring Boot Starter Web | REST endpoints |
| Spring Boot Starter Data JPA | Database access |
| Spring Boot Starter Security | Authentication and authorization |
| Spring Boot Starter WebSocket | Real-time browser communication |
| Hibernate Search + Lucene | Full-text search over camera profiles and jobs |
| ImageJ (ij) | Headless image analysis |
| PostgreSQL / H2 | Production / development databases |
| Testcontainers | Integration tests with real PostgreSQL |
| JaCoCo | Code coverage reporting |

## Build

```bash
# Unit tests only (quick feedback)
mvn -B test -pl common-api,app -am -DskipITs=true -Djava.awt.headless=true

# Full build with integration tests (requires Docker for Testcontainers)
mvn -B clean verify -pl common-api,app -am -Djava.awt.headless=true
```

## Run

Follow the [application quickstart](../docs/quickstart.md) to start with Docker Compose and PostgreSQL, or build the executable JAR with its React UI and run locally with H2. Sign in as `admin` using the password you set in `FOCUSSTACK_SECURITY_ADMIN_PASSWORD`. The [web walkthrough](../docs/web-interface.md) covers a capture with the mock camera.

## Canonical documentation

The application composition root and dependency direction are maintained in [Architecture](../docs/developer/architecture.md) and [Modules and public surfaces](../docs/developer/modules.md). This README covers only application-local build and runtime details.
