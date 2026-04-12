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

```java
// Simple usage — reads the embedded JPEG preview by default
BufferedImage image = ImageIO.read(new File("photo.cr3"));

// Advanced usage — full RAW decoding
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
reader.setInput(ImageIO.createImageInputStream(new File("photo.cr3")));
CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
param.setDecodeOptions(Cr3DecodeOptions.rawProcessed());
BufferedImage rawImage = reader.read(0, param);
```

## SPI Registration

The SPI is registered via `META-INF/services/javax.imageio.spi.ImageReaderSpi`.

## Dependencies

- `canon-cr3-core` — Core decoding logic
- `slf4j-api` — Logging

## Build

```bash
mvn -B clean verify -pl canon-cr3-imageio -am
```
