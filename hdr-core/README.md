# hdr-core

HDR processing and tonemapping interfaces with mock adapter.

## Purpose

This module defines the `HdrProcessor` and `Tonemapper` interfaces for
merging bracketed exposures into HDR images and tonemapping them to LDR.

## Components

- `HdrProcessor`: Interface for HDR merge algorithms
- `Tonemapper`: Interface for tonemapping algorithms
- `MockHdrProcessor`: Test adapter that returns the first bracket image
- `MockTonemapper`: Test adapter that returns input unchanged

## Build

```bash
mvn -B clean verify -pl common-api,hdr-core -am -Djava.awt.headless=true
```
