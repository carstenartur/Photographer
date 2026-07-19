# canon-cr3-container

Lowest-level CR3 production module.

## Responsibilities

- ISO BMFF box parsing
- Canon CR3 file detection
- CR3 container and track models
- CMP1/IAD1 parsed box structures attached to tracks
- sensor/crop geometry carried by the container model
- embedded JPEG preview and thumbnail payload extraction

## Dependency rule

This module has no dependency on CR3 metadata, RAW codec, processing, ImageIO,
CLI, or research modules. Higher CR3 layers may depend on this module.

## Build

```bash
mvn -B verify -pl canon-cr3-container -am -Djava.awt.headless=true
```
