# canon-cr3-imageio

`javax.imageio` adapter for Canon CR3 files.

## Purpose

This module provides transparent CR3 support through the standard Java ImageIO
API. After adding this module to the classpath, `ImageIO.read(new File("photo.cr3"))`
will work automatically via SPI service loading.

## Key Types

| Type | Description |
|---|---|
| `CanonCr3ImageReaderSpi` | ImageIO service provider — detects CR3 files |
| `CanonCr3ImageReader` | ImageIO reader — delegates to `Cr3Decoder` |
| `CanonCr3ImageReadParam` | Extended read parameters with CR3-specific options |

## Usage

### Basic `ImageIO.read(file)` usage

```java
// Reads the embedded JPEG preview by default
BufferedImage image = ImageIO.read(new File("photo.cr3"));
```

### Explicit reader usage

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
reader.setInput(ImageIO.createImageInputStream(new File("photo.cr3")));
BufferedImage preview = reader.read(0);
reader.dispose();
```

### Preview mode

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
reader.setInput(ImageIO.createImageInputStream(new File("photo.cr3")));
CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
param.setPreviewOnly(true);
BufferedImage preview = reader.read(0, param);
reader.dispose();
```

### RAW processed mode

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
reader.setInput(ImageIO.createImageInputStream(new File("photo.cr3")));
CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
param.setDecodeOptions(Cr3DecodeOptions.rawProcessed());
BufferedImage rawImage = reader.read(0, param);
reader.dispose();
```

### Linear RAW / `readRaster()` path

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
reader.setInput(ImageIO.createImageInputStream(new File("photo.cr3")));
CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
param.setDecodeOptions(Cr3DecodeOptions.rawLinear().cropToActiveArea(true));
BufferedImage linearRaw = reader.read(0, param);
Raster raster = reader.readRaster(0, param);
reader.dispose();
```

### Thumbnail access

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
reader.setInput(ImageIO.createImageInputStream(new File("photo.cr3")));
BufferedImage thumbnail = reader.readThumbnail(0, 0);
reader.dispose();
```

### Metadata access

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
reader.setInput(ImageIO.createImageInputStream(new File("photo.cr3")));
IIOMetadata metadata = reader.getImageMetadata(0);
Node metadataTree = metadata.getAsTree(Cr3ImageMetadata.NATIVE_FORMAT_NAME);
reader.dispose();
```

## SPI Registration

The SPI is registered via `META-INF/services/javax.imageio.spi.ImageReaderSpi`.

## Current limitations of the RAW decoder

- The CR3 ImageIO plugin is intended for normal external Maven/ImageIO consumption, but the RAW decoder remains experimental.
- `ImageIO.read(file)` and `reader.read(0)` default to the embedded JPEG preview because that is the most reliable path today.
- `Cr3DecodeOptions.rawProcessed()`, `Cr3DecodeOptions.rawLinear()`, and `readRaster()` work only for CR3 variants currently supported by `canon-cr3-core`.
- Metadata and thumbnail access depend on the source CR3 containing those structures.
- This module does not attempt to improve or alter CRX decoding algorithms; unsupported RAW variants should be treated as current decoder limitations.

## Dependencies

- `canon-cr3-core` — Core decoding logic
- `slf4j-api` — Logging

## Build

```bash
mvn -B clean verify -pl canon-cr3-imageio -am
```
