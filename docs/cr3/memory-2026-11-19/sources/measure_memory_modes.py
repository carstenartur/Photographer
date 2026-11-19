#!/usr/bin/env python3
"""Compare FAST and LOW_MEMORY in the same frozen CR3 binary, serially.

Import the unchanged public_measurements.py runner next to this file. Preserve
every process, observation and stderr message; do not retry failed imports.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import sys

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("common_measurements", HERE / "public_measurements.py")
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-manifest", required=True, type=Path)
    parser.add_argument("--input-index", nargs="+", type=int, default=[0, 1])
    parser.add_argument("--cpus", nargs="+", type=int, choices=(1, 4), default=[4])
    parser.add_argument("--modes", nargs="+", choices=("rgb8", "rgb16", "rgb16linear"), default=["rgb8", "rgb16"])
    parser.add_argument("--orientation", type=int, choices=range(1, 9), default=6)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--probe-classes", type=Path, required=True)
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--timeout-seconds", type=float, default=600)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.forks < 1 or args.warmup < 0 or args.iterations < 1 or args.timeout_seconds <= 0:
        parser.error("forks/iterations/timeout must be positive and warmup nonnegative")
    inputs = json.loads(args.input_manifest.read_text())
    samples = []
    for index in dict.fromkeys(args.input_index):
        if not 0 <= index < len(inputs):
            parser.error("input index outside manifest")
        sample = dict(inputs[index], index=index)
        sample["path"] = str(Path(sample["path"]).resolve())
        if common.sha256(Path(sample["path"])) != sample["sha256"]:
            raise ValueError("Input SHA-256 mismatch: " + sample["path"])
        samples.append(sample)
    probe_main = "com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe"
    classpath = str(args.probe_classes.resolve()) + os.pathsep + str(args.jar.resolve())
    plan = []
    for fork in range(1, args.forks + 1):
        order = ("FAST", "LOW_MEMORY") if fork % 2 else ("LOW_MEMORY", "FAST")
        for cpus in dict.fromkeys(args.cpus):
            for sample in samples:
                for mode in dict.fromkeys(args.modes):
                    for position, policy in enumerate(order, 1):
                        command = ["taskset", "-c", f"0-{cpus - 1}", str(args.java.resolve()),
                                   *common.JVM_FLAGS, f"-XX:ActiveProcessorCount={cpus}", "-cp", classpath,
                                   probe_main, "--input", sample["path"], "--mode", mode,
                                   "--memory-mode", policy, "--orientation", str(args.orientation),
                                   "--warmup", str(args.warmup), "--iterations", str(args.iterations), "--gc-between"]
                        plan.append(dict(sequence=len(plan) + 1, stage="memory", variant=policy,
                                         input_index=sample["index"], input=sample["path"], sample=sample["sample"],
                                         mode=mode, cpus=cpus, fork=fork, pair_position=position,
                                         requested_memory_mode=policy, orientation_override=args.orientation,
                                         command=command, environment_overrides={}))
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    manifest = dict(created_utc=common.utc_now(), runner=common.file_record(Path(__file__)),
                    common_runner=common.file_record(HERE / "public_measurements.py"),
                    python=sys.version, platform=platform.platform(),
                    configuration={key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items()},
                    inputs=samples, artifacts=dict(runtime=common.file_record(args.jar),
                        java=common.file_record(args.java),
                        probe=common.file_record(args.probe_classes / (probe_main.replace(".", "/") + ".class"))),
                    jvm_flags=list(common.JVM_FLAGS), explicit_gc_between_java_imports=True,
                    inherited_runtime_environment={key: os.environ[key] for key in
                        ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "LD_LIBRARY_PATH") if key in os.environ},
                    planned_processes=len(plan), planned_decodes=len(plan) * (args.warmup + args.iterations),
                    order="fork/CPU/input/mode; FAST and LOW_MEMORY alternate AB/BA/AB",
                    checksum="Sampled diagnostic only; full-value equivalence verified separately",
                    warnings="LOW_MEMORY emits one warning for each fresh read, including warmups; stderr retained")
    common.write_json(output / "manifest.json", manifest)
    common.write_json(output / "plan.json", plan)
    records = []
    for item in plan:
        print(f"[{item['sequence']}/{len(plan)}] {item['variant']} cpu={item['cpus']} input={item['input_index']} mode={item['mode']}", flush=True)
        record, interrupted = common.run_one(item, args, output)
        rows = record["results"]
        warnings = Path(record["stderr"]).read_text(errors="replace").count("CR3 low-memory:")
        record["warning_count"] = warnings
        if record["status"] == "ok" and (any(row.get("requested_memory_mode") != item["variant"]
                or row.get("orientation_override") != args.orientation
                or row.get("input") != item["input"] or row.get("mode") != item["mode"]
                or row.get("available_processors") != item["cpus"] or row.get("workers") != 0
                or row.get("warmup") != args.warmup
                or row.get("checksum_kind") != "sampled-rows-u16le-or-u8" for row in rows)
                or warnings != (args.warmup + args.iterations if item["variant"] == "LOW_MEMORY" else 0)):
            record.update(status="invalid-output", error="Policy, orientation or warning count mismatch")
        common.write_json(Path(record["stdout"]).parent / "result.json", record)
        common.write_json(Path(record["stdout"]).parent / "exit.json", {key: record.get(key) for key in
            ("started_utc", "finished_utc", "exit_code", "status", "error", "process_wall_ms")})
        records.append(record)
        common.save_summary(output, records, len(plan))
        print(f"  {record['status']}; median total_ms={record['process_summary']['median'].get('total_ms')}; warnings={warnings}", flush=True)
        if interrupted:
            return 130
    return 1 if any(record["status"] != "ok" for record in records) else 0


if __name__ == "__main__":
    raise SystemExit(main())
