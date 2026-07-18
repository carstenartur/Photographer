# canon-cr3-research

Clean-room reverse-engineering experiments and reference-backed diagnostics for Canon CR3/CRX.

## Responsibilities

- entropy, residual and K-adaptation experiments
- predictor and state-isolation experiments
- tile/plane/bitstream diagnostics
- pedestal and optical-black experiments
- fixture intelligence and cross-file stability reports
- reference manifest and rebuild validation
- historical hypothesis rounds retained as evidence

## Dependency direction

This module may depend on every production CR3 module and on
`canon-cr3-reference-testkit`. No production module may depend on this module.

Research code is not a public Maven API. Package names are retained during the
mechanical extraction so historical reports and test sources remain traceable.

## Fixture compatibility

The committed fixture corpus remains physically owned by
`canon-cr3-imageio/src/test/resources/samples` during this split. Maven adds that
directory as a test resource and executes historical research tests with the
ImageIO module as working directory, preserving existing relative paths and
report output locations without copying large CR3 binaries.

A future fixture-module extraction may remove this transitional filesystem
coupling; it is explicit here rather than hidden in individual tests.

## Intentional compatibility exception

`SubbandStatistics` remains in `canon-cr3-codec` under its legacy
`com.photographer.focusstack.cr3.research` package because the production CLI
uses it as codec diagnostics. This avoids a forbidden CLI-to-research module
dependency during the mechanical split. #102 may rename that diagnostic package
when downstream dependencies are rewired.

## Test execution

The default Maven lifecycle compiles every research test source but only runs
normal unit tests. This keeps ordinary local and pull-request validation fast
while still detecting broken research imports after production refactorings.

```bash
mvn -B clean test \
  -pl canon-cr3-research \
  -am \
  -Djava.awt.headless=true
```

The full report-generating and reference-backed integration suite is owned by
the Maven profile `cr3-research-it`. It is not implemented inside a GitHub-only
script and can be executed from any checkout with Maven and JDK 25:

```bash
mvn -B clean verify \
  -Pcr3-research-it \
  -pl canon-cr3-research \
  -am \
  -Djava.awt.headless=true
```

`.github/workflows/cr3-research.yml` invokes exactly this command manually and
on a weekly schedule, publishes JUnit reports, and uploads generated artifacts.
