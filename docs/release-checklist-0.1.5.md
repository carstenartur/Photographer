# Photographer 0.1.5 qualification checklist

Historical preparation source: `41d0460c76957717f67961c9d42328c50456ea7b`.
This is a preparation record; unchecked items remain pending. The preparation
set `project.version=revision=0.1.5-SNAPSHOT`. A new release handoff must start
from freshly verified main after the workflow repair merges. Historical checks
and this checklist do not qualify a later source or publish an application.

## Exact-source checks

- [ ] Exact preparation-commit CI passes frontend tests/lint/build, Maven product verification and Docker smoke.
- [ ] With JDK 25, run `bash scripts/verify-product.sh` (canonical product gate).
- [ ] Run `mvn -B --no-transfer-progress -pl canon-cr3-maven-smoke -am clean verify`; retain isolated external-consumer results.
- [ ] Run the existing CR3 Research Integration workflow for research evidence; ordinary product verification intentionally excludes research-only modules.
- [ ] Review API stability, supported camera/decoder limits and release notes against the packaged artifacts.

## Source and approval contract

Use only the existing manual **Release Workflow** (`deploy-release.yml`), selected
on `main`. Checkout binds the dispatched `github.sha`; the helper rejects a
non-main ref, a dirty tracked tree, or movement between control SHA and current
main. No workflow event or direct update of main is a release shortcut.

The serial handoff records these exact commits:

| Name | Meaning |
| --- | --- |
| S | Fresh snapshot source on main before preparation |
| R | One stable-version metadata commit, whose sole parent is S |
| M | Actual normally merged stable commit on main, whose sole parent is S and whose complete tree equals R |
| N | Next-snapshot metadata commit, whose sole parent is M |
| C | Dispatched workflow/control SHA; C=S for preparation and C=M for resume |

A squash or rebase merge can change R's identity. Resume validates M by parentage
and exact tree equivalence, rather than assuming M=R. It also validates the
immutable source/release/next-version trailers on R; changing PR-body prose cannot
change the release plan. If squash/rebase history and branch deletion leave R
out of a fresh main checkout, resume fetches the PR head ref and requires its
exact recorded SHA before provenance checks. Any source movement is a hold
requiring fresh planning.

Existing PR, required Maven verification, linear-history, resolved-thread and
code-quality protection apply to both metadata PRs. Open each PR through the
ordinary authorized connector/maintainer flow using the printed comparison URL.
The workflow stages branches and does not create PRs. If a bot-created PR already
has approval-required runs, a writer must use **Approve workflows to run**. Do
not manufacture another event, change settings, or bypass approval/checks.

## Prepare and review the stable release

- [ ] Merge the workflow repair only after its own real exact-head CI and review.
- [ ] On freshly verified main, run **Release Workflow** with `dry_run=true`,
      `skip_tests=false`, `resume_release_pr` empty,
      `next_version_increment=patch`, `next_development_version=0.1.6-SNAPSHOT`.
- [ ] Confirm Maven `project.version` and `revision` agree, release derives as
      `0.1.5`, next version is `0.1.6-SNAPSHOT`, reactor POMs are stable for the
      complete `mvn -B clean verify`, and the local next-snapshot transition validates.
- [ ] Review the dry-run source/control SHAs and plan. A dry run performs no remote
      writes and cannot authorize publication; its comparison branch may not exist.
- [ ] When preparation is authorized, repeat on the same still-current main with
      `dry_run=false`, `skip_tests=false`, and the same version choices.
- [ ] Record S, R, C, release/next versions, deterministic staged branch and printed
      comparison URL. Preparation ends at a stable metadata handoff, before any
      tag, application release, image or maintenance ref operation.
- [ ] Open the stable PR against main using that comparison. Verify its base is S,
      head is the recorded staged commit, it contains one exact metadata commit,
      and its real checks/review/threads satisfy protection. Normally squash or
      rebase merge it with the verified expected head; never directly push main.
- [ ] Record actual M and wait for its canonical CI **push** run to finish:
      **Maven verification**, **Frontend Test, Lint & Build** and **Docker Build
      Smoke Test** must all succeed for exact M. The optional **Scheduled Maven
      Site & Badges** job may legitimately be skipped. Missing, stale, pending,
      skipped required, cancelled, failed and approval-required results do not qualify.

