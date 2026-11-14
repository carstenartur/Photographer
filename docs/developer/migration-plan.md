# Developer Documentation Migration Plan

## Status

- **Phase 1 complete** — Developer Manual entry point, authority rules, documentation map and migration plan.
- **Phase 2 complete** — canonical architecture, modules, contribution, testing and operations chapters, checkout-runnable verification and stale cross-module redirects.
- **Phase 3 complete** — canonical CR3 entry point, stable/experimental API chapters, RG1-aware current research process, migrated state/hypothesis/results paths and retirement of obsolete D5/Phase-A defaults.
- **Phase 4 complete** — generated-evidence ownership, complete inventory, read-only verification and explicit compare/update contracts delivered by #291.
- **Phase 5 complete** — stale roadmap audit and `TODO.md` retirement.
- **Phase 6 complete** — mandatory link, redirect, canonical-ownership, module-manual and generated-output validation.

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
    documentation-ownership.json    # machine-readable ownership contract
    migration-plan.md
    repository-inventory.md         # dated/generated baseline
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
    ...                             # process, source registry, attestations
```

Module-local READMEs and architecture files remain in their modules. Maintained API, camera, quickstart, reference-pipeline, CR3-boundary and CCAPI chapters remain at stable existing paths because moving them would add link churn without removing duplication.

## Phase 1 — Authority map and entry point — complete

Delivered the Developer Manual entry, source-authority order, documentation map and ordered migration plan.

## Phase 2 — Maintained developer documentation — complete

Delivered code-first architecture, current module map, contribution/testing/operations guides, `scripts/verify-product.sh`, frontend Vitest in CI, corrected hardware/agent commands and redirects from stale cross-module documents.

## Phase 3 — Maintained CR3 documentation — complete

Delivered:

- `docs/cr3/index.md` — module map and maturity boundary;
- `docs/cr3/imageio.md` — stable ImageIO behavior and RAW raster parameters;
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

## Phase 4 — Generated report boundary — complete (#279)

Generated CR3 evidence remains under `docs/cr3-results/`; relocation is optional and must still move each generator, all inbound links and its verification contract atomically.

Delivered by #291:

- `docs/cleanroom/generated-evidence.json` registers every committed result family and generated image/table under exactly one owner;
- each entry records its owning module or documentation area, generator where available, fixed inputs, narrow output paths, timestamp policy, clean-room ledger binding and historical rationale where isolated generation does not yet exist;
- `scripts/manage_cr3_generated_evidence.py verify` validates schema, ownership, paths, generators, inputs, ledger references, unique complete output coverage and managed Git-blob locks without modifying the checkout;
- `compare` byte-compares isolated generated output with committed evidence;
- `update` is the only operation allowed to copy isolated output into committed paths and refresh managed locks;
- D12 metrics are the first managed-generated evidence and are verified from the generator's isolated `target` output;
- CI tests and runs the verifier before building the provenance bundle and never rewrites scientific evidence;
- historical reports remain byte-preserved and explicitly classified rather than silently normalized.

Maven Site, Javadoc, coverage, test HTML and frontend output remain uncommitted build artifacts or Pages content. Rendered screenshots are supporting evidence only; registered machine-readable metrics and source bindings remain authoritative.

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

## Phase 6 — Validation and ownership — complete

Delivered a checkout-runnable repository gate:

```bash
python3 scripts/test_validate_documentation.py
python3 scripts/validate_documentation.py .
```

The same commands run through `scripts/verify-product.sh` before Maven, so normal CI and local verification use one implementation.

The validator now enforces:

- every tracked repository-relative Markdown file and heading-anchor link;
- GitHub-style heading anchors, including inline-code text and duplicate headings;
- unique canonical topic ownership through `documentation-ownership.json`;
- exclusion of generated evidence from current canonical-topic ownership;
- retained redirect links to every declared canonical destination;
- selected module README links to Developer or CR3 Manual chapters;
- rejection of tracked Maven/frontend/generated site, test-report and coverage output.

External URL schemes are not fetched. Fenced code, HTML comments and link-like inline-code examples are excluded from link discovery. Failures identify the source file, line and unresolved target or anchor.

The first repository audit found and corrected stale links to nonexistent CCAPI shutdown/security documents. It also corrected the Developer architecture chapter after the completed vision-contract dependency refactor. Copilot review findings around inline-code anchors, inline-code link masking and Markdown list formatting were covered by focused regression tests and resolved.

## Remaining optional migration work

Generated evidence may move from legacy result paths only when the owning generator, manifest entry, all inbound links and verification/update mapping move in one reviewed change. This is maintenance work, not an incomplete documentation phase.

Do not combine unrelated production-code changes with evidence-path migration unless generator or verification code must change to keep documentation truthful and reproducible.
