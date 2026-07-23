# Documentation Map

This map classifies the complete Markdown inventory recorded in [`repository-inventory.md`](repository-inventory.md) on 2026-06-17. It is the planning baseline for issue #91; it is not a claim that the inventory is still complete after that date.

## Classification

- **canonical** — authoritative maintained documentation for a cross-module topic.
- **merge** — useful content that should be consolidated into a named canonical destination before the source is retired or reduced to a redirect.
- **obsolete** — no longer a valid source of truth; retain only until useful content is cross-referenced.
- **generated** — reproducible evidence or inventory; preserve it, but change the generator rather than manually editing the output.
- **local** — module-, fixture- or experiment-local documentation that should remain near its code or data and be linked from canonical documentation.

No file is removed merely because this map classifies it as `merge` or `obsolete`. Removal requires a separate content audit and link-preserving migration.

## Repository and contributor documents

| File | Class | Destination or action |
|---|---|---|
| `.github/copilot-instructions.md` | canonical | Keep as repository-specific contributor/agent policy; link to this manual rather than duplicating architecture prose. |
| `BACKUP.md` | merge | Audit current repository visibility, tracked fixtures and recovery commands; merge durable operations guidance into `docs/developer/operations.md`. |
| `README.md` | canonical | Keep as concise product/user entry point with one clear link to `docs/developer/index.md`. |
| `TODO.md` | obsolete | GitHub issues now own roadmap state. Cross-reference any still-valid item to an issue, then replace with a short pointer or remove. |

## Module-local documentation

These files remain close to their modules. Their role is module ownership, public contracts, dependencies, independent build/test commands and module-local limitations. Cross-module explanations must be linked, not copied.

| File | Class | Destination or action |
|---|---|---|
| `ai-vision/README.md` | local | Keep. |
| `ai-vision/ARCHITECTURE.md` | local | Keep; remove only content duplicated verbatim from canonical architecture. |
| `app/README.md` | local | Keep. |
| `app/ARCHITECTURE.md` | local | Keep. |
| `astro-core/README.md` | local | Keep. |
| `astro-core/ARCHITECTURE.md` | local | Keep. |
| `camera-ptp/README.md` | local | Keep. |
| `camera-ptp/ARCHITECTURE.md` | local | Keep. |
| `canon-cr3-benchmark/README.md` | local | Keep as benchmark-module usage and interpretation boundary. |
| `canon-cr3-cli/README.md` | local | Keep as CLI user reference; link from future `docs/cr3/cli.md`. |
| `canon-cr3-cli/ARCHITECTURE.md` | local | Keep. |
| `canon-cr3-core/README.md` | local | Keep as supported library surface and maturity boundary. |
| `canon-cr3-core/ARCHITECTURE.md` | local | Keep. |
| `canon-cr3-core/SubbandFixtures.md` | local | Preserve as a research-fixture ledger; do not present it as current decoder documentation. |
| `canon-cr3-imageio/README.md` | local | Keep as ImageIO API and usage reference. |
| `canon-cr3-imageio/ARCHITECTURE.md` | local | Keep. |
| `canon-cr3-imageio/CR3_FORMAT.md` | local | Keep as a module-adjacent clean-room format reference; link from future `docs/cr3/decoder.md`. |
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
| `plugin-api/README.md` | local | Keep as public plugin SPI entry point. |
| `plugin-api/ARCHITECTURE.md` | local | Keep. |
| `sample-data/astro/README.md` | local | Keep with the sample corpus. |
| `session-core/README.md` | local | Keep. |
| `session-core/ARCHITECTURE.md` | local | Keep. |
| `storage/README.md` | local | Keep. |
| `storage/ARCHITECTURE.md` | local | Keep. |
| `video-core/README.md` | local | Keep. |
| `video-core/ARCHITECTURE.md` | local | Keep. |

## Maintained cross-module documentation

