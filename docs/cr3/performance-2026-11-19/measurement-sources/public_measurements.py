#!/usr/bin/env python3
"""Serial full-file measurements; no builds, decoded-result caches, or parallel jobs.

Default: RGB8 baseline versus rgb8-only, with 3 fresh processes per condition.
The final stage requires an explicit --final-jar. LibRaw is a separate group.
Raw stdout/stderr and every parsed observation remain in the output directory.
"""

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import shlex
import statistics
import subprocess
import sys
import time


WORK = Path(__file__).resolve().parent
STAGES = ("rgb8", "final", "libraw")
MODES = ("sensor", "default", "rgb16linear", "rgb16")
JVM_FLAGS = ("-Xms24m", "-Xmx768m", "-XX:+UseSerialGC")
LIBRAW_MODES = {
    "sensor": ("sensor", "linear"),
    "default": ("rgb8", "srgb"),
    "rgb16linear": ("rgb16", "linear"),
    "rgb16": ("rgb16", "srgb"),
}


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def stamp():
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", nargs="+", choices=STAGES, default=["rgb8"])
    parser.add_argument("--cpus", nargs="+", type=int, choices=(1, 4), default=[1, 4])
    parser.add_argument("--input-index", nargs="+", type=int, help="Indices in inputs.json followed by extra-input.json")
    parser.add_argument("--modes", nargs="+", choices=MODES, default=list(MODES), help="Final/LibRaw modes; RGB8 always uses default")
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--timeout-seconds", type=float, default=600)
    parser.add_argument("--input-manifest", type=Path, default=WORK / "inputs.json")
    parser.add_argument("--extra-input", type=Path, default=WORK / "extra-input.json")
    parser.add_argument("--baseline-jar", type=Path, default=WORK / "baseline-83a687e-benchmarks.jar")
    parser.add_argument("--optimized16-jar", type=Path, default=WORK / "optimized16-benchmarks.jar", help="Immutable reference recorded in the manifest")
    parser.add_argument("--rgb8-jar", type=Path, default=WORK / "rgb8-only-benchmarks.jar")
    parser.add_argument("--final-jar", type=Path, help="Explicit final candidate for --stage final")
    parser.add_argument("--java", type=Path, default=WORK.parent / "rgb16-tools/jdk-25.0.5.1+2/bin/java")
    parser.add_argument("--probe-classes", type=Path, default=WORK / "public-probe-classes")
    parser.add_argument("--probe-main", default="com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe")
    parser.add_argument("--libraw", type=Path, default=WORK.parent / "qa-tools/libraw-bench")
    parser.add_argument("--output", type=Path, help="New output directory; existing directories are never overwritten")
    parser.add_argument("--plan-only", action="store_true", help="Write provenance and commands, but launch no processes")
    args = parser.parse_args()
    if args.forks < 1 or args.warmup < 0 or args.iterations < 1 or args.timeout_seconds <= 0:
        parser.error("forks/iterations/timeout must be positive and warmup nonnegative")
    if args.warmup + args.iterations > 100000:
        parser.error("warmup + iterations must be <= 100000 (LibRaw client limit)")
    if "final" in args.stage and args.final_jar is None:
        parser.error("--stage final requires --final-jar PATH; optimized16 is not silently treated as final")
    return args


def load_inputs(args):
    samples = json.loads(args.input_manifest.read_text(encoding="utf-8"))
    extra = json.loads(args.extra_input.read_text(encoding="utf-8"))
    samples.extend(extra if isinstance(extra, list) else [extra])
    indices = list(range(len(samples))) if args.input_index is None else list(dict.fromkeys(args.input_index))
    if any(index < 0 or index >= len(samples) for index in indices):
        raise ValueError("--input-index is outside the combined input manifests")
    selected = []
    for index in indices:
        sample = dict(samples[index], index=index)
        path = Path(sample["path"]).resolve()
        sample["path"] = str(path)
        sample["observed_sha256"] = sha256(path)
        if sample["observed_sha256"] != sample["sha256"]:
            raise ValueError(f"Input SHA-256 mismatch: {path}")
        selected.append(sample)
    return selected


def file_record(path):
    path = path.resolve()
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": sha256(path)}


