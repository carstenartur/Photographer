# canon-cr3-core

Compatibility facade that composes the split Canon CR3 container, metadata, codec,
and processing modules into one decoder API.

## Purpose

`canon-cr3-core` no longer owns the low-level parser, CRX codec, or image-processing
implementations. Those responsibilities live in dedicated Maven artifacts:

- `canon-cr3-container`
- `canon-cr3-metadata`
- `canon-cr3-codec`
- `canon-cr3-processing`

This module keeps the unified `Cr3Decoder` and `Cr3DecodeOptions` entry points for
callers that want one facade. It has no dependency on `javax.imageio`; the standard
ImageIO adapter is provided by `canon-cr3-imageio`.

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
`canon-cr3-container`; applications that need structured metadata should also use
`canon-cr3-metadata`.

## `Cr3DecodeOptions` output modes

| Mode | Status | Description |
|---|---|---|
| `OutputMode.PREVIEW` | Stable subset | Returns the embedded JPEG preview; default and most reliable path. |
| `OutputMode.THUMBNAIL` | Stable subset | Returns the embedded JPEG thumbnail. |
| `OutputMode.RAW_PROCESSED` | Experimental | Decodes RAW data with demosaicing and color processing. |
| `OutputMode.RAW_LINEAR` | Experimental | Returns linear Bayer-pattern data without demosaicing. |

Factory shortcuts: `Cr3DecodeOptions.defaults()`,
`Cr3DecodeOptions.rawProcessed()`, and `Cr3DecodeOptions.rawLinear()`.

`experimentalPedestal(int)` and
`PedestalStrategy.EXPERIMENTAL_REFERENCE_MATCH` are research-only controls. They
exist for fixture/reference comparison and may change or be removed without notice.

## Dependencies

- `canon-cr3-container` — ISO-BMFF/CR3 container and embedded preview structures
- `canon-cr3-metadata` — structured CR3 metadata
- `canon-cr3-codec` — internal CRX decoding implementation
- `canon-cr3-processing` — internal RAW-to-image processing pipeline
- `slf4j-api` — logging

## Build

```bash
mvn -B clean verify -pl canon-cr3-core -am
```

## Canonical documentation

The [CR3 / CRX Manual](../docs/cr3/index.md) owns cross-module maturity, supported behavior and navigation. This README remains the facade-local API summary.
