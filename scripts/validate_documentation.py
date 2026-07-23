#!/usr/bin/env python3
"""Validate repository Markdown links, canonical ownership and generated-output boundaries."""

from __future__ import annotations

import argparse
import fnmatch
import html
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable
from urllib.parse import unquote, urlsplit

FENCE = re.compile(r"^\s*(`{3,}|~{3,})")
HEADING = re.compile(r"^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$")
EXPLICIT_ANCHOR = re.compile(
    r"<(?:a|span)\s+(?:[^>]*?\s)?(?:id|name)=[\"']([^\"']+)[\"']",
    re.IGNORECASE,
)
INLINE_LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
REFERENCE_USE = re.compile(r"!?\[([^\]]+)\]\[([^\]]*)\]")
REFERENCE_DEF = re.compile(r"^\s{0,3}\[([^\]]+)\]:\s*(.+?)\s*$")
HTML_LINK = re.compile(
    r"<(?:a|img)\b[^>]*?\b(?:href|src)=[\"']([^\"']+)[\"']",
    re.IGNORECASE,
)
INLINE_CODE = re.compile(r"`+[^`]*`+")
HTML_TAG = re.compile(r"<[^>]+>")
MARKDOWN_PUNCTUATION = re.compile(r"[!\"#$%&'()*+,./:;<=>?@\[\\\]^`{|}~]")

DEFAULT_MANIFEST = Path("docs/developer/documentation-ownership.json")
DEFAULT_IGNORED_DIRS = {
    ".git",
    ".idea",
    ".vscode",
    "build",
    "coverage",
    "dist",
    "node_modules",
    "target",
}


@dataclass(frozen=True)
class Link:
    source: Path
    line: int
    target: str


@dataclass(frozen=True)
class Violation:
    path: Path
    line: int | None
    detail: str

    def format(self) -> str:
        suffix = f":{self.line}" if self.line is not None else ""
        return f"{self.path.as_posix()}{suffix}: {self.detail}"


class DocumentationValidationError(RuntimeError):
    """Raised when documentation ownership or links violate the repository contract."""


def _load_manifest(root: Path, manifest_path: Path) -> dict:
    path = root / manifest_path
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise DocumentationValidationError(
            f"Missing documentation manifest: {manifest_path}"
        ) from exc
    except json.JSONDecodeError as exc:
        raise DocumentationValidationError(
            f"Invalid documentation manifest {manifest_path}: {exc}"
        ) from exc
    if data.get("schemaVersion") != 1:
        raise DocumentationValidationError(
            "Unsupported documentation manifest schemaVersion: "
            f"{data.get('schemaVersion')!r}"
        )
    return data


def _tracked_files(root: Path) -> list[Path]:
    try:
        completed = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z"],
            check=True,
            capture_output=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return sorted(
            path.relative_to(root)
            for path in root.rglob("*")
            if path.is_file() and ".git" not in path.relative_to(root).parts
        )
    return [
        Path(item.decode("utf-8"))
        for item in completed.stdout.split(b"\0")
        if item
    ]


def _markdown_files(root: Path, tracked: Iterable[Path]) -> list[Path]:
    return sorted(
        path
        for path in tracked
        if path.suffix.lower() == ".md"
        and not any(part in DEFAULT_IGNORED_DIRS for part in path.parts)
        and (root / path).is_file()
    )


def _strip_code_and_comments(lines: Iterable[str]) -> list[tuple[int, str]]:
    result: list[tuple[int, str]] = []
    fence: str | None = None
    in_comment = False
    for number, original in enumerate(lines, 1):
        line = original.rstrip("\n")
        fence_match = FENCE.match(line)
        if fence_match:
            marker = fence_match.group(1)
            if fence is None:
                fence = marker[0]
            elif marker[0] == fence:
                fence = None
            continue
        if fence is not None:
            continue

        visible = line
        while True:
            if in_comment:
                end = visible.find("-->")
                if end < 0:
                    visible = ""
                    break
                visible = visible[end + 3 :]
                in_comment = False
            start = visible.find("<!--")
            if start < 0:
                break
            end = visible.find("-->", start + 4)
            if end < 0:
                visible = visible[:start]
                in_comment = True
                break
            visible = visible[:start] + visible[end + 3 :]
        result.append((number, INLINE_CODE.sub("", visible)))
    return result


def _destination(raw: str) -> str:
    value = raw.strip()
    if value.startswith("<") and ">" in value:
        return value[1 : value.index(">")].strip()
    # Optional Markdown link titles follow the destination after whitespace.
    return value.split(maxsplit=1)[0] if value else ""


