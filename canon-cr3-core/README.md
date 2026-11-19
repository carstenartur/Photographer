# cr3-core

Compatibility facade that composes the split Canon CR3 container, metadata, codec,
and processing modules into one decoder API.

## Purpose

`cr3-core` no longer owns the low-level parser, CRX codec, or image-processing
implementations. Those responsibilities live in dedicated Maven artifacts:

- `cr3-container`
- `cr3-metadata`
- `cr3-codec`
- `cr3-processing`

This module keeps the unified `Cr3Decoder` and `Cr3DecodeOptions` entry points for
callers that want one facade. It accepts JDK ImageIO streams; the provider discovered
by `ImageIO.getImageReaders` lives in the separate `cr3-imageio` module.

## API stability

Compatibility expectations are defined in
[`docs/cr3-api-boundaries.md`](../docs/cr3-api-boundaries.md).

- Embedded preview and thumbnail selection are intended for normal external use.
- Full RAW decoding and the `Cr3RawImage` transfer model remain experimental while
  CRX support is completed and validated across camera variants.
- Codec and processing implementation classes are internal APIs even when Java
  visibility is public for module integration or tests.
- Reference-matching pedestal controls are research-only and must not be used by
  production applications.
- Production modules must not depend on `canon-cr3-research` or
  `canon-cr3-reference-testkit` at runtime.

## Main facade types

| Type | Status | Description |
|---|---|---|
| `Cr3Decoder` | `PUBLIC_EXPERIMENTAL` | Unified preview, metadata, and RAW decoding facade. |
| `Cr3DecodeOptions` | `PUBLIC_EXPERIMENTAL` | Selects output mode and processing behavior. Preview/thumbnail selection is the stable subset. |
| `Cr3Decoder.PedestalReport` and related diagnostic values | `PUBLIC_EXPERIMENTAL` | Diagnostic schema that may evolve with decoder research. |

Stable container and metadata APIs are owned by their dedicated artifacts rather
than duplicated here. Applications that need only file inspection should depend on
`cr3-container`; applications that need structured metadata should also use
`cr3-metadata`.

## `Cr3DecodeOptions` output modes

| Mode | Status | Description |
|---|---|---|
| `OutputMode.PREVIEW` | Stable subset | Returns the embedded JPEG preview; default and most reliable path. |
| `OutputMode.THUMBNAIL` | Stable subset | Returns the embedded JPEG thumbnail. |
| `OutputMode.RAW_PROCESSED` | Experimental | Decodes RAW data with demosaicing and color processing; 8-bit RGB by default, or three unsigned 16-bit channels with `bitDepth(SIXTEEN)` in 0.1.8-SNAPSHOT. |
| `OutputMode.RAW_LINEAR` | Experimental | Unsigned-short Bayer image scaled to the 16-bit output range. Honors active-area crop and black-level options. |
| `OutputMode.RAW_SENSOR` | Experimental | Original sensor codes in a single unsigned-short band, in uncropped sensor coordinates. |

Factory shortcuts: `Cr3DecodeOptions.defaults()`,
`Cr3DecodeOptions.rawProcessed()`, `Cr3DecodeOptions.rawLinear()` and `Cr3DecodeOptions.rawSensor()`.

The 16-bit color path keeps fractional values through black-level subtraction,
white balance, demosaicing and color conversion, and rounds only the final output
to `0..65535`. It returns a banded RGB `BufferedImage`; read the full-depth samples
through `image.getRaster()`, since `getRGB()` converts to eight bits per channel.
Disable `applySrgbConversion` to retain linear RGB with a matching color model.
The existing eight-bit default and native sensor codes are unchanged. This color
output requires the development version; stable 0.1.7 does not implement it.

`experimentalPedestal(int)` and
`PedestalStrategy.EXPERIMENTAL_REFERENCE_MATCH` are research-only controls. They
exist for fixture/reference comparison and may change or be removed without notice.

## Dependencies

- `cr3-container` — ISO-BMFF/CR3 container and embedded preview structures
- `cr3-metadata` — structured CR3 metadata
- `cr3-codec` — internal CRX decoding implementation
- `cr3-processing` — internal RAW-to-image processing pipeline
- `slf4j-api` — logging

## Build

```bash
mvn -B clean verify -pl canon-cr3-core -am
```

## Canonical documentation

The [CR3 / CRX Manual](../docs/cr3/index.md) owns cross-module maturity, supported behavior and navigation. This README remains the facade-local API summary.
