# Public release and publication checklist

This document defines the repository-owned gate for changing the project from private development to public distribution. A public repository and a supported software release are separate decisions: visibility may change before a stable release, but public API, artifact and compatibility claims must remain precise.

## Required repository checks

Run from a complete Git checkout:

```bash
bash scripts/verify-product.sh
python3 scripts/audit_public_release.py --history --media-metadata
```

The first command is the normal checkout-runnable product verification. The second command adds two publication-specific checks:

- every reachable text blob in Git history is scanned for high-confidence credential formats;
- tracked CR3 and image files are inspected with ExifTool for GPS, owner/creator, copyright and device/lens serial metadata.

`--media-metadata` requires ExifTool (`exiftool` or the Debian/Ubuntu package `libimage-exiftool-perl`). Findings display paths and field names but deliberately redact values.

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

- the full-history and media-metadata audit passes;
- any finding has been removed from the current tree and, where necessary, purged from history and the credential rotated;
- redistribution rights for committed binaries and documentation have been checked;
- the README still describes experimental and hardware support boundaries accurately;
- the security reporting channel is ready.
