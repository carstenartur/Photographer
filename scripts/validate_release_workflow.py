#!/usr/bin/env python3
"""Fail when the public release workflow accepts free-form version strings."""

from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "deploy-release.yml"


def main() -> int:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    required = (
        "      next_version_increment:\n",
        "        type: choice\n",
        "          - patch\n          - minor\n          - major\n",
        "PROJECT_VERSION=$(mvn help:evaluate",
        "RELEASE_VERSION=${PROJECT_VERSION%-SNAPSHOT}",
        "NEXT_VERSION_INCREMENT: ${{ inputs.next_version_increment }}",
        "ref: main",
    )
    forbidden = (
        "      release_version:\n",
        "      next_development_version:\n",
        "${{ inputs.release_version }}",
        "${{ inputs.next_development_version }}",
    )

    failures = [f"missing required release contract token {token!r}" for token in required if token not in workflow]
    failures.extend(
        f"free-form release input remains: {token!r}"
        for token in forbidden
        if token in workflow
    )
    if failures:
        print("Release workflow contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("Release workflow derives versions from repository state.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
