# canon-cr3-metadata

Structured Canon CR3 metadata model and clean-room metadata parsers.

## Responsibilities

This module owns:

- `Cr3Metadata`,
- CMT1/CMT2 EXIF parsing,
- CMT3 Canon MakerNote parsing,
- CMT4 GPS parsing,
- CTMD timed metadata records,
- mapping parsed container geometry into the consumer-facing metadata model.

It depends only on `canon-cr3-container` and SLF4J at runtime.

It does **not** own:

- CRX entropy or predictor decoding,
- RAW mosaics,
- black-level or color processing,
- ImageIO adapters,
- CLI or research code.

## Public entry points

- `Cr3Metadata`
- `Cr3MetadataReader`
- `CtmdParser` / `CtmdRecord`
- `CanonMakerNoteData` for advanced metadata inspection

Parser implementation types such as `TiffIfdParser` and `CanonMakerNoteParser` are internal implementation details unless explicitly promoted by the API-boundary documentation.

## Usage

```java
try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
    Cr3Container container = Cr3Parser.parse(input);
    Cr3Metadata metadata = Cr3MetadataReader.read(input, container);
    System.out.println(metadata.cameraModel());
}
```

## Build

```bash
mvn -B clean verify -pl canon-cr3-metadata -am
```
