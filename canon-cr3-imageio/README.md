# cr3-imageio

`javax.imageio` adapter for Canon CR3 files and uncompressed 16-bit Bayer DNGs.

## Purpose

This module provides transparent CR3 support through the standard Java ImageIO
API. After adding this module to the classpath,
`ImageIO.read(new File("photo.cr3"))` works through SPI service loading and
develops a full-resolution color image from the RAW track. Set
`CanonCr3ImageReadParam.setPreviewOnly(true)` for the camera's embedded JPEG.

## API stability

The ImageIO provider, reader discovery, embedded preview path, thumbnail access,
and metadata access are intended as stable external APIs.

CR3-specific full-RAW controls exposed through `CanonCr3ImageReadParam` select native decoding for supported profiles. The complete symbol and package
classification is maintained in
[`docs/cr3-api-boundaries.md`](../docs/cr3-api-boundaries.md).

Use standard ImageIO APIs to load the developed photograph or request the
embedded JPEG. The [ImageIO guide](../docs/cr3/imageio.md) describes the
available output modes. Since 0.1.9, the default has been full-resolution
sRGB with three unsigned 16-bit bands. Read channel values through the raster.
For RGB8, select `BitDepth.EIGHT` on the default read parameters or request
the advertised `TYPE_INT_RGB` destination. Version 0.1.8 requires explicit
`BitDepth.SIXTEEN` for RGB16. For a sequence of full-frame reads the current checkout can
develop directly into one caller-supplied, three-bank RGB16 image. The example creates
an advertised sRGB or linear RGB destination and reuses it across files of matching size.
[Reuse an RGB16 destination](../docs/cr3/imageio.md#reuse-an-rgb16-destination)

## Key types

| Type | Status | Description |
|---|---|---|
| `CanonCr3ImageReaderSpi` | `PUBLIC_STABLE` | ImageIO service provider that detects CR3 files. |
| `CanonCr3ImageReader` | `PUBLIC_STABLE` | Default RAW color read, explicit preview, thumbnail and metadata access. |
| `Cr3ImageMetadata` | `PUBLIC_STABLE` | CR3 metadata exposed through the standard `IIOMetadata` contract. |
| `CanonCr3ImageReadParam` | `PUBLIC_EXPERIMENTAL` | Preview selection is the stable subset; RAW-specific controls follow decoder maturity. |

## Usage

See the [Java ImageIO guide](../docs/cr3/imageio.md) for the Maven setup, runnable examples and read parameters for embedded JPEG, thumbnail, processed RGB, linear Bayer and original sensor samples. The [CR3 import guide](../docs/cr3/import.md) covers the standalone decoder, original JPEG byte extraction and captured frames in Photographer.

The [CR3 writer example](../docs/cr3/imageio.md#write-a-lossless-cr3-using-a-camera-profile) shows how to select a built-in camera profile and write a matching native sensor mosaic.

The [DNG examples](../docs/cr3/imageio.md#write-and-read-a-bayer-dng) show how to export a full native sensor image or an explicit raster with calibration, and how to read the sensor values back.

## SPI registration

The SPI is registered through
`META-INF/services/javax.imageio.spi.ImageReaderSpi` and `META-INF/services/javax.imageio.spi.ImageWriterSpi`.

## Current RAW limitations

- Processed RGB, scaled linear Bayer, and native sensor reads support the CR3/CRX profiles validated by the decoder.
- RAW decoding failures are exposed as `IOException`. An embedded preview, when present, can be read explicitly.
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
