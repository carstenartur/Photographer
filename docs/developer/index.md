# Developer Manual

This is the single maintained entry point for contributors and coding agents working on Photographer.

Photographer is a multi-module Java 25 / Spring Boot 4 application with a React frontend, camera backends, photography workflows and a separate CR3/CRX clean-room research track. The repository intentionally keeps module-local documentation close to code and preserves research evidence, but those files do not all have the same authority.

## Start here

| Need | Current source |
|---|---|
| Build or run the repository | [Development guide](../development.md) |
| Understand the current module graph | Root [`pom.xml`](../../pom.xml), module POMs and the [architecture migration source](../architecture.md) |
| Find a module or public API | [Repository inventory](repository-inventory.md) and [documentation map](documentation-map.md) |
| Run unit, integration or hardware tests | [Test strategy](../test-strategy.md) |
| Use the REST API | [REST API reference](../api.md) |
| Configure a camera | [Camera setup](../camera-setup.md) |
| Work on Canon CCAPI session ownership | [CCAPI session ownership](../ccapi-session-ownership.md) |
| Work on CR3/CRX | [CR3 current state](../cr3-current-state.md) and [CR3 results index](../cr3-results-index.md) |
| Follow clean-room rules | [Clean-room engineering record](../cleanroom/README.md) |
| Understand the documentation migration | [Migration plan](migration-plan.md) |

The links above point to existing sources. Several predate later module splits or implementation changes and are migration inputs rather than unquestioned truth. Until Phase 2 completes, production code, tests and current POMs take precedence. This index remains stable while individual files move or are retired.

## Documentation authority

When sources disagree, use this order:

1. **Production code and executable tests** define implemented behaviour.
2. **Current Maven POMs and public API contracts** define the reactor, dependencies and supported public surfaces.
3. **Module-local README/ARCHITECTURE files** define one module's supported surface, provided they agree with code, tests and POMs.
4. **Canonical maintained documents** describe cross-module architecture, development, testing and current support boundaries after they have been audited against the current revision.
5. **Current-state and hypothesis documents** describe the explicitly dated research state.
6. **Generated reports and inventories** are reproducible evidence snapshots, not current normative truth.
7. **Historical/local research notes and design proposals** preserve provenance but must not be generalized into implementation claims.

A generated report must never silently override a maintained current-state document. A planning document must never be cited as proof that a feature exists.

## Module documentation

Each production module may keep a local `README.md` and `ARCHITECTURE.md` when they answer module-specific questions:

- what the module owns;
- its public contracts and dependencies;
- how to test it independently;
- known module-local limitations.

Cross-module explanations belong in the Developer Manual and should be linked rather than copied into every module.

The complete baseline module list is in the [repository inventory](repository-inventory.md). That inventory was generated on 2026-06-17 and is evidence of that revision, not an automatically current module catalogue.

## CR3 and CRX documentation

The CR3 area has three distinct document classes:

- **maintained implementation documentation** — module READMEs, current state, format/API documentation;
- **research process and hypotheses** — clean-room rules, playbooks and open hypotheses;
- **generated or dated evidence** — comparison reports, benchmark outputs and experiment results.

Do not replace generated or dated evidence with a short summary that loses provenance. Instead, keep the evidence and link it from a maintained current-state or results-index document that explains whether it still represents the current implementation.

## Generated documentation

Generated reports must identify their generator or reproducible command. They are edited by changing the generator and regenerating the output, not by manually patching the report.

Generated Maven/Site HTML is published as a build artifact or through GitHub Pages and is not committed to `main`.

## Change discipline

Documentation changes follow the same correctness standard as code:

- claims about implemented behaviour cite code, tests or a capability snapshot;
- commands are runnable from a normal checkout and do not depend on GitHub Actions;
- hardware claims name the tested model, firmware and connection mode;
- experimental and planned behaviour is labelled explicitly;
- file moves preserve links or add a short redirect during migration;
- generated reports are not rewritten as maintained prose.

See the [documentation map](documentation-map.md) for the classification of the 2026-06-17 inventory and the [migration plan](migration-plan.md) for the ordered consolidation work.
