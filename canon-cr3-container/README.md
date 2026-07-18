# canon-cr3-container

Low-level ISO BMFF and Canon CR3 container support.

## Responsibilities

This module owns:

- ISO BMFF box parsing,
- Canon CR3 file-type detection,
- CR3 container and track models,
- CMP1/IAD1 and related Canon box models attached to tracks,
- embedded JPEG preview and thumbnail extraction,
- sensor/active-area geometry records that are part of the parsed container model.

It deliberately does **not** own:

- EXIF/MakerNote metadata interpretation,
- CRX entropy or predictor decoding,
- RAW processing and demosaicing,
- ImageIO adapters,
- CLI or research code.

## Public entry points

- `Cr3Parser`
- `Cr3Container`
- `Cr3Track`
- `Cr3PreviewExtractor`

Low-level types in `com.photographer.focusstack.cr3.bmff` are implementation and diagnostics types unless explicitly promoted by the API boundary documentation.

## Dependency direction

`canon-cr3-container` is the lowest CR3 production module. It must not depend on metadata, codec, processing, ImageIO, CLI, reference-testkit, or research modules.

## Build

```bash
mvn -B clean verify -pl canon-cr3-container
```
