# Documentation Map

This map classifies the Markdown inventory recorded in [`repository-inventory.md`](repository-inventory.md) on 2026-06-17 and the maintained documentation added since that inventory. It defines ownership; it does not claim that the dated inventory remains complete.

## Classification

- **canonical** — authoritative maintained documentation for a cross-module topic after audit against current code, tests and POMs.
- **merge** — useful content has been consolidated into a canonical destination; keep the old path as a redirect while links migrate.
- **obsolete** — no longer a valid source of truth; retain only until useful, code-backed content is cross-referenced.
- **generated** — reproducible or historical evidence/inventory governed by an explicit ownership lifecycle rather than edited as current prose.
- **local** — module-, fixture- or experiment-local documentation that remains near its code/data and links to canonical documentation.

Removal still requires a content audit and link-preserving migration.

## Canonical Developer Manual

| File | Class | Ownership |
|---|---|---|
| `docs/developer/index.md` | canonical | Single developer entry point and authority rules. |
| `docs/developer/architecture.md` | canonical | Current cross-module architecture derived from POMs, code and tests. |
| `docs/developer/modules.md` | canonical | Reactor modules, support classification and public surfaces. |
| `docs/developer/contributing.md` | canonical | Checkout, local development, build and contribution workflow. |
| `docs/developer/testing.md` | canonical | Test layers, local commands, CI orchestration and evidence boundaries. |
| `docs/developer/operations.md` | canonical | Secrets, private references, runtime data, backup and recovery. |
| `docs/developer/documentation-map.md` | canonical | Documentation ownership, classification and correction workflow. |
| `docs/developer/documentation-ownership.json` | canonical machine-readable contract | Unique topic owners, redirects, required module links and forbidden generated-output paths. |
| `docs/developer/migration-plan.md` | canonical | Remaining documentation work. |
| `docs/developer/repository-inventory.md` | generated | Dated 2026-06-17 baseline; regenerate as a whole instead of editing counts. |

## Canonical CR3 / CRX Manual

| File | Class | Ownership |
|---|---|---|
| `docs/cr3/index.md` | canonical | CR3 entry point, module map and maturity boundary. |
| `docs/cr3/imageio.md` | canonical | Stable ImageIO integration and experimental RAW controls. |
| `docs/cr3/decoder.md` | canonical | Stable container/metadata versus experimental codec/internal processing. |
| `docs/cr3/cli.md` | canonical | User-facing CLI commands and experimental decode boundary. |
| `docs/cr3/research.md` | canonical | Current clean-room process, RG1 boundary and controlled-capture contract. |
| `docs/cr3/current-state.md` | canonical | Maintained implementation/research truth boundary. |
| `docs/cr3/open-hypotheses.md` | canonical | Durable supported/rejected/blocked/open evidence state. |
| `docs/cr3/reports/index.md` | canonical | Curated bridge from current state to dated/generated evidence. |

The API compatibility source of truth remains `docs/cr3-api-boundaries.md`. Clean-room policy and contributor evidence remain under `docs/cleanroom/`.

## Repository and contributor documents

| File | Class | Destination or action |
|---|---|---|
| `.github/copilot-instructions.md` | canonical | Repository-specific agent policy; link to the manuals instead of duplicating changing architecture. |
| `BACKUP.md` | merge | Durable content moved to `docs/developer/operations.md`; retain redirect. |
| `README.md` | canonical | Concise product/user entry point with a Developer Manual path. |
| `TODO.md` | canonical | Stable issue-tracker entry point. GitHub issues own current work; Git history preserves the retired checklist. |

The TODO audit moved confirmed current work into scoped issues:

- #282 vision SPI/module dependency direction;
- #283 real-browser E2E coverage;
- #284 API versioning;
- #285 AI-provider resilience;
- #286 robust astro stacking.

Existing issues continue to own CCAPI/hardware evidence, CR3 research/release/benchmark work and documentation phases. Additional brands, multi-user/RBAC and a dedicated hardware runner remain product/operational decisions rather than implied commitments.

## Module-local documentation

Module `README.md` and `ARCHITECTURE.md` files are **local** by default. They own module purpose, public contracts, dependencies, independent verification and module-specific limitations. Cross-module architecture, release maturity, testing and research policy belong in the manuals.

Explicit local evidence includes `canon-cr3-core/SubbandFixtures.md`, `canon-cr3-imageio/CR3_FORMAT.md`, fixture/sample READMEs, benchmark documentation and the research/reference-testkit READMEs.

Local documentation does not become a supported external API merely because a Java class is public.

## Maintained cross-module documents outside the manuals

| File/group | Class | Ownership |
|---|---|---|
| `docs/api.md` | canonical | Maintained REST overview without duplicating generated OpenAPI output. |
| `docs/camera-setup.md` | canonical | Hardware/connection setup; support claims require evidence. |
| `docs/quickstart.md` | canonical | Concise user/operator quickstart. |
| `docs/reference-pipeline.md` | canonical | Independent CR3 reference-sidecar generation and validation. |
| `docs/cr3-api-boundaries.md` | canonical | Symbol/package compatibility classification. |
| `docs/crx-cleanroom-spec.md` | canonical | Implementation-aligned internal clean-room specification. |
| `docs/cleanroom/**` process/attestation files | canonical | Clean-room policy, source registry, experiment ledger, generated-evidence ownership and contributor records. |
| `docs/ccapi-*.md` maintained contracts | canonical | Transport, security, ownership, shutdown, media, event and live-view behavior backed by code/tests. |

