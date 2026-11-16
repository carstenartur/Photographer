#!/usr/bin/env python3
"""Validate and explicitly update committed CR3 generated evidence."""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Iterable

DEFAULT_MANIFEST = Path("docs/cleanroom/generated-evidence.json")
DEFAULT_LEDGER = Path("docs/cleanroom/experiment-ledger.json")
CLASSIFICATIONS = {
    "managed-generated",
    "historical-generated",
    "historical-manual",
}
TIMESTAMP_POLICIES = {
    "prohibited",
    "required",
    "historical-embedded",
    "not-applicable",
}
GLOB_CHARS = set("*?[")


class EvidenceContractError(RuntimeError):
    """Raised when generated evidence violates its repository contract."""


def _load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise EvidenceContractError(f"Missing JSON file: {path}") from exc
    except json.JSONDecodeError as exc:
        raise EvidenceContractError(f"Invalid JSON in {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise EvidenceContractError(f"JSON root must be an object: {path}")
    return value


def repository_files(root: Path) -> list[Path]:
    """List tracked files or use the file tree in a source archive."""
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
    return sorted(
        Path(raw.decode("utf-8"))
        for raw in completed.stdout.split(b"\0")
        if raw
    )


def _non_blank(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _safe_relative(value: Any, context: str, errors: list[str]) -> Path | None:
    if not _non_blank(value):
        errors.append(f"{context}: path must be non-empty")
        return None
    text = value.strip()
    path = Path(text)
    if path.is_absolute() or ".." in path.parts or "://" in text:
        errors.append(f"{context}: invalid repository-relative path {text!r}")
        return None
    return path


def _required_safe_relative(value: Any, context: str) -> Path:
    errors: list[str] = []
    path = _safe_relative(value, context, errors)
    if path is None or errors:
        raise EvidenceContractError("; ".join(errors))
    return path


def _matches(pattern: str, tracked: Iterable[Path]) -> list[Path]:
    return [
        path
        for path in tracked
        if fnmatch.fnmatchcase(path.as_posix(), pattern)
    ]


def _path_or_pattern_exists(root: Path, value: str, tracked: list[Path]) -> bool:
    if any(char in value for char in GLOB_CHARS):
        return bool(_matches(value, tracked))
    return (root / value).exists()


def _below(path: Path, parent: Path) -> bool:
    return path != parent and parent in path.parents


def git_blob_sha1(path: Path) -> str:
    """Return the SHA-1 object id Git assigns to the current file bytes."""
    payload = path.read_bytes()
    header = f"blob {len(payload)}\0".encode("ascii")
    # SHA-1 is required here because this is a Git object identifier, not a
    # cryptographic trust decision. Scientific artifact integrity remains SHA-256.
    return hashlib.sha1(header + payload).hexdigest()  # noqa: S324


def _validate_entry(
    root: Path,
    tracked: list[Path],
    entry: Any,
    index: int,
    seen_ids: set[str],
    ledger_ids: set[str],
    results_root: str,
    errors: list[str],
) -> tuple[str | None, list[Path]]:
    context = f"entry[{index}]"
    if not isinstance(entry, dict):
        errors.append(f"{context}: must be an object")
        return None, []

    entry_id = entry.get("id")
    if not _non_blank(entry_id):
        errors.append(f"{context}: id must be non-empty")
        return None, []
    entry_id = entry_id.strip()
    context = entry_id
    if entry_id in seen_ids:
        errors.append(f"{context}: duplicate entry id")
    seen_ids.add(entry_id)

    classification = entry.get("classification")
    if classification not in CLASSIFICATIONS:
        errors.append(f"{context}: invalid classification {classification!r}")

    owner = _safe_relative(
        entry.get("ownerModule"), f"{context}.ownerModule", errors)
    if owner is not None and not (root / owner).exists():
        errors.append(f"{context}: ownerModule does not exist: {owner}")

    timestamp_policy = entry.get("timestampPolicy")
    if timestamp_policy not in TIMESTAMP_POLICIES:
        errors.append(f"{context}: invalid timestampPolicy {timestamp_policy!r}")

    generator = entry.get("generator")
    if classification != "historical-manual":
        generator_path = _safe_relative(
            generator, f"{context}.generator", errors)
        if generator_path is not None and not (root / generator_path).is_file():
            errors.append(f"{context}: generator does not exist: {generator_path}")
    elif generator is not None:
        generator_path = _safe_relative(
            generator, f"{context}.generator", errors)
        if generator_path is not None and not (root / generator_path).is_file():
            errors.append(f"{context}: generator does not exist: {generator_path}")

    if classification == "historical-generated" \
            and not _non_blank(entry.get("generateCommand")):
        errors.append(f"{context}: historical generated evidence requires generateCommand")
    if classification.startswith("historical") \
            and not _non_blank(entry.get("rationale")):
        errors.append(f"{context}: historical evidence requires a rationale")

    inputs = entry.get("inputs")
    if not isinstance(inputs, list) or not inputs:
        errors.append(f"{context}: inputs must be a non-empty array")
    else:
        for input_index, value in enumerate(inputs):
            path = _safe_relative(
                value, f"{context}.inputs[{input_index}]", errors)
            if path is not None:
                normalized = path.as_posix()
                if not _path_or_pattern_exists(root, normalized, tracked):
                    errors.append(
                        f"{context}: input does not exist or match: {normalized}")

    experiment = entry.get("ledgerExperiment")
    if experiment is not None and experiment not in ledger_ids:
        errors.append(f"{context}: unknown ledger experiment {experiment!r}")

    outputs = entry.get("outputs")
    matched: list[Path] = []
    if not isinstance(outputs, list) or not outputs:
        errors.append(f"{context}: outputs must be a non-empty array")
    else:
        for output_index, value in enumerate(outputs):
            path = _safe_relative(
                value, f"{context}.outputs[{output_index}]", errors)
            if path is None:
                continue
            pattern = path.as_posix()
            if not (pattern == results_root
                    or pattern.startswith(results_root + "/")):
                errors.append(
                    f"{context}: output must be below {results_root}: {pattern}")
                continue
            if pattern == results_root + "/**":
                errors.append(
                    f"{context}: blanket result-root coverage is prohibited")
                continue
            values = _matches(pattern, tracked)
            if not values:
                errors.append(
                    f"{context}: output pattern matches no tracked files: {pattern}")
            matched.extend(values)

    mappings = entry.get("generatedMappings", [])
    locks = entry.get("gitBlobLocks", {})
    if classification == "managed-generated":
        if not _non_blank(entry.get("generateCommand")):
            errors.append(f"{context}: managed evidence requires generateCommand")
        if not isinstance(mappings, list) or not mappings:
            errors.append(f"{context}: managed evidence requires generatedMappings")
        if not isinstance(locks, dict):
            errors.append(f"{context}: gitBlobLocks must be an object")
            locks = {}
    elif mappings:
        errors.append(
            f"{context}: only managed-generated entries may define generatedMappings")
    if classification != "managed-generated" and locks:
        errors.append(
            f"{context}: only managed-generated entries may define gitBlobLocks")

    output_set = set(matched)
    mapped_destinations: set[str] = set()
    if isinstance(mappings, list):
        for mapping_index, mapping in enumerate(mappings):
            mapping_context = f"{context}.generatedMappings[{mapping_index}]"
            if not isinstance(mapping, dict):
                errors.append(f"{mapping_context}: must be an object")
                continue
            source = _safe_relative(
                mapping.get("source"), mapping_context + ".source", errors)
            destination = _safe_relative(
                mapping.get("destination"),
                mapping_context + ".destination",
                errors)
            if source is None or destination is None:
                continue
            destination_text = destination.as_posix()
            if destination_text in mapped_destinations:
                errors.append(
                    f"{mapping_context}: duplicate destination {destination}")
            mapped_destinations.add(destination_text)
            if destination not in output_set:
                errors.append(
                    f"{mapping_context}: destination is not covered by outputs: "
                    f"{destination}")
            expected = locks.get(destination_text) \
                if isinstance(locks, dict) else None
            if not isinstance(expected, str) or len(expected) != 40:
                errors.append(
                    f"{mapping_context}: destination requires a 40-character "
                    "Git blob lock")
            elif (root / destination).is_file():
                actual = git_blob_sha1(root / destination)
                if actual != expected:
                    errors.append(
                        f"{context}: stale Git blob lock for {destination}: "
                        f"expected {expected}, actual {actual}")

    if classification == "managed-generated" and isinstance(locks, dict):
        lock_destinations = set(locks)
        if lock_destinations != mapped_destinations:
            missing = sorted(mapped_destinations - lock_destinations)
            extra = sorted(lock_destinations - mapped_destinations)
            if missing:
                errors.append(
                    f"{context}: missing locks for mapped destinations: {missing}")
            if extra:
                errors.append(
                    f"{context}: locks without generated mappings: {extra}")

    return entry_id, sorted(set(matched))


def validate_inventory(
    root: Path,
    manifest_path: Path = DEFAULT_MANIFEST,
) -> tuple[list[str], list[Path]]:
    """Return validation errors and the registered repository-relative outputs."""
    root = root.resolve()
    errors: list[str] = []
    try:
        manifest = _load_json(root / manifest_path)
    except EvidenceContractError as exc:
        return [str(exc)], []

    if manifest.get("schemaVersion") != 1:
        errors.append("generated-evidence.json: schemaVersion must be 1")

    results_root_value = manifest.get("resultsRoot")
    results_root_path = _safe_relative(
        results_root_value,
        "generated-evidence.json.resultsRoot",
        errors)
    results_root = results_root_path.as_posix() \
        if results_root_path else "docs/cr3-results"
    if results_root_path is not None and not (root / results_root_path).is_dir():
        errors.append(
            f"generated-evidence.json: resultsRoot does not exist: {results_root}")

    tracked = repository_files(root)
    try:
        ledger = _load_json(root / DEFAULT_LEDGER)
    except EvidenceContractError as exc:
        errors.append(str(exc))
        ledger = {}
    ledger_ids = {
        item.get("id")
        for item in ledger.get("experiments", [])
        if isinstance(item, dict) and _non_blank(item.get("id"))
    }

    entries = manifest.get("entries")
    if not isinstance(entries, list) or not entries:
        errors.append("generated-evidence.json: entries must be a non-empty array")
        entries = []

    coverage: dict[Path, list[str]] = {}
    seen_ids: set[str] = set()
    for index, entry in enumerate(entries):
        entry_id, matched = _validate_entry(
            root,
            tracked,
            entry,
            index,
            seen_ids,
            ledger_ids,
            results_root,
            errors)
        if entry_id is not None:
            for path in matched:
                coverage.setdefault(path, []).append(entry_id)

    result_files = [
        path
        for path in tracked
        if path.as_posix().startswith(results_root + "/")
    ]
    for path in result_files:
        owners = coverage.get(path, [])
        if not owners:
            errors.append(f"unregistered generated evidence: {path}")
        elif len(owners) > 1:
            errors.append(
                f"generated evidence has multiple owners: {path}: {owners}")

    return errors, sorted(coverage)


def validate_repository(
    root: Path,
    manifest_path: Path = DEFAULT_MANIFEST,
) -> list[str]:
    errors, _ = validate_inventory(root, manifest_path)
    return errors


def _entry(manifest: dict[str, Any], entry_id: str) -> dict[str, Any]:
    entries = manifest.get("entries", [])
    if not isinstance(entries, list):
        raise EvidenceContractError("generated-evidence entries must be an array")
    for entry in entries:
        if isinstance(entry, dict) and entry.get("id") == entry_id:
            return entry
    raise EvidenceContractError(f"Unknown generated-evidence entry: {entry_id}")


def _mapping_paths(
    root: Path,
    manifest: dict[str, Any],
    entry: dict[str, Any],
    source_root: Path,
) -> list[tuple[Path, Path]]:
    entry_id = entry.get("id", "<unknown>")
    if entry.get("classification") != "managed-generated":
        raise EvidenceContractError(
            f"Entry {entry_id} is not managed-generated and cannot be compared/updated")

    results_root = _required_safe_relative(
        manifest.get("resultsRoot"),
        "generated-evidence.json.resultsRoot")
    mappings = entry.get("generatedMappings")
    if not isinstance(mappings, list) or not mappings:
        raise EvidenceContractError(
            f"Entry {entry_id} requires a non-empty generatedMappings array")

    source_root = source_root.resolve()
    values: list[tuple[Path, Path]] = []
    destinations: set[Path] = set()
    outputs = entry.get("outputs")
    if not isinstance(outputs, list) or not outputs:
        raise EvidenceContractError(
            f"Entry {entry_id} requires a non-empty outputs array")
    output_patterns = [
        _required_safe_relative(
            value,
            f"Entry {entry_id}.outputs[{index}]").as_posix()
        for index, value in enumerate(outputs)
    ]

    for index, mapping in enumerate(mappings):
        context = f"Entry {entry_id}.generatedMappings[{index}]"
        if not isinstance(mapping, dict):
            raise EvidenceContractError(f"{context}: must be an object")
        source_relative = _required_safe_relative(
            mapping.get("source"), context + ".source")
        destination = _required_safe_relative(
            mapping.get("destination"), context + ".destination")
        if not _below(destination, results_root):
            raise EvidenceContractError(
                f"{context}: destination must be below {results_root}: "
                f"{destination}")
        if not any(
            fnmatch.fnmatchcase(destination.as_posix(), pattern)
            for pattern in output_patterns
        ):
            raise EvidenceContractError(
                f"{context}: destination is not covered by declared outputs: "
                f"{destination}")
        if destination in destinations:
            raise EvidenceContractError(
                f"{context}: duplicate destination {destination}")
        destinations.add(destination)

        source = source_root / source_relative
        try:
            source.resolve().relative_to(source_root)
        except ValueError as exc:
            raise EvidenceContractError(
                f"{context}: generated source escapes source root: {source_relative}") \
                from exc
        if not source.is_file():
            raise EvidenceContractError(
                f"Generated source does not exist: {source}")
        values.append((source, destination))
    return values


def compare_generated(root: Path, entry_id: str, source_root: Path) -> None:
    root = root.resolve()
    manifest = _load_json(root / DEFAULT_MANIFEST)
    entry = _entry(manifest, entry_id)
    differences: list[str] = []
    for source, destination in _mapping_paths(
            root, manifest, entry, source_root):
        committed = root / destination
        if not committed.is_file():
            differences.append(f"missing committed output: {destination}")
        elif source.read_bytes() != committed.read_bytes():
            differences.append(
                f"generated output differs: {source} -> {destination}")
    if differences:
        raise EvidenceContractError(
            "Generated-evidence comparison failed:\n- "
            + "\n- ".join(differences))


def update_generated(root: Path, entry_id: str, source_root: Path) -> None:
    root = root.resolve()
    manifest_file = root / DEFAULT_MANIFEST
    manifest = _load_json(manifest_file)
    entry = _entry(manifest, entry_id)
    mappings = _mapping_paths(root, manifest, entry, source_root)
    locks: dict[str, str] = {}
    for source, destination in mappings:
        target = root / destination
        try:
            target.resolve().relative_to(root)
        except ValueError as exc:
            raise EvidenceContractError(
                f"Committed output escapes repository root: {destination}") from exc
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        locks[destination.as_posix()] = git_blob_sha1(target)
    entry["gitBlobLocks"] = locks
    manifest_file.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    subparsers = parser.add_subparsers(dest="command")
    subparsers.add_parser("verify")
    for name in ("compare", "update"):
        child = subparsers.add_parser(name)
        child.add_argument("entry_id")
        child.add_argument("--source-root", type=Path, required=True)
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        if args.command in (None, "verify"):
            errors = validate_repository(root)
            if errors:
                raise EvidenceContractError(
                    "CR3 generated-evidence validation failed:\n- "
                    + "\n- ".join(errors))
        elif args.command == "compare":
            compare_generated(root, args.entry_id, args.source_root)
        else:
            update_generated(root, args.entry_id, args.source_root)
    except EvidenceContractError as exc:
        print(exc, file=sys.stderr)
        return 1
    print(f"CR3 generated-evidence {args.command or 'verify'} passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
