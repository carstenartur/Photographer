# Developer Documentation Migration Plan

## Status

- **Phase 1 complete** — Developer Manual entry point, authority rules, documentation map and migration plan.
- **Phase 2 complete** — canonical architecture, modules, contribution, testing and operations chapters, checkout-runnable verification and stale cross-module redirects.
- **Phase 3 implemented** — canonical CR3 entry point, stable/experimental API chapters, current research process, migrated current-state/hypothesis/results paths and retirement of obsolete D5/Phase-A default plans.
- **Phases 4–6 remain open** — generated-report ownership, stale roadmap retirement and final documentation validation.

The dated [`repository-inventory.md`](repository-inventory.md) was generated on 2026-06-17. It remains evidence of that revision, not an automatically current catalogue.

## Goals

- one stable developer entry point;
- one canonical place for each cross-module topic;
- module-local documentation retained where proximity to code is useful;
- stable implementation/API documentation separated from experimental and research state;
- generated evidence preserved without being presented as current truth;
- commands runnable from a normal checkout, with GitHub Actions used only for orchestration;
- no generated HTML committed to `main`.

## Completed target structure

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
  cleanroom/
    README.md
    ...                            # process, source registry, attestations
```

Module-local READMEs and architecture files remain in their modules. `docs/api.md`, `camera-setup.md`, `quickstart.md`, `reference-pipeline.md`, `cr3-api-boundaries.md` and maintained CCAPI chapters remain at stable existing paths because moving them would add link churn without removing duplication.

## Phase 1 — Authority map and entry point — complete

Delivered:

- `docs/developer/index.md`;
- classification of the dated Markdown inventory and post-inventory rules;
- authority order for code/tests, POMs, canonical docs, current research state and generated evidence;
- root README path into the Developer Manual;
- ordered migration plan.

## Phase 2 — Maintained developer documentation — complete

Delivered:

- code-first `docs/developer/architecture.md`;
- current `docs/developer/modules.md`;
- checkout and contribution guide;
- executable test strategy;
- operations/secrets/recovery guide;
- `scripts/verify-product.sh` as local and CI product gate;
- frontend Vitest in CI;
- corrected real-camera and Copilot/agent commands;
- redirects from stale architecture, development, testing, backup and generic-camera documents.

The generic camera audit records the actual partial implementation: `GenericCameraBackend` and adapters exist, but media and USB bridge capabilities are intentionally withheld where not implemented.

## Phase 3 — Maintained CR3 documentation — implemented

### Entry point and maturity

Delivered `docs/cr3/index.md` with distinct routes for:

- stable container/metadata/embedded-JPEG functionality;
- stable standard ImageIO integration plus experimental RAW controls;
- CLI inspection/extraction versus experimental decode commands;
- experimental codec and internal processing;
- clean-room research and dated evidence.

### Supported-library bridge pages

Delivered:

- `docs/cr3/imageio.md`;
- `docs/cr3/decoder.md`;
- `docs/cr3/cli.md`.

These pages explain relationships and maturity while module READMEs retain local API examples and build commands.

### Current state and research

Delivered:

- `docs/cr3/current-state.md`;
- `docs/cr3/open-hypotheses.md`;
- `docs/cr3/reports/index.md`;
- `docs/cr3/research.md`.

Old paths remain redirects:

- `docs/cr3-current-state.md`;
- `docs/cr3-open-hypotheses.md`;
- `docs/cr3-results-index.md`.

The stale `docs/cr3-agent-playbook.md` and `docs/cleanroom-fixture-plan.md` now redirect to the current research process. They explicitly retire D5 and Phase-A capture lists as default work after D13 established that no D14 entropy candidate is justified without genuinely new evidence.

### Phase 3 acceptance

- supported, experimental, internal, research-only and generated CR3 information are distinguishable;
- no generated report is presented as current implementation truth;
- stable module APIs and local documentation remain available;
- clean-room source registration and contributor evidence remain canonical;
- dated results are preserved at their existing paths.

## Phase 4 — Generated report boundary — open

Generated CR3 reports remain under `docs/cr3-results/` until each owning generator, every inbound link and verification behavior can move atomically.

Each committed generated report must identify or be associated with:

- generator class/script/test;
- exact local regeneration command;
- source fixture/manifest contract;
- source revision and timestamp where scientifically relevant;
- quantitative decision metrics;
- warning that rendered screenshots are not sole scientific evidence.

Generation needs two explicit modes:

- **verify** — fail when committed evidence is missing or stale; never overwrite;
- **update** — intentionally regenerate reviewed evidence.

CI uses verify mode and never commits scientific output. Maven Site, Javadoc, coverage, test HTML and frontend build output remain artifacts/Pages content, not source documentation.

### Phase 4 implementation sequence

1. inventory every committed CR3 report and its generator;
2. add missing generator/reproduction metadata;
3. define a small generated-document ownership manifest;
4. implement local verify/update commands;
5. move reports only when generators and links move in the same PR;
6. preserve historical outputs and scientific provenance.

## Phase 5 — Retire stale roadmap documents — open

### `TODO.md`

Audit every unchecked item:

1. link it to an open issue with current scope, or create/update one broader issue when still valid;
2. mark claims superseded by current code/research as obsolete;
3. remove completed checklists that add no enduring rationale.

Then replace `TODO.md` with a short pointer to GitHub issues or remove it after inbound links are updated. Planned work must not exist only in Markdown.

### Redirect retention

After an inbound-link audit, decide whether to keep or remove redirects at:

- `BACKUP.md`;
- `docs/architecture.md`;
- `docs/development.md`;
- `docs/test-strategy.md`;
- old CR3 current-state/hypothesis/results paths;
- retired research-plan paths.

Keep cheap redirects longer where external links are likely.

## Phase 6 — Validation and ownership — open

Add repository-owned checks runnable locally and orchestrated by CI:

- internal Markdown link validation;
- generated-document ownership and verify mode;
- prohibition on committed generated HTML/build output;
- canonical-topic ownership manifest;
- module-doc links to canonical cross-module manuals;
- optional spelling/style checks that preserve scientific terminology.

A documentation change is complete only when:

- links pass;
- referenced commands are executable;
- generated evidence is verified rather than silently rewritten;
- affected module/current-state documents agree with code and tests;
- screenshots used as product evidence are deterministic or mask volatile regions and have an explicit update mode.

## Remaining pull-request sequence

1. generated-report inventory and ownership manifest;
2. verify/update tooling for committed CR3 evidence;
3. optional atomic report-path migration;
4. `TODO.md` retirement;
5. Markdown link/canonical ownership automation;
6. final redirect and documentation audit.

Do not combine unrelated production-code changes with these documentation phases unless a generator, executable documentation test or repository-owned verification command must change to keep documentation truthful and reproducible.
