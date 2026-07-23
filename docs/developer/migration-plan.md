# Developer Documentation Migration Plan

## Status

- **Phase 1 is complete on `main`**: the Developer Manual entry point, authority rules, documentation map and migration plan exist.
- **Phase 2 is implemented in the Developer Manual core change**: canonical architecture, module, contribution, testing and operations chapters replace contradictory cross-module documents while preserving old paths as redirects.
- **Phases 3–6 remain open**: CR3 consolidation, generated-report boundaries, stale roadmap retirement and final documentation validation.

The prerequisite correctness audit in issue #90 is complete. The dated source inventory is [`repository-inventory.md`](repository-inventory.md), generated on 2026-06-17. It is evidence of that revision, not an automatically current catalogue.

## Goals

- one stable developer entry point;
- one canonical place for every cross-module topic;
- module-local documentation retained where proximity to code is useful;
- stable API/implementation documentation separated from research state;
- generated and dated evidence preserved without being presented as current truth;
- commands runnable from a normal checkout, with GitHub Actions used only for orchestration;
- no generated HTML committed to `main`.

## Non-goals

- no production or decoder behavior changes solely to fit documentation structure;
- no rewrite of all documentation from scratch;
- no loss of dated research evidence;
- no manual cosmetic rewrite of generated reports;
- no relocation merely to match a proposed directory tree when the existing location is clearer.

## Target structure

Module-local files remain in module directories. The cross-module target is:

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

`docs/api.md`, `docs/camera-setup.md`, `docs/quickstart.md` and transport-specific CCAPI chapters may remain at their current paths when moving them would add link churn without removing duplication.

## Phase 1 — Authority map and entry point — complete

Delivered:

- `docs/developer/index.md`;
- classification of the 2026-06-17 Markdown inventory and post-inventory rules;
- documentation authority and generated-document rules;
- root README path into the Developer Manual;
- this ordered migration plan.

## Phase 2 — Maintained developer documentation — implemented

### Architecture and modules

The canonical architecture is derived from:

1. current root and module POMs;
2. production package/module boundaries and executable tests;
3. module-local README/ARCHITECTURE files where they agree with code;
4. audited material from the previous cross-module documents.

`docs/generic-camera-architecture.md` originally mixed implemented contracts with future migration steps. The code audit confirmed that `GenericCameraBackend`, `CapabilitySet`, `CcapiCameraBackend` and `UsbCameraBackend` exist, but the adapters are only partially implemented. The canonical architecture therefore records both the real contracts and their actual capability-safe limitations.

Delivered:

- `docs/developer/architecture.md`;
- `docs/developer/modules.md`;
- redirect from `docs/architecture.md`;
- concise current-status redirect from `docs/generic-camera-architecture.md`.

### Contributing and local development

Delivered:

- `docs/developer/contributing.md`;
- repository-owned `scripts/verify-product.sh`;
- correction that no Maven Wrapper is currently committed;
- accurate local H2, Compose, frontend and focused-test commands;
- redirect from `docs/development.md`.

### Testing

Delivered:

- `docs/developer/testing.md`;
- explicit unit, Failsafe/Testcontainers, Docker, real-camera, frontend, HTTP-smoke, research and JMH boundaries;
- corrected real-camera command that overrides the default excluded groups;
- CI invocation of the repository-owned product-verify script;
- frontend Vitest execution in CI;
- redirect from `docs/test-strategy.md`.

### Operations and recovery

Delivered:

- `docs/developer/operations.md`;
- durable secret, private-reference, runtime-data, fixture, release-key, backup and restore rules;
- replacement of workstation-specific untracked-file claims;
- redirect from `BACKUP.md`.

Phase 2 is accepted when the change passes frontend tests/lint/build, the complete product Java/Testcontainers verification and Docker build smoke.

## Phase 3 — Consolidate maintained CR3 documentation

### CR3 entry point

Create `docs/cr3/index.md` with four clear routes:

- supported CR3 container/metadata/ImageIO/CLI functionality;
- experimental decoder status and limitations;
- clean-room research process;
- dated/generated evidence.