Version metadata updates follow the root/nested module declarations, including
inactive-profile benchmark modules. The helper applies an exact byte-preserving
coordinate/revision transformation and then re-evaluates Maven; it does not rely
on Maven Versions plugin discovery. Non-reactor consumer fixtures retain their
own `1.0-SNAPSHOT` and `@revision@` placeholders. Stable validation applies to
reactor POMs rather than rejecting every unrelated fixture snapshot.

The optional exact next version must use `X.Y.Z-SNAPSHOT` and be strictly newer
than the release. If empty, the existing patch/minor/major choice is preserved.
`skip_tests=true` is retained as an input for compatibility but is rejected for
this qualified preparation/resume flow, including dry runs.

## Explicitly resume publication from the merged stable PR

- [ ] Verify current main still equals M and the stable PR was normally merged.
- [ ] Invoke **Release Workflow** on main with `resume_release_pr` set to that PR's
      positive number, `dry_run=false`, `skip_tests=false`. Leave
      `next_development_version` empty, or supply the same immutable exact plan.
      The preparation increment no longer selects a new plan during resume.
- [ ] Confirm resume evidence records S/R/M/C and the immutable version plan,
      validates the one-commit metadata shape and complete source tree, and
      rebuilds exact M with the full `mvn -B clean verify` and tests enabled.
- [ ] Require `app/target/focusstack.jar` to be an executable Spring Boot JAR and
      record its SHA256. Missing/invalid JAR stops before tag/release creation.
- [ ] Confirm the final guard rechecks current main=M, stable checkout and JAR hash,
      explicit tag/release absence, and maintenance lookup before publication.
      Release absence also requires complete paginated collection inspection,
      including drafts: release-by-tag404 alone can hide an existing draft.
      Authentication, server, malformed or truncated responses are holds, not absence.
- [ ] Verify the annotated `v0.1.5` tag targets M, the GitHub application release
      uploads the required verified JAR without replacing assets, and Docker
      publication uses the same stable M checkout. The image guard rechecks main,
      tag/release identities and artifact hash immediately before the image action.

The workflow serializes release runs without cancelling an active release. This
does not freeze main or remove the requirement for final source checks. No other
application publisher, Central upload or repository visibility change is added.

## Next snapshot and maintenance

Only after successful application/image publication does the workflow stage N,
setting consistent next-development coordinates to `0.1.6-SNAPSHOT`. It prints a
second comparison URL. Open that PR normally, verify its exact head and full real
checks/review, merge normally, and confirm the exact new main checks. The two
metadata PRs are serial: do not stack the snapshot PR before publication.

Both R and N branches are deterministic. A compatible existing branch is reused
only when its sole parent, exact complete tree and immutable plan all match.
A different commit identity with the same complete state may qualify. Conflict
never permits force, deletion/recreation, or replacement. Git Database object
uploads preserve the full parent tree, verify local/remote blob/tree/commit SHA
identity, and atomically create only a new ref. An API serialization mismatch or
creation race stops; orphan immutable objects do not authorize ref replacement.

The existing maintenance responsibility remains: create `maintenance/0.1.x` at M
when absent. An existing maintenance tip is recorded before publication and left
unchanged, even when it points to an older release; it is never silently repointed.
Unknown lookup state or unexpected movement is a hold for maintainer review.

## Holds, evidence and later distribution

Any existing target tag/release, partial publication, late source movement or
conflicting branch requires separate diagnosis. A new `resume_release_pr` run is
**not a recovery/retry mode**. It cannot replace release assets, rewrite a tag,
continue a failed partial publication, or claim an earlier dry run published
anything. If tag/release/image publication fails partway through, stop and retain
the exact workflow, source, artifact and target-state evidence before deciding on
any separately authorized recovery. A failure of the later snapshot handoff also
requires explicit diagnosis; publication may already have completed.

Offline Python handoff/version/validator tests exercise these contracts, not the
Java/frontend/Docker product. The normal repair PR, stable PR and snapshot PR need
their own actual hosted gates. The resumed canonical full release build is a
further gate; historical CI, a local syntax check and a dry run do not substitute
for it. Use a complete checkout with JDK 25, Maven 3.9+, frontend tooling, Docker
and sufficient memory for product/release verification.

- [ ] Verify application/image provenance and the next-snapshot handoff evidence.
- [ ] Review citation/archival version and real release date when publication
      actually occurs; never assign a speculative DOI.
- [ ] Treat optional Maven Central distribution and any visibility change as
      separate reviewed operations under the existing runbooks.

The repository remains private. No public visibility change, history rewrite,
Central upload or application publication is authorized merely by this checklist.
