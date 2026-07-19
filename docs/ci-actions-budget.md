# GitHub Actions usage and recovery

This repository is private. GitHub-hosted runner time and artifact storage therefore consume the owner's included Actions allowance or configured Actions budget.

## Recognising an account-level runner block

A repository build has not reached Maven, npm, Docker, or any project test when all of the following are true:

- the workflow job finishes immediately with `conclusion=failure`;
- the job has no assigned runner and `steps` is absent or `null`;
- no job log or downloadable log archive exists;
- rerunning the job produces the same result before checkout starts.

That failure mode is normally caused by an exhausted hosted-runner allowance, a zero/blocked Actions budget, or a billing restriction. Repository code cannot release that account-level block.

## Restoring hosted-runner execution

The repository owner must inspect the account or organisation settings under:

**Settings → Billing and licensing → Usage / Budgets → Actions**

Use one of the following recovery paths:

1. add or confirm a valid payment method and configure an Actions budget that permits additional usage;
2. wait until the included-minute billing cycle resets; or
3. attach a trusted self-hosted runner with the required Java, Node.js, Docker, and operating-system capabilities.

After the account is unblocked, rerun only the latest failed workflow attempt. Do not rerun superseded attempts because they duplicate work and consume more allowance.

## Repository-side workload policy

The workflows are intentionally split by purpose:

- `ci.yml` runs the production Java reactor and frontend checks. Superseded runs are cancelled. Maven site and badge reports run only weekly or by manual dispatch.
- `cr3-modules.yml` does not repeat CR3 production unit tests. It installs production artifacts with tests skipped and runs only the independent Maven consumer and compiled-boundary verification.
- `benchmark.yml` keeps the same-runner base/head pull-request sentinel. Full benchmark history runs only weekly or by manual dispatch.
- `cr3-research.yml` runs the expensive reference-backed research profile monthly or manually.
- `docker.yml` publishes on relevant `main` changes or manual dispatch and ignores documentation-only and CR3 research/test-infrastructure changes.

All recurring jobs have concurrency cancellation, explicit timeouts, and short artifact retention.

## Equivalent local validation

Normal Java and Testcontainers verification:

```bash
mvn -B --no-transfer-progress clean verify \
  -pl common-api,plugin-api,capture-core,storage,camera-ptp,hdr-core,video-core,face-analysis-core,face-profile-core,session-core,observer-core,ai-vision,astro-core,canon-cr3-container,canon-cr3-metadata,canon-cr3-codec,canon-cr3-processing,canon-cr3-core,canon-cr3-imageio,canon-cr3-cli,app \
  -am -Djava.awt.headless=true
```

CR3 independent-consumer verification without repeating production unit tests:

```bash
mvn -B --no-transfer-progress install \
  -DskipTests -Dinvoker.skip=true \
  -pl canon-cr3-container,canon-cr3-metadata,canon-cr3-codec,canon-cr3-processing,canon-cr3-core,canon-cr3-imageio \
  -am -Djava.awt.headless=true

mvn -B --no-transfer-progress verify \
  -pl canon-cr3-maven-smoke \
  -Djava.awt.headless=true
```

Frontend validation:

```bash
cd frontend-web
npm ci
npm run lint
npm run build
```

Docker validation:

```bash
docker build -t focusstack:test .
```

Manifest and badge utilities can also be run directly:

```bash
python3 scripts/validate_cr3_reference_manifest.py
python3 scripts/generate_ci_badges.py
```
