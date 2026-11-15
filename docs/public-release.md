# Public release and publication checklist

This document defines the repository-owned gate for changing the project from private development to public distribution. A public repository and a supported software release are separate decisions: visibility may change before a stable release, but public API, artifact and compatibility claims must remain precise.

## Verified scope on 15 November 2026

The tracked tree of `0d94d6c5` passed the high-confidence text scan and the
ExifTool metadata scan (1,480 tracked files, including 278 CR3, TIFF, PNG
and JPEG files). The repository's 13 real-camera CR3 fixtures were sanitized
in [commit 1401c547](https://github.com/carstenartur/Photographer/commit/1401c547ec).
The earlier version of `samples/sample.cr3` in commit `3003597b` is still
an ancestor's file on `main`. The earlier historical audit recorded camera,
lens and creator fields. Closing the tracking issue did not remove those
objects. [Original finding and scope](https://github.com/carstenartur/Photographer/issues/302).

These checks cover common credential formats and embedded metadata. The
maintainer still needs to review visible subjects and redistribution rights
for the photographs, derivative reference images and downloadable assets.
A visual review of all 13 real-camera previews and 11 reference PNGs found
indoor scenes and diagnostic exposures. In particular, the two G5 X Mark II
detail captures include objects from the room. Review the originals at full
resolution when deciding which photographs to redistribute.
The project-generated D14 pattern PDFs are tracked. Canon developer manuals
belong in private local storage.

## Required repository checks

Run from a complete Git checkout containing every branch and tag:

```bash
bash scripts/verify-product.sh
python3 scripts/audit_public_release.py --history --media-metadata
python3 scripts/audit_historical_media.py
```

The commands provide three distinct guarantees:

- normal product verification and the current-tree high-confidence credential scan;
- reachable **text** blobs up to 2,000,000 bytes are scanned for high-confidence credential formats, while currently tracked CR3 and image files are inspected for identifying metadata;
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

### Rehearse a snapshot without changing a remote

To keep the original research history private, create a standalone local
repository containing the exact reviewed tree in one root commit. Run in
Bash from the source repository. Set `ref` to the reviewed commit. Git and
ExifTool must be installed. Allow space for an additional checkout, its
object database and temporary media copies.

```bash
set -euo pipefail
ref=$(git rev-parse --verify 'HEAD^{commit}')
source_tree=$(git rev-parse "$ref^{tree}")
candidate=$(mktemp -d)
git archive "$ref" | tar -x -C "$candidate"
git -C "$candidate" init -q -b main
git -C "$candidate" -c core.autocrlf=false add --force --all
test "$(git -C "$candidate" write-tree)" = "$source_tree"
git -C "$candidate" commit -qm "Initial public source snapshot"
test "$(git -C "$candidate" rev-list --all --count)" = 1
(
  cd "$candidate"
  python3 scripts/audit_public_release.py --history --media-metadata
  python3 scripts/audit_historical_media.py
  python3 scripts/validate_cr3_reference_manifest.py
  python3 scripts/validate_cleanroom_evidence.py
  python3 scripts/validate_documentation.py .
)
printf 'Local candidate: %s\nSource tree: %s\n' "$candidate" "$source_tree"
```

The tree-ID check compares tracked paths, file contents and executable bits.
The new root commit has its own ID. Keep the original commit/tree mapping in
the private archive. Run product, frontend and Docker verification against
the reviewed candidate and review repository URLs, release assets, Pages
and issue references for the intended destination. Creating this candidate
does not change the visibility of the existing repository.

The rehearsal on 15 November 2026 reproduced tree
`216179278914000fdb4804796759507c365452bb` from `0d94d6c5`. The independent,
single-commit repository passed both historical audits, the current metadata
scan, CR3 fixture hashes, clean-room evidence validation and documentation
validation. This result applies to that snapshot. Repeat the checks for the
final reviewed tree before publishing it.

## Public contracts

The [REST version policy](api.md#version-contract) applies to the web
client, third-party API clients and downloads. Use `/api/v1/**` for new
clients. The [compatibility and removal policy](api.md#compatibility-and-removal-policy)
defines the lifetime of the legacy aliases. [PR #295](https://github.com/carstenartur/Photographer/pull/295)
introduced both routes and their contract tests.

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
