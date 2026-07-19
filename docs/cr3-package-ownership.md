# CR3 Package Ownership and Dependency Direction

This document is the authoritative ownership and dependency contract for the
split Canon CR3 Maven artifacts.

The physical module split is complete. The contract is verified from the
**installed POMs and compiled JARs** by the independent project in
`canon-cr3-maven-smoke`; Java source files are not parsed to infer dependencies.

## Production dependency graph

```text
canon-cr3-container
        ↓
canon-cr3-metadata
        ↓
canon-cr3-codec
        ↓
canon-cr3-processing
        ↓
canon-cr3-core (compatibility facade / orchestration)
        ↓
canon-cr3-imageio       canon-cr3-cli       app
```

The arrows show the allowed upward composition direction. A module may declare a
narrower set of direct lower-layer dependencies than the diagram suggests.

The exact direct CR3 dependencies verified for the library artifacts are:

| Artifact | Direct CR3 runtime dependencies |
|---|---|
| `canon-cr3-container` | none |
| `canon-cr3-metadata` | `canon-cr3-container` |
| `canon-cr3-codec` | `canon-cr3-container`, `canon-cr3-metadata` |
| `canon-cr3-processing` | `canon-cr3-metadata`, `canon-cr3-codec` |
| `canon-cr3-core` | container, metadata, codec, processing |
| `canon-cr3-imageio` | container, metadata, codec, processing, core |

Production runtime artifacts must not resolve:

- `canon-cr3-research`;
- `canon-cr3-reference-testkit`;
- `canon-cr3-maven-smoke`;
- `canon-cr3-benchmark`;
- `canon-cr3-cli` as a transitive library dependency.

`canon-cr3-reference-testkit` may depend on production artifacts only in test or
research contexts. `canon-cr3-research` may consume production artifacts and the
reference testkit, but no production artifact may depend back on either module.

## Package ownership

| Package / symbol group | Owning artifact | API role | Allowed CR3 package references |
|---|---|---|---|
| `com.photographer.focusstack.cr3.Cr3Exception` and `Cr3FormatException` | `canon-cr3-container` | stable shared exceptions | container layer |
| `com.photographer.focusstack.cr3.bmff` | `canon-cr3-container` | internal ISO-BMFF implementation | container layer |
| `com.photographer.focusstack.cr3.container` | `canon-cr3-container` | stable container API | container layer |
| `com.photographer.focusstack.cr3.preview` | `canon-cr3-container` | stable embedded-preview API | container layer |
| `com.photographer.focusstack.cr3.metadata` | `canon-cr3-metadata` | stable model, internal parser helpers | container, metadata |
| `com.photographer.focusstack.cr3.raw` | `canon-cr3-codec` | experimental RAW model | container, metadata, codec |
| `com.photographer.focusstack.cr3.raw.codec` | `canon-cr3-codec` | internal CRX implementation and diagnostics | container, metadata, codec |
| `com.photographer.focusstack.cr3.processing` | `canon-cr3-processing` | internal RAW processing | metadata, codec, processing |
| `com.photographer.focusstack.cr3.decode` | `canon-cr3-core` | experimental compatibility facade | all lower production layers, core |
| `com.photographer.focusstack.cr3.imageio` | `canon-cr3-imageio` | stable ImageIO adapter, experimental RAW controls | all lower production layers, ImageIO |
| `com.photographer.focusstack.cr3.cli` | `canon-cr3-cli` | CLI product surface, internal Java API | lower production layers, CLI |
| `com.photographer.focusstack.cr3.reference` | `canon-cr3-reference-testkit` | test-only | production test context only |
| `com.photographer.focusstack.cr3.research` | `canon-cr3-research` | research-only | production modules and testkit |

### Compatibility exception

`com.photographer.focusstack.cr3.research.SubbandStatistics` remains in
`canon-cr3-codec` as a documented research-only compatibility type used by the
current CLI and research tools. The compiled ownership test permits exactly this
class and its nested classes in the codec JAR; no other `cr3.research` class may
appear in a production library artifact.

