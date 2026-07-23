# Documentation Map

This map classifies the complete Markdown inventory recorded in [`repository-inventory.md`](repository-inventory.md) on 2026-06-17 and the maintained documentation added since that inventory. It is an ownership map, not a claim that the dated inventory is still complete.

## Classification

- **canonical** — authoritative maintained documentation for a cross-module topic after audit against current code, tests and POMs.
- **merge** — useful content has been or should be consolidated into a named canonical destination; preserve the old path as a redirect while links migrate.
- **obsolete** — no longer a valid source of truth; retain only until useful, code-backed content is cross-referenced.
- **generated** — reproducible evidence or inventory; preserve it, but change the generator rather than manually editing the output.
- **local** — module-, fixture- or experiment-local documentation that should remain near its code or data and link to canonical cross-module documentation.

No file is removed merely because this map classifies it as `merge` or `obsolete`. Removal requires a content audit and link-preserving migration.

## Canonical Developer Manual

| File | Class | Ownership |
|---|---|---|
| `docs/developer/index.md` | canonical | Single developer entry point and documentation authority. |
| `docs/developer/architecture.md` | canonical | Current cross-module architecture derived from POMs, code and tests. |
| `docs/developer/modules.md` | canonical | Reactor modules, support classification and public surfaces. |
| `docs/developer/contributing.md` | canonical | Checkout, local development, build and contribution workflow. |
| `docs/developer/testing.md` | canonical | Test layers, local commands, CI orchestration and evidence boundaries. |
| `docs/developer/operations.md` | canonical | Secrets, private references, runtime data, backup and recovery. |
| `docs/developer/documentation-map.md` | canonical | Documentation ownership and migration state. |
| `docs/developer/migration-plan.md` | canonical | Ordered remaining migration work. |
| `docs/developer/repository-inventory.md` | generated | Dated 2026-06-17 architecture/inventory baseline; regenerate as a whole rather than editing counts. |

## Repository and contributor documents

| File | Class | Destination or action |
|---|---|---|
| `.github/copilot-instructions.md` | canonical | Keep as repository-specific contributor/agent policy; link to the Developer Manual rather than duplicating architecture prose. |
| `BACKUP.md` | merge | Durable content moved to `docs/developer/operations.md`; retain a short redirect for one migration interval. |
| `README.md` | canonical | Concise product/user entry point with one clear Developer Manual path. |
| `TODO.md` | obsolete | GitHub issues own roadmap state. Link any still-valid unique item to an issue, then replace with a short pointer or remove. |

## Module-local documentation

These files remain close to their modules. Their role is module ownership, public contracts, dependencies, independent build/test commands and module-local limitations. Cross-module explanations must link to the Developer Manual rather than be copied.

| File | Class | Destination or action |
|---|---|---|
| `ai-vision/README.md` | local | Keep. |
| `ai-vision/ARCHITECTURE.md` | local | Keep; remove only cross-module duplication. |
| `app/README.md` | local | Keep. |
| `app/ARCHITECTURE.md` | local | Keep. |
| `astro-core/README.md` | local | Keep. |
| `astro-core/ARCHITECTURE.md` | local | Keep. |
| `camera-ptp/README.md` | local | Keep. |
| `camera-ptp/ARCHITECTURE.md` | local | Keep. |
| `canon-cr3-benchmark/README.md` | local | Keep as benchmark-module usage and interpretation boundary. |
| `canon-cr3-cli/README.md` | local | Keep as CLI usage reference; link from future `docs/cr3/cli.md`. |
| `canon-cr3-cli/ARCHITECTURE.md` | local | Keep. |
| `canon-cr3-core/README.md` | local | Keep as supported facade/API and maturity boundary. |
| `canon-cr3-core/ARCHITECTURE.md` | local | Keep. |
| `canon-cr3-core/SubbandFixtures.md` | local | Preserve as research-fixture ledger; do not present as current decoder documentation. |
| `canon-cr3-imageio/README.md` | local | Keep as ImageIO API and usage reference. |
| `canon-cr3-imageio/ARCHITECTURE.md` | local | Keep. |
| `canon-cr3-imageio/CR3_FORMAT.md` | local | Keep as module-adjacent clean-room format reference. |
| `canon-cr3-imageio/src/test/resources/samples/research/README.md` | local | Preserve with fixture corpus and historical experiment provenance. |
| `capture-core/README.md` | local | Keep. |
| `capture-core/ARCHITECTURE.md` | local | Keep. |
| `common-api/README.md` | local | Keep. |
| `common-api/ARCHITECTURE.md` | local | Keep. |
| `face-analysis-core/README.md` | local | Keep. |
| `face-analysis-core/ARCHITECTURE.md` | local | Keep. |
| `face-profile-core/README.md` | local | Keep. |
| `face-profile-core/ARCHITECTURE.md` | local | Keep. |
| `frontend-web/README.md` | local | Keep. |
| `frontend-web/ARCHITECTURE.md` | local | Keep. |
| `hdr-core/README.md` | local | Keep. |
| `hdr-core/ARCHITECTURE.md` | local | Keep. |
| `observer-core/README.md` | local | Keep. |
| `observer-core/ARCHITECTURE.md` | local | Keep. |
| `plugin-api/README.md` | local | Keep as plugin SPI entry point. |
| `plugin-api/ARCHITECTURE.md` | local | Keep. |
| `sample-data/astro/README.md` | local | Keep with the sample corpus. |
| `session-core/README.md` | local | Keep. |
| `session-core/ARCHITECTURE.md` | local | Keep. |
| `storage/README.md` | local | Keep. |
| `storage/ARCHITECTURE.md` | local | Keep. |
| `video-core/README.md` | local | Keep. |
| `video-core/ARCHITECTURE.md` | local | Keep. |

