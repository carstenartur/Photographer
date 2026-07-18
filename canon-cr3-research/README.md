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

## Build

Unit tests only:

```bash
mvn -B test -pl canon-cr3-research -am -Djava.awt.headless=true
```

Full research integration suite:

```bash
mvn -B verify -pl canon-cr3-research -am -Djava.awt.headless=true
```
