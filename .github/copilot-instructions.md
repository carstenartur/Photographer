# Copilot Instructions for Photographer (Focus Stack)

Use the [Developer Manual](../docs/developer/index.md) as the canonical cross-module documentation. Production code, executable tests and current POMs take precedence over planning documents and dated inventories.

## Build and test commands

### Canonical product verification — must pass before finishing product work

```bash
bash scripts/verify-product.sh
```

This repository-owned command is also called by the Java CI job. It runs `clean verify` for the supported product modules and their Maven prerequisites, including unit and Failsafe/Testcontainers integration tests. It intentionally excludes research-only, Maven-smoke and benchmark modules from the requested module list.

Requirements:

- JDK 25;
- Maven 3.9+ installed as `mvn` — there is no committed Maven Wrapper;
- Docker for Testcontainers integration tests;
- sufficient memory for the 4 GB Failsafe fork heap.

### Faster feedback while developing

```bash
bash scripts/verify-product.sh -DskipITs=true
```

This is not the completion gate. Before marking product work complete, run the unqualified canonical verification and confirm `BUILD SUCCESS`.

### Focused tests

```bash
mvn -B test -pl <owning-module> -am

mvn -B test -pl app -am \
  -Dtest=<TestClass> \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Djava.awt.headless=true
```

A focused test supplements but does not replace product verification for cross-module changes.

## Mandatory CI-aligned validation

Before completing a task or closing a PR:

- run the smallest relevant tests while developing;
- run `bash scripts/verify-product.sh` for product changes;
- run frontend tests, lint and build for frontend changes;
- inspect generated files, workflow permissions and changed-file scope;
- do not claim local verification unless the command was actually run and succeeded;
- distinguish a cancelled/step-less runner from a real compiler or test failure.

If workflow semantics change, put the behavior in a repository-owned script or Maven/npm test first and make the workflow call it. GitHub Actions is orchestration, not the only executable definition of a test.

## Maven reactor rules

The root `pom.xml` is the authoritative module list. The default reactor includes the application/domain/camera modules, the split CR3 runtime modules, CR3 test/research infrastructure, frontend and app. The `benchmarks` profile adds the two JMH modules.

Rules:

- always use `-am` with `-pl` when upstream project modules are required;
- do not build a chain where one manual `mvn install` is needed for a later Maven call;
- do not copy a static module list into new workflows when `scripts/verify-product.sh` already owns the product gate;
- keep research/testkit/benchmark artifacts out of normal supported runtime dependency graphs;
- use the smallest owning module for focused development.

Examples:

```bash
# Product gate
bash scripts/verify-product.sh

# One module and its upstream dependencies
mvn -B verify -pl canon-cr3-imageio -am -Djava.awt.headless=true

# Benchmark profile
mvn -B -P benchmarks -pl canon-cr3-benchmark -am package -DskipTests
```

## Project structure and ownership

- `common-api`: shared framework-light contracts, including legacy and generic camera abstractions;
- `plugin-api`: ServiceLoader extension contracts;
- `capture-core`, `session-core`, `observer-core`, `astro-core`, `hdr-core`, `video-core`, face modules: focused domain/workflow libraries;
- `camera-ptp`: clean-room PTP/USB stack;
- split `canon-cr3-*` runtime modules: container, metadata, codec, processing, core facade and ImageIO/CLI adapters;
- `canon-cr3-reference-testkit`, `canon-cr3-research`, `canon-cr3-maven-smoke`: internal test/research infrastructure, not supported runtime libraries;
- `frontend-web`: React/Vite product UI;
- `app`: Spring Boot composition, REST/security/JPA/Hibernate Search and production CCAPI transport.

Do not add Spring dependencies to framework-light modules merely to avoid composition code in `app`.

## Test conventions

- JUnit 5 + AssertJ; Mockito where isolation is appropriate;
- unit tests use `*Test.java` and Surefire;
- integration tests use `*IT.java` and Failsafe;
- PostgreSQL integration tests use Testcontainers and must run from a normal checkout;
- `docker` and `real-camera` groups are excluded by default;
- real-camera tests require explicit model/firmware/connection evidence and must not run in normal CI;
- long-running CR3 research tests state fixed inputs, metrics, runtime and promotion criteria;
- generated scientific evidence uses explicit verify/update modes and is never silently committed by CI.

## Camera and CCAPI changes

- use exact camera-advertised endpoint paths and HTTP methods;
- never infer model support from another camera or construct an unadvertised modern endpoint;
- retain typed, sanitized Canon error categories rather than parsing user-facing strings in callers;
- state-changing workflows require session ownership and bounded shutdown;
- long-running GET/stream operations require explicit cancellation and one owner;
- do not commit or reproduce private Canon manuals, credentials, authorization headers, serial numbers or MAC addresses;
- support claims name camera model, firmware and connection mode.

## CR3 clean-room changes

Changes to container, entropy, predictor, wavelet, dequantisation or image assembly follow `docs/cleanroom/README.md`:

- register permissible sources or project-owned observations;
- state hypotheses before execution;
- use fixed inputs and quantitative reference metrics;
- preserve rejected results;
- add executable regression coverage;
- keep experimental CRX reconstruction distinct from stable container/metadata/preview APIs;
- include the required contributor attestation for semantic changes.

## Frontend

```bash
cd frontend-web
npm ci
npm test
npm run lint
npm run build
```

Prefer accessible roles and user-observable behavior in tests. Control volatile timestamps, IDs and polling rather than broadly ignoring them.