def parse_links(path: Path, text: str) -> list[Link]:
    visible = _strip_code_and_comments(text.splitlines(keepends=True))
    references: dict[str, str] = {}
    for _, line in visible:
        match = REFERENCE_DEF.match(line)
        if match:
            references[match.group(1).strip().casefold()] = _destination(
                match.group(2)
            )

    links: list[Link] = []
    for number, line in visible:
        if REFERENCE_DEF.match(line):
            continue
        for match in INLINE_LINK.finditer(line):
            target = _destination(match.group(1))
            if target:
                links.append(Link(path, number, target))
        for match in REFERENCE_USE.finditer(line):
            label = (match.group(2) or match.group(1)).strip().casefold()
            target = references.get(label)
            if target:
                links.append(Link(path, number, target))
        for match in HTML_LINK.finditer(line):
            target = html.unescape(match.group(1).strip())
            if target:
                links.append(Link(path, number, target))
    return links


def _slug(text: str) -> str:
    value = html.unescape(HTML_TAG.sub("", text))
    value = INLINE_CODE.sub("", value)
    value = re.sub(r"[*_~]", "", value).strip().casefold()
    value = MARKDOWN_PUNCTUATION.sub("", value)
    value = re.sub(r"\s+", "-", value)
    value = re.sub(r"-+", "-", value)
    return value.strip("-")


def anchors(text: str) -> set[str]:
    result: set[str] = set()
    counts: dict[str, int] = {}
    for _, line in _strip_code_and_comments(text.splitlines(keepends=True)):
        for explicit in EXPLICIT_ANCHOR.findall(line):
            result.add(unquote(explicit))
        match = HEADING.match(line)
        if not match:
            continue
        base = _slug(match.group(2))
        if not base:
            continue
        count = counts.get(base, 0)
        counts[base] = count + 1
        result.add(base if count == 0 else f"{base}-{count}")
    return result


def _is_ignored_target(target: str, manifest: dict) -> bool:
    value = target.strip()
    if not value or value.startswith("//"):
        return True
    if urlsplit(value).scheme:
        return True
    return any(
        value.startswith(prefix)
        for prefix in manifest.get("ignoredLinkPrefixes", [])
    )


def _resolve_target(root: Path, link: Link) -> tuple[Path, str | None] | None:
    split = urlsplit(link.target)
    raw_path = unquote(split.path)
    fragment = unquote(split.fragment) if split.fragment else None
    if not raw_path:
        return link.source, fragment
    relative = (
        Path(raw_path.lstrip("/"))
        if raw_path.startswith("/")
        else link.source.parent / raw_path
    )
    try:
        resolved = (root / relative).resolve().relative_to(root.resolve())
    except ValueError:
        return None
    return resolved, fragment


def validate_links(
    root: Path, manifest: dict, markdown_files: Iterable[Path]
) -> list[Violation]:
    violations: list[Violation] = []
    cache: dict[Path, str] = {}
    anchor_cache: dict[Path, set[str]] = {}
    for source in markdown_files:
        text = (root / source).read_text(encoding="utf-8")
        cache[source] = text
        for link in parse_links(source, text):
            if _is_ignored_target(link.target, manifest):
                continue
            resolved = _resolve_target(root, link)
            if resolved is None:
                violations.append(
                    Violation(
                        source,
                        link.line,
                        f"link escapes repository root: {link.target}",
                    )
                )
                continue
            target, fragment = resolved
            absolute = root / target
            if not absolute.exists():
                violations.append(
                    Violation(
                        source,
                        link.line,
                        f"missing internal link target: {link.target}",
                    )
                )
                continue
            if fragment and absolute.is_file() and target.suffix.lower() == ".md":
                target_text = cache.get(target)
                if target_text is None:
                    target_text = absolute.read_text(encoding="utf-8")
                    cache[target] = target_text
                available = anchor_cache.setdefault(target, anchors(target_text))
                if fragment not in available and fragment.casefold() not in available:
                    violations.append(
                        Violation(
                            source,
                            link.line,
                            f"missing Markdown anchor #{fragment} in "
                            f"{target.as_posix()}",
                        )
                    )
    return violations


def _normalized_link_targets(
    root: Path, source: Path, manifest: dict
) -> set[tuple[Path, str | None]]:
    text = (root / source).read_text(encoding="utf-8")
    values: set[tuple[Path, str | None]] = set()
    for link in parse_links(source, text):
        if _is_ignored_target(link.target, manifest):
            continue
        resolved = _resolve_target(root, link)
        if resolved is not None:
            values.add(resolved)
    return values


