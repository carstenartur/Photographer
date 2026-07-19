#!/usr/bin/env python3
"""Reject in-repository dependencies on retired public CR3 Maven coordinates."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

LEGACY_GROUP_ID = "com.photographer.focusstack"
PUBLIC_GROUP_ID = "io.github.carstenartur"
PUBLIC_ARTIFACTS = frozenset(
    {
        "canon-cr3-container",
        "canon-cr3-metadata",
        "canon-cr3-codec",
        "canon-cr3-processing",
        "canon-cr3-core",
        "canon-cr3-imageio",
    }
)
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
    """Return public runtime dependencies that still use the retired groupId."""
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
            if group_id == LEGACY_GROUP_ID and artifact_id in PUBLIC_ARTIFACTS:
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
        "In-repository consumers still reference retired public Canon CR3 coordinates:\n"
        f"{formatted}\n"
        f"Use {PUBLIC_GROUP_ID} for the six public runtime artifacts."
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
    print("Validated in-repository public Canon CR3 dependency coordinates")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
