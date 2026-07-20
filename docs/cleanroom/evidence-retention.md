# Clean-room Evidence Retention

GitHub Actions artifacts are useful execution evidence but are not a durable archive because they expire. Decision-bearing experiments therefore use three layers.

## Layer 1: committed durable summary

Every completed experiment keeps deterministic Markdown plus machine-readable output or a ledger record in the repository. The summary contains:

- fixed fixture identities;
- changed variable and held-constant variables;
- acceptance gate and verdict;
- commit SHA and workflow run where available;
- artifact digest;
- production decision.

This is the minimum evidence that survives ordinary repository cloning.

## Layer 2: deterministic clean-room bundle

`scripts/build_cleanroom_evidence_bundle.py` creates a reproducible ZIP containing:

- clean-room process and manifests;
- CR3 current state, hypotheses and results index;
- committed CR3 research reports;
- reference inventory and reference manifest;
- a generated `BUNDLE-MANIFEST.json` with SHA-256 for every included file.

The bundle excludes CR3/JPEG/TIFF/PNG media, credentials and stable hardware identifiers. Two builds from the same checkout must have the same SHA-256.

CI uploads the bundle for review. Before public release, generate it from the tagged commit and attach it to the release or another durable archive.

## Layer 3: full execution artifacts

Where practical, preserve the full workflow artifact for milestone experiments as a release asset or archival object. Record its SHA-256 in `experiment-ledger.json`.

The full artifact may include test reports and generated TSV/JSON. It must not be made public until fixture privacy and licensing are reviewed.

## Release procedure

1. check out the intended release tag;
2. run `python3 scripts/validate_cleanroom_evidence.py .`;
3. run `python3 scripts/build_cleanroom_evidence_bundle.py .`;
4. verify the generated SHA-256 file;
5. attach the ZIP and checksum to the release;
6. record the release/tag and bundle digest in release notes;
7. retain full experiment artifacts separately when they are safe to publish.

A later repository rewrite or report correction requires a new bundle. Existing released bundles should not be replaced silently.
