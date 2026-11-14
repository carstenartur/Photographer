# canon-cr3-cli Architecture

## Overview

`canon-cr3-cli` is a command-line diagnostics application for Canon CR3 files.
It uses the compatibility facade for end-to-end decode commands and the lower
split modules directly for container, metadata and subband inspection.

## Package Structure

```text
com.photographer.focusstack.cr3.cli
└── Cr3CliApp   # Picocli entry point and command implementations
```

## Module dependencies

- `cr3-core` — end-to-end decode facade and options
- `cr3-container` — BMFF boxes, parser, tracks, crop geometry and previews
- `cr3-metadata` — metadata inspection
- `cr3-codec` — subband extraction and codec diagnostics

The CLI deliberately does **not** depend on `canon-cr3-research`. The legacy
`SubbandStatistics` FQCN is supplied by `cr3-codec`; this preserves source
compatibility without creating a production-to-research dependency.

## Design Principles

- **Explicit command dependencies**: each directly imported CR3 layer is a
  direct Maven dependency.
- **No hidden research runtime**: report-only harnesses remain in
  `canon-cr3-research`.
- **Debug-friendly**: box, metadata, crop, pedestal and subband commands expose
  inspectable data without changing decoder behaviour.
- **No application framework**: the executable uses Picocli plus the CR3
  modules and logging only.
