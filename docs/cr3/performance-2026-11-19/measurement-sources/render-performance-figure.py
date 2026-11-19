#!/usr/bin/env python3
"""Render the completed full-file RGB16 comparison as PNG and SVG.

Usage after measurements finish:
    python3 qa-work/render-performance-figure.py --output-dir qa-work/performance-figure

Requires the existing Matplotlib installation. No partial-result mode is provided.
The complete final measurement matrix must pass validation before Matplotlib is
imported or any output directory is created.
"""

import argparse
from collections import defaultdict
import hashlib
from itertools import product
import json
import math
from pathlib import Path
import statistics


WORK = Path(__file__).resolve().parent
EXPECTED_PROCESSES = 192
INPUTS = (0, 1, 2, 3)
VARIANTS = ("baseline", "final")
MODES = ("sensor", "default", "rgb16linear", "rgb16")
CPU_COUNTS = (1, 4)
FORKS = (1, 2, 3)
LABELS = (
    "G5 X II RAW\n20.2 MP",
    "EOS R RAW\n30.3 MP",
    "G5 X II C-RAW\n20.2 MP",
    "EOS M50 C-RAW\n24.2 MP",
)
EXPECTED_FILENAMES = (
    "sample.cr3",
    "eosr-iso-low.cr3",
    "g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3",
    "eos-m50-craw-4656.cr3",
)
COLORS = {"baseline": "#4C78A8", "final": "#C68A43"}
DISPLAY_NAMES = {"baseline": "Baseline", "final": "Optimized (#423)"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def finite_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def close_numbers(actual, expected, description):
    require(finite_number(actual) and math.isclose(actual, expected, rel_tol=1e-12, abs_tol=1e-9),
            f"Inconsistent summary value for {description}: {actual!r}; expected {expected!r}")


def group_key(record):
    return (record["input_index"], record["mode"], record["cpus"], record["variant"])


def argument_value(command, option):
    require(command.count(option) == 1, f"Expected exactly one {option} in recorded command")
    index = command.index(option)
    require(index + 1 < len(command), f"Missing recorded value for {option}")
    return command[index + 1]


def load_and_validate(input_dir):
    source_bytes = {name: (input_dir / name).read_bytes() for name in ("summary.json", "results.json")}
    summary = json.loads(source_bytes["summary.json"])
    records = json.loads(source_bytes["results.json"])
    require(isinstance(summary, dict) and isinstance(records, list), "Unexpected measurement file structure")
    require(summary.get("planned_processes") == EXPECTED_PROCESSES
            and summary.get("completed_processes") == EXPECTED_PROCESSES,
            "Refusing to plot: summary must report exactly 192 planned and completed processes")
    require(len(records) == EXPECTED_PROCESSES, "Refusing to plot: results must contain exactly 192 processes")

    expected_groups = set(product(INPUTS, MODES, CPU_COUNTS, VARIANTS))
    expected_processes = {(*key, fork) for key in expected_groups for fork in FORKS}
    seen_processes = set()
    measured = defaultdict(list)

    for record in records:
        require(isinstance(record, dict), "A process record is not an object")
        require(record.get("stage") == "final", "Only the final measurement stage may be plotted")
        require(record.get("status") == "ok" and record.get("exit_code") == 0,
                f"Refusing to plot: unsuccessful process {record.get('sequence')}")
        require(not record.get("parse_errors"), "Refusing to plot: a process has JSON parse errors")
        key = group_key(record)
        identity = (*key, record["fork"])
        require(identity in expected_processes and identity not in seen_processes,
                f"Unexpected or duplicate process condition: {identity}")
        seen_processes.add(identity)
        index, mode, cpus, variant = key
        require(Path(record["input"]).name == EXPECTED_FILENAMES[index],
                f"Input {index} does not match the camera label: {record['input']}")

        command = record.get("command")
        require(isinstance(command, list) and all(isinstance(value, str) for value in command),
                "Missing recorded command")
        require(argument_value(command, "--warmup") == "3"
                and argument_value(command, "--iterations") == "5",
                "Figure requires three warmups and five measured imports per process")
        require(argument_value(command, "--mode") == mode
                and Path(argument_value(command, "--input")) == Path(record["input"]),
                "Recorded command does not match the process input or mode")
        require("--gc-between" in command and "-XX:+UseSerialGC" in command,
                "Figure requires the recorded Serial GC and explicit-GC protocol")
        require(f"-XX:ActiveProcessorCount={cpus}" in command,
                "Recorded JVM processor budget does not match the process condition")
        require("taskset" in command and argument_value(command, "-c") == f"0-{cpus - 1}",
                "Recorded CPU affinity does not match the measurement protocol")

        observations = record.get("results")
        require(isinstance(observations, list) and len(observations) == 5,
                f"Process {identity} does not contain five observations")
        require(all(isinstance(row, dict) for row in observations), "An observation is not an object")
        require([row.get("iteration") for row in observations] == list(range(5)),
                f"Incomplete measured iterations in process {identity}")
        for row in observations:
            require(row.get("engine") == "Photographer" and row.get("mode") == mode,
                    f"Unexpected engine or mode in process {identity}")
            require(row.get("warmup") == 3 and row.get("available_processors") == cpus,
                    f"Unexpected warmup or processor count in process {identity}")
            require(row.get("bits") == (8 if mode == "default" else 16)
                    and row.get("channels") == (1 if mode == "sensor" else 3),
                    f"Unexpected output bit depth or channel count in process {identity}")
            require(finite_number(row.get("total_ms")) and row["total_ms"] > 0,
                    f"Invalid import time in process {identity}")
            require(finite_number(row.get("maxrss_kb")) and row["maxrss_kb"] > 0,
                    f"Missing positive process RSS high-water mark in process {identity}")

        # RSS observations are cumulative Linux high-water marks. Their maximum is
        # the process observation; taking the median of the five RSS rows is wrong.
        median_ms = statistics.median(row["total_ms"] for row in observations)
        peak_rss_kb = max(row["maxrss_kb"] for row in observations)
        stored = record.get("process_summary", {})
        close_numbers(stored.get("median", {}).get("total_ms"), median_ms, f"process time {identity}")
        close_numbers(stored.get("peak_rss_kb"), peak_rss_kb, f"process RSS {identity}")
        measured[key].append({"fork": record["fork"], "median_ms": median_ms, "peak_rss_kb": peak_rss_kb})

    require(seen_processes == expected_processes, "Refusing to plot: the 192-process matrix is incomplete")
    groups = summary.get("groups")
    require(isinstance(groups, list) and len(groups) == len(expected_groups),
            "Refusing to plot: expected all 64 summary groups")
    seen_groups = set()
    for group in groups:
        require(isinstance(group, dict) and group.get("stage") == "final", "Unexpected summary group")
        key = group_key(group)
        require(key in expected_groups and key not in seen_groups, f"Unexpected or duplicate summary group: {key}")
        seen_groups.add(key)
        require(group.get("completed_processes") == 3 and group.get("successful_processes") == 3
                and group.get("failed_processes") == 0,
                f"Refusing to plot: group {key} must contain three successful forks")
        values = measured[key]
        require(sorted(value["fork"] for value in values) == list(FORKS), f"Missing forks in group {key}")
        times = [value["median_ms"] for value in values]
        peaks = [value["peak_rss_kb"] for value in values]
        close_numbers(group.get("median_of_process_medians", {}).get("total_ms"),
                      statistics.median(times), f"aggregate time {key}")
        close_numbers(group.get("median_process_peak_rss_kb"), statistics.median(peaks), f"aggregate RSS {key}")
        close_numbers(group.get("max_process_peak_rss_kb"), max(peaks), f"maximum RSS {key}")

    provenance = {name: hashlib.sha256(data).hexdigest() for name, data in source_bytes.items()}
    return measured, provenance


def panel_values(measured, variant, field, divisor):
    medians, lower_errors, upper_errors = [], [], []
    for index in INPUTS:
        values = [process[field] / divisor for process in measured[(index, "rgb16", 1, variant)]]
        median = statistics.median(values)
        medians.append(median)
        lower_errors.append(median - min(values))
        upper_errors.append(max(values) - median)
    return medians, [lower_errors, upper_errors]


def render(measured, provenance, output_dir):
    # Import only after the complete matrix passes validation. This script neither
    # installs dependencies nor starts a JVM, benchmark, or decoder.
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.ticker import MaxNLocator

    plt.rcParams.update({
        "font.family": "DejaVu Sans",
        "font.size": 10.5,
        "axes.titlesize": 12,
        "axes.labelsize": 11,
        "xtick.labelsize": 9.5,
        "ytick.labelsize": 10,
        "figure.facecolor": "white",
        "axes.facecolor": "white",
        "savefig.facecolor": "white",
        "svg.fonttype": "none",
        "svg.hashsalt": "photographer-cr3-rgb16-performance",
    })
    figure, axes = plt.subplots(1, 2, figsize=(13.2, 5.4))
    figure.subplots_adjust(left=0.075, right=0.985, bottom=0.25, top=0.77, wspace=0.24)
    figure.suptitle("Full-file CR3 RGB16 sRGB import · 1 CPU", y=0.98, fontsize=15, fontweight="semibold")
    panels = (
        ("(a) Import time", "Import time (s)", "median_ms", 1000.0, ".2f"),
        ("(b) Process peak memory", "Peak RSS (MiB)", "peak_rss_kb", 1024.0, ".0f"),
    )
    width = 0.34
    for axis, (title, ylabel, field, divisor, number_format) in zip(axes, panels):
        maximum = 0.0
        for position, variant in enumerate(VARIANTS):
            medians, errors = panel_values(measured, variant, field, divisor)
            x = [index + (position - 0.5) * width for index in INPUTS]
            axis.bar(x, medians, width=width, color=COLORS[variant], label=DISPLAY_NAMES[variant],
                     edgecolor="white", linewidth=0.7, yerr=errors, zorder=3,
                     error_kw={"ecolor": "#30343A", "elinewidth": 1.1, "capsize": 4, "capthick": 1.1})
            for center, median, upper_error in zip(x, medians, errors[1]):
                axis.annotate(format(median, number_format), xy=(center, median + upper_error),
                              xytext=(0, 5), textcoords="offset points", ha="center", va="bottom", fontsize=9)
                maximum = max(maximum, median + upper_error)
        axis.set_title(title, loc="left", pad=13)
        axis.set_ylabel(ylabel)
        axis.set_xticks(list(INPUTS), LABELS)
        axis.set_ylim(0, maximum * 1.19)
        axis.set_xlim(-0.6, 3.6)
        axis.yaxis.set_major_locator(MaxNLocator(nbins=5, min_n_ticks=4))
        axis.set_axisbelow(True)
        axis.grid(axis="y", color="#E4E7EB", linewidth=0.8)
        axis.tick_params(axis="x", length=0, pad=10)
        for side in ("top", "right"):
            axis.spines[side].set_visible(False)
        for side in ("left", "bottom"):
            axis.spines[side].set_color("#A6ADB6")
            axis.spines[side].set_linewidth(0.7)

    handles, labels = axes[0].get_legend_handles_labels()
    figure.legend(handles, labels, loc="upper center", bbox_to_anchor=(0.5, 0.91),
                  ncol=2, frameon=False, fontsize=11, handlelength=1.6, columnspacing=2.4)
    figure.text(0.075, 0.085,
                "3 fresh JVMs per variant and input; each 3 warmups + 5 measured imports. Java 25, Serial GC.\n"
                "Time: median of process medians. RSS: median of process peaks. Error bars: min–max across 3 JVMs.",
                fontsize=9.5, ha="left", va="center", color="#40464D", linespacing=1.65)

    output_dir.mkdir(parents=True, exist_ok=True)
    stem = output_dir / "cr3-rgb16-performance"
    description = "Complete 192-process final matrix validated. Source SHA-256: " + json.dumps(provenance, sort_keys=True)
    figure.savefig(stem.with_suffix(".png"), dpi=320, bbox_inches="tight",
                   metadata={"Title": "CR3 RGB16 sRGB import performance", "Description": description})
    figure.savefig(stem.with_suffix(".svg"), bbox_inches="tight",
                   metadata={"Title": "CR3 RGB16 sRGB import performance", "Description": description,
                             "Creator": "Photographer performance QA", "Date": None})
    plt.close(figure)
    print(f"Saved {stem.with_suffix('.png')}")
    print(f"Saved {stem.with_suffix('.svg')}")
    print("Source SHA-256:", json.dumps(provenance, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, default=WORK / "import-final",
                        help="Completed final measurement directory containing summary.json and results.json")
    parser.add_argument("--output-dir", type=Path, required=True, help="Destination for the PNG and SVG")
    args = parser.parse_args()
    try:
        measured, provenance = load_and_validate(args.input_dir)
    except (OSError, ValueError, KeyError, TypeError) as exception:
        parser.error(f"Cannot render the final comparison: {exception}")
    render(measured, provenance, args.output_dir)


if __name__ == "__main__":
    main()
