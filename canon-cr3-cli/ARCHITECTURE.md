# canon-cr3-cli Architecture

## Overview

`canon-cr3-cli` is a simple command-line application that uses `canon-cr3-core`
to inspect and extract data from Canon CR3 files. It serves as both a debug tool
and an example of how to use the CR3 decoder API.

## Package Structure

```
com.photographer.focusstack.cr3.cli
└── Cr3CliApp   # Main class with info/boxes/preview/thumb commands
```

## Design Principles

- **Thin wrapper**: The CLI delegates all work to `canon-cr3-core` classes.
- **Debug-friendly**: Box structure and metadata dumps help diagnose parsing issues.
- **No framework dependencies**: Uses only the core module and SLF4J/Logback.
