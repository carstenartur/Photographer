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
| [CI](../../.github/workflows/ci.yml) | Path classification, Java verification, frontend tests/lint/build and packaged browser smoke | Classifies pushes to main. Scheduled and manual runs also generate the Maven site, aggregate CPD report and badges. |
| [CR3 Packaged Consumer](../../.github/workflows/cr3-modules.yml) | Packaged CR3 artifacts, installed-POM dependencies, isolated Java 21 consumer and Maven Central preflight on relevant changes | Same checks on relevant pushes to main or manual runs. |
| [CR3 Benchmark History](../../.github/workflows/cr3-benchmark.yml) | Base/head JMH sentinel comparison on the same runner for relevant changes | Full JMH history on relevant pushes to main, weekly schedule and ordinary manual runs. |
| [CR3 Research Integration](../../.github/workflows/cr3-research.yml) | Focused evidence profile for changes to the CR3 implementation, fixtures and evidence inputs | Full reference-backed profile on scheduled and manual runs. |
| [CR3 Clean-room Evidence](../../.github/workflows/cleanroom-evidence.yml) | Validates the source register and retained reports, then builds a deterministic evidence archive on relevant changes | Same checks on relevant pushes to main or manual runs. |
| [CR3 Fiji distribution](../../.github/workflows/cr3-fiji-distribution.yml) | Tests the importer and installation archives when the Fiji module or distribution inputs change | A live release attaches the verified ZIPs and updates the hosted site. Manual dispatch can verify a build or republish the archive of an existing stable tag. |
| [Release Workflow](../../.github/workflows/deploy-release.yml) | Merging a same-repository PR labelled `release: publish` into `main` starts a release. The next development version returns to `main` through a PR. | Maintainers can also start a release manually on `main` or preview it with `dry_run`. |

For Markdown-only changes under `docs/` and to the root `README.md`, CI validates documentation links and ownership in the path classification job. It skips the Maven, frontend and Docker smoke jobs. The CR3 reference manifest is a build input. The other workflows apply their own path filters.

Product CI owns the production unit and integration suite. The packaged-consumer workflow checks the Git-backed Maven repository and the signed Central bundle at the candidate version derived from the root Maven `revision`. It then stages the runtime artifacts with `-DskipTests` and tests their use in an independent Maven project on Java 21.

The report job runs the [source-only CPD command](testing.md#duplicate-code-report-cpd)
once for the full Java reactor and uploads its HTML assets, raw XML, badge and
revision summary as `duplicate-code-report`. Successful reporting runs on `main`
publish it to the `duplication/` directory on GitHub Pages after Java verification
passes. Other Pages content, including benchmark history and the Fiji update site,
is preserved. The job explicitly requests a Pages build after publication because
commits made with `GITHUB_TOKEN` do not trigger that build automatically.
Duplicate groups are a metric for review, not an additional build
failure threshold; report-generation errors still fail the job.

## Publish a release

Merge a same-repository pull request labelled `release: publish` into `main`, or open **Actions → Release Workflow → Run workflow**, select `main` and run with the defaults. You can also start it from the CLI.

```bash
gh workflow run deploy-release.yml --ref main
```

Both version fields can be left blank. For example, starting at `0.1.6-SNAPSHOT` produces tag `v0.1.6` and next development version `0.1.7-SNAPSHOT`. On manual runs, set `release_version` to override the stable version or `development_version` to override the next snapshot. Select `dry_run` to build and verify the candidate locally on the runner.

The Maven Release Plugin's `release:prepare` goal versions the reactor, including the benchmark modules. Its `release:perform` goal checks out the stable tag and stages the six CR3 libraries. A live run publishes the application JAR and Fiji ZIPs to GitHub Releases, the image to GHCR, the Fiji update site to Pages and the libraries to the Git-backed Maven repository and Maven Central. [Maven publication and verification](../cr3-maven-central.md) · [Fiji release verification](../cr3/fiji-update-site.md)

The workflow opens one PR to bring the next development snapshot back to `main`. If repository settings block automatic PR creation, open the comparison link in the snapshot job and create the PR from the prepared branch. Select **Approve workflows to run** if prompted, and merge after the required checks and reviews pass. You can rerun failed jobs from the original release run. [GitHub Actions settings](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository#preventing-github-actions-from-creating-or-approving-pull-requests)

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
