# Developer Documentation Migration Plan

## Status

This plan is the required decision step before documentation moves begin. The planning PR adds the Developer Manual entry point and classification map only; it does not move or delete existing documentation.

The prerequisite correctness audit in issue #90 is complete. The source inventory is [`repository-inventory.md`](repository-inventory.md), generated on 2026-06-17. Because the repository has changed since that snapshot, every migration phase must re-check affected paths against current code and tests.

## Goals

- one stable developer entry point;
- one canonical place for every cross-module topic;
- module-local documentation retained where proximity to code is useful;
- stable API/implementation documentation separated from research state;
- generated and dated evidence preserved without being presented as current truth;
- commands runnable from a normal checkout, with GitHub Actions used only for orchestration;
- no generated HTML committed to `main`.

## Non-goals

- no production or decoder behaviour changes;
- no rewrite of all documentation from scratch;
- no new architecture or research framework invented for the documentation project;
- no loss of dated research evidence;
- no manual cosmetic rewrite of generated reports;
- no relocation merely to match a proposed directory tree when the existing location is clearer.

## Target structure

The target is intentionally small. Module-local files remain in module directories.

```text
docs/
  developer/
    index.md
    architecture.md
    modules.md
    testing.md
    contributing.md
    operations.md
    documentation-map.md
    migration-plan.md
    repository-inventory.md        # dated/generated baseline
  cr3/
    index.md
    imageio.md
    decoder.md
    cli.md
    research.md
    current-state.md
    open-hypotheses.md
    reports/
      index.md
      generated/
  cleanroom/
    README.md
    ...                            # process, source registry, attestations
```

`docs/api.md`, `docs/camera-setup.md`, `docs/quickstart.md` and transport-specific CCAPI documents may remain at their current paths when moving them would add link churn without removing duplication. The Developer Manual links them as canonical external chapters.

## Phase 1 — Authority map and entry point

**Scope**

- add `docs/developer/index.md`;
- classify every file from the 2026-06-17 Markdown inventory;
- define authority and generated-document rules;
- add this migration plan;
- change the root README documentation section to expose one Developer Manual path.

**No moves or removals are allowed in this phase.**

**Acceptance**

- every inventoried Markdown file has one classification;
- post-inventory additions have deterministic classification rules;
- root README has one obvious developer entry;
- generated reports are explicitly non-normative.

## Phase 2 — Consolidate maintained developer documentation

### 2.1 Architecture

Merge cross-module material from:

- `docs/architecture.md`;
- `docs/generic-camera-architecture.md`;
- cross-module sections currently duplicated in the root README;
- only the cross-module parts of module `ARCHITECTURE.md` files.

Create `docs/developer/architecture.md`. Module architecture files retain module internals and link to it. Do not copy the complete dependency graph into every module.

### 2.2 Modules and public surfaces

Create `docs/developer/modules.md` from current `pom.xml`, module READMEs and public API contracts. The file should answer:

- which modules are in the default reactor and benchmark profile;
- what each module owns;
- which modules are supported libraries, application internals, test infrastructure or research-only;
- where the authoritative module README and public API live.

Counts from `repository-inventory.md` must not be copied as timeless facts. Regenerate or omit volatile counts.

### 2.3 Contributing and local development

Merge maintained content from `docs/development.md` and the detailed local-development parts of the root README into `docs/developer/contributing.md`.

Keep the root README quick path concise. Commands must work from a normal checkout and must not require GitHub Actions.

### 2.4 Testing

Merge `docs/test-strategy.md` into `docs/developer/testing.md` and verify every command against Maven profiles and test naming conventions.

The chapter must distinguish:

- unit tests;
- Testcontainers integration tests runnable from a checkout;
- opt-in real-camera tests;
- long-running CR3 research tests;
- JMH benchmarks;
- deterministic screenshot/UI evidence when present.

GitHub workflows should call repository-owned test commands and collect results; they must not become the only place where test semantics exist.

### 2.5 Operations and recovery

Audit `BACKUP.md` against the current private repository, current tracked fixtures, `.gitignore` and reproducibility commands. Move durable recovery guidance to `docs/developer/operations.md`.

Do not preserve stale statements merely because they describe an earlier local working tree. Private/non-redistributable Canon reference material remains explicitly outside the repository.

**Acceptance for Phase 2**

- canonical developer chapters exist and are linked from the index;
- module docs contain only module-local detail plus links;
- no build/test command exists only inside a workflow;
- old paths either remain valid redirects or are updated in the same PR;
- root README is shorter, not duplicated elsewhere verbatim.

## Phase 3 — Consolidate CR3 maintained documentation

### 3.1 CR3 entry point

