# cr3-imageio

`javax.imageio` adapter for Canon CR3 files.

## Purpose

This module provides transparent CR3 support through the standard Java ImageIO
API. After adding this module to the classpath,
`ImageIO.read(new File("photo.cr3"))` works through SPI service loading and reads
the embedded JPEG preview by default.

## API stability

The ImageIO provider, reader discovery, embedded preview path, thumbnail access,
and metadata access are intended as stable external APIs.

CR3-specific full-RAW controls exposed through `CanonCr3ImageReadParam` select native decoding for supported profiles. The complete symbol and package
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

### Bayer samples via `readRaster()`

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
try (ImageInputStream input = ImageIO.createImageInputStream(new File("photo.cr3"))) {
  reader.setInput(input);
  CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
  param.setDecodeOptions(Cr3DecodeOptions.rawSensor());
  param.setSourceRegion(new java.awt.Rectangle(160, 64, 64, 64));
  Raster patch = reader.readRaster(0, param);
  int firstSensorCode = patch.getSample(0, 0, 0);
} finally {
  reader.dispose();
}
```

`rawSensor()` preserves original numeric values and sensor coordinates. `rawLinear()` scales values to 16 bits and offers active-area cropping and black-level adjustment. `readRaster(0, null)` uses linear defaults. Both modes also work through `read(0, param)`. See [ImageIO read parameters](../docs/cr3/imageio.md#standard-imageio-regions-and-destinations) for region, subsampling, band, and destination behavior.

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
- Processed RGB, scaled linear Bayer, and native sensor reads support the CR3/CRX profiles validated by the decoder.
- Unsupported RAW variants are decoder limitations, not ImageIO fallback behavior.
- Metadata and thumbnail availability still depends on the source file.

## Dependencies

- `cr3-core` — compatibility decoder facade
- `cr3-container` — container and preview structures
- `cr3-metadata` — metadata model
- `cr3-codec` — internal CRX decoder
- `cr3-processing` — internal RAW processing pipeline
- `slf4j-api` — logging

No runtime dependency on `canon-cr3-research` or
`canon-cr3-reference-testkit` is allowed.

## Build

```bash
mvn -B clean verify -pl canon-cr3-imageio -am
```

## Canonical documentation

The [CR3 / CRX Manual](../docs/cr3/index.md) owns stable-versus-experimental ImageIO claims and cross-module navigation. This README remains the module-local API and packaging guide.
