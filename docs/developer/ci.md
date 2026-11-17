# Continuous Integration

This repository keeps test logic in Maven, npm, Python and Docker commands that
can be executed from a normal checkout. GitHub Actions orchestrates those commands;
it is not the only supported execution environment.

## Local verification

Run the project verification script for Java product changes.

```bash
bash scripts/verify-product.sh
```

Run the Markdown and ownership checks directly for documentation changes.

```bash
python3 scripts/test_validate_documentation.py
python3 scripts/validate_documentation.py .
```

Use the [testing guide](testing.md) for the frontend and packaged-browser smoke commands.

To verify the installed CR3 artifacts against the isolated Maven consumer, stage the six Maven libraries with JDK 25, then run the smoke module with JDK 21.

```bash
mvn -B --no-transfer-progress install \
  -DskipTests \
  -pl canon-cr3-container,canon-cr3-metadata,canon-cr3-codec,canon-cr3-processing,canon-cr3-core,canon-cr3-imageio \
  -am \
  -Djava.awt.headless=true
mvn -B --no-transfer-progress verify -pl canon-cr3-maven-smoke -Djava.awt.headless=true
```

Run the focused or full CR3 research suite from a checkout.

```bash
bash scripts/verify-cr3-research.sh --focused
bash scripts/verify-cr3-research.sh --full
```

## Workflow responsibilities

| Workflow | Pull requests | Main, scheduled and manual runs |
|---|---|---|
| [CI](../../.github/workflows/ci.yml) | Path classification, Java verification, frontend tests/lint/build and packaged browser smoke | Classifies pushes to main. Scheduled and manual runs also generate the Maven site and badges. |
| [CR3 Packaged Consumer](../../.github/workflows/cr3-modules.yml) | Packaged CR3 artifacts, installed-POM dependencies, isolated Java 21 consumer and Maven Central preflight on relevant changes | Same checks on relevant pushes to main or manual runs. |
| [CR3 Benchmark History](../../.github/workflows/cr3-benchmark.yml) | Base/head JMH sentinel comparison on the same runner for relevant changes | Full JMH history on relevant pushes to main, weekly schedule and ordinary manual runs. |
| [CR3 Research Integration](../../.github/workflows/cr3-research.yml) | Focused evidence profile for changes to the CR3 implementation, fixtures and evidence inputs | Full reference-backed profile on scheduled and manual runs. |
| [CR3 Clean-room Evidence](../../.github/workflows/cleanroom-evidence.yml) | Validates the source register and retained reports, then builds a deterministic evidence archive on relevant changes | Same checks on relevant pushes to main or manual runs. |
| [CR3 Fiji distribution](../../.github/workflows/cr3-fiji-distribution.yml) | Tests the importer and installation archives when the Fiji module or distribution inputs change | A live release attaches the verified ZIPs and updates the hosted site. Manual dispatch can verify a build or republish the archive of an existing stable tag. |
| [Release Workflow](../../.github/workflows/deploy-release.yml) | The staged version PR goes through normal CI | Manual preparation on main stages the version change. Explicit resume on the merged stable commit builds and publishes the selected channels. |

For Markdown-only changes under `docs/` and to the root `README.md`, CI validates documentation links and ownership in the path classification job. It skips the Maven, frontend and Docker smoke jobs. The CR3 reference manifest is a build input. The other workflows apply their own path filters.

Product CI owns the production unit and integration suite. The packaged-consumer workflow checks the Git-backed Maven repository and the signed Central bundle at the candidate version derived from the root Maven `revision`. It then stages the runtime artifacts with `-DskipTests` and tests their use in an independent Maven project on Java 21.

## Private-repository Actions usage

`Photographer` is a private repository. Standard GitHub-hosted runner time therefore
counts against the repository owner's included GitHub Actions allowance and any
configured Actions budget. Jobs are rounded to billable minutes individually, so
splitting trivial steps into many separate jobs can consume more allowance than the
same commands in one bounded job.

Repository controls used here:

- superseded runs are cancelled with workflow concurrency groups;
- the reference manifest check shares the Java runner;
- the Maven site and badge publication run on scheduled and manual invocations;
- the full reactor schedule is weekly rather than daily;
- CR3 production tests are not repeated by the packaged-consumer workflow;
- research uses the focused profile on relevant pull requests;
- benchmark pull requests use the sentinel suite;
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

Use the workflow error annotations and GitHub status to diagnose failures before runner assignment. If the annotations indicate billing or quota, check the owner's Actions usage and budgets.

1. Open **Settings** for the owner account.
2. Open **Billing and licensing**.
3. Check **Usage** and **Budgets** for **Actions**.
4. Confirm that included minutes remain, or add a valid payment method and a small
   explicit budget that does not stop usage at zero.
5. Alternatively, attach a trusted self-hosted runner or wait for the billing-cycle
   allowance reset.

Resume the affected jobs after the reported runner-allocation problem is resolved.

## Change policy

- Do not move test assertions into workflow YAML.
- Do not weaken Maven or external-consumer verification to save minutes.
- Prefer eliminating duplicate execution, narrowing triggers and shortening artifact
  retention.
- Any new scheduled workflow must document expected runtime, frequency and why it
  cannot be covered by an existing job.
