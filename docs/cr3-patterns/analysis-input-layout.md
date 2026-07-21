# D14 analysis input layout

When captures become available, store them outside Git and point the research harness at one immutable series directory:

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

Requirements:

- directory names are exact pattern IDs from `pattern-manifest.json`;
- each selected pattern contains repeat `A` and repeat `B`;
- original camera filenames and hashes are retained in `capture-metadata.json`;
- files are read-only research inputs and must never be rewritten;
- the harness must reject missing duplicates, unknown IDs, hash mismatches and settings changes within a series;
- companion JPEG/HEIF files are provenance and registration inputs, not normative RAW decoder references.

The first analysis pass establishes same-pattern A/B noise before comparing different patterns. Spatial claims must reproduce across both duplicates and a second independent pattern family.