### Supported library documentation

Create short maintained bridge pages:

- `docs/cr3/imageio.md` links the supported ImageIO SPI, read parameter, metadata and tests;
- `docs/cr3/decoder.md` links the core API and labels experimental CRX paths explicitly;
- `docs/cr3/cli.md` links tested CLI commands.

These pages must not duplicate complete module READMEs. Module documentation remains the local usage reference; bridge pages explain relationships and maturity.

### Current state and hypotheses

Move with redirects or equivalent link preservation:

- `docs/cr3-current-state.md` → `docs/cr3/current-state.md`;
- `docs/cr3-open-hypotheses.md` → `docs/cr3/open-hypotheses.md`;
- `docs/cr3-results-index.md` → `docs/cr3/reports/index.md`.

The current-state page is the maintained truth boundary. It links to generated reports as evidence and records whether each result still applies to current implementation code.

### Research process

Merge still-current process content from:

- `docs/cr3-agent-playbook.md`;
- `docs/cleanroom-fixture-plan.md`;
- CR3-specific parts of `docs/reference-pipeline.md`.

Create `docs/cr3/research.md`, while keeping clean-room policy and contributor attestations canonical under `docs/cleanroom/`.

Historical fixture ledgers, patent analyses and experiment-local READMEs remain local and linked.

### Acceptance

- supported, experimental, planned and generated CR3 information are distinguishable from the index;
- no generated report is presented as current implementation truth;
- module API docs and research evidence remain available;
- clean-room source registration and attribution survive all moves.

## Phase 4 — Generated report boundary

Move or regenerate CR3 report outputs under `docs/cr3/reports/generated/` only after updating their generators and all links in the same change.

Each generated report must identify:

- generator class/script/test;
- exact local regeneration command;
- source fixture/manifest contract;
- generation timestamp or source revision where scientifically relevant;
- quantitative decision metrics;
- warning that rendered screenshots are not the sole scientific evidence.

Generation has two explicit modes:

- **verify** — fail when committed evidence is missing or stale; never overwrite;
- **update** — intentionally regenerate committed evidence.

CI uses verify mode. It must never rewrite and commit scientific evidence implicitly.

Maven Site, Javadoc, coverage, test HTML and frontend build output remain build artifacts or GitHub Pages publications.

## Phase 5 — Retire stale root roadmap documents

### `TODO.md`

Audit every unchecked item:

1. link it to an open issue with current scope, or create/update one broader issue when still valid;
2. mark claims superseded by current code/research as obsolete;
3. remove completed historical checklists that add no enduring rationale.

Then replace `TODO.md` with a short pointer to GitHub issues or remove it after inbound links are updated. Planned work must not exist only in Markdown.

### Redirect retirement

After one migration interval and an inbound-link audit, consider removing the redirects at:

- `BACKUP.md`;
- `docs/architecture.md`;
- `docs/development.md`;
- `docs/test-strategy.md`.

Keep them longer when external links are likely and maintenance cost is negligible.

## Phase 6 — Validation and ownership

Add repository-owned checks that are runnable locally and only orchestrated by CI:

- internal Markdown link validation;
- generated-document verify mode;
- check that generated HTML is not committed;
- a small maintained canonical-topic ownership manifest;
- check that module docs link to the Developer Manual for cross-module topics;
- optional spelling/style checks that preserve scientific terminology.

A documentation change is complete only when:

- links pass;
- referenced commands are executable;
- generated evidence is verified rather than silently rewritten;
- affected module/current-state documents agree with code and tests;
- screenshots used as product evidence are deterministic or mask volatile regions and have an explicit update mode.

## Remaining pull-request sequence

1. CR3 entry point and maintained bridge pages;
2. CR3 current-state/hypothesis/results path migration;
3. research-process consolidation;
4. generated report relocation and generator updates;
5. `TODO.md` retirement;
6. validation automation and final link audit.

Do not combine unrelated production-code changes with these documentation moves unless a generator, executable documentation test or repository-owned verification command must change to keep the documentation truthful and reproducible.
