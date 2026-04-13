# canon-cr3-cli

Command-line debug tool for Canon CR3 files.

## Purpose

Provides CLI commands for inspecting and extracting data from CR3 files.
Useful for debugging and testing the CR3 decoder.

## Commands

```bash
# Show file info and metadata
java -jar canon-cr3-cli.jar info photo.cr3

# Dump ISO BMFF box structure
java -jar canon-cr3-cli.jar boxes photo.cr3

# Extract JPEG preview
java -jar canon-cr3-cli.jar preview photo.cr3 output.jpg

# Extract JPEG thumbnail
java -jar canon-cr3-cli.jar thumb photo.cr3 thumbnail.jpg
```

## Dependencies

- `canon-cr3-core` — Core decoding logic
- `logback-classic` — Logging implementation

## Build

```bash
mvn -B clean verify -pl canon-cr3-cli -am
```
