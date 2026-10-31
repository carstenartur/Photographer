# Photographer 0.1.5 qualification checklist

Candidate source: `41d0460c76957717f67961c9d42328c50456ea7b`. This is a preparation record; unchecked items remain pending.
The root POM already has `project.version=revision=0.1.5-SNAPSHOT`.
No version bump or publication date is introduced here.

## Exact-source checks

- [ ] Exact preparation-commit CI passes frontend tests/lint/build, Maven product verification and Docker smoke.
- [ ] With JDK 25, run `bash scripts/verify-product.sh` (canonical product gate).
- [ ] Run `mvn -B --no-transfer-progress -pl canon-cr3-maven-smoke -am clean verify`; retain isolated external-consumer results.
- [ ] Run the existing CR3 Research Integration workflow for research evidence; ordinary product verification intentionally excludes research-only modules.
- [ ] Review API stability, supported camera/decoder limits and release notes against the packaged artifacts.

## Existing release dry run

- [ ] Merge only after required review and exact-head checks.
- [ ] On current `main`, run **Release Workflow** with `dry_run=true`, `skip_tests=false`,
      `next_version_increment=patch`, `next_development_version=0.1.6-SNAPSHOT`.
- [ ] Confirm derived release version `0.1.5`, consistent root/module POM versions, no remaining snapshot POMs in release state, and successful `mvn -B clean verify`.
- [ ] Inspect workflow evidence and artifacts before a separate publication decision.

The release workflow always checks out main. It must not be used to claim a dry run of an unmerged branch.
Its dry-run path builds and changes local workflow-checkout commits only; remote publication steps are gated off.

## Publication and later distribution

- [ ] For an authorized private application release, verify the workflow-owned `v0.1.5` tag, executable application artifact and image provenance.
- [ ] Review citation/archival version and real release date when publication actually occurs; never assign a speculative DOI.
- [ ] Verify workflow-owned next-development POMs consistently become `0.1.6-SNAPSHOT`.
- [ ] Treat optional Maven Central distribution and any visibility change as separate reviewed operations under the existing runbooks.

No public visibility change, history rewrite, Central upload or publication is requested by this preparation.

