# Developer Manual

This is the single maintained entry point for contributors and coding agents working on Photographer.

Photographer is a multi-module Java 25 / Spring Boot 4 application with a React frontend, camera backends, photography workflows and a separate CR3/CRX clean-room research track. Module-local documentation stays close to code, while this manual owns cross-module architecture, development, testing and operations.

## Start here

| Need | Canonical source |
|---|---|
| Understand the system and dependency direction | [Architecture](architecture.md) |
| Find a module or supported public surface | [Modules and public surfaces](modules.md) |
| Build, run or contribute from a checkout | [Contributing and local development](contributing.md) |
| Run unit, integration, Docker, hardware or research tests | [Testing](testing.md) |
| Find CI checks and their triggers | [Continuous Integration](ci.md) |
| Handle secrets, backups, generated evidence or recovery | [Operations, secrets and recovery](operations.md) |
| Use the REST API | [REST API reference](../api.md) |
| Configure a camera | [Camera setup](../camera-setup.md) |
| Work on Canon CCAPI session ownership | [CCAPI session ownership](../ccapi-session-ownership.md) |
| Use or develop CR3/CRX functionality | [CR3 / CRX Manual](../cr3/index.md) · [CR3 and CRX format reference](../cr3/format.md) |
| Inspect the current RAW research boundary | [CR3 current state](../cr3/current-state.md) and [results](../cr3/reports/index.md) |
| Follow clean-room rules | [Clean-room engineering record](../cleanroom/README.md) |
| Understand remaining documentation migration | [Migration plan](migration-plan.md) |
| Inspect the dated 2026-06-17 baseline | [Repository inventory](repository-inventory.md) |

## Documentation authority

When sources disagree, use this order:

1. **Production code and executable tests** define implemented behaviour.
2. **Current Maven POMs and public API contracts** define the reactor, dependencies and supported public surfaces.
3. **Canonical Developer and CR3 Manual chapters** describe audited cross-module architecture, development, testing, operations and CR3 maturity.
4. **Module-local README/ARCHITECTURE files** define one module's supported surface when they agree with code, tests, POMs and canonical cross-module documentation.
5. **Current-state and hypothesis documents** describe the maintained research boundary.
6. **Generated reports and inventories** are reproducible evidence snapshots, not current normative truth.
7. **Historical/local research notes and design proposals** preserve provenance but must not be generalized into implementation claims.

A generated report must never silently override a maintained current-state document. An issue, TODO or migration plan must never be cited as proof that a feature exists.

## Module documentation

Each production module may keep a local `README.md` and `ARCHITECTURE.md` when they answer module-specific questions:

- what the module owns;
- its public contracts and dependencies;
- how to test it independently;
- known module-local limitations.

Cross-module explanations belong in the Developer Manual and should be linked rather than copied into every module.

The [repository inventory](repository-inventory.md) was generated on 2026-06-17. It remains useful historical evidence, but its counts and module list are not automatically current. The root and module POMs take precedence.

## Camera documentation

Camera support is capability- and evidence-driven:

- transport-independent contracts and partial generic adapters are described in [Architecture](architecture.md);
- exact CCAPI transport, security, session, shutdown and live-view contracts remain in the maintained `docs/ccapi-*.md` chapters;
- model/firmware support claims require redacted real-camera evidence;
- private Canon manuals remain outside Git.

## CR3 and CRX documentation

The [CR3 / CRX Manual](../cr3/index.md) distinguishes:

- **supported implementation documentation** — stable container, metadata, embedded preview/thumbnail and ImageIO surfaces;
- **experimental implementation documentation** — full RAW/CRX reconstruction, processing and evolving diagnostics;
- **research process and hypotheses** — clean-room rules, current state, rejected directions and controlled evidence acquisition;
- **generated or dated evidence** — comparison reports, benchmark outputs and experiment results.

Do not replace generated or dated evidence with a short summary that loses provenance. Keep the evidence and link it from maintained current-state and results-index documents that explain whether it still applies to current code.

## Generated documentation

Generated reports must identify their generator or reproducible command. They are changed by modifying the generator and using an explicit update mode, not by manually patching the report.

Generated Maven Site, Javadoc, coverage and test HTML are published as build artifacts or through GitHub Pages and are not committed to `main`.

## Change discipline

Documentation changes follow the same correctness standard as code:

- claims about implemented behaviour cite code, tests or a capability snapshot;
- commands are runnable from a normal checkout and do not depend on GitHub Actions;
- hardware claims name the tested model, firmware and connection mode;
- experimental and planned behaviour is labelled explicitly;
- file moves preserve links or leave a short redirect;
- generated reports are not rewritten as maintained prose;
- obsolete workstation snapshots, research plans and duplicate roadmaps are retired rather than copied forward.

See the [documentation map](documentation-map.md) for ownership and the [migration plan](migration-plan.md) for generated-report boundaries, stale-roadmap retirement and final documentation validation.
