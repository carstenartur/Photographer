# Public release and publication checklist

This document defines the repository-owned gate for changing the project from private development to public distribution. A public repository and a supported software release are separate decisions: visibility may change before a stable release, but public API, artifact and compatibility claims must remain precise.

## Required repository checks

Run from a complete Git checkout containing every branch and tag:

```bash
bash scripts/verify-product.sh
python3 scripts/audit_public_release.py --history --media-metadata
python3 scripts/audit_historical_media.py
```

The commands provide three distinct guarantees:

- normal product verification and the current-tree high-confidence credential scan;
- every reachable **text** blob in Git history is scanned for high-confidence credential formats, while currently tracked CR3 and image files are inspected for identifying metadata;
- every unique historical CR3/image blob reachable from a branch or tag is materialized and inspected with ExifTool.

The media checks require ExifTool (`exiftool` or the Debian/Ubuntu package `libimage-exiftool-perl`). Findings display blob/path and field names but deliberately redact values. The historical media audit may require substantial temporary disk space because it inspects each unique binary version.

A passing automated scan is not permission to publish third-party material. Review unusual binaries, generated reports, screenshots and archives for provenance and privacy before changing visibility.

## Fixture and evidence policy

Public fixtures and generated evidence must not contain:

- camera or lens serial numbers;
- GPS coordinates or location fields;
- owner, artist, creator or personal copyright metadata;
- Wi-Fi credentials, authorization headers, cookies, API keys or tokens;
- private Canon manuals, SDK files or material whose redistribution is not permitted;
- unrelated personal images or identifiable private environments.

Capture dates and technical exposure data may be retained when they are relevant to reproducible evidence and do not expose a material privacy risk. If sensitive metadata is required for a test, replace it with a synthetic fixture rather than allowlisting real personal data.

Use `scripts/sanitize_cr3_fixtures.py --write` only on controlled fixture copies or a dedicated maintenance branch. It neutralizes known identifying TIFF/MakerNote entries and XMP creator content without changing file length or BMFF/TIFF offsets. ExifTool and the complete test suite must verify the result before it is committed.

## Sensitive binary history

Replacing a fixture in the current tree does **not** remove its earlier bytes from Git history. If `scripts/audit_historical_media.py` reports a reachable historical blob, repository visibility must remain private until one of these reviewed strategies is completed:

1. rewrite every public branch and tag so the sensitive blob is no longer reachable, then force-push during a declared maintenance window; or
2. publish a new repository from a verified sanitized snapshot and retain the original repository privately.

A history rewrite is intentionally not automated by normal CI. It changes commit IDs, invalidates open branch ancestry and requires all collaborators to re-clone or carefully rebase. Before rewriting:

- create and verify an offline mirror backup;
- record open pull-request heads and release tags;
- suspend writes and merge activity;
- test the rewrite on a disposable mirror;
- rerun both historical audits against all rewritten refs;
- coordinate deletion or rebasing of stale remote branches;
- request server-side removal of cached sensitive objects where the hosting provider requires it.

Changing visibility before that process is complete would expose the historical bytes even when the current files are clean.

## Public contracts

Before describing the REST API as stable, complete issue [#284](https://github.com/carstenartur/Photographer/issues/284) through pull request [#295](https://github.com/carstenartur/Photographer/pull/295) or a reviewed successor. The intended policy is:

- `/api/v1/**` is the canonical versioned REST surface;
- `/api/**` is a temporary tested compatibility alias;
- WebSocket endpoints retain independent, explicitly versioned payload contracts;
- breaking changes require a new version or a documented pre-release decision.

The repository may be public while this work remains open, but documentation must not imply a stable third-party API until the contract is merged and verified.

## Security settings after changing visibility

After the repository becomes public:

1. enable GitHub private vulnerability reporting;
2. enable secret scanning and push protection when available for the repository;
3. protect `main` with the required CI checks and review policy appropriate to the maintainer workflow;
4. keep workflow permissions minimal and review every write-capable workflow;
5. verify that GitHub Pages, release artifacts, Actions logs and retained artifacts contain no private data.

The reporting route and supported-version policy are defined in [SECURITY.md](../SECURITY.md).

## Citation and archival metadata

The repository contains three aligned metadata formats:

- [`CITATION.cff`](../CITATION.cff) for GitHub's citation interface and citation tools;
- [`codemeta.json`](../codemeta.json) for machine-readable software metadata;
- [`.zenodo.json`](../.zenodo.json) for a future Zenodo archive.

All identify Carsten Hammer with ORCID [`0009-0005-1047-6381`](https://orcid.org/0009-0005-1047-6381). Do not claim a DOI until a public release has actually been archived and the DOI resolves.

For a release, add the exact version and release date to the release metadata or generate them from the release workflow. Avoid committing a speculative version or DOI.

## Visibility decision

Changing repository visibility should happen only after:

- the current-tree, text-history and historical-media audits all pass;
- any finding has been removed from the current tree and purged from every reachable public ref;
- redistribution rights for committed binaries and documentation have been checked;
- the README still describes experimental and hardware support boundaries accurately;
- the security reporting channel is ready.
