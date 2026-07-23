# Developer Documentation Migration Plan

## Status

- **Phase 1 complete** — Developer Manual entry point, authority rules, documentation map and migration plan.
- **Phase 2 complete** — canonical architecture, modules, contribution, testing and operations chapters, checkout-runnable verification and stale cross-module redirects.
- **Phase 3 complete** — canonical CR3 entry point, stable/experimental API chapters, RG1-aware current research process, migrated state/hypothesis/results paths and retirement of obsolete D5/Phase-A defaults.
- **Phase 4 open** — generated-report ownership and verify/update contracts, tracked by #279.
- **Phase 5 complete** — stale roadmap audit and `TODO.md` retirement.
- **Phase 6 open** — link and canonical-ownership validation, tracked by #281.

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

## Phase 3 — Maintained CR3 documentation — complete

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

## Phase 4 — Generated report boundary — open (#279)

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

Issue #279 owns the generated-document manifest, inventory and verifier.

## Phase 5 — Retire stale roadmap documents — complete

The previous root `TODO.md` mixed completed work, speculative product ideas and outdated CRX claims. The audit:

- replaced it with a stable pointer to GitHub Issues;
- mapped existing camera, hardware, CR3, release, benchmark and documentation work to current issues;
- created scoped issues for confirmed unowned work:
  - #282 module/vision SPI dependency direction;
  - #283 browser E2E coverage;
  - #284 API versioning;
  - #285 AI-provider resilience;
  - #286 robust astro stacking;
- removed obsolete claims that one K-update formula or LibRaw JNI was the next direct CRX implementation step;
- classified additional brands, multi-user/RBAC and a dedicated hardware runner as product/operational decisions rather than implicit commitments;
- retained cheap compatibility redirects (`BACKUP.md`, legacy Developer and CR3 paths) after the inbound-link audit.

Git history preserves the original checklist and rationale. No planned engineering work remains exclusively in `TODO.md`.

## Phase 6 — Validation and ownership — open (#281)

Add checkout-runnable repository checks for:

- internal Markdown links;
- generated-document ownership and verify mode;
- prohibition on committed generated HTML/build output;
- canonical-topic ownership;
- module links to canonical manuals;
- optional spelling/style rules that preserve scientific terminology.

A documentation change is complete only when links pass, commands execute, generated evidence is verified, affected documentation agrees with code/tests and screenshots used as evidence have deterministic update rules.

Issue #281 owns implementation and CI enforcement.

## Remaining pull-request sequence

1. #279 generated-report inventory and ownership manifest;
2. #279 verify/update tooling for committed CR3 evidence;
3. optional atomic report-path migration after ownership is complete;
4. #281 Markdown link and canonical-ownership automation;
5. final redirect audit after the mandatory validators are green.

Do not combine unrelated production-code changes with these documentation phases unless generator or verification code must change to keep documentation truthful and reproducible.