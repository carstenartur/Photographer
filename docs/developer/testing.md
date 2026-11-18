# Testing

Photographer uses layered, locally runnable verification. CI calls repository-owned Maven, npm, shell and Python behavior; it is not the definition of the tests.

## Test layers

| Layer | Typical runner | Purpose |
|---|---|---|
| Java unit tests | Maven Surefire / JUnit Jupiter | Fast class and contract tests without external infrastructure. |
| Java integration tests | Maven Failsafe / JUnit Jupiter | Module interaction, Spring contexts, persistence and reference pipelines. |
| Testcontainers integration | Maven Failsafe + Docker | PostgreSQL and packaged/infrastructure behavior from a normal checkout. |
| Docker-tagged tests | JUnit tag `docker` | Image builds or heavyweight container scenarios, excluded by default. |
| Real-camera tests | JUnit tag `real-camera` | Hardware-backed CCAPI/PTP behavior, excluded by default. |
| Frontend tests | Vitest + Testing Library | React behavior in jsdom. |
| Frontend static/build checks | ESLint + Vite | Accessibility/static checks and production bundling. |
| Packaged browser test | [Playwright smoke](../../frontend-web/e2e/README.md) | Chromium operates the packaged React UI against Spring Boot, H2 and a mock camera. |
| HTTP smoke | `scripts/test-e2e.sh` | Starts Compose and checks HTTP endpoints with `curl`. |
| CR3 research tests | Failsafe / explicit test selection | Fixed-input scientific experiments and generated evidence. |
| Benchmarks | JMH | Performance measurement with recorded environment and same-runner comparisons. |

## Duplicate code report (CPD)

Run the same source-only analysis used by the scheduled/manual CI report job:

```bash
mvn -B --no-transfer-progress -P reports pmd:aggregate-cpd
python3 scripts/prepare_cpd_report.py \
  --xml target/cpd.xml \
  --report-dir target/duplication-report \
  --output-dir target/cpd-site \
  --revision "$(git rev-parse HEAD)"
```

