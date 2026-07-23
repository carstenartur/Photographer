# Developer Documentation Migration Plan

## Status

- **Phase 1 complete** — Developer Manual entry point, authority rules, documentation map and migration plan.
- **Phase 2 complete** — canonical architecture, modules, contribution, testing and operations chapters, checkout-runnable verification and stale cross-module redirects.
- **Phase 3 implemented** — canonical CR3 entry point, stable/experimental API chapters, RG1-aware current research process, migrated state/hypothesis/results paths and retirement of obsolete D5/Phase-A defaults.
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

Module-local READMEs and architecture files remain in their modules. Maintained API, camera, quickstart, reference-pipeline, CR3-boundary and CCAPI chapters remain at stable existing paths because moving them would add link churn without removing duplication.

## Phase 1 — Authority map and entry point — complete

Delivered the Developer Manual entry, source-authority order, documentation map and ordered migration plan.

## Phase 2 — Maintained developer documentation — complete

Delivered code-first architecture, current module map, contribution/testing/operations guides, `scripts/verify-product.sh`, frontend Vitest in CI, corrected hardware/agent commands and redirects from stale cross-module documents.

## Phase 3 — Maintained CR3 documentation — implemented

Delivered:

- `docs/cr3/index.md` — module map and maturity boundary;
- `docs/cr3/imageio.md` — stable ImageIO behavior and explicit `readRaster()` limitation;
- `docs/cr3/decoder.md` — stable container/metadata versus experimental RAW/internal processing;
- `docs/cr3/cli.md` — stable inspection/extraction versus experimental decode commands;
- `docs/cr3/current-state.md` — D5–D13 and RG1 durable interpretation;
- `docs/cr3/open-hypotheses.md` — rejected, partial, blocked and open evidence-acquisition state;
- `docs/cr3/reports/index.md` — curated evidence chronology including RG1;
- `docs/cr3/research.md` — clean-room process and controlled camera/JPEG/CR3 acquisition contract.

Old paths remain redirects:

- `docs/cr3-current-state.md`;
- `docs/cr3-open-hypotheses.md`;
- `docs/cr3-results-index.md`;
- `docs/cr3-agent-playbook.md`;
- `docs/cleanroom-fixture-plan.md`.

The final two explicitly retire D5 and unconditional Phase-A capture lists as default tasks. They point to the current RG1-aware process, where issue #249 is an open causal evidence program rather than an already authorized entropy rule.

### Phase 3 acceptance

- supported, experimental, internal, research-only and generated CR3 information are distinguishable;
- no generated report is presented as current implementation truth;
- stable module APIs and local documentation remain available;
- clean-room source registration and contributor evidence remain canonical;
- dated results stay at existing paths;
- RG1 and the controlled-capture next step agree with current code and evidence.

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

CI uses verify mode and never commits scientific output. Maven Site, Javadoc, coverage, test HTML and frontend output remain artifacts/Pages content.

### Phase 4 implementation sequence

1. inventory every committed CR3 report and its generator;
2. add missing generator/reproduction metadata;
3. define a generated-document ownership manifest;
4. implement local verify/update commands;
5. move reports only when generators and links move together;
6. preserve historical outputs and provenance.

## Phase 5 — Retire stale roadmap documents — open

Audit `TODO.md`: link each still-valid item to a current issue, mark superseded claims obsolete, and remove completed checklists without enduring rationale. Then replace it with a short pointer to GitHub issues or remove it after inbound links are updated.

After an inbound-link audit, decide whether redirects can be removed. Keep cheap redirects longer where external links are likely.

## Phase 6 — Validation and ownership — open

Add checkout-runnable repository checks for:

- internal Markdown links;
- generated-document ownership and verify mode;
- prohibition on committed generated HTML/build output;
- canonical-topic ownership;
- module links to canonical manuals;
- optional spelling/style rules that preserve scientific terminology.

A documentation change is complete only when links pass, commands execute, generated evidence is verified, affected documentation agrees with code/tests and screenshots used as evidence have deterministic update rules.

## Remaining pull-request sequence

1. generated-report inventory and ownership manifest;
2. verify/update tooling for committed CR3 evidence;
3. optional atomic report-path migration;
4. `TODO.md` retirement;
5. Markdown link/canonical ownership automation;
6. final redirect and documentation audit.

Do not combine unrelated production-code changes with these documentation phases unless generator or verification code must change to keep documentation truthful and reproducible.
