# Public release and publication checklist

This document defines the repository-owned gate for changing the project from private development to public distribution. A public repository and a supported software release are separate decisions: visibility may change before a stable release, but public API, artifact and compatibility claims must remain precise.

## Verified scope on 15 November 2026

The tracked tree of `0d94d6c5` passed the high-confidence text scan and the
ExifTool metadata scan (1,480 tracked files, including 278 CR3, TIFF, PNG
and JPEG files). The repository's 13 real-camera CR3 fixtures were sanitized
in [commit 1401c547](https://github.com/carstenartur/Photographer/commit/1401c547ec).
The earlier version of `samples/sample.cr3` in commit `3003597b` is still
an ancestor's file on `main`. The earlier historical audit recorded camera body and internal serial numbers,
lens serial numbers, and owner, artist and creator fields, including XMP creator
metadata. Closing the tracking issue did not remove those objects. [Original finding and scope](https://github.com/carstenartur/Photographer/issues/302).

A second check compared the 13 pre-sanitization Git blob IDs with the current
trees of all 67 branches and five tags listed during the review. **42 branch
tips and two release tags still contain original fixture bytes**. The tree
of [v0.1.4](https://github.com/carstenartur/Photographer/tree/v0.1.4)
contains all 13. The tree of [v0.1.3](https://github.com/carstenartur/Photographer/tree/v0.1.3)
contains eight. A source archive downloaded from one of these tags includes
those files. This check identifies direct exposure at the listed tips.
Earlier commits and provider-side archives need separate review.

A visual review of 13 real-camera previews and 11 reference PNGs recorded
indoor scenes and diagnostic exposures. The project-generated D14 pattern PDFs are tracked. Canon developer manuals
belong in private local storage.

## Repository audit commands

Use a complete checkout for product verification and the text-history audit.

```bash
bash scripts/verify-product.sh
python3 scripts/audit_public_release.py --history
```

The optional media audits inspect identifying fields in current and historical
image files. They report paths and field names with the values redacted.

```bash
python3 scripts/audit_public_release.py --media-metadata
python3 scripts/audit_historical_media.py
```

The media audits require ExifTool. The historical audit may use substantial
temporary disk space because it inspects each unique image blob reachable
from a branch or tag.

Review redistribution rights for third-party material in binaries, reports,
screenshots and archives.

## Fixture and evidence policy

Use synthetic metadata when tests need fields that identify a camera or photographer.
Keep credentials and restricted Canon manuals or SDK files out of published material.

Use `scripts/sanitize_cr3_fixtures.py --write` only on controlled fixture copies or a dedicated maintenance branch. It neutralizes known identifying TIFF/MakerNote entries and XMP creator content without changing file length or BMFF/TIFF offsets. ExifTool and the complete test suite must verify the result before it is committed.

## Sensitive binary history

Replacing a fixture in the current tree leaves earlier file versions reachable through Git history. The [historical media audit](../scripts/audit_historical_media.py) reports the paths and field names in those versions.

When a historical file or field should stay private, two strategies are available.

1. Rewrite the affected refs so the file version is unreachable, then force-push during a declared maintenance window.
2. Publish a new repository from a verified sanitized snapshot and retain the original repository privately.

A history rewrite is intentionally not automated by normal CI. It changes commit IDs, invalidates open branch ancestry and requires all collaborators to re-clone or carefully rebase. Before rewriting:

- create and verify an offline mirror backup;
- record open pull-request heads and release tags;
- suspend writes and merge activity;
- test the rewrite on a disposable mirror;
- rerun both historical audits against all rewritten refs;
- coordinate deletion or rebasing of stale remote branches;
- request server-side removal of cached sensitive objects where the hosting provider requires it.

Changing visibility exposes reachable historical file versions even when the current files are sanitized.

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

On 15 November 2026 the rehearsal reproduced tree
`6d348164b3a66875b0e196d9a14bdf4634f95b32` from
[commit 8ca11a8d](https://github.com/carstenartur/Photographer/commit/8ca11a8dec162adbe806e7d83aa74e5f619813ba).
The candidate contained 1,513 tracked files, including 282 media files.
Its independent, single-commit history passed both historical audits, the
current metadata scan, CR3 fixture hashes, clean-room evidence validation
and documentation validation. Repeat the checks for the final reviewed
tree before publishing it.

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

Before changing repository visibility, complete the following checks.

- Review the current-tree and text-history audit results.
- Remove credentials and material that must stay private from the refs and downloads exposed by publication.
- Verify redistribution rights for committed binaries and documentation.
- Check that the README describes experimental features and hardware support accurately.
- Verify that the security reporting channel is ready.