Post-inventory module documentation follows the same rule:

- `canon-cr3-container`, `canon-cr3-metadata`, `canon-cr3-codec` and `canon-cr3-processing` module docs are **local**;
- `canon-cr3-reference-testkit/README.md` and `canon-cr3-research/README.md` are **local** but explicitly non-runtime;
- new module `README.md` and `ARCHITECTURE.md` files are **local** by default.

## Maintained cross-module documentation

| File | Class | Destination or action |
|---|---|---|
| `docs/api.md` | canonical | Keep as maintained REST overview without duplicating generated OpenAPI output. |
| `docs/architecture.md` | merge | Consolidated into `docs/developer/architecture.md`; retain a redirect while inbound links migrate. |
| `docs/camera-setup.md` | canonical | Keep as hardware/connection setup guide. Hardware claims must name evidence. |
| `docs/development.md` | merge | Consolidated into `docs/developer/contributing.md`; retain a redirect. |
| `docs/generic-camera-architecture.md` | merge | Its named generic contracts and adapters exist, but the implementation is partial. Current state is consolidated into `docs/developer/architecture.md`; retain a concise status redirect. |
| `docs/quickstart.md` | canonical | Keep as concise user/operator quickstart; do not turn it into the Developer Manual. |
| `docs/reference-pipeline.md` | canonical | Keep as maintained reference-export pipeline contract; link from CR3 research documentation. |
| `docs/test-strategy.md` | merge | Consolidated and corrected in `docs/developer/testing.md`; retain a redirect. |

## CCAPI documentation

Maintained `docs/ccapi-*.md` transport, security, authentication, session, shutdown, event, media and live-view contracts are **canonical** when backed by code/tests. Redacted model/firmware snapshots and generated capability reports are **generated evidence**.

Private Canon manuals and extracted text remain outside Git and are not documentation artifacts that may be redistributed.

## CR3 maintained state, process and evidence

| File | Class | Destination or action |
|---|---|---|
| `docs/cleanroom-fixture-plan.md` | merge | Merge still-current capture rules into `docs/cr3/research.md`; preserve dated decisions through links. |
| `docs/cr3-agent-playbook.md` | merge | Merge maintained research workflow and agent constraints into `docs/cr3/research.md`. |
| `docs/cr3-current-state.md` | canonical | Move/redirect to `docs/cr3/current-state.md`; maintained implementation/research boundary. |
| `docs/cr3-fixture-audit.md` | generated | Preserve as corpus evidence snapshot with reproducible extraction command. |
| `docs/cr3-open-hypotheses.md` | canonical | Keep as explicitly dated current hypothesis backlog; move/redirect under `docs/cr3/`. |
| `docs/cr3-reference-inventory.md` | generated | Preserve as reference-corpus snapshot; generator/source manifest is authoritative. |
| `docs/cr3-reference-manifest.md` | canonical | Keep as manifest schema/provenance explanation. |
| `docs/cr3-results-index.md` | canonical | Curated bridge from current state to generated and dated evidence. |
| `docs/crx-cleanroom-spec.md` | canonical | Implementation-aligned internal clean-room specification with experimental status explicit. |
| `docs/patent-us20160323602a1-analysis.md` | local | Preserve as registered-source research analysis, not product/API documentation. |
| `docs/cleanroom/**` maintained process/attestation files | canonical | Clean-room policy, source registry and contributor evidence. |

## Generated and dated CR3 reports

| File | Class | Destination or action |
|---|---|---|
| `docs/cr3-results/2026-06-14-B1-predictor-state-isolation.md` | generated | Preserve; never rewrite as current truth. |
| `docs/cr3-results/2026-06-14-predictor-evidence-matrix.md` | generated | Preserve. |
| `docs/cr3-results/2026-06-16-pedestal-hypothesis-validation.md` | generated | Preserve; update through its report generator. |
| `docs/cr3-results/reference-comparison.md` | generated | Preserve; update through the reference-comparison generator/test. |
| other `docs/cr3-results/**` files | generated | Generated unless explicitly identified as the curated index. |

## Generated build documentation

Maven Site, Javadoc, coverage, test HTML and frontend build output are build artifacts or GitHub Pages publications. They are not committed maintained documentation.

## Duplicate-resolution rule

A `Duplicate? yes` marker in the dated inventory is not an instruction to delete a file. For every duplicate pair:

1. identify the cross-module claim;
2. verify it against current code, tests and POMs;
3. select one canonical destination;
4. reduce module-local copies to module-specific facts plus a link;
5. preserve executable examples only where they are valid;
6. retain a short redirect for moved public paths;
7. verify links before retiring old text.

The remaining work is defined in [`migration-plan.md`](migration-plan.md).
