# D14 analysis input layout

Store captures outside Git in one immutable directory per fixed camera setup.

```text
<series-id>/
  capture-metadata.json
  U00/
    A.CR3
    A.JPG
    B.CR3
    B.JPG
  EX25/
    A.CR3
    A.JPG
    B.CR3
    B.JPG
  ...
```

## Series requirements

- Use exact pattern IDs from `pattern-manifest.json` for the subdirectories.
- Store repeat `A` and repeat `B` for each selected pattern. For each repeat, store one `.CR3` and one `.JPG` or `.JPEG` companion. The preflight also accepts HEIF companions (`.HIF` or `.HEIF`).
- Copy `capture-metadata-template.json` to `capture-metadata.json` and complete the series settings. Fill `captureOrder` in the actual exposure order, with `A` immediately followed by `B` for each pattern.
- Record the original camera filenames in `cr3File` and `companionFile`, and the SHA-256 hex digests in `cr3Sha256` and `companionSha256`. The normalized `A` and `B` copies must retain the original bytes.
- Keep the source captures read-only. Use companion images for provenance and registration, and use the [reference pipeline](../reference-pipeline.md) for RAW decoder comparisons.

## Capture preflight

Run the preflight from the repository root with Python 3.11 or later.

```bash
python3 tools/cr3/verify_d14_capture_series.py "/path/to/series-id"
```

The preflight checks the declared pattern IDs, consecutive repeat order, normalized filenames and file hashes without writing to the series. It rejects empty captures and CR3 copies used as separate exposures. The [minimum first acquisition](README.md#minimum-first-acquisition) should report 35 patterns, 70 exposures and 140 file hashes.

Verify that the recorded camera settings stayed fixed and calibrate the active field in the companion images before differential analysis. Reject a series for differential analysis if the settings changed during the run.

The first analysis pass establishes same-pattern A/B noise before comparing different patterns. Spatial claims must reproduce across both duplicates and a second independent pattern family.
