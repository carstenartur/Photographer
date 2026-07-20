#!/usr/bin/env python3
"""Validate the CR3 clean-room source register and experiment ledger."""

from __future__ import annotations

import json
import re
import sys
from datetime import date
from pathlib import Path
from typing import Any

SOURCE_ID = re.compile(r"SRC-\d{3}$")
EXPERIMENT_ID = re.compile(r"[A-Z][A-Z0-9-]*\d+$")
COMMIT_SHA = re.compile(r"[0-9a-f]{40}$")
ARTIFACT_DIGEST = re.compile(r"sha256:[0-9a-f]{64}$")
SOURCE_CLASSES = {
    "public-specification",
    "first-party-observation",
    "black-box-reference",
    "project-analysis",
}
EXPERIMENT_STATUSES = {"planned", "running", "complete"}


def load_json(path: Path, errors: list[str]) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        errors.append(f"missing manifest: {path}")
        return {}
    except json.JSONDecodeError as exc:
        errors.append(f"invalid JSON in {path}: {exc}")
        return {}
    if not isinstance(value, dict):
        errors.append(f"manifest root must be an object: {path}")
        return {}
    return value


def non_blank(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def validate_repo_path(root: Path, value: Any, context: str,
                       errors: list[str]) -> None:
    if not non_blank(value):
        errors.append(f"{context}: repository path must be non-empty")
        return
    path_text = value.strip()
    if "://" in path_text or Path(path_text).is_absolute() or ".." in Path(path_text).parts:
        errors.append(f"{context}: invalid repository-relative path {path_text!r}")
        return
    if not (root / path_text).exists():
        errors.append(f"{context}: path does not exist: {path_text}")


def validate_date(value: Any, context: str, errors: list[str]) -> None:
    if not non_blank(value):
        errors.append(f"{context}: verifiedDate must be non-empty")
        return
    try:
        date.fromisoformat(value)
    except ValueError:
        errors.append(f"{context}: invalid ISO date {value!r}")


def validate_sources(root: Path, data: dict[str, Any],
                     errors: list[str]) -> set[str]:
    if data.get("schemaVersion") != 1:
        errors.append("source-register.json: schemaVersion must be 1")
    values = data.get("sources")
    if not isinstance(values, list) or not values:
        errors.append("source-register.json: sources must be a non-empty array")
        return set()

    source_ids: set[str] = set()
    for index, source in enumerate(values):
        context = f"source[{index}]"
        if not isinstance(source, dict):
            errors.append(f"{context}: must be an object")
            continue
        source_id = source.get("id")
        if not isinstance(source_id, str) or not SOURCE_ID.fullmatch(source_id):
            errors.append(f"{context}: invalid source id {source_id!r}")
            continue
        if source_id in source_ids:
            errors.append(f"{context}: duplicate source id {source_id}")
        source_ids.add(source_id)
        context = source_id

        if source.get("class") not in SOURCE_CLASSES:
            errors.append(f"{context}: unsupported class {source.get('class')!r}")
        for field in ("title", "citation", "permittedUse", "prohibitedUse"):
            if not non_blank(source.get(field)):
                errors.append(f"{context}: {field} must be non-empty")
        validate_date(source.get("verifiedDate"), context, errors)

        evidence = source.get("repositoryEvidence")
        if not isinstance(evidence, list) or not evidence:
            errors.append(f"{context}: repositoryEvidence must be non-empty")
        else:
            for evidence_index, path in enumerate(evidence):
                validate_repo_path(root, path,
                                   f"{context}.repositoryEvidence[{evidence_index}]",
                                   errors)

        integrity = source.get("integrity")
        if source.get("class") == "public-specification":
            if integrity is not None and not isinstance(integrity, dict):
                errors.append(f"{context}: integrity must be null or an object")
        elif not isinstance(integrity, dict):
            errors.append(f"{context}: non-public source requires integrity metadata")
        if isinstance(integrity, dict):
            for field in ("algorithm", "digest"):
                if not non_blank(integrity.get(field)):
                    errors.append(f"{context}: integrity.{field} must be non-empty")
    return source_ids


def validate_experiments(root: Path, data: dict[str, Any],
                         source_ids: set[str], errors: list[str]) -> None:
    if data.get("schemaVersion") != 1:
        errors.append("experiment-ledger.json: schemaVersion must be 1")
    values = data.get("experiments")
    if not isinstance(values, list) or not values:
        errors.append("experiment-ledger.json: experiments must be a non-empty array")
        return

    experiment_ids: set[str] = set()
    for index, experiment in enumerate(values):
        context = f"experiment[{index}]"
        if not isinstance(experiment, dict):
            errors.append(f"{context}: must be an object")
            continue
        experiment_id = experiment.get("id")
        if not isinstance(experiment_id, str) or not EXPERIMENT_ID.fullmatch(experiment_id):
            errors.append(f"{context}: invalid experiment id {experiment_id!r}")
            continue
        if experiment_id in experiment_ids:
            errors.append(f"{context}: duplicate experiment id {experiment_id}")
        experiment_ids.add(experiment_id)
        context = experiment_id

        if not isinstance(experiment.get("issue"), int) or experiment["issue"] <= 0:
            errors.append(f"{context}: issue must be a positive integer")
        for field in ("title", "oneChangedVariable", "fixedInputs",
                      "implementationScope", "verdict", "productionDecision"):
            if not non_blank(experiment.get(field)):
                errors.append(f"{context}: {field} must be non-empty")

        status = experiment.get("status")
        if status not in EXPERIMENT_STATUSES:
            errors.append(f"{context}: invalid status {status!r}")

        references = experiment.get("sourceIds")
        if not isinstance(references, list) or not references:
            errors.append(f"{context}: sourceIds must be non-empty")
        else:
            unknown = sorted({source for source in references if source not in source_ids})
            if unknown:
                errors.append(f"{context}: unknown source IDs: {unknown}")

        commit = experiment.get("commit")
        workflow_run = experiment.get("workflowRun")
        digest = experiment.get("artifactDigest")
        if commit is not None and (not isinstance(commit, str) or not COMMIT_SHA.fullmatch(commit)):
            errors.append(f"{context}: invalid commit SHA {commit!r}")
        if workflow_run is not None and (not isinstance(workflow_run, int) or workflow_run <= 0):
            errors.append(f"{context}: workflowRun must be a positive integer or null")
        if digest is not None and (not isinstance(digest, str)
                                   or not ARTIFACT_DIGEST.fullmatch(digest)):
            errors.append(f"{context}: invalid artifact digest {digest!r}")

        report = experiment.get("durableReport")
        if report is not None:
            validate_repo_path(root, report, f"{context}.durableReport", errors)
            if isinstance(report, str) and not report.startswith("docs/cr3-results/"):
                errors.append(f"{context}: durableReport must be under docs/cr3-results/")

        if status == "complete":
            if report is None:
                errors.append(f"{context}: complete experiment requires durableReport")
            modern_metadata = commit is not None and workflow_run is not None and digest is not None
            if not modern_metadata and not non_blank(experiment.get("missingRunMetadataReason")):
                errors.append(
                    f"{context}: complete experiment requires commit, workflowRun and "
                    "artifactDigest, or missingRunMetadataReason"
                )
            if experiment.get("verdict") == "PENDING":
                errors.append(f"{context}: complete experiment cannot have PENDING verdict")


def validate_repository(root: Path) -> list[str]:
    errors: list[str] = []
    cleanroom = root / "docs" / "cleanroom"
    sources = load_json(cleanroom / "source-register.json", errors)
    ledger = load_json(cleanroom / "experiment-ledger.json", errors)
    source_ids = validate_sources(root, sources, errors)
    validate_experiments(root, ledger, source_ids, errors)
    return errors


def main(argv: list[str]) -> int:
    root = Path(argv[1]).resolve() if len(argv) > 1 else Path.cwd().resolve()
    errors = validate_repository(root)
    if errors:
        print("Clean-room evidence validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("Clean-room evidence validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