def make_plan(args, samples, jars):
    plan = []
    for stage in STAGES:
        if stage not in args.stage:
            continue
        variants = {"rgb8": ["baseline", "rgb8-only"], "final": ["baseline", "final"], "libraw": ["libraw"]}[stage]
        modes = ["default"] if stage == "rgb8" else list(dict.fromkeys(args.modes))
        for fork in range(1, args.forks + 1):
            order = variants if fork % 2 else list(reversed(variants))
            for cpus in dict.fromkeys(args.cpus):
                for sample in samples:
                    for mode in modes:
                        for position, variant in enumerate(order, start=1):
                            environment = {}
                            command = ["taskset", "-c", f"0-{cpus - 1}"]
                            if stage == "libraw":
                                native_mode, transfer = LIBRAW_MODES[mode]
                                command += [str(args.libraw.resolve()), "--input", sample["path"],
                                            "--mode", native_mode, "--transfer", transfer,
                                            "--quality", "0", "--wb", "camera", "--auto-bright", "0",
                                            "--orientation", "camera", "--warmup", str(args.warmup),
                                            "--iterations", str(args.iterations)]
                                environment = {"OMP_NUM_THREADS": str(cpus), "OMP_THREAD_LIMIT": str(cpus),
                                               "OMP_DYNAMIC": "FALSE"}
                            else:
                                classpath = str(args.probe_classes.resolve()) + os.pathsep + str(jars[variant].resolve())
                                command += [str(args.java.resolve()), *JVM_FLAGS,
                                            f"-XX:ActiveProcessorCount={cpus}", "-cp", classpath,
                                            args.probe_main, "--input", sample["path"], "--mode", mode,
                                            "--warmup", str(args.warmup), "--iterations", str(args.iterations),
                                            "--gc-between"]
                            plan.append({"sequence": len(plan) + 1, "stage": stage, "variant": variant,
                                         "input_index": sample["index"], "input": sample["path"],
                                         "sample": sample["sample"], "mode": mode, "cpus": cpus,
                                         "fork": fork, "pair_position": position, "command": command,
                                         "environment_overrides": environment})
    return plan


def read_observations(path):
    observations, errors = [], []
    for number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), start=1):
        if not line.lstrip().startswith("{"):
            continue
        try:
            observation = json.loads(line)
            if not isinstance(observation, dict):
                raise ValueError("JSON observation is not an object")
            observations.append(observation)
        except (json.JSONDecodeError, ValueError) as exception:
            errors.append({"line": number, "error": str(exception), "text": line})
    return observations, errors


def process_summary(observations):
    metrics = {}
    for observation in observations:
        for key, value in observation.items():
            if (key.endswith("_ms") or key in ("allocated_bytes", "heap_used_bytes")) \
                    and isinstance(value, (int, float)) and math.isfinite(value):
                metrics.setdefault(key, []).append(value)
    peaks = [row["maxrss_kb"] for row in observations if isinstance(row.get("maxrss_kb"), (int, float))]
    return {"median": {key: statistics.median(values) for key, values in metrics.items()},
            "peak_rss_kb": max(peaks) if peaks else None,
            "observations": len(observations)}


def aggregate(records):
    grouped = {}
    for record in records:
        key = tuple(record[name] for name in ("stage", "variant", "input_index", "mode", "cpus"))
        grouped.setdefault(key, []).append(record)
    result = []
    for key, processes in grouped.items():
        good = [record for record in processes if record["status"] == "ok"]
        metrics = {}
        for record in good:
            for name, value in record["process_summary"]["median"].items():
                metrics.setdefault(name, []).append(value)
        peaks = [record["process_summary"]["peak_rss_kb"] for record in good
                 if record["process_summary"]["peak_rss_kb"] is not None]
        item = dict(zip(("stage", "variant", "input_index", "mode", "cpus"), key))
        item.update({"sample": processes[0]["sample"], "completed_processes": len(processes),
                     "successful_processes": len(good), "failed_processes": len(processes) - len(good),
                     "median_of_process_medians": {name: statistics.median(values) for name, values in metrics.items()},
                     "process_medians": {name: values for name, values in metrics.items()},
                     "median_process_peak_rss_kb": statistics.median(peaks) if peaks else None,
                     "max_process_peak_rss_kb": max(peaks) if peaks else None})
        result.append(item)
    return result


