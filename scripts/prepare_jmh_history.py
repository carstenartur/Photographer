#!/usr/bin/env python3
"""Prepare stable, reader-friendly JMH series names for GitHub Pages.

The raw JMH JSON remains unchanged for diagnostics and same-runner comparisons.
The chart-ready copy and existing github-action-benchmark history use the same
stable naming function so changing labels does not split an existing time series.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


BENCHMARK_LABELS = {
    "com.photographer.focusstack.cr3.benchmark.Cr3BoxParseBenchmark.parse":
        "Container · Parse ISO-BMFF boxes",
    "com.photographer.focusstack.cr3.benchmark.Cr3MetadataBenchmark.readMetadata":
        "Metadata · Read EXIF and MakerNote",
    "com.photographer.focusstack.cr3.benchmark.Cr3PreviewExtractBenchmark.extractPreview":
        "Preview · Extract embedded JPEG",
    "com.photographer.focusstack.cr3.benchmark.CrxLosslessDecodeBenchmark.decode":
        "CRX decoder · Decode complete lossless RAW",
    "com.photographer.focusstack.cr3.benchmark.Cr3RawLinearBenchmark.decodeRawLinear":
        "RAW pipeline · Decode linear RAW",
    "com.photographer.focusstack.cr3.benchmark.Cr3RawProcessedBenchmark.decodeRawProcessed":
        "RAW pipeline · Decode display-ready sRGB (stream input)",
    "com.photographer.focusstack.cr3.benchmark.Cr3RawProcessedBenchmark.decodeRawProcessedMmap":
        "RAW pipeline · Decode display-ready sRGB (memory-mapped input)",
    "com.photographer.focusstack.cr3.raw.codec.CrxDecoderStagesBenchmark.parseHeader":
        "CRX stage · Parse codec header",
    "com.photographer.focusstack.cr3.raw.codec.CrxDecoderStagesBenchmark.decodeFirstPlane":
        "CRX stage · Decode first tile plane",
    "com.photographer.focusstack.cr3.raw.codec.CrxDecoderStagesBenchmark.copyFirstTilePlane":
        "CRX stage · Copy first tile plane",
    "com.photographer.focusstack.cr3.raw.codec.CrxDecoderStagesBenchmark.interleavePositionalPlanes":
        "CRX stage · Interleave Bayer planes",
    "com.photographer.focusstack.cr3.raw.codec.CrxDecoderStagesBenchmark.predictFirstPlane":
        "CRX stage · Apply MED prediction to first plane",
    "com.photographer.focusstack.cr3.raw.codec.CrxDecoderMicroStagesBenchmark.decodeRiceSymbols":
        "CRX inner loop · Decode Rice symbol",
    "com.photographer.focusstack.cr3.raw.codec.CrxDecoderMicroStagesBenchmark.mapResidualSigns":
        "CRX inner loop · Map residual sign",
    "com.photographer.focusstack.cr3.raw.codec.CrxDecoderMicroStagesBenchmark.updateRiceAdaptation":
        "CRX inner loop · Update Rice parameter",
    "com.photographer.focusstack.cr3.benchmark.Cr3RawProcessingStagesBenchmark.subtractBlackLevel":
        "RAW processing · Subtract black level",
    "com.photographer.focusstack.cr3.benchmark.Cr3RawProcessingStagesBenchmark.applyWhiteBalance":
        "RAW processing · Apply white balance",
    "com.photographer.focusstack.cr3.benchmark.Cr3RawProcessingStagesBenchmark.demosaicBayer":
        "RAW processing · Demosaic Bayer data",
    "com.photographer.focusstack.cr3.benchmark.Cr3RawProcessingStagesBenchmark.applyColorMatrix":
        "RAW processing · Apply camera colour matrix",
    "com.photographer.focusstack.cr3.benchmark.Cr3RawProcessingStagesBenchmark.convertToSrgb":
        "RAW processing · Convert linear RGB to sRGB",
    "com.photographer.focusstack.cr3.benchmark.Cr3ImageImportBenchmark.importProcessedRawViaImageIo":
        "Complete import · Open CR3 as display-ready RAW",
    "com.photographer.focusstack.cr3.benchmark.Cr3ImageImportBenchmark.importPreviewViaImageIo":
        "Complete import · Open embedded preview",
}

# These labels intentionally reflect extracted EXIF rather than the fixture file
# names. The three EOS R files all report ISO 12800, and the G5 X II files named
# "wide" and "tele" both report the same 8.8 mm focal length.
SAMPLE_LABELS = {
    "sample.cr3": "G5 X Mark II · ISO 125 · frame 1",
    "sample-tele.cr3": "G5 X Mark II · ISO 125 · frame 2",
    "sample-wide.cr3": "G5 X Mark II · ISO 125 · frame 3",
    "sample-iso-high.cr3": "G5 X Mark II · ISO 1600",
    "sample-wb-tungsten.cr3": "G5 X Mark II · ISO 1600 · tungsten WB",
    "eosr-iso-low.cr3": "EOS R · ISO 12800 · frame 1",
    "eosr-iso-mid.cr3": "EOS R · ISO 12800 · frame 2",
    "eosr-iso-high.cr3": "EOS R · ISO 12800 · frame 3",
}

PUBLISHED_SERIES = re.compile(
    r"^(?P<benchmark>.+?)\s*\(\s*(?P<params>\{.*\})\s*\)\s*$")
DATA_JS = re.compile(
    r"^\s*window\.BENCHMARK_DATA\s*=\s*(?P<data>\{.*\})\s*;?\s*$",
    re.DOTALL,
)


def _humanize(identifier: str) -> str:
    """Turn a Java or parameter identifier into a readable fallback label."""
    without_suffix = identifier.removesuffix("Benchmark")
    words = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", without_suffix)
    words = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", words)
    words = words.replace("_", " ").strip()
    return words[:1].upper() + words[1:] if words else words


def _is_technical_benchmark(name: str) -> bool:
    return name in BENCHMARK_LABELS or (
        name.startswith("com.photographer.") and "Benchmark." in name
    )


def friendly_benchmark_name(raw_name: str) -> str:
    known = BENCHMARK_LABELS.get(raw_name)
    if known is not None:
        return known

    class_and_method = raw_name.rsplit(".", 2)
    if len(class_and_method) >= 2:
        class_name = class_and_method[-2]
        method_name = class_and_method[-1]
        return f"{_humanize(class_name)} · {_humanize(method_name)}"
    return _humanize(raw_name)


def _format_parameters(params: dict[str, Any]) -> str | None:
    details: list[str] = []
    sample = params.get("sample")
    if isinstance(sample, str):
        details.append(SAMPLE_LABELS.get(sample, sample))

    for key in sorted(params):
        if key == "sample":
            continue
        value = params[key]
        if isinstance(value, bool):
            rendered = "yes" if value else "no"
        elif value is None:
            rendered = "none"
        else:
            rendered = str(value)
        details.append(f"{_humanize(key)}: {rendered}")

    return " · ".join(details) if details else None


def friendly_series_name(raw_name: str, params: dict[str, Any] | None = None) -> str:
    """Build the exact stable series identity used in the published charts."""
    label = friendly_benchmark_name(raw_name)
    details = _format_parameters(params or {})
    return f"{label} — {details}" if details else label


def transform_results(results: Any) -> list[dict[str, Any]]:
    """Transform raw JMH records without mutating the supplied value."""
    if not isinstance(results, list):
        raise ValueError("JMH result must be a JSON array")

    transformed: list[dict[str, Any]] = []
    for index, item in enumerate(results):
        if not isinstance(item, dict):
            raise ValueError(f"JMH entry {index} must be a JSON object")
        raw_name = item.get("benchmark")
        if not isinstance(raw_name, str) or not raw_name:
            raise ValueError(f"JMH entry {index} has no benchmark name")

        raw_params = item.get("params")
        if raw_params is not None and not isinstance(raw_params, dict):
            raise ValueError(f"JMH entry {index} params must be a JSON object")

        rewritten = dict(item)
        rewritten["benchmark"] = friendly_series_name(
            raw_name, dict(raw_params or {}))
        # Parameters are part of the readable benchmark identity. Leaving them
        # here would make github-action-benchmark append raw JSON to the label.
        rewritten.pop("params", None)
        transformed.append(rewritten)

    return transformed


def transform_published_series_name(name: str) -> str:
    """Migrate a historical github-action-benchmark series name idempotently."""
    match = PUBLISHED_SERIES.fullmatch(name)
    if match is not None:
        benchmark = match.group("benchmark")
        if not _is_technical_benchmark(benchmark):
            return name
        try:
            params = json.loads(match.group("params"))
        except json.JSONDecodeError as error:
            raise ValueError(f"Invalid parameter JSON in published series: {name}") from error
        if not isinstance(params, dict):
            raise ValueError(f"Published series parameters must be an object: {name}")
        return friendly_series_name(benchmark, params)

    if _is_technical_benchmark(name):
        return friendly_series_name(name)
    return name


def transform_history_data(data: Any) -> dict[str, Any]:
    """Rewrite all historical bench names while preserving commits and values."""
    if not isinstance(data, dict):
        raise ValueError("Benchmark history must be a JSON object")
    entries = data.get("entries")
    if not isinstance(entries, dict):
        raise ValueError("Benchmark history has no entries object")

    for suite_name, suite_entries in entries.items():
        if not isinstance(suite_entries, list):
            raise ValueError(f"Benchmark suite {suite_name!r} must be an array")
        for entry_index, entry in enumerate(suite_entries):
            if not isinstance(entry, dict):
                raise ValueError(
                    f"Benchmark suite {suite_name!r} entry {entry_index} must be an object")
            benches = entry.get("benches")
            if not isinstance(benches, list):
                raise ValueError(
                    f"Benchmark suite {suite_name!r} entry {entry_index} has no benches array")
            for bench_index, bench in enumerate(benches):
                if not isinstance(bench, dict) or not isinstance(bench.get("name"), str):
                    raise ValueError(
                        f"Benchmark suite {suite_name!r} entry {entry_index} "
                        f"bench {bench_index} has no name")
                bench["name"] = transform_published_series_name(bench["name"])
    return data


def transform_data_js(document: str) -> str:
    """Transform the generated dev/bench/data.js document."""
    match = DATA_JS.fullmatch(document)
    if match is None:
        raise ValueError("Benchmark data.js has an unsupported wrapper")
    try:
        data = json.loads(match.group("data"))
    except json.JSONDecodeError as error:
        raise ValueError("Benchmark data.js contains invalid JSON") from error
    transformed = transform_history_data(data)
    return (
        "window.BENCHMARK_DATA = "
        + json.dumps(transformed, ensure_ascii=False, indent=2)
        + ";\n"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prepare readable and history-preserving JMH chart labels")
    subparsers = parser.add_subparsers(dest="mode", required=True)
    for mode, help_text in (
        ("jmh", "transform raw JMH result JSON"),
        ("history", "migrate github-action-benchmark data.js"),
    ):
        subparser = subparsers.add_parser(mode, help=help_text)
        subparser.add_argument("input", type=Path)
        subparser.add_argument("output", type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.mode == "jmh":
        with args.input.open(encoding="utf-8") as source:
            results = json.load(source)
        output = json.dumps(
            transform_results(results), ensure_ascii=False, indent=2) + "\n"
    else:
        output = transform_data_js(args.input.read_text(encoding="utf-8"))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(output, encoding="utf-8")


if __name__ == "__main__":
    main()
