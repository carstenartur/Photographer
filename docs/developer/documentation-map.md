# Documentation Map

This map classifies the Markdown inventory recorded in [`repository-inventory.md`](repository-inventory.md) on 2026-06-17 and the maintained documentation added since that inventory. It defines ownership; it does not claim that the dated inventory remains complete.

## Classification

- **canonical** — authoritative maintained documentation for a cross-module topic after audit against current code, tests and POMs.
- **merge** — useful content has been consolidated into a canonical destination; keep the old path as a redirect while links migrate.
- **obsolete** — no longer a valid source of truth; retain only until useful, code-backed content is cross-referenced.
- **generated** — reproducible evidence or inventory; change the generator rather than manually editing the output.
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
| `docs/developer/documentation-map.md` | canonical | Documentation ownership and migration state. |
| `docs/developer/migration-plan.md` | canonical | Remaining documentation work. |
| `docs/developer/repository-inventory.md` | generated | Dated 2026-06-17 baseline; regenerate as a whole instead of editing counts. |

## Canonical CR3 / CRX Manual

| File | Class | Ownership |
|---|---|---|
| `docs/cr3/index.md` | canonical | CR3 entry point, module map and maturity boundary. |
| `docs/cr3/imageio.md` | canonical | Stable ImageIO integration and experimental RAW controls. |
| `docs/cr3/decoder.md` | canonical | Stable container/metadata versus experimental codec/internal processing. |
| `docs/cr3/cli.md` | canonical | User-facing CLI commands and experimental decode boundary. |
| `docs/cr3/research.md` | canonical | Current clean-room research process and D14 resumption gate. |
| `docs/cr3/current-state.md` | canonical | Maintained implementation/research truth boundary. |
| `docs/cr3/open-hypotheses.md` | canonical | Durable supported/rejected/blocked hypothesis state. |
| `docs/cr3/reports/index.md` | canonical | Curated bridge from current state to dated/generated evidence. |

The API compatibility source of truth remains `docs/cr3-api-boundaries.md`. Clean-room policy and contributor evidence remain under `docs/cleanroom/`.

## Repository and contributor documents

| File | Class | Destination or action |
|---|---|---|
| `.github/copilot-instructions.md` | canonical | Repository-specific agent policy; link to the manuals instead of duplicating changing architecture. |
| `BACKUP.md` | merge | Durable content moved to `docs/developer/operations.md`; retain redirect. |
| `README.md` | canonical | Concise product/user entry point with a Developer Manual path. |
| `TODO.md` | obsolete | GitHub issues own roadmap state; unique valid items must be linked to issues before retirement. |

## Module-local documentation

Module `README.md` and `ARCHITECTURE.md` files are **local** by default. They own:

- module purpose and boundaries;
- public contracts;
- independent build/test commands;
- module-specific limitations.

They must link to the Developer or CR3 Manual for cross-module architecture, release maturity, testing and research policy.

Explicit local evidence includes:

- `canon-cr3-core/SubbandFixtures.md` — fixture/subband ledger;
- `canon-cr3-imageio/CR3_FORMAT.md` — module-adjacent format reference;
- fixture-local research READMEs;
- sample-data READMEs;
- benchmark-module usage documentation;
- `canon-cr3-reference-testkit/README.md` and `canon-cr3-research/README.md`, explicitly non-runtime.

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
| `docs/cleanroom/**` process/attestation files | canonical | Clean-room policy, source registry and contributor records. |
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

The last two redirects explicitly retire obsolete D5/Phase-A default tasks while preserving historical details in Git history.

## CR3 evidence and research-local documents

| File/group | Class | Ownership |
|---|---|---|
| `docs/cr3-fixture-audit.md` | generated | Corpus evidence snapshot; preserve generator/reproduction boundary. |
| `docs/cr3-reference-inventory.md` | generated | Reference-corpus snapshot. |
| `docs/cr3-reference-manifest.md` | canonical/generated boundary | Maintained schema plus generated/hash-bound entries. |
| `docs/patent-us20160323602a1-analysis.md` | local | Registered-source analysis, not product/API documentation. |
| `docs/cr3-results/**` | generated | Dated/generated evidence except the curated index at `docs/cr3/reports/index.md`. |

Generated reports are not relocated until their generators, links and verify/update modes move in the same change.

## Generated build documentation

Maven Site, Javadoc, coverage, test HTML and frontend build output are build artifacts or GitHub Pages publications. They are not committed maintained documentation.

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