Open `target/cpd-site/cpd.html` for the report, including the matching code blocks,
file paths and line numbers. The directory also contains the raw `cpd.xml`,
the Shields endpoint `badge.json` and `summary.json` with the analysed revision.
The [published report](https://carstenartur.github.io/Photographer/duplication/cpd.html)
is linked from the badge at the top of the project README.

The `reports` profile pins Maven PMD Plugin 3.28.0. Its aggregate CPD goal compares
production Java sources across the reactor with a minimum of **100 matching
tokens**. Identifiers and literals retain their normal meaning during comparison.
Conventional test source roots, the CR3 reference testkit and research module, and
benchmark modules are excluded. Frontend JavaScript/TypeScript and generated build
outputs are outside this Java source metric. Run the command at the repository
root with the full reactor; `-N` or a restricted `-pl` selection changes its scope.
It does not compile the application or run its tests.

The badge reports **duplicate groups**, not files, occurrences or a percentage.
A group can contain several occurrences. Findings are advisory and should be
reviewed for useful refactorings; they do not fail the product build. Report
generation errors fail the reporting job, and missing or malformed input cannot
produce a misleading zero badge. A successful scan with no matches reports zero.

## Canonical product verification

```bash
bash scripts/verify-product.sh
```

The script requests the supported product modules and their Maven prerequisites. Research-only, Maven-smoke and benchmark modules are not direct product-verification targets.

The root POM excludes the JUnit groups `docker` and `real-camera` by default. Unit tests run with a 512 MB fork heap; Failsafe integration tests run with a 4 GB fork heap. CI gives the Maven process 2 GB through `MAVEN_OPTS`.

### Faster feedback

```bash
bash scripts/verify-product.sh -DskipITs=true
```

This is useful while developing but is not equivalent to the complete product gate.

## Focused Java tests

```bash
# One module and its prerequisites
mvn -B test -pl capture-core -am

# One unit-test class in the application reactor
mvn -B test -pl app -am \
  -Dtest=CcapiLiveViewClientTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Djava.awt.headless=true

# One integration-test class
mvn -B verify -pl app -am \
  -Dit.test=CcapiDeviceBusyRetryIT \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -Djava.awt.headless=true
```

Use `clean` when a test depends on generated resources or when stale reactor output could mask a source change.

## Testcontainers

Application integration tests use Testcontainers for PostgreSQL. They require a working Docker daemon but not GitHub Actions.

```bash
mvn -B verify -pl app -am -Djava.awt.headless=true
```

Testcontainers owns container startup and cleanup. Tests should not require a manually pre-created database, fixed container name or GitHub-specific network.

A test that only mocks a database client is not an integration test. Conversely, a container should not be introduced when an isolated unit test proves the same contract more clearly.

## Docker-tagged tests

The `docker` tag is excluded by default. Run only that group with:

```bash
mvn -B verify -pl app -am \
  -Dgroups=docker \
  -DexcludedGroups= \
  -Djava.awt.headless=true
```

Run all normally selected tests including excluded groups with:

```bash
bash scripts/verify-product.sh -DexcludedGroups=
```

Docker-tagged tests must have bounded startup and shutdown, must remove temporary containers/resources and must not depend on a GitHub runner.

## Real-camera tests

Real-camera tests are tagged `real-camera` and are never part of normal CI. Run them only with the intended camera connected and an explicit test/profile selection.

For a read-only capability report, use the [model/firmware snapshot procedure](../ccapi-capabilities.md#collect-a-model-and-firmware-snapshot). It selects only `CcapiEndpointDiagnosticsIT` and produces redacted JSON for comparison across firmware and connection modes.

For capture, focus drive, file transfer or bracketing, select the individual hardware test class and read its configuration requirements before execution. These tests can actuate the camera. Existing G5 X Mark II and EOS R capture evidence is listed in [Camera setup](../camera-setup.md).

The CCAPI capture/transfer tests and the ImageIO CR3 capture-fixture generators take the camera origin from `CCAPI_CAMERA_URL` (for example `http://camera.example:8080`). Set it in the test process environment before selecting one of those classes. They skip when it is absent. USB/PTP tests use their own device selection. The read-only evidence procedure uses a separate `CCAPI_EVIDENCE_URL` so opting into a report does not enable capture.

Hardware evidence records:

- camera model;
- firmware version;
- CCAPI/PTP connection mode;
- advertised endpoints and methods where relevant;
- fixture/output provenance;
- test revision and result.

A successful test on one model must not be generalized to another model without evidence.

## Frontend

```bash
cd frontend-web
npm ci
npm test
npm run lint
npm run build
```

Vitest covers React behavior. ESLint and a successful Vite build are separate gates and do not replace component tests.

Tests should prefer accessible roles and user-observable behavior over CSS selectors or implementation details. Volatile timestamps, generated IDs and asynchronous polling should be controlled rather than broadly ignored.

## Full-stack smoke test

```bash
./scripts/test-e2e.sh
```

Despite its historical name, the current script is an HTTP/Compose smoke test, not a browser automation suite. It:

1. builds and starts the Compose stack;
2. waits for backend and frontend availability;
3. checks selected REST endpoints and Swagger access with `curl`;
4. tears the stack down and removes volumes.

The [Playwright smoke test](../../frontend-web/e2e/README.md) exercises the packaged UI, including sign-in, profile changes, a capture, frame download, deep links and a phone viewport. The Docker CI job runs it against the image it has just built.

## CR3 runtime tests

CR3 tests are split with the production modules:

- container parsing and preview extraction in `cr3-container`;
- metadata in `cr3-metadata`;
- experimental codec behavior in `cr3-codec`;
- image processing in `cr3-processing`;
- decoder orchestration and camera-profile writing in `cr3-core`;
- ImageIO reader, writer and reference-backed integration in `cr3-imageio`.

The stable container/metadata/ImageIO tests and experimental decoder tests must remain distinguishable. A green preview test is not evidence of correct sensor RAW reconstruction.

## Reference testkit and generated evidence

`canon-cr3-reference-testkit` provides shared reference resolution, TIFF loading, geometry/alignment checks, MAE/RMSE/Pearson metrics, optical-black and boundary metrics, difference images and deterministic output utilities.

Generated scientific reports follow two modes:

- **verify**: compare/recompute and fail on missing or stale evidence without overwriting committed files;
- **update**: intentionally regenerate evidence after review of inputs, environment and expected changes.

CI uses verify semantics. A workflow must not rewrite and commit scientific evidence automatically.

Reference tests need manifest-bound external outputs or committed project-owned fixtures. Missing private references must produce a clear skip/failure boundary rather than silently substituting rendered previews.

## Research tests

Long-running or exploratory tests belong in `canon-cr3-research` or explicitly identified research test packages. They should state:

- fixed input corpus and manifest;
- hypothesis before execution;
- one changed variable or bounded search space;
- quantitative decision metric;
- expected runtime and memory;
- output directory and regeneration command;
- promotion criteria for any production change.

Disabled long-running experiments are not part of the normal product gate. Their last generated report remains dated evidence, not proof that current code still produces the same result.

## Benchmarks

Build and run the CR3 suite locally:

```bash
mvn -B -P benchmarks -pl canon-cr3-benchmark -am package -DskipTests
java -Djava.awt.headless=true \
  -Dsamples.dir=canon-cr3-imageio/src/test/resources/samples \
  -jar canon-cr3-benchmark/target/benchmarks.jar
```

For pull requests, `scripts/run_jmh_compare_refs.py` builds and measures base and head on the same host. Never compare unrelated hosted runners as though CPU and JVM variance were a code regression.

Benchmark evidence records base/head SHAs, order, CPU, runner image, kernel, Java, Maven and raw JMH output. Threshold policy remains evidence-driven work; one noisy sample is not a performance contract.

## CI gates

See [Continuous Integration](ci.md#workflow-responsibilities) for the current workflow triggers, documentation-only path check and packaged-consumer checks.

## Failure diagnosis

On a Java failure:

1. identify whether the failure occurred before checkout, during setup, during compilation, in Surefire, Failsafe, packaging or Docker;
2. inspect uploaded `maven-verify.log` and XML reports;
3. distinguish a cancelled runner from a failing test;
4. reproduce with the smallest local command;
5. change code only for a grounded failure.

A workflow marked red with no steps or a cancelled Maven process is not automatically evidence of a product defect. A green summary with missing/skipped required tests is not automatically evidence of correctness either.

## Coverage and quality

JaCoCo reports are generated during application verification. Coverage is a navigation aid, not a substitute for meaningful assertions. Prioritize:

- lifecycle and ownership boundaries;
- typed error mapping;
- persistence and migration behavior;
- deterministic camera catalogue decisions;
- shutdown/cancellation;
- data-loss prevention;
- scientific metric contracts;
- user-visible frontend behavior.
