#!/usr/bin/env python3
"""Measure unchanged-code JMH variance and elapsed time in both orders on one host."""

import argparse
import json
import subprocess
import time
from pathlib import Path

from compare_jmh import compare_files, write_json
from run_jmh_compare_refs import (
    DEFAULT_PATTERN, jmh_command, maven_command, ordered_revisions, write_environment,
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path("target/jmh-calibration"))
    args = parser.parse_args()
    root = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True, cwd=root).strip()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    subprocess.run(maven_command(), cwd=root, check=True)
    build_seconds = time.monotonic() - start
    timings = []
    for forks in (1, 2, 3):
        for order in ("base-head", "head-base"):
            case = output / f"{forks}-{order}"
            case.mkdir(exist_ok=True)
            write_environment(case / "environment.txt", repo_root=root, base_ref="HEAD",
                              head_ref="HEAD", base_sha=sha, head_sha=sha, order=order, forks=forks)
            for revision in ordered_revisions(order, sha, sha):
                command = jmh_command(root, case / f"{revision.label}.json", DEFAULT_PATTERN, forks=forks)
                start = time.monotonic()
                subprocess.run(command, cwd=root, check=True)
                timings.append({"forks": forks, "order": order, "label": revision.label,
                                "elapsedSeconds": time.monotonic() - start})
            write_json(case / "comparison.json", compare_files(case / "base.json", case / "head.json", 1.15), 1.15)
    (output / "timings.json").write_text(json.dumps({
        "commit": sha, "buildSeconds": build_seconds, "measurements": timings,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
