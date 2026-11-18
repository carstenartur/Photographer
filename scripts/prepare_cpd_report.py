#!/usr/bin/env python3
"""Stage the Maven CPD report and its Shields endpoint for GitHub Pages."""

from __future__ import annotations

import argparse
import json
import shutil
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


def count_duplicate_groups(xml: bytes, xml_path: Path) -> int:
    try:
        root = ET.fromstring(xml)
    except (ET.ParseError, LookupError) as error:
        raise ValueError(f"Malformed CPD XML in {xml_path}: {error}") from error

    if root.tag.rsplit("}", 1)[-1] != "pmd-cpd":
        raise ValueError(f"Expected pmd-cpd root in {xml_path}, got {root.tag}")

    processing_error = root.find("{*}error")
    if processing_error is not None:
        filename = processing_error.get("filename", "unknown file")
        message = processing_error.get("msg", "no details")
        raise ValueError(f"CPD processing error in {filename}: {message} ({xml_path})")

    duplicate_groups = len(root.findall("{*}duplication"))
    if duplicate_groups == 0 and not any(
        source.get("path", "").strip() for source in root.iterfind("{*}file")
    ):
        raise ValueError(f"CPD XML in {xml_path} contains no scanned files")

    return duplicate_groups


def prepare_report(xml_path: Path, report_dir: Path, output_dir: Path, revision: str) -> None:
    xml = xml_path.read_bytes()
    duplicate_groups = count_duplicate_groups(xml, xml_path)
    badge = {
        "schemaVersion": 1,
        "label": "CPD groups",
        "message": str(duplicate_groups),
        "color": "blue",
    }
    summary = {"revision": revision, "duplicate_groups": duplicate_groups}

    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f".{output_dir.name}-", dir=output_dir.parent) as staging:
        staged_report = Path(staging) / "report"
        shutil.copytree(report_dir, staged_report)
        if not (staged_report / "cpd.html").is_file():
            raise ValueError(f"Missing CPD HTML report: {report_dir / 'cpd.html'}")

        (staged_report / "cpd.xml").write_bytes(xml)
        (staged_report / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        (staged_report / "badge.json").write_text(json.dumps(badge, indent=2) + "\n", encoding="utf-8")

        # Keep the previous report available in case the final rename fails.
        previous_report = Path(staging) / "previous"
        if output_dir.is_dir():
            output_dir.rename(previous_report)
        try:
            staged_report.rename(output_dir)
        except OSError:
            if previous_report.exists():
                previous_report.rename(output_dir)
            raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--xml", required=True, type=Path)
    parser.add_argument("--report-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()

    try:
        prepare_report(args.xml, args.report_dir, args.output_dir, args.revision)
    except (OSError, ValueError) as error:
        parser.exit(1, f"{parser.prog}: {error}\n")


if __name__ == "__main__":
    main()
