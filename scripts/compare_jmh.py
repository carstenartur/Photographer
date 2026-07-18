#!/usr/bin/env python3
"""Compare two JMH JSON result files produced on the same runner."""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class BenchmarkResult:
    benchmark: str
    params: tuple[tuple[str, str], ...]
    mode: str
    score: float
    score_unit: str
    confidence_low: float
    confidence_high: float
    confidence_available: bool

    @property
    def key(self) -> tuple[str, tuple[tuple[str, str], ...]]:
        return self.benchmark, self.params


@dataclass(frozen=True)
class Comparison:
    benchmark: str
    params: tuple[tuple[str, str], ...]
    base_score: float
    head_score: float
    score_unit: str
    ratio: float
    change_percent: float
    confidence_available: bool
    confidence_intervals_overlap: bool | None
    verdict: str


def _finite(value: Any) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _confidence(metric: dict[str, Any], score: float) -> tuple[float, float, bool]:
    confidence = metric.get("scoreConfidence")
    if isinstance(confidence, list) and len(confidence) == 2:
        low = _finite(confidence[0])
        high = _finite(confidence[1])
        if low is not None and high is not None and low <= high:
            return low, high, True

    error = _finite(metric.get("scoreError"))
    if error is not None and error >= 0:
        return score - error, score + error, True

    # A point interval is retained for display, but confidence_available=False
    # prevents it from manufacturing a confirmed regression verdict.
    return score, score, False


def load_results(path: Path) -> dict[tuple[str, tuple[tuple[str, str], ...]], BenchmarkResult]:
    with path.open(encoding="utf-8") as handle:
        payload = json.load(handle)

    if not isinstance(payload, list):
        raise ValueError(f"Expected a JMH result array in {path}")

    results: dict[tuple[str, tuple[tuple[str, str], ...]], BenchmarkResult] = {}
    for entry in payload:
        if not isinstance(entry, dict):
            raise ValueError(f"Invalid JMH result entry in {path}: {entry!r}")

        metric = entry.get("primaryMetric")
        if not isinstance(metric, dict):
            raise ValueError(f"Missing primaryMetric in {path}: {entry!r}")

        score = _finite(metric.get("score"))
        if score is None or score <= 0:
            raise ValueError(f"Invalid positive JMH score in {path}: {metric.get('score')!r}")

        params_object = entry.get("params") or {}
        if not isinstance(params_object, dict):
            raise ValueError(f"Invalid params object in {path}: {params_object!r}")
        params = tuple(sorted((str(name), str(value)) for name, value in params_object.items()))
        confidence_low, confidence_high, confidence_available = _confidence(metric, score)

        result = BenchmarkResult(
            benchmark=str(entry.get("benchmark", "")),
            params=params,
            mode=str(entry.get("mode", "")),
            score=score,
            score_unit=str(metric.get("scoreUnit", "")),
            confidence_low=confidence_low,
            confidence_high=confidence_high,
            confidence_available=confidence_available,
        )
        if not result.benchmark:
            raise ValueError(f"Missing benchmark name in {path}")
        if result.key in results:
            raise ValueError(f"Duplicate benchmark/parameter result in {path}: {result.key}")
        results[result.key] = result

    if not results:
        raise ValueError(f"No JMH results in {path}")
    return results


def classify(base: BenchmarkResult, head: BenchmarkResult, threshold: float) -> Comparison:
    if base.key != head.key:
        raise ValueError("Cannot compare different benchmark keys")
    if base.mode != head.mode:
        raise ValueError(f"Benchmark mode changed for {base.key}: {base.mode} -> {head.mode}")
    if base.score_unit != head.score_unit:
        raise ValueError(
            f"Benchmark score unit changed for {base.key}: {base.score_unit} -> {head.score_unit}"
        )
    if base.mode not in {"avgt", "AverageTime"}:
        raise ValueError(
            f"Unsupported benchmark mode {base.mode!r} for {base.key}; comparator assumes lower is better"
        )

    ratio = head.score / base.score
    confidence_available = base.confidence_available and head.confidence_available
    intervals_overlap = None
    if confidence_available:
        intervals_overlap = not (
            head.confidence_low > base.confidence_high
            or base.confidence_low > head.confidence_high
        )

    if (
        ratio >= threshold
        and confidence_available
        and head.confidence_low > base.confidence_high
    ):
        verdict = "REGRESSION"
    elif ratio >= threshold:
        verdict = "NOISY_WARNING"
    elif (
        ratio <= 1.0 / threshold
        and confidence_available
        and base.confidence_low > head.confidence_high
    ):
        verdict = "IMPROVEMENT"
    else:
        verdict = "STABLE"

    return Comparison(
        benchmark=base.benchmark,
        params=base.params,
        base_score=base.score,
        head_score=head.score,
        score_unit=base.score_unit,
        ratio=ratio,
        change_percent=(ratio - 1.0) * 100.0,
        confidence_available=confidence_available,
        confidence_intervals_overlap=intervals_overlap,
        verdict=verdict,
    )


