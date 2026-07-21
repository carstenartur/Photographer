# CR3 D14 diagnostic patterns

This directory contains a pre-registered clean-room stimulus pack for issue #249.

## Files

- `cr3-diagnostic-patterns-screen-16x9.pdf`: screen/tablet edition.
- `cr3-diagnostic-patterns-print-a4-landscape.pdf`: A4 landscape edition.
- `pattern-manifest.json`: stable pattern IDs, parameters, page numbers and analysis intent.

Both PDFs contain the same 48 pattern pages after one cover page. The active field uses the normalized rectangle:

```json
{
  "x": 0.04,
  "y": 0.06,
  "width": 0.92,
  "height": 0.88
}
```

The outer 50% gray border and its labels are registration/provenance aids. Exclude that border from spatial correlation.

## Capture contract

- Use one uninterrupted manual camera setup.
- Keep RAW/CR3 enabled; retain a companion JPEG only as aiming/provenance evidence.
- Fix exposure, ISO, aperture, focus, white balance, framing and display/print illumination.
- Disable variable automatic processing where the camera permits.
- Slightly defocus screen captures when needed to suppress RGB subpixel/Moire structure while preserving coarse cells.
- Capture every selected page twice consecutively.
- Record camera model, firmware, lens, focal length, distance, medium, brightness/illumination and complete settings.
- Preserve original files and hashes.

## Minimum first acquisition

`U00 U03 U04 U07`

`EX25 EX50 EX75 EY25 EY50 EY75`

`VX08 VX32 HY08 HY32`

`B_C B_TL B_TR B_BL B_BR`

`GX GY STEP16`

`XG0 XG1 XG2 XG3 XG4 XG5`

`YG0 YG1 YG2 YG3 YG4`

`PRBS32 PRBS32I`

Capture two repeats per page.

## Analysis contract

1. Establish same-pattern A/B noise baselines before comparing different stimuli.
2. Separate container/metadata changes from CRX payload changes.
3. Report changed bytes and bits per record, contiguous intervals, density and first divergence.
4. Correlate Gray-code change vectors with predeclared spatial cell code words.
5. Accept a spatial mapping only when it reproduces across duplicates and an independent stimulus family.
6. Do not choose decoder semantics by rendered-image MAE alone.
7. Keep production decoder semantics unchanged until a pre-registered candidate passes structural completion and fixed reference gates.

## Pattern families

- uniform level ladder;
- single horizontal and vertical edges;
- directional binary frequencies;
- checkerboards;
- moved localized blocks;
- gradients and discrete staircase;
- X/Y Gray-code coordinate planes;
- deterministic PRBS field and inverse;
- orientation and diagonal controls.

All generation parameters are in `pattern-manifest.json`.
