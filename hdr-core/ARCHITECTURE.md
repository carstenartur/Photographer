# hdr-core Architecture

## Overview

HDR processing is abstracted behind the `HdrProcessor` and `Tonemapper` interfaces.
Concrete implementations wrap specific libraries in adapter classes.

## Interfaces

- **HdrProcessor.merge()**: Takes bracketed images + EV offsets, returns merged HDR.
- **Tonemapper.tonemap()**: Takes HDR image + gamma, returns displayable LDR.

## Mock Adapters

- `MockHdrProcessor`: Returns first bracket image (for tests).
- `MockTonemapper`: Returns input unchanged (for tests).

## Extension Points

To add a real HDR library (e.g. ImageJ-based or custom):
1. Create a new class implementing `HdrProcessor`
2. Wrap all library-specific code in the adapter
3. Register via Spring configuration or factory