Private Canon manuals and extracted text remain outside Git and are never redistributable project documentation.

## Consolidated redirects

| Old path | Class | Canonical destination |
|---|---|---|
| `docs/architecture.md` | merge | `docs/developer/architecture.md` |
| `docs/development.md` | merge | `docs/developer/contributing.md` and `testing.md` |
| `docs/test-strategy.md` | merge | `docs/developer/testing.md` |
| `docs/generic-camera-architecture.md` | merge | `docs/developer/architecture.md#camera-architecture` |
| `docs/cr3-current-state.md` | merge | `docs/cr3/current-state.md` |
| `docs/cr3-open-hypotheses.md` | merge | `docs/cr3/open-hypotheses.md` |
| `docs/cr3-results-index.md` | merge | `docs/cr3/reports/index.md` |
| `docs/cr3-agent-playbook.md` | merge | `docs/cr3/research.md` |
| `docs/cleanroom-fixture-plan.md` | merge | `docs/cr3/research.md` |

The last two redirects retire obsolete D5/Phase-A defaults while preserving history. They now point to the active controlled-capture contract and RG1-aware research boundary. The redirect audit retained these paths plus `BACKUP.md` because they are cheap, useful compatibility entry points and no replacement benefit justifies link breakage.

## CR3 evidence and research-local documents

| File/group | Class | Ownership |
|---|---|---|
| `docs/cr3-fixture-audit.md` | generated | Corpus evidence snapshot; preserve generator/reproduction boundary. |
| `docs/cr3-reference-inventory.md` | generated | Reference-corpus snapshot. |
| `docs/cr3-reference-manifest.md` | canonical/generated boundary | Maintained schema plus generated/hash-bound entries. |
| `docs/patent-us20160323602a1-analysis.md` | local | Registered-source analysis, not product/API documentation. |
| `docs/cr3-results/**` | generated | Complete per-file ownership is defined by `docs/cleanroom/generated-evidence.json`; the curated current interpretation remains in `docs/cr3/reports/index.md`. |
| `docs/cleanroom/generated-evidence.json` | canonical machine-readable evidence contract | Exact owner, generator/input metadata, classification, outputs, timestamp policy, mappings and locks. |
| `docs/cleanroom/generated-evidence.md` | canonical process documentation | Read-only verify/compare and explicit reviewed update procedure. |
| `docs/cr3-patterns/**` | local/generated boundary | Deterministic D14 stimulus assets, schema and capture contract. |

Historical reports are not relocated or normalized merely to make them look reproducible. A path moves only with its generator, links and verify/update behavior. Managed outputs are generated outside `docs/` and compared before an explicit reviewed update.

## Generated build documentation

Maven Site, Javadoc, coverage, test HTML and frontend build output are build artifacts or GitHub Pages publications. They are not committed maintained documentation.

## Automated validation and correction workflow

Run the complete documentation gate from a normal checkout:

```bash
python3 scripts/test_validate_documentation.py
python3 scripts/validate_documentation.py .
```

Validate committed CR3 evidence ownership separately:

```bash
python3 scripts/test_manage_cr3_generated_evidence.py
python3 scripts/manage_cr3_generated_evidence.py --root . verify
```

`scripts/verify-product.sh` runs the Markdown/ownership commands before Maven verification. The CR3 Clean-room Evidence workflow runs the generated-evidence validator and deterministic bundle builder. The focused CR3 research workflow performs a read-only D12 generator comparison.

The documentation validator checks every tracked Markdown file for repository-relative file and heading-anchor links. External URL schemes are not fetched. It also enforces `documentation-ownership.json`:

- each topic id has exactly one existing canonical owner;
- generated evidence cannot be registered as current canonical truth;
- retained redirect documents link to every declared canonical destination;
- selected module READMEs link to their required Developer or CR3 Manual chapters;
- build output under target, coverage, dist, site and equivalent generated directories is not committed.

The generated-evidence validator additionally checks:

- every tracked file below `docs/cr3-results/` has exactly one owner;
- generator and input paths exist;
- historical classifications state why current regeneration is not claimed;
- managed outputs have isolated mappings and current Git blob locks;
- no blanket result-root pattern hides missing ownership.

When validation fails, correct the source rather than adding a broad ignore:

1. fix or remove the stale relative link at the reported file and line;
2. update the target heading deliberately when an anchor changed;
3. change the JSON manifest only when ownership, evidence classification or redirect policy genuinely changed;
4. add a module requirement only together with the corresponding README link;
5. register each new research output under its actual generator or reviewed manual owner;
6. migrate historical evidence only with an isolated generator and a passing compare contract;
7. keep normal CI read-only and invoke generated-evidence `update` only as a deliberate reviewed action.

Explicit exclusions must be narrow, reviewable and represented by a focused test. Private or external documents are linked through URL schemes and are outside repository-file validation; their contents are never fetched.

## Duplicate-resolution rule

For every duplicate pair:

1. identify the cross-module claim;
2. verify it against current code, tests and POMs;
3. select one canonical destination;
4. reduce local copies to local facts plus a link;
5. preserve executable examples only where valid;
6. retain redirects for moved public paths;
7. verify links before retiring old text.

Remaining work is defined in [`migration-plan.md`](migration-plan.md).
