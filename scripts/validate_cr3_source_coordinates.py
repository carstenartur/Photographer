#!/usr/bin/env python3
"""Reject in-repository dependencies on the retired CR3 Maven groupId."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

LEGACY_GROUP_ID = "com.photographer.focusstack"
PUBLIC_ARTIFACT_PREFIX = "canon-cr3-"
MAVEN_NAMESPACE = "http://maven.apache.org/POM/4.0.0"
NS = {"m": MAVEN_NAMESPACE}


@dataclass(frozen=True)
class LegacyDependency:
    pom: Path
    artifact_id: str


def _child_text(element: ET.Element, name: str) -> str:
    child = element.find(f"m:{name}", NS)
    return "" if child is None or child.text is None else child.text.strip()


def find_legacy_dependencies(repository_root: Path) -> list[LegacyDependency]:
    """Return CR3 dependencies that still use the retired monoreactor groupId."""
    repository_root = repository_root.resolve()
    violations: list[LegacyDependency] = []
    for pom in sorted(repository_root.rglob("pom.xml")):
        relative = pom.relative_to(repository_root)
        if "target" in relative.parts or ".git" in relative.parts:
            continue
        try:
            root = ET.parse(pom).getroot()
        except ET.ParseError as exc:
            raise RuntimeError(f"Invalid Maven POM XML: {relative}: {exc}") from exc

        for dependency in root.findall(".//m:dependency", NS):
            group_id = _child_text(dependency, "groupId")
            artifact_id = _child_text(dependency, "artifactId")
            if (
                group_id == LEGACY_GROUP_ID
                and artifact_id.startswith(PUBLIC_ARTIFACT_PREFIX)
            ):
                violations.append(LegacyDependency(relative, artifact_id))
    return violations


def validate(repository_root: Path) -> None:
    violations = find_legacy_dependencies(repository_root)
    if not violations:
        return
    formatted = "\n".join(
        f"- {violation.pom}: {LEGACY_GROUP_ID}:{violation.artifact_id}"
        for violation in violations
    )
    raise RuntimeError(
        "In-repository consumers still reference retired Canon CR3 coordinates:\n"
        f"{formatted}\n"
        "Use io.github.carstenartur for public canon-cr3-* dependencies."
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "repository_root",
        nargs="?",
        type=Path,
        default=Path.cwd(),
        help="Repository root to scan (default: current directory)",
    )
    args = parser.parse_args()
    try:
        validate(args.repository_root)
    except RuntimeError as exc:
        print(exc, file=sys.stderr)
        return 1
    print("Validated in-repository Canon CR3 dependency coordinates")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
