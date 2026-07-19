#!/usr/bin/env python3
"""Validate that CCAPI transport and credential internals do not leak across modules."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

MAVEN_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
APACHE_PREFIX = "org.apache.hc."
HTTPCLIENT_GROUP_PREFIX = "org.apache.httpcomponents"

ALLOWED_APACHE_MAIN_FILES = {
    Path("app/src/main/java/com/photographer/focusstack/camera/backend/CcapiRestTemplateFactory.java"),
    Path("app/src/main/java/com/photographer/focusstack/camera/backend/CcapiTransport.java"),
}
ALLOWED_CREDENTIAL_MAIN_FILES = {
    Path("app/src/main/java/com/photographer/focusstack/FocusStackApplication.java"),
    Path("app/src/main/java/com/photographer/focusstack/app/config/CcapiAuthenticationProperties.java"),
    Path("app/src/main/java/com/photographer/focusstack/camera/backend/CameraBackendFactory.java"),
    Path("app/src/main/java/com/photographer/focusstack/camera/backend/CcapiRestTemplateFactory.java"),
}
ALLOWED_PASSWORD_COPY_CALLERS = {
    Path("app/src/main/java/com/photographer/focusstack/app/config/CcapiAuthenticationProperties.java"),
    Path("app/src/main/java/com/photographer/focusstack/camera/backend/CcapiRestTemplateFactory.java"),
}

CREDENTIAL_TYPE_TOKENS = (
    "CcapiAuthenticationProperties",
    "ResolvedCredentials",
    "CredentialSnapshot",
    "UsernamePasswordCredentials",
    "BasicCredentialsProvider",
    "AuthScope",
)
PASSWORD_ACCESS_PATTERNS = (
    re.compile(r"\.passwordCopy\s*\("),
    re.compile(r"\.getPassword\s*\("),
)
LOGGER_START = re.compile(r"\b(?:log|LOG|LOGGER)\s*\.\s*(?:trace|debug|info|warn|error)\s*\(")
PROTECTED_LOG_TOKEN = re.compile(
    r"(?i)authorization|password|nonce|opaque|wifi\s*password|wi-fi\s*password|request\s*body"
)


@dataclass(frozen=True)
class BoundaryViolation:
    path: Path
    detail: str


class BoundaryValidationError(RuntimeError):
    """Raised when a source or dependency crosses the CCAPI security boundary."""


def _text(element: ET.Element, name: str) -> str:
    child = element.find(f"m:{name}", MAVEN_NS)
    return "" if child is None or child.text is None else child.text.strip()


def _is_generated(path: Path) -> bool:
    return any(part in {"target", ".git", "node_modules"} for part in path.parts)


def _main_java_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for path in root.rglob("*.java"):
        relative = path.relative_to(root)
        if _is_generated(relative) or "/src/main/java/" not in f"/{relative.as_posix()}":
            continue
        files.append(path)
    return sorted(files)


def _logger_invocations(source: str) -> list[str]:
    invocations: list[str] = []
    for match in LOGGER_START.finditer(source):
        start = match.start()
        index = match.end()
        depth = 1
        in_string = False
        escaped = False
        while index < len(source) and depth > 0:
            current = source[index]
            if in_string:
                if escaped:
                    escaped = False
                elif current == "\\":
                    escaped = True
                elif current == '"':
                    in_string = False
            else:
                if current == '"':
                    in_string = True
                elif current == "(":
                    depth += 1
                elif current == ")":
                    depth -= 1
            index += 1
        invocations.append(source[start:index])
    return invocations


def find_pom_violations(root: Path) -> list[BoundaryViolation]:
    violations: list[BoundaryViolation] = []
    for pom in sorted(root.rglob("pom.xml")):
        relative = pom.relative_to(root)
        if _is_generated(relative):
            continue
        try:
            project = ET.parse(pom).getroot()
        except ET.ParseError as exc:
            raise BoundaryValidationError(f"Invalid Maven POM XML: {relative}: {exc}") from exc
        for dependency in project.findall(".//m:dependency", MAVEN_NS):
            group_id = _text(dependency, "groupId")
            artifact_id = _text(dependency, "artifactId")
            if group_id.startswith(HTTPCLIENT_GROUP_PREFIX) and relative != Path("app/pom.xml"):
                violations.append(BoundaryViolation(
                    relative,
                    f"Apache HTTP dependency outside app: {group_id}:{artifact_id}",
                ))
    return violations


def find_source_violations(root: Path) -> list[BoundaryViolation]:
    violations: list[BoundaryViolation] = []
    for java_file in _main_java_files(root):
        relative = java_file.relative_to(root)
        source = java_file.read_text(encoding="utf-8")

        if APACHE_PREFIX in source and relative not in ALLOWED_APACHE_MAIN_FILES:
            violations.append(BoundaryViolation(
                relative,
                "Apache HttpClient type outside the two transport implementation files",
            ))

        if (any(token in source for token in CREDENTIAL_TYPE_TOKENS)
                and relative not in ALLOWED_CREDENTIAL_MAIN_FILES
                and APACHE_PREFIX not in source):
            violations.append(BoundaryViolation(
                relative,
                "CCAPI credential type outside the explicit binding/transport ownership set",
            ))

        for pattern in PASSWORD_ACCESS_PATTERNS:
            if pattern.search(source) and relative not in ALLOWED_PASSWORD_COPY_CALLERS:
                violations.append(BoundaryViolation(
                    relative,
                    f"Password accessor outside credential binding/transport: {pattern.pattern}",
                ))

        for invocation in _logger_invocations(source):
            if PROTECTED_LOG_TOKEN.search(invocation):
                violations.append(BoundaryViolation(
                    relative,
                    "Logger invocation refers to a protected authentication/network asset",
                ))
                break

        if relative.name == "CcapiAuthenticationProperties.java":
            if "implements java.io.Serializable" in source or "implements Serializable" in source:
                violations.append(BoundaryViolation(
                    relative,
                    "Credential binding must not become Java-serializable",
                ))
            if "<redacted>" not in source:
                violations.append(BoundaryViolation(
                    relative,
                    "Credential binding string representations must remain explicitly redacted",
                ))
    return violations


def validate(root: Path) -> None:
    root = root.resolve()
    violations = find_pom_violations(root) + find_source_violations(root)
    if not violations:
        return
    formatted = "\n".join(
        f"- {violation.path}: {violation.detail}" for violation in violations
    )
    raise BoundaryValidationError(
        "CCAPI transport security boundary violations:\n"
        f"{formatted}\n"
        "Keep Apache transport and password access inside the reviewed app transport boundary."
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
    print("Validated CCAPI transport and credential dependency boundary")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
