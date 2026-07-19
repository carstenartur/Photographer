#!/usr/bin/env python3
"""Generate Shields.io endpoint JSON from Maven test and JaCoCo XML reports."""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path

COVERAGE_GREEN_THRESHOLD = 80
COVERAGE_YELLOW_THRESHOLD = 60


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--site", type=Path, default=Path("app/target/site"))
    return parser.parse_args()


def coverage_badge(root: Path) -> dict[str, object]:
    covered = 0
    missed = 0
    for report in root.glob("**/target/site/jacoco/jacoco.xml"):
        xml_root = ET.parse(report).getroot()
        counter = next(
            (
                item
                for item in xml_root.findall("counter")
                if item.get("type") == "INSTRUCTION"
            ),
            None,
        )
        if counter is not None:
            covered += int(counter.get("covered", "0"))
            missed += int(counter.get("missed", "0"))

    total = covered + missed
    percentage = round(covered / total * 100) if total else 0
    if percentage >= COVERAGE_GREEN_THRESHOLD:
        color = "brightgreen"
    elif percentage >= COVERAGE_YELLOW_THRESHOLD:
        color = "yellow"
    else:
        color = "red"
    return {
        "schemaVersion": 1,
        "label": "coverage",
        "message": f"{percentage}%" if total else "unknown",
        "color": color if total else "lightgrey",
    }


def tests_badge(root: Path) -> dict[str, object]:
    reports = list(root.glob("**/target/surefire-reports/TEST-*.xml"))
    reports.extend(root.glob("**/target/failsafe-reports/TEST-*.xml"))

    tests = failures = errors = skipped = 0
    for report in reports:
        xml_root = ET.parse(report).getroot()
        tests += int(xml_root.get("tests", "0"))
        failures += int(xml_root.get("failures", "0"))
        errors += int(xml_root.get("errors", "0"))
        skipped += int(xml_root.get("skipped", "0"))

    failed = failures + errors
    passed = max(tests - failed - skipped, 0)
    if not reports:
        message, color = "unknown", "lightgrey"
    elif failed:
        message, color = f"{failed}/{tests} failed", "red"
    else:
        message, color = f"{passed} passed", "brightgreen"
    return {
        "schemaVersion": 1,
        "label": "tests",
        "message": message,
        "color": color,
    }


def write_json(path: Path, value: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, separators=(",", ":")), encoding="utf-8")


def main() -> int:
    args = parse_args()
    try:
        coverage = coverage_badge(args.root)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"Coverage badge generation failed: {error}")
        coverage = {
            "schemaVersion": 1,
            "label": "coverage",
            "message": "unknown",
            "color": "lightgrey",
        }

    try:
        tests = tests_badge(args.root)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"Tests badge generation failed: {error}")
        tests = {
            "schemaVersion": 1,
            "label": "tests",
            "message": "unknown",
            "color": "lightgrey",
        }

    write_json(args.site / "jacoco" / "badge.json", coverage)
    write_json(args.site / "badge.json", tests)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