def save_summary(output, records, planned):
    groups = aggregate(records)
    write_json(output / "results.json", records)
    write_json(output / "summary.json", {"updated_utc": utc_now(), "planned_processes": planned,
               "completed_processes": len(records), "groups": groups,
               "notes": ["Each successful process contributes one median; iterations are not pooled across forks.",
                         "RSS is process-lifetime high-water: use each process maximum, then aggregate those maxima.",
                         "LibRaw is a separate workload group, not a claim of pixel-equivalent RGB output.",
                         "Sampled checksums are retained diagnostics, not cross-engine equivalence proofs."]})
    columns = ["stage", "variant", "input_index", "sample", "mode", "cpus", "successful_processes",
               "failed_processes", "total_ms", "cpu_ms", "allocated_bytes", "heap_used_bytes",
               "median_process_peak_rss_kb", "max_process_peak_rss_kb"]
    with (output / "summary.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns, extrasaction="ignore")
        writer.writeheader()
        for group in groups:
            writer.writerow({**group, **group["median_of_process_medians"]})


def run_one(item, args, output):
    name = (f"{item['sequence']:04d}-{stamp()}-{item['stage']}-{item['variant']}-"
            f"cpu{item['cpus']}-input{item['input_index']}-{item['mode']}-fork{item['fork']}")
    directory = output / "invocations" / name
    directory.mkdir(parents=True)
    record = dict(item, started_utc=utc_now(), stdout=str(directory / "stdout.txt"),
                  stderr=str(directory / "stderr.txt"), command_display=shlex.join(item["command"]))
    write_json(directory / "command.json", record)
    environment = os.environ.copy()
    environment.update(item["environment_overrides"])
    start = time.monotonic()
    interrupted = False
    try:
        with Path(record["stdout"]).open("wb") as stdout, Path(record["stderr"]).open("wb") as stderr:
            completed = subprocess.run(item["command"], stdout=stdout, stderr=stderr, env=environment,
                                       timeout=args.timeout_seconds, check=False)
        record.update(exit_code=completed.returncode, status="ok" if completed.returncode == 0 else "failed")
    except (OSError, subprocess.TimeoutExpired) as exception:
        record.update(exit_code=None, status="failed", error=f"{type(exception).__name__}: {exception}")
    except KeyboardInterrupt:
        record.update(exit_code=None, status="interrupted", error="Interrupted by user")
        interrupted = True
    record.update(finished_utc=utc_now(), process_wall_ms=(time.monotonic() - start) * 1000)
    rows, errors = read_observations(Path(record["stdout"]))
    record["results"], record["parse_errors"] = rows, errors
    expected_iterations = list(range(args.iterations))
    valid_rows = all(isinstance(row.get("total_ms"), (int, float))
                     and math.isfinite(row["total_ms"]) and row["total_ms"] >= 0 for row in rows)
    if record["status"] == "ok" and (errors or not valid_rows
            or [row.get("iteration") for row in rows] != expected_iterations):
        record.update(status="invalid-output", error="Expected one valid observation per measured iteration")
    record["process_summary"] = process_summary(rows)
    write_json(directory / "result.json", record)
    write_json(directory / "exit.json", {key: record.get(key) for key in
               ("started_utc", "finished_utc", "exit_code", "status", "error", "process_wall_ms")})
    return record, interrupted


def main():
    args = parse_args()
    samples = load_inputs(args)
    jars = {"baseline": args.baseline_jar, "optimized16-reference": args.optimized16_jar,
            "rgb8-only": args.rgb8_jar}
    if args.final_jar is not None:
        jars["final"] = args.final_jar
    output = (args.output or WORK / "final-measurements" / stamp()).resolve()
    output.mkdir(parents=True, exist_ok=False)
    artifacts = {name: file_record(path) for name, path in jars.items() if path.is_file()}
    if any(stage in args.stage for stage in ("rgb8", "final")):
        artifacts["temporary_probe"] = file_record(args.probe_classes / (args.probe_main.replace(".", "/") + ".class"))
        artifacts["java"] = file_record(args.java)
    if "libraw" in args.stage:
        artifacts["libraw_client"] = file_record(args.libraw)
    plan = make_plan(args, samples, jars)
    manifest = {"created_utc": utc_now(), "runner": file_record(Path(__file__)), "python": sys.version,
                "platform": platform.platform(), "configuration": {key: str(value) if isinstance(value, Path)
                    else value for key, value in vars(args).items()}, "inputs": samples, "artifacts": artifacts,
                "jvm_flags": list(JVM_FLAGS), "explicit_gc_between_java_imports": True,
                "java_workers": "Probe default 0; ActiveProcessorCount and taskset cap available CPUs",
                "affinity": "taskset -c 0-(cpus-1), matching the existing runners",
                "probe_class": args.probe_main, "checksum": "Existing sampled checksum; no --checksum or --dump",
                "inherited_runtime_environment": {key: os.environ[key] for key in
                    ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "LD_LIBRARY_PATH") if key in os.environ},
                "planned_processes": len(plan), "planned_decodes": len(plan) * (args.warmup + args.iterations),
                "order": "Stages rgb8/final/libraw; fork/CPU/input/mode; Java variants alternate AB/BA/AB"}
    write_json(output / "manifest.json", manifest)
    write_json(output / "plan.json", plan)
    print(f"Output: {output}\nPlanned fresh processes: {len(plan)}", flush=True)
    if args.plan_only:
        print("Plan only; no child process launched.", flush=True)
        return 0
    records = []
    for item in plan:
        print(f"[{item['sequence']}/{len(plan)}] {item['stage']} {item['variant']} "
              f"cpu={item['cpus']} input={item['input_index']} mode={item['mode']} fork={item['fork']}", flush=True)
        record, interrupted = run_one(item, args, output)
        records.append(record)
        save_summary(output, records, len(plan))
        median = record["process_summary"]["median"].get("total_ms")
        print(f"  {record['status']}; process median total_ms={median}", flush=True)
        if interrupted:
            return 130
    return 1 if any(record["status"] != "ok" for record in records) else 0


if __name__ == "__main__":
    raise SystemExit(main())
