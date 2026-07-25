#!/usr/bin/env python3
"""Prepare stable, reader-friendly JMH series names for GitHub Pages.

The raw JMH JSON remains unchanged for diagnostics and same-runner comparisons.
Only the copy consumed by github-action-benchmark is rewritten.
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

SAMPLE_LABELS = {
    "sample.cr3": "PowerShot · baseline",
    "sample-iso-high.cr3": "PowerShot · high ISO",
    "sample-wb-tungsten.cr3": "PowerShot · tungsten white balance",
    "sample-wide.cr3": "PowerShot · wide angle",
    "sample-tele.cr3": "PowerShot · telephoto",
    "eosr-iso-low.cr3": "EOS R · low ISO",
    "eosr-iso-mid.cr3": "EOS R · medium ISO",
    "eosr-iso-high.cr3": "EOS R · high ISO",
}


def _humanize(identifier: str) -> str:
    """Turn an unknown Java identifier into a readable fallback label."""
    without_suffix = identifier.removesuffix("Benchmark")
    words = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", without_suffix)
    words = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", words)
    return words.replace("_", " ").strip()


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


def transform_results(results: Any) -> list[dict[str, Any]]:
    if not isinstance(results, list):
        raise ValueError("JMH result must be a JSON array")

    transformed: list[dict[str, Any]] = []
    for index, item in enumerate(results):
        if not isinstance(item, dict):
            raise ValueError(f"JMH entry {index} must be a JSON object")
        raw_name = item.get("benchmark")
        if not isinstance(raw_name, str) or not raw_name:
            raise ValueError(f"JMH entry {index} has no benchmark name")

        rewritten = dict(item)
        label = friendly_benchmark_name(raw_name)

        raw_params = item.get("params")
        if raw_params is not None and not isinstance(raw_params, dict):
            raise ValueError(f"JMH entry {index} params must be a JSON object")
        params = dict(raw_params or {})
        sample = params.pop("sample", None)
        if isinstance(sample, str):
            label = f"{label} — {SAMPLE_LABELS.get(sample, sample)}"

        rewritten["benchmark"] = label
        if params:
            rewritten["params"] = params
        else:
            rewritten.pop("params", None)
        transformed.append(rewritten)

    return transformed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Rewrite JMH benchmark and fixture names for the history charts")
    parser.add_argument("input", type=Path, help="Raw JMH JSON")
    parser.add_argument("output", type=Path, help="Chart-ready JMH JSON")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    with args.input.open(encoding="utf-8") as source:
        results = json.load(source)
    transformed = transform_results(results)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as destination:
        json.dump(transformed, destination, ensure_ascii=False, indent=2)
        destination.write("\n")


if __name__ == "__main__":
    main()