| File | Class | Destination or action |
|---|---|---|
| `docs/api.md` | canonical | Keep until moved or redirected to `docs/developer/api.md`; do not duplicate generated OpenAPI output. |
| `docs/architecture.md` | canonical | Consolidate cross-module architecture here, then move/redirect to `docs/developer/architecture.md`. |
| `docs/camera-setup.md` | canonical | Keep as the user/developer hardware and connection setup guide. Hardware claims must name evidence. |
| `docs/development.md` | merge | Merge maintained checkout, build, configuration and local-run guidance into `docs/developer/contributing.md`. |
| `docs/generic-camera-architecture.md` | merge | Merge cross-backend concepts into canonical developer architecture; retain transport-specific details by link. |
| `docs/quickstart.md` | canonical | Keep as a concise user/operator quickstart; do not turn it into the Developer Manual. |
| `docs/reference-pipeline.md` | canonical | Keep as the maintained reference-export pipeline contract; link from CR3 research documentation. |
| `docs/test-strategy.md` | merge | Merge into `docs/developer/testing.md`, preserving local/Testcontainers commands and hardware-test boundaries. |

## CR3 maintained state, process and evidence

| File | Class | Destination or action |
|---|---|---|
| `docs/cleanroom-fixture-plan.md` | merge | Merge still-current capture rules into `docs/cr3/research.md`; preserve dated decisions through links. |
| `docs/cr3-agent-playbook.md` | merge | Merge maintained research workflow and agent constraints into `docs/cr3/research.md`. |
| `docs/cr3-current-state.md` | canonical | Move/redirect to `docs/cr3/current-state.md`; this is the maintained implementation/research boundary. |
| `docs/cr3-fixture-audit.md` | generated | Preserve as a corpus evidence snapshot; link from current state and record its reproducible extraction command. |
| `docs/cr3-open-hypotheses.md` | canonical | Keep as explicitly current, dated hypothesis backlog; move/redirect under `docs/cr3/`. |
| `docs/cr3-reference-inventory.md` | generated | Preserve as a reference-corpus snapshot; generator/source manifest is authoritative. |
| `docs/cr3-reference-manifest.md` | canonical | Keep as the maintained manifest schema/provenance explanation. |
| `docs/cr3-results-index.md` | canonical | Keep as the curated bridge from current state to generated and dated evidence. |
| `docs/crx-cleanroom-spec.md` | canonical | Keep as the implementation-aligned internal clean-room specification, with experimental status explicit. |
| `docs/patent-us20160323602a1-analysis.md` | local | Preserve as registered-source research analysis; it is evidence, not product/API documentation. |

## Generated and dated CR3 reports

| File | Class | Destination or action |
|---|---|---|
| `docs/cr3-results/2026-06-14-B1-predictor-state-isolation.md` | generated | Preserve under the generated/dataset evidence area; never rewrite as current truth. |
| `docs/cr3-results/2026-06-14-predictor-evidence-matrix.md` | generated | Preserve. |
| `docs/cr3-results/2026-06-16-pedestal-hypothesis-validation.md` | generated | Preserve; update through its report generator. |
| `docs/cr3-results/reference-comparison.md` | generated | Preserve; update through the reference-comparison integration test. |

## Generated inventory

| File | Class | Destination or action |
|---|---|---|
| `docs/developer/repository-inventory.md` | generated | Preserve as the dated 2026-06-17 architecture baseline; do not manually refresh individual counts. Replace with a regenerated inventory when the generator exists. |

## Post-inventory additions

The 2026-06-17 inventory predates later CCAPI, clean-room and CR3-testkit documentation. Until the inventory is regenerated, use these rules:

- `docs/cleanroom/**` maintained process/attestation files are **canonical**; generated evidence below that tree must be marked **generated**.
- maintained `docs/ccapi-*.md` transport, security and ownership contracts are **canonical** when backed by code/tests; model/firmware snapshots are **generated evidence**.
- `docs/cr3-results/**` is **generated** unless the file is the curated index.
- `canon-cr3-reference-testkit/README.md` and `canon-cr3-research/README.md` are **local** module documentation.
- new module `README.md` and `ARCHITECTURE.md` files are **local** by default.
- generated Maven/Site HTML is not committed; it remains a build artifact or GitHub Pages publication.

## Duplicate-resolution rule

A `Duplicate? yes` marker in the inventory is not an instruction to delete a file. For every duplicate pair:

1. identify the cross-module claim;
2. select one canonical destination;
3. reduce module-local copies to module-specific facts plus a link;
4. preserve code examples only where they are executable for that module;
5. verify links before retiring the old text.

The ordered work is defined in [`migration-plan.md`](migration-plan.md).
