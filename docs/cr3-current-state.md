# CR3 Current State

## What is already known

- CR3 container parsing works.
- CMP1, IAD1 and CRX headers are parsed.
- Fixture inventory exists.
- Subband extraction exists.
- Bitstream statistics exist.
- Hypothesis sweep infrastructure exists.
- Preview-based comparison infrastructure exists.

## Main bottleneck

The entropy/residual codec used by CRX is not yet understood sufficiently to reconstruct the original RAW signal.

## Current research focus

Focus on:

- entropy coding,
- residual reconstruction,
- predictor behaviour,
- bitstream structure.

Avoid spending research time on:

- colour rendering,
- white balance tuning,
- demosaicing improvements,
- camera-specific image appearance.

## Success metric

A hypothesis is considered promising only when it improves reproducible numerical metrics across fixtures and not merely visual appearance.