def compare_files(base_path: Path, head_path: Path, threshold: float) -> list[Comparison]:
    base_results = load_results(base_path)
    head_results = load_results(head_path)

    missing_in_head = sorted(set(base_results) - set(head_results))
    missing_in_base = sorted(set(head_results) - set(base_results))
    if missing_in_head or missing_in_base:
        raise ValueError(
            "Benchmark sets differ; "
            f"missing in head={missing_in_head}, missing in base={missing_in_base}"
        )

    return [
        classify(base_results[key], head_results[key], threshold)
        for key in sorted(base_results)
    ]


def render_markdown(comparisons: list[Comparison], threshold: float) -> str:
    regressions = sum(item.verdict == "REGRESSION" for item in comparisons)
    warnings = sum(item.verdict == "NOISY_WARNING" for item in comparisons)
    improvements = sum(item.verdict == "IMPROVEMENT" for item in comparisons)

    lines = [
        "## Same-runner CR3 JMH comparison",
        "",
        (
            f"Regression threshold: **{(threshold - 1.0) * 100:.1f}%**. "
            "A build fails only when the threshold is exceeded and both JMH "
            "confidence intervals are available and no longer overlap."
        ),
        "",
        f"Confirmed regressions: **{regressions}** · Noisy warnings: **{warnings}** · Improvements: **{improvements}**",
        "",
        "| Benchmark | Parameters | Base | Head | Change | Verdict |",
        "|---|---|---:|---:|---:|---|",
    ]

    for item in comparisons:
        display_name = item.benchmark.rsplit(".", 1)[-1]
        params = ", ".join(f"{name}={value}" for name, value in item.params) or "—"
        lines.append(
            f"| `{display_name}` | `{params}` | {item.base_score:.6g} | "
            f"{item.head_score:.6g} | {item.change_percent:+.1f}% | **{item.verdict}** |"
        )

    lines.extend(
        [
            "",
            "`NOISY_WARNING` means the point estimate exceeded the threshold, but confidence data was missing or the intervals still overlap.",
            "",
        ]
    )
    return "\n".join(lines)


def write_json(path: Path, comparisons: list[Comparison], threshold: float) -> None:
    payload = {
        "thresholdRatio": threshold,
        "confirmedRegressions": sum(item.verdict == "REGRESSION" for item in comparisons),
        "noisyWarnings": sum(item.verdict == "NOISY_WARNING" for item in comparisons),
        "comparisons": [asdict(item) for item in comparisons],
    }
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", type=Path, help="JMH JSON for the base revision")
    parser.add_argument("head", type=Path, help="JMH JSON for the head revision")
    parser.add_argument("--threshold", type=float, default=1.15, help="Regression ratio threshold")
    parser.add_argument("--markdown-out", type=Path, required=True)
    parser.add_argument("--json-out", type=Path, required=True)
    parser.add_argument("--fail-on-regression", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.threshold <= 1.0:
        raise ValueError("Threshold must be greater than 1.0")

    comparisons = compare_files(args.base, args.head, args.threshold)
    args.markdown_out.write_text(render_markdown(comparisons, args.threshold), encoding="utf-8")
    write_json(args.json_out, comparisons, args.threshold)

    has_regression = any(item.verdict == "REGRESSION" for item in comparisons)
    return 1 if args.fail_on_regression and has_regression else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"JMH comparison failed: {error}", file=sys.stderr)
        raise SystemExit(2) from error
