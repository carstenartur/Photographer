# canon-cr3-maven-smoke

This module verifies that the split CR3 artifacts work as ordinary Maven
dependencies from a project that does not inherit the repository parent POM and
does not reuse the reactor test classpath.

## What is verified

The Maven Invoker Plugin stages the current reactor artifacts into an isolated
repository and builds the project under `src/it/external-consumer`.

That external project verifies:

- `canon-cr3-container` parses a packaged CR3 fixture;
- `canon-cr3-metadata` reads camera and sensor metadata;
- `canon-cr3-codec` exposes its intended public bitstream API;
- `canon-cr3-imageio` is discovered through `ImageReaderSpi`;
- `ImageIO.read(File)` returns the embedded preview;
- explicit linear RAW raster access returns unsigned 16-bit data;
- runtime resolution includes the expected production artifacts;
- `canon-cr3-reference-testkit`, `canon-cr3-research`, and this smoke-test
  module do not leak into the external runtime dependency tree.

The fixture path is injected only into the integration test invocation. None of
the published CR3 POMs or JARs contains a repository-local filesystem path.

## Run locally

```bash
mvn -B clean verify -pl canon-cr3-maven-smoke -am -Djava.awt.headless=true
```

The cloned external project, its build log, dependency tree, and test reports
are available under `canon-cr3-maven-smoke/target/it/external-consumer/`.
