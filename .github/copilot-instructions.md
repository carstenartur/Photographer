# Copilot Instructions for Photographer (Focus Stack)

## Build & Test Commands

### Full build (unit + integration tests) — **must pass before finishing work**

```bash
mvn -B clean verify -pl common-api,app -am -Djava.awt.headless=true
```

> **Note:** Requires Java 21. If `JAVA_HOME` is not set, configure it for your environment (e.g., `export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64` on Ubuntu with Temurin).

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

## Mandatory: CI Validation Before Finishing Work

**You MUST run the full build and confirm it passes before marking any task as done or closing a PR.**

- Run `mvn -B clean verify -pl common-api,app -am -Djava.awt.headless=true` and verify the output shows `BUILD SUCCESS`.
- Do **not** check off tasks as completed until the build has actually been executed and passed.
- Do **not** write "locally verified" in a PR description unless the build command was actually run and succeeded.
- If you change any file under `.github/workflows/`, simulate the workflow steps locally as closely as possible (e.g. run the same `mvn` commands the workflow uses).

## Mandatory: Never Break the Maven Reactor

The Maven multi-module reactor resolves dependencies between modules automatically. Breaking it creates cascading failures that cannot be fixed with workarounds.

**Rules:**
- **Never** run separate `mvn install`, `mvn site`, or `mvn surefire-report:report` commands with `-pl <module-name>` **without** `-am`.
  - Without `-am`, Maven does not build upstream dependencies, so the local repository is incomplete and subsequent steps fail.
- **Always** use `-pl common-api,app -am` (or the full reactor) when running any Maven goal across modules.
- **Never** build a chain where one `mvn install` is needed so a later `mvn` call can find artifacts. That is a sign the reactor is being broken.
- Correct pattern for multi-module goals: `mvn <goals> --no-transfer-progress -DskipTests -pl common-api,app -am -Djava.awt.headless=true`

**Examples:**

```bash
# WRONG — breaks the reactor, site cannot resolve common-api:
mvn install --no-transfer-progress -DskipTests -pl common-api
mvn site --no-transfer-progress -DskipTests -pl app

# CORRECT — single call, reactor handles dependency order:
mvn site surefire-report:report --no-transfer-progress -DskipTests -DgenerateReports=false -pl common-api,app -am -Djava.awt.headless=true
```

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
- **Security**: `@WebMvcTest` needs `@WithMockUser` + `@MockitoBean UserRepository`; `@SpringBootTest` needs `restTemplate.withBasicAuth("admin","focusstack")`

## Frontend

```bash
cd frontend-web
npm ci
npm run lint
npm run build
```

Requires Node.js ^20.19.0 or >=22.12.0.