def validate_manifest(root: Path, manifest: dict) -> list[Violation]:
    violations: list[Violation] = []
    seen: dict[str, Path] = {}
    generated_prefixes = tuple(
        Path(value) for value in manifest.get("generatedDocumentPrefixes", [])
    )
    for topic in manifest.get("topics", []):
        identifier = str(topic.get("id", "")).strip()
        owner = Path(str(topic.get("owner", "")))
        if not identifier:
            violations.append(Violation(DEFAULT_MANIFEST, None, "topic without id"))
            continue
        if identifier in seen:
            violations.append(
                Violation(
                    DEFAULT_MANIFEST,
                    None,
                    f"canonical topic {identifier!r} has multiple owners: "
                    f"{seen[identifier]} and {owner}",
                )
            )
            continue
        seen[identifier] = owner
        if not (root / owner).is_file():
            violations.append(
                Violation(
                    DEFAULT_MANIFEST,
                    None,
                    f"owner for topic {identifier!r} does not exist: {owner}",
                )
            )
        if any(
            owner == prefix or prefix in owner.parents
            for prefix in generated_prefixes
        ):
            violations.append(
                Violation(
                    DEFAULT_MANIFEST,
                    None,
                    f"generated document cannot own canonical topic "
                    f"{identifier!r}: {owner}",
                )
            )

    for redirect in manifest.get("redirects", []):
        source = Path(redirect["source"])
        if not (root / source).is_file():
            violations.append(
                Violation(
                    DEFAULT_MANIFEST,
                    None,
                    f"redirect source does not exist: {source}",
                )
            )
            continue
        actual = _normalized_link_targets(root, source, manifest)
        for value in redirect.get("targets", []):
            expected = _resolve_target(root, Link(source, 0, value))
            if expected is None or not (root / expected[0]).exists():
                violations.append(
                    Violation(
                        DEFAULT_MANIFEST,
                        None,
                        f"redirect target does not exist: {source} -> {value}",
                    )
                )
            elif expected not in actual:
                violations.append(
                    Violation(
                        source,
                        None,
                        f"redirect does not link to declared canonical target: "
                        f"{value}",
                    )
                )

    for requirement in manifest.get("moduleManualLinks", []):
        readme = Path(requirement["readme"])
        if not (root / readme).is_file():
            violations.append(
                Violation(
                    DEFAULT_MANIFEST,
                    None,
                    f"required module README does not exist: {readme}",
                )
            )
            continue
        actual = _normalized_link_targets(root, readme, manifest)
        for value in requirement.get("targets", []):
            expected = _resolve_target(root, Link(readme, 0, value))
            if expected is None or expected not in actual:
                violations.append(
                    Violation(
                        readme,
                        None,
                        f"missing required canonical-manual link: {value}",
                    )
                )
    return violations


def validate_generated_outputs(
    tracked: Iterable[Path], manifest: dict
) -> list[Violation]:
    patterns = manifest.get("forbiddenTrackedPatterns", [])
    return [
        Violation(path, None, "generated build/report output must not be committed")
        for path in tracked
        if any(fnmatch.fnmatch(path.as_posix(), pattern) for pattern in patterns)
    ]


def validate(root: Path, manifest_path: Path = DEFAULT_MANIFEST) -> None:
    root = root.resolve()
    manifest = _load_manifest(root, manifest_path)
    tracked = _tracked_files(root)
    violations = (
        validate_links(root, manifest, _markdown_files(root, tracked))
        + validate_manifest(root, manifest)
        + validate_generated_outputs(tracked, manifest)
    )
    if violations:
        formatted = "\n".join(
            f"- {violation.format()}"
            for violation in sorted(
                violations,
                key=lambda item: (
                    item.path.as_posix(),
                    item.line or 0,
                    item.detail,
                ),
            )
        )
        raise DocumentationValidationError(
            "Documentation validation failed:\n"
            f"{formatted}\n"
            "Fix the source link/ownership declaration; do not hide failures "
            "with broad exclusions."
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "repository_root", nargs="?", type=Path, default=Path.cwd()
    )
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    args = parser.parse_args()
    try:
        validate(args.repository_root, args.manifest)
    except DocumentationValidationError as exc:
        print(exc, file=sys.stderr)
        return 1
    print(
        "Validated Markdown links, canonical documentation ownership and "
        "generated-output boundaries"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
