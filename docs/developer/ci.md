# Continuous Integration

This repository keeps test logic in Maven, npm, Python and Docker commands that
can be executed from a normal checkout. GitHub Actions orchestrates those commands;
it is not the only supported execution environment.

## Local verification

Run the same product checks used by the general CI workflow:

```bash
python3 scripts/validate_cr3_reference_manifest.py
mvn -B --no-transfer-progress clean verify \
  -pl common-api,plugin-api,capture-core,storage,camera-ptp,hdr-core,video-core,face-analysis-core,face-profile-core,session-core,observer-core,ai-vision,astro-core,canon-cr3-container,canon-cr3-metadata,canon-cr3-codec,canon-cr3-processing,canon-cr3-core,canon-cr3-imageio,canon-cr3-cli,app \
  -am \
  -Djava.awt.headless=true
npm --prefix frontend-web ci
npm --prefix frontend-web run lint
npm --prefix frontend-web run build
docker build -t focusstack:test .
```

Verify the packaged CR3 artifacts from an isolated Maven consumer:

```bash
mvn -B --no-transfer-progress install \
  -DskipTests \
  -pl canon-cr3-container,canon-cr3-metadata,canon-cr3-codec,canon-cr3-processing,canon-cr3-core,canon-cr3-imageio \
  -am \
  -Djava.awt.headless=true
mvn -B --no-transfer-progress verify \
  -pl canon-cr3-maven-smoke \
  -Djava.awt.headless=true
```

Run the intentionally expensive CR3 research suite explicitly:

```bash
mvn -B --no-transfer-progress install \
  -DskipTests \
  -pl canon-cr3-research \
  -am \
  -Djava.awt.headless=true
mvn -B --no-transfer-progress verify \
  -Pcr3-research-it \
  -pl canon-cr3-research \
  -Djava.awt.headless=true
```

## Workflow responsibilities

| Workflow | Pull requests | Main/scheduled responsibility |
|---|---|---|
| `CI` | Java tests, Testcontainers integration tests, manifest validation, frontend lint/build and Docker smoke build | Same product verification; Maven site and badges only on scheduled/manual runs |
| `CR3 Packaged Consumer` | Installed-POM graph, compiled JAR boundaries, runtime dependency leaks and external ImageIO/Maven consumption | Same packaged-consumer gate after CR3 changes |
| `Benchmark` | Short base/head sentinel comparison on the same runner | Full longitudinal JMH suite weekly or manually |
| `CR3 Research Integration` | Not automatic | Full reference-backed research profile monthly or manually |

The general CI owns production unit and integration tests. The CR3 packaged-consumer
workflow must not repeat those tests; it stages already-tested production artifacts
with `-DskipTests` and then executes only the independent consumer.

## Private-repository Actions usage

`Photographer` is a private repository. Standard GitHub-hosted runner time therefore
counts against the repository owner's included GitHub Actions allowance and any
configured Actions budget. Jobs are rounded to billable minutes individually, so
splitting trivial steps into many separate jobs can consume more allowance than the
same commands in one bounded job.

Repository controls used here:

- superseded runs are cancelled with workflow concurrency groups;
- the lightweight manifest gate shares the Java runner;
- reports never run on pull requests;
- the full reactor schedule is weekly rather than daily;
- CR3 production tests are not repeated by the packaged-consumer workflow;
- heavy research is monthly/manual;
- full benchmark history is weekly/manual rather than per merge;
- every job has a timeout;
- transient artifacts use short retention periods.

## Jobs fail before step 1

A workflow failure with all of the following characteristics is not a Maven, npm or
test failure:

- the job is created and immediately marked failed;
- no runner is assigned;
- the API reports `steps: null`;
- no job log blob exists;
- unrelated Java, frontend and Python jobs fail identically.

For a private repository, first inspect the repository owner's GitHub billing page:

1. Open **Settings** for the owner account.
2. Open **Billing and licensing**.
3. Check **Usage** and **Budgets** for **Actions**.
4. Confirm that included minutes remain, or add a valid payment method and a small
   explicit budget that does not stop usage at zero.
5. Alternatively, attach a trusted self-hosted runner or wait for the billing-cycle
   allowance reset.

Repository commits cannot re-enable hosted-runner usage after the account allowance
or budget has blocked it. Re-running the same workflow before resolving the account
setting only creates another immediately failed attempt.

## Change policy

- Do not move test assertions into workflow YAML.
- Do not weaken Maven or external-consumer verification to save minutes.
- Prefer eliminating duplicate execution, narrowing triggers and shortening artifact
  retention.
- Any new scheduled workflow must document expected runtime, frequency and why it
  cannot be covered by an existing job.