Create `docs/cr3/index.md` with four clear routes:

- supported CR3 container/metadata/ImageIO/CLI functionality;
- experimental decoder status and limitations;
- clean-room research process;
- dated/generated evidence.

### 3.2 Supported library documentation

Create short maintained bridge pages:

- `docs/cr3/imageio.md` links the supported ImageIO SPI, read parameter, metadata and tests;
- `docs/cr3/decoder.md` links the supported core API and labels experimental CRX paths explicitly;
- `docs/cr3/cli.md` links the tested CLI commands.

These pages must not duplicate complete module READMEs. The module README remains the local usage reference; the bridge page explains maturity and relationships.

### 3.3 Current state and hypotheses

Move with redirects or equivalent link preservation:

- `docs/cr3-current-state.md` → `docs/cr3/current-state.md`;
- `docs/cr3-open-hypotheses.md` → `docs/cr3/open-hypotheses.md`;
- `docs/cr3-results-index.md` → `docs/cr3/reports/index.md`.

The current-state page is the maintained truth boundary. It links to generated reports as evidence and records whether each result still applies to the current implementation.

### 3.4 Research process

Merge still-current process content from:

- `docs/cr3-agent-playbook.md`;
- `docs/cleanroom-fixture-plan.md`;
- `docs/reference-pipeline.md` where it is CR3-specific.

Create `docs/cr3/research.md`, while keeping clean-room policy and contributor attestations canonical under `docs/cleanroom/`.

Historical fixture ledgers, patent analyses and experiment-local READMEs remain local and linked. They are not rewritten into product documentation.

**Acceptance for Phase 3**

- a contributor can distinguish supported, experimental, planned and generated CR3 information from the CR3 index;
- no generated report is presented as current implementation truth;
- module API docs and research evidence remain available;
- clean-room source registration and attribution links survive all moves.

## Phase 4 — Generated report boundary

Move or regenerate CR3 report outputs under `docs/cr3/reports/generated/` only after updating their generators and all links in the same change.

Each generated report must contain or be accompanied by:

- generator class/script/test;
- exact local regeneration command;
- source fixture/manifest contract;
- generation timestamp or source revision where scientifically relevant;
- a warning that scientific conclusions use quantitative data, not rendered screenshots.

Generation must have two explicit modes:

- **verify** — fail when committed evidence is missing or stale; never overwrite;
- **update** — intentionally regenerate the committed evidence.

CI uses verify mode. It must never rewrite and commit generated evidence implicitly.

Maven Site, Javadoc, coverage, test HTML and similar build reports stay in build artifacts or GitHub Pages and are not committed to `main`.

**Acceptance for Phase 4**

- generated files have identifiable generators;
- verify and update behaviour are separate;
- all local generation commands work without GitHub Actions;
- report links and images remain valid;
- no historical evidence is lost.

## Phase 5 — Retire stale root documents

### `TODO.md`

Audit every unchecked item:

1. link it to an open issue with current scope, or create/update one broader issue when the work is still valid;
2. mark claims already superseded by current code/research as obsolete;
3. remove completed historical checklists that add no enduring rationale.

Then replace `TODO.md` with a short pointer to GitHub issues or remove it after inbound links are updated. It must not remain a competing roadmap.

### `BACKUP.md`

After Phase 2.5, replace it with a short redirect to `docs/developer/operations.md` for one migration interval, then remove it when no inbound links remain.

**Acceptance for Phase 5**

- no planned work exists only in Markdown;
- no stale working-tree snapshot is presented as a current backup requirement;
- all removed paths have updated inbound links or temporary redirects.

## Phase 6 — Validation and ownership

Add repository-owned checks that are runnable locally and only orchestrated by CI:

- internal Markdown link validation;
- duplicate canonical-topic detection based on a small maintained ownership manifest, not vague text similarity;
- generated-document verify mode;
- check that generated HTML is not committed;
- check that new module READMEs link to the Developer Manual for cross-module topics;
- optional spelling/style checks that do not rewrite scientific terminology.

A documentation change is complete only when:

- links pass;
- referenced commands are executable;
- generated evidence is verified rather than silently rewritten;
- affected module and current-state documents agree with code/tests;
- screenshots, when part of a user-visible workflow, are deterministic or mask volatile regions and are regenerated through an explicit update mode.

## Pull-request sequence

Keep migrations reviewable and reversible:

1. authority map and index;
2. architecture/modules;
3. contributing/testing/operations;
4. CR3 index and maintained pages;
5. generated report relocation and generator updates;
6. stale root-document retirement;
7. validation automation and final link audit.

Do not combine production-code changes with these documentation moves unless a generator or executable documentation test must change to keep evidence reproducible.
