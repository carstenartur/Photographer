# canon-cr3-imageio

`javax.imageio` adapter for Canon CR3 files.

## Purpose

This module provides transparent CR3 support through the standard Java ImageIO
API. After adding this module to the classpath,
`ImageIO.read(new File("photo.cr3"))` works through SPI service loading and reads
the embedded JPEG preview by default.

## API stability

The ImageIO provider, reader discovery, embedded preview path, thumbnail access,
and metadata access are intended as stable external APIs.

CR3-specific full-RAW controls exposed through `CanonCr3ImageReadParam` remain
experimental while the CRX codec is completed. The complete symbol and package
classification is maintained in
[`docs/cr3-api-boundaries.md`](../docs/cr3-api-boundaries.md).

Applications that require the most stable behavior should use standard ImageIO
APIs and the default embedded-preview path. They should not directly call CRX
entropy, predictor, subband, processing, or research implementation classes.

## Key types

| Type | Status | Description |
|---|---|---|
| `CanonCr3ImageReaderSpi` | `PUBLIC_STABLE` | ImageIO service provider that detects CR3 files. |
| `CanonCr3ImageReader` | `PUBLIC_STABLE` | Preview, thumbnail, metadata, and experimental RAW reader. |
| `Cr3ImageMetadata` | `PUBLIC_STABLE` | CR3 metadata exposed through the standard `IIOMetadata` contract. |
| `CanonCr3ImageReadParam` | `PUBLIC_EXPERIMENTAL` | Preview selection is the stable subset; RAW-specific controls follow decoder maturity. |

## Usage

### Basic `ImageIO.read(file)` usage

```java
// Reads the embedded JPEG preview by default.
BufferedImage image = ImageIO.read(new File("photo.cr3"));
```

### Explicit reader usage

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
try (ImageInputStream input = ImageIO.createImageInputStream(new File("photo.cr3"))) {
  reader.setInput(input);
  BufferedImage preview = reader.read(0);
} finally {
  reader.dispose();
}
```

### Preview mode

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
try (ImageInputStream input = ImageIO.createImageInputStream(new File("photo.cr3"))) {
  reader.setInput(input);
  CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
  param.setPreviewOnly(true);
  BufferedImage preview = reader.read(0, param);
} finally {
  reader.dispose();
}
```

### RAW processed mode — experimental

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
try (ImageInputStream input = ImageIO.createImageInputStream(new File("photo.cr3"))) {
  reader.setInput(input);
  CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
  param.setDecodeOptions(Cr3DecodeOptions.rawProcessed());
  BufferedImage rawImage = reader.read(0, param);
} finally {
  reader.dispose();
}
```

### Linear RAW / `readRaster()` — experimental

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
try (ImageInputStream input = ImageIO.createImageInputStream(new File("photo.cr3"))) {
  reader.setInput(input);
  CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
  param.setDecodeOptions(Cr3DecodeOptions.rawLinear().cropToActiveArea(true));
  BufferedImage linearRaw = reader.read(0, param);
  Raster raster = reader.readRaster(0, param);
} finally {
  reader.dispose();
}
```

### Thumbnail access

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
try (ImageInputStream input = ImageIO.createImageInputStream(new File("photo.cr3"))) {
  reader.setInput(input);
  BufferedImage thumbnail = reader.readThumbnail(0, 0);
} finally {
  reader.dispose();
}
```

### Metadata access

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
try (ImageInputStream input = ImageIO.createImageInputStream(new File("photo.cr3"))) {
  reader.setInput(input);
  IIOMetadata metadata = reader.getImageMetadata(0);
  Node metadataTree = metadata.getAsTree(Cr3ImageMetadata.NATIVE_FORMAT_NAME);
} finally {
  reader.dispose();
}
```

## SPI registration

The SPI is registered through
`META-INF/services/javax.imageio.spi.ImageReaderSpi`.

## Current RAW limitations

- `ImageIO.read(file)` and `reader.read(0)` default to the embedded preview because
  it is the most reliable path.
- `Cr3DecodeOptions.rawProcessed()`, `Cr3DecodeOptions.rawLinear()`, and
  `readRaster()` support only the CR3/CRX variants currently validated by the
  experimental decoder.
- Unsupported RAW variants are decoder limitations, not ImageIO fallback behavior.
- Metadata and thumbnail availability still depends on the source file.

## Dependencies

- `canon-cr3-core` — compatibility decoder facade
- `canon-cr3-container` — container and preview structures
- `canon-cr3-metadata` — metadata model
- `canon-cr3-codec` — internal CRX decoder
- `canon-cr3-processing` — internal RAW processing pipeline
- `slf4j-api` — logging

No runtime dependency on `canon-cr3-research` or
`canon-cr3-reference-testkit` is allowed.

## Build

```bash
mvn -B clean verify -pl canon-cr3-imageio -am
```
