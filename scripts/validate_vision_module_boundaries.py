#!/usr/bin/env python3
"""Validate neutral vision-contract ownership and forbidden Maven dependency edges."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

MAVEN_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PROJECT_GROUP = "com.photographer.focusstack"

FORBIDDEN_EDGES = {
    ("plugin-api", "ai-vision"),
    ("plugin-api", "observer-core"),
    ("ai-vision", "observer-core"),
}

CONTRACT_FILES = (
    Path("com/photographer/focusstack/ai/VisionProvider.java"),
    Path("com/photographer/focusstack/ai/VisionContext.java"),
    Path("com/photographer/focusstack/ai/VisionResult.java"),
    Path("com/photographer/focusstack/ai/ProviderCapabilities.java"),
    Path("com/photographer/focusstack/observer/events/BoundingBox.java"),
)
COMMON_MAIN = Path("common-api/src/main/java")
OTHER_MAIN_ROOTS = (
    Path("plugin-api/src/main/java"),
    Path("ai-vision/src/main/java"),
    Path("observer-core/src/main/java"),
)


@dataclass(frozen=True)
class BoundaryViolation:
    path: Path
    detail: str


class BoundaryValidationError(RuntimeError):
    """Raised when neutral vision contracts cross the intended module boundary."""


def _text(element: ET.Element, name: str) -> str:
    child = element.find(f"m:{name}", MAVEN_NS)
    return "" if child is None or child.text is None else child.text.strip()


def _artifact_id(project: ET.Element, pom: Path) -> str:
    artifact_id = _text(project, "artifactId")
    if not artifact_id:
        raise BoundaryValidationError(f"Missing artifactId in {pom}")
    return artifact_id


def find_pom_violations(root: Path) -> list[BoundaryViolation]:
    violations: list[BoundaryViolation] = []
    for module in ("plugin-api", "ai-vision"):
        pom = root / module / "pom.xml"
        if not pom.is_file():
            violations.append(BoundaryViolation(
                pom.relative_to(root), "required module POM is missing"))
            continue
        try:
            project = ET.parse(pom).getroot()
        except ET.ParseError as exc:
            raise BoundaryValidationError(f"Invalid Maven POM XML: {pom}: {exc}") from exc
        owner = _artifact_id(project, pom)
        for dependency in project.findall(".//m:dependency", MAVEN_NS):
            group_id = _text(dependency, "groupId")
            artifact_id = _text(dependency, "artifactId")
            if group_id == PROJECT_GROUP and (owner, artifact_id) in FORBIDDEN_EDGES:
                violations.append(BoundaryViolation(
                    pom.relative_to(root),
                    f"forbidden dependency edge {owner} -> {artifact_id}",
                ))
    return violations


def find_contract_ownership_violations(root: Path) -> list[BoundaryViolation]:
    violations: list[BoundaryViolation] = []
    for relative in CONTRACT_FILES:
        canonical = root / COMMON_MAIN / relative
        if not canonical.is_file():
            violations.append(BoundaryViolation(
                canonical.relative_to(root), "neutral vision contract is not owned by common-api"))
        for source_root in OTHER_MAIN_ROOTS:
            duplicate = root / source_root / relative
            if duplicate.exists():
                violations.append(BoundaryViolation(
                    duplicate.relative_to(root),
                    "neutral vision contract is duplicated outside common-api",
                ))
    return violations


def validate(root: Path) -> None:
    root = root.resolve()
    violations = find_pom_violations(root) + find_contract_ownership_violations(root)
    if not violations:
        return
    formatted = "\n".join(
        f"- {violation.path}: {violation.detail}" for violation in violations)
    raise BoundaryValidationError(
        "Vision module boundary violations:\n"
        f"{formatted}\n"
        "Keep neutral vision contracts in common-api, provider implementations in "
        "ai-vision, plugin SPI in plugin-api and observer-domain logic in observer-core."
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
    except BoundaryValidationError as exc:
        print(exc, file=sys.stderr)
        return 1
    print("Validated vision contract ownership and module dependency direction")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
