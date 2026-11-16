# Contributing and Local Development

This is the canonical checkout-to-pull-request guide. Commands run from a normal Git checkout; GitHub Actions only orchestrates the same repository-owned behavior.

## Prerequisites

| Tool | Required for | Version used by CI |
|---|---|---|
| JDK | Java build and tests | 25 (Temurin) |
| Maven | Multi-module reactor | 3.9+ |
| Node.js | Frontend | 24.21.0 LTS (same version in CI and Maven) |
| npm | Frontend dependencies/build | Version bundled with the selected Node release |
| Docker + Compose | PostgreSQL, Testcontainers and packaged smoke tests | Current supported Docker installation |
| Python 3 | Repository validation and benchmark helper scripts | Python 3 |

The repository does **not** currently contain a Maven Wrapper. Use an installed `mvn` executable.

## Clone and inspect

```bash
git clone https://github.com/carstenartur/Photographer.git
cd Photographer
mvn -version
java -version
node --version
npm --version
```

The root `pom.xml` is the authoritative reactor definition. See [Modules](modules.md) before assuming a dated module list is complete.

## Local backend with H2

Follow the [Maven quickstart](../quickstart.md#run-locally-with-maven) to build the executable JAR with the frontend and run it on port 8080. The default profile uses an in-memory H2 database and local-heap Hibernate Search.

With PowerShell 7, run from the repository root:

```powershell
mvn -B -pl frontend-web,app -am package -DskipTests "-Djava.awt.headless=true"
$env:FOCUSSTACK_SECURITY_ADMIN_PASSWORD = Read-Host "Photographer admin password" -MaskInput
java "-Djava.awt.headless=true" -jar app/target/focusstack.jar
```

## Docker Compose development

Create a local environment file and replace the example administrator password:

```bash
cp .env.example .env
docker compose up --build
```

The Compose stack provides PostgreSQL and the packaged application. `FOCUSSTACK_SECURITY_ADMIN_PASSWORD` is required by Compose and startup validation rejects known insecure defaults.

To remove containers and persistent development volumes:

```bash
docker compose down --volumes --remove-orphans
```

Do not use that command against data that has not been backed up.

## Frontend development

```bash
cd frontend-web
npm ci
npm run lint
npm run dev
```

The Vite development server uses its configured backend proxy. Production packaging uses `npm run build`; the resulting `frontend-web/dist` tree is copied into the Spring Boot artifact.

Use `npm ci`, not `npm install`, when verifying a pull request so the committed lockfile is honored.

## Product verification

Run the same product-module boundary used by CI:

```bash
bash scripts/verify-product.sh
```

This runs repository validators, the current-tree credential audit, `clean verify` for the supported product modules and their Maven prerequisites. It excludes research-only, Maven-smoke and benchmark modules from the requested module list.

Useful variants pass normal Maven properties through the script:

```bash
# Faster unit-oriented feedback; integration tests are skipped.
bash scripts/verify-product.sh -DskipITs=true

# Include groups excluded by the default parent configuration.
bash scripts/verify-product.sh -DexcludedGroups=
```

The unqualified command below builds every module in the default reactor, including internal test and research modules, and may be materially slower:

```bash
mvn -B --no-transfer-progress clean verify -Djava.awt.headless=true
```

See [Testing](testing.md) before enabling Docker, real-camera or long-running research groups.

## Focused module work

Build the smallest relevant module plus its prerequisites:

```bash
mvn -B test -pl capture-core -am
mvn -B verify -pl canon-cr3-imageio -am -Djava.awt.headless=true
mvn -B test -pl app -am -Dtest=CcapiLiveViewClientTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Djava.awt.headless=true
```

A focused command supplements but does not replace the canonical product verification for cross-module changes.

## Repository validation scripts

The Java CI also runs repository-owned Python checks:

```bash
python3 scripts/validate_cr3_reference_manifest.py
python3 scripts/test_compare_jmh.py
python3 scripts/test_run_jmh_compare_refs.py
python3 scripts/test_validate_ccapi_transport_boundary.py
python3 scripts/validate_ccapi_transport_boundary.py .
python3 scripts/test_audit_public_release.py
python3 scripts/audit_public_release.py
```

The normal public-release audit scans the current tracked tree for high-confidence credential formats. From a complete checkout, the following command can also inspect text history and media metadata. [Publication checks](../public-release.md)

```bash
python3 scripts/audit_public_release.py --history --media-metadata
```

When a workflow needs new validation semantics, implement them in a local script or Maven test first, then call that code from the workflow.

## Change placement

Use the smallest owning module:

- shared records and transport-independent contracts: `common-api`;
- plugin extension contracts: `plugin-api`;
- focused workflow algorithms: the relevant `*-core` module;
- PTP protocol and Canon USB extensions: `camera-ptp`;
- Spring/JPA/HTTP composition and CCAPI production transport: `app`;
- CR3 container, metadata, codec, processing and adapters: the corresponding split CR3 module;
- experiment-only code and report generation: `canon-cr3-research` or test sources, not a public runtime artifact.

Do not add a Spring dependency to a domain module merely to avoid writing composition code in `app`.

## Camera changes

Camera behavior is capability- and evidence-driven:

- use exact advertised CCAPI paths and methods;
- do not infer model support from another camera;
- retain typed Canon errors rather than parsing arbitrary UI text;
- state-changing workflows need session ownership and bounded shutdown behavior;
- real-camera support claims name model, firmware and connection mode;
- tests without hardware prove protocol decisions and lifecycle behavior, not universal camera compatibility.

Private Canon manuals and credentials must not be committed.

## CR3 clean-room changes

Changes to CR3/CRX container, entropy, predictor, wavelet, dequantisation or image-assembly semantics must follow the [clean-room engineering record](../cleanroom/README.md).

A semantic decoder change requires:

- a registered permissible source or project-owned observation;
- fixed inputs and a pre-stated hypothesis;
- quantitative reference evidence;
- executable regression coverage;
- explicit separation of experimental behavior from stable preview/metadata APIs.

Do not use image appearance alone as scientific evidence when quantitative reference metrics exist.

## Pull-request discipline

Before opening or updating a pull request:

1. keep the diff focused and remove temporary write-enabled workflows;
2. run the smallest relevant tests while developing;
3. run `bash scripts/verify-product.sh` for product changes;
4. run frontend lint/build for frontend changes;
5. update module-local and canonical documentation together when a contract changes;
6. describe hardware, generated evidence and unverified boundaries precisely;
7. inspect generated files and ensure CI verifies rather than silently rewrites them.

A green workflow is necessary but not sufficient: review the changed-file list, generated output, permissions and user-visible behavior before merge.

## Documentation changes

Use the [documentation map](documentation-map.md):

- update canonical cross-module chapters here;
- keep module-specific usage near the module;
- change generators rather than hand-editing generated reports;
- preserve dated research evidence and link it from current-state documents;
- keep old paths as short redirects when a move would otherwise break useful links.
