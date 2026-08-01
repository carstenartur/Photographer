#!/usr/bin/env python3
"""Validate the repository-derived public release version contract."""

from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "deploy-release.yml"


def main() -> int:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    required = (
        "      next_development_version:\n",
        "Optional exact next version (X.Y.Z-SNAPSHOT)",
        "      next_version_increment:\n",
        "        type: choice\n",
        "- patch\n",
        "- minor\n",
        "- major\n",
        "PROJECT_VERSION=$(mvn help:evaluate",
        "RELEASE_VERSION=${PROJECT_VERSION%-SNAPSHOT}",
        "EXACT_NEXT_VERSION: ${{ inputs.next_development_version }}",
        "NEXT_VERSION_INCREMENT: ${{ inputs.next_version_increment }}",
        "next_development_version must use X.Y.Z-SNAPSHOT",
        "must be newer than release",
        "ref: main",
    )
    forbidden = (
        "      release_version:\n",
        "${{ inputs.release_version }}",
    )

    failures = [
        f"missing required release contract token {token!r}"
        for token in required
        if token not in workflow
    ]
    failures.extend(
        f"human-entered release version remains: {token!r}"
        for token in forbidden
        if token in workflow
    )
    if failures:
        print("Release workflow contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print(
        "Release workflow derives the release version and supports a validated "
        "optional next-version override."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