This exception is not a general permission for production code to depend on the
research module and may be removed in a dedicated API/CLI migration.

## Evaluated verification approaches

The post-split cleanup considered the following mechanisms:

| Approach | Decision | Reason |
|---|---|---|
| Maven Enforcer `bannedDependencies` / convergence rules | not the primary architecture test | Useful for broad dependency bans, but the exact permitted edge set differs by module and would require repeated configuration plus exceptions for legitimate research/test scopes. |
| Aggregator graph test | selected through the external consumer | The installed POMs are inspected directly, so the test sees what a downstream Maven build actually resolves rather than the reactor's source layout. |
| ArchUnit over all CR3 classes | not added | The physical module graph already makes most upward references impossible. A small class-file verifier covers the remaining package rules without adding a general architecture framework solely for this subsystem. |
| External Maven smoke module | selected and strengthened | It already verifies packaged behavior and runtime leaks from an isolated repository, and can inspect the same installed POMs and JARs without a working-directory dependency. |

The selected combination is therefore:

1. Maven module boundaries for compilation;
2. exact installed-POM graph verification;
3. targeted compiled-class ownership/reference verification;
4. exact independent-consumer runtime-tree verification.

## Verification strategy

The command

```bash
mvn -B clean verify -pl canon-cr3-maven-smoke -am -Djava.awt.headless=true
```

builds all required reactor artifacts, installs them into an isolated Maven
repository, and builds an independent consumer that does not inherit the
repository parent POM.

The consumer verifies:

1. **Installed POM graph** — the direct CR3 dependencies of every library artifact
   exactly match the table above.
2. **Compiled artifact ownership** — each CR3 class is contained in the owning JAR;
   duplicate classes and unknown CR3 packages fail the test.
3. **Compiled dependency direction** — class-file constant-pool references are
   checked against the allowed layer direction. This also sees fully-qualified
   and reflective class-name strings that import-only scanners miss.
4. **Exact external runtime graph** — only container, metadata, codec, processing,
   core, and ImageIO may resolve for the independent library consumer.
5. **No test/research leaks** — reference-testkit, research, smoke, benchmark, and
   CLI artifacts are absent from the external runtime dependency tree.
6. **Packaged behavior** — container parsing, metadata, codec API, ImageIO SPI,
   preview decoding, and linear RAW raster access work from installed artifacts.

The test locates artifacts through the loaded classes' `CodeSource` and their
installed sibling POMs. It does not depend on the process working directory or on
repository-relative source paths.

## Removed transitional guards

The following source-text scanners were migration safety rails and are obsolete
after the physical split:

| Removed test | Previous rule | Final enforcement |
|---|---|---|
| `Cr3ContainerModuleBoundaryTest` | container imports no higher CR3 package | installed container POM + compiled container JAR |
| `Cr3MetadataModuleBoundaryTest` | metadata imports no codec/processing/adapter package | exact metadata POM edge + compiled references |
| `Cr3CodecModuleBoundaryTest` | codec imports no processing/facade/adapter/research package | exact codec POM edge + compiled references + explicit statistics exception |
| `Cr3ProcessingModuleBoundaryTest` | processing imports no container/facade/adapter/research package | exact processing POM edge + compiled references |
| `Cr3PackageBoundaryTest` | planned monolithic package layers | obsolete after split; replaced by all-artifact compiled verification |
| `Cr3ImageIoBoundaryTest` | ImageIO imports no CLI/research package | exact ImageIO POM edge + compiled references |

No Java-source parser remains solely for import-prefix checks.

## Change policy

- Add or remove a production CR3 dependency only by updating the verified graph and
  explaining the architectural reason in the same pull request.
- Do not weaken a compiled package rule merely to make a new dependency pass;
  reconsider the ownership first.
- Research and reference-test infrastructure must remain absent from production
  runtime graphs.
- Decoder hypothesis changes belong in dedicated research/production pull requests
  and must not be hidden in architecture cleanup.
