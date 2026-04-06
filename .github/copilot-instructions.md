# Copilot Instructions for Photographer (Focus Stack)

## Build & Test Commands

### Full build (unit + integration tests) — **must pass before finishing work**

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
mvn -B clean verify -pl common-api,app -am -Djava.awt.headless=true
```

This runs:
- **Unit tests** via `maven-surefire-plugin` (`*Test.java`)
- **Integration tests** via `maven-failsafe-plugin` (`*IT.java`) — these use Testcontainers (PostgreSQL) and require Docker

### Unit tests only (quick feedback)

```bash
mvn -B test -pl common-api,app -am -DskipITs=true -Djava.awt.headless=true
```

## Critical: Always Run Integration Tests

Before completing any task, **you must run the full `mvn verify` command** (not just `mvn test`) and ensure **all tests pass, including integration tests** (`*IT.java` classes).

The CI pipeline runs `mvn -B clean verify -pl common-api,app -am -Djava.awt.headless=true` which includes integration tests. If you only run unit tests, you will miss failures that break the CI build.

Integration tests (`*IT.java`) test the full Spring Boot application with a real PostgreSQL database (via Testcontainers) and exercise REST endpoints, JPA repositories, and Hibernate Search. These tests catch issues that unit tests cannot.

## Project Structure

- **Root `pom.xml`**: Parent POM with modules `common-api`, `frontend-web`, `app`
- **`common-api/`**: Shared model records (e.g., `CameraProfile`, `StackingJob`)
- **`app/`**: Main Spring Boot application (merged camera + analysis services)
- **`frontend-web/`**: Vite/React frontend

## Key Conventions

- **Spring Boot 4.0** with Jakarta EE, Jackson 3 (`tools.jackson.databind`), relocated test annotations
- **Test annotations**: `@MockitoBean` (not `@MockBean`), `@WebMvcTest` from `org.springframework.boot.webmvc.test.autoconfigure`
- **Integration tests**: Extend `AbstractPostgresIntegrationTest` for Testcontainers setup; use `*IT.java` naming
- **Unit tests**: Use `*Test.java` naming
- **Security**: `@WebMvcTest` needs `@WithMockUser` + `@MockitoBean UserRepository`; `@SpringBootTest` needs `restTemplate.withBasicAuth("admin","admin")`

## Frontend

```bash
cd frontend-web
npm ci
npm run lint
npm run build
```

Requires Node.js ^20.19.0 or >=22.12.0.
