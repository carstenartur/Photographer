# canon-cr3-maven-smoke

This module verifies that the split CR3 artifacts work as ordinary Maven
dependencies from a project that does not inherit the repository parent POM and
does not reuse the reactor test classpath.

It is also the authoritative compiled architecture test for the CR3 library
artifacts. The verification inspects installed POMs and packaged class files, not
repository Java sources.

## What is verified

The Maven Invoker Plugin stages the current reactor artifacts into an isolated
repository and builds the project under `src/it/external-consumer`.

That external project verifies:

- `cr3-container` parses a packaged CR3 fixture;
- `cr3-metadata` reads camera and sensor metadata;
- `cr3-codec` exposes its intended public bitstream API;
- `cr3-imageio` is discovered through `ImageReaderSpi`;
- `ImageIO.read(File)` returns a full-resolution color image from RAW and an explicit preview read retrieves the embedded JPEG;
- an explicitly requested native Bayer raster returns LibRaw-verified sensor codes at subsampled coordinates and honors the logical raster origin;
- the installed POMs contain the exact direct CR3 dependency graph documented in
  `docs/cr3-package-ownership.md`;
- each compiled CR3 class is packaged in its owning artifact;
- compiled class-file references obey the lower-to-higher layer direction;
- duplicate or unknown CR3 package ownership fails verification;
- runtime resolution contains exactly container, metadata, codec, processing,
  core, and ImageIO;
- reference-testkit, research, smoke, benchmark, and CLI artifacts do not leak
  into the external library runtime graph.

The compiled-class test reads constant-pool type references from the installed
JARs. This catches package dependencies expressed through normal bytecode,
fully-qualified type names, and reflective class-name literals without relying on
import statements or the repository working directory.

The only explicit production-JAR package exception is the documented
research-only compatibility type
`com.photographer.focusstack.cr3.research.SubbandStatistics` in
`cr3-codec`.

The fixture path is injected only into the integration-test invocation. None of
the published CR3 POMs or JARs contains a repository-local filesystem path.

## Run locally

```bash
mvn -B clean verify -pl canon-cr3-maven-smoke -am -Djava.awt.headless=true
```

The cloned external project, its build log, dependency tree, and test reports are
available under `canon-cr3-maven-smoke/target/it/external-consumer/`.
