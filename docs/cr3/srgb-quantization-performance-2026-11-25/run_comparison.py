#!/usr/bin/env python3
"""Paired complete ImageIO imports; preserves every record and checks full outputs."""
import argparse
import hashlib
import json
import os
import platform
import statistics
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
for name in ("repo", "baseline", "candidate", "java21", "java25", "classes", "output"):
    parser.add_argument("--" + name, type=Path, required=True)
parser.add_argument("--baseline-ref", default="567983ff58790dd1390c3bd89c986429d09e7cbf")
parser.add_argument("--forks", type=int, default=3)
parser.add_argument("--warmup", type=int, default=5)
parser.add_argument("--iterations", type=int, default=7)
parser.add_argument("--workers", type=int, default=4,
                    help="ActiveProcessorCount; the normal ImageIO worker limit remains automatic (0)")
parser.add_argument("--heap", default="768m")
parser.add_argument("--gc", choices=("serial", "g1"), default="serial")
parser.add_argument("--cpus", default="")
parser.add_argument("--configurations", nargs="+", choices=("jdk21", "jdk25", "jdk25-vector"),
                    default=["jdk21", "jdk25", "jdk25-vector"])
parser.add_argument("--samples", nargs="+", default=["sample.cr3", "eosr-iso-low.cr3", "sample-wide.cr3"])
args = parser.parse_args()
if min(args.forks, args.iterations, args.workers) < 1 or args.warmup < 0:
    parser.error("forks, iterations and workers must be positive; warmup must be nonnegative")
args.output.mkdir(parents=True, exist_ok=True)
flags = ["-Xmx" + args.heap, "-XX:+UseSerialGC" if args.gc == "serial" else "-XX:+UseG1GC",
         f"-XX:ActiveProcessorCount={args.workers}"]
probe = "com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe"
sample_root = Path("canon-cr3-imageio/src/test/resources/samples")
probe_source = Path("canon-cr3-benchmark/src/main/java/com/photographer/focusstack/cr3/benchmark/Cr3ImportProbe.java")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def output(command):
    result = subprocess.run(command, cwd=args.repo, capture_output=True, text=True, check=True)
    return (result.stdout + result.stderr).strip()


def write(name, value):
    (args.output / name).write_text(json.dumps(value, indent=2) + "\n")


changed = output(["git", "diff", "--name-only", args.baseline_ref, "--", "*.java"]).splitlines()
untracked = output(["git", "ls-files", "--others", "--exclude-standard", "--", "*.java"]).splitlines()
sources = sorted({str(probe_source), *changed, *untracked})
source_hashes = {path: sha(args.repo / path) for path in sources if (args.repo / path).is_file()}
versions = {name: output([str(getattr(args, name)), "-version"]) for name in ("java21", "java25")}
injected = {name: os.environ.get(name) for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")}
if any(injected.values()):
    raise RuntimeError("Clear externally injected JVM options before measuring: " + str(injected))
manifest = {
    "baseline_ref": args.baseline_ref,
    "jars_sha256": {arm: sha(getattr(args, arm)) for arm in ("baseline", "candidate")},
    "source_sha256": source_hashes,
    "inputs_sha256": {str(sample_root / sample): sha(args.repo / sample_root / sample) for sample in args.samples},
    "jvm_versions": versions,
    "jvm_flags": flags,
    "configurations": args.configurations,
    "vector_option": "--add-modules=jdk.incubator.vector",
    "cpu_affinity": args.cpus or None,
    "available_affinity": sorted(os.sched_getaffinity(0)),
    "platform": platform.platform(),
    "cpu_model": next((line.split(":", 1)[1].strip() for line in Path("/proc/cpuinfo").read_text().splitlines()
                       if line.startswith("model name")), "unavailable"),
    "available_processors": args.workers, "requested_workers": 0,
    "forks": args.forks, "warmup": args.warmup, "iterations": args.iterations,
    "mode": "default",
    "timing_scope": "Fresh ImageIO.read(File) per invocation; validation and complete hashing outside timer.",
    "cache_scope": "Fresh JVM per arm/fork; filesystem caches are not flushed. Zero warmup is a first-import measurement, not cold storage.",
    "aggregation": "Median of per-fork medians; raw records and each fork median retained.",
    "rss_scope": "Process lifetime high-water mark including warmup; not live workspace or a cold-start measure.",
}
write("environment.json", manifest)
results = []
fingerprints = {}
for fork in range(args.forks):
    configurations = args.configurations[fork % len(args.configurations):] + args.configurations[:fork % len(args.configurations)]
    samples = args.samples if fork % 2 == 0 else list(reversed(args.samples))
    arms = ["baseline", "candidate"] if fork % 2 == 0 else ["candidate", "baseline"]
    for configuration in configurations:
        java = args.java21 if configuration == "jdk21" else args.java25
        for sample in samples:
            for arm in arms:
                command = [str(java), *flags]
                if configuration.endswith("-vector"):
                    command.append("--add-modules=jdk.incubator.vector")
                command += ["-cp", str(args.classes) + os.pathsep + str(getattr(args, arm)), probe,
                            "--input", str(sample_root / sample), "--mode", "default", "--warmup", str(args.warmup),
                            "--iterations", str(args.iterations), "--workers", "0", "--checksum"]
                if args.cpus:
                    command = ["taskset", "-c", args.cpus, *command]
                label = f"{configuration}-{Path(sample).stem}-{arm}-fork{fork + 1}"
                result = subprocess.run(command, cwd=args.repo, capture_output=True, text=True)
                # Preserve failed/OOM launches as well as successful observations.
                (args.output / (label + ".jsonl")).write_text(result.stdout)
                if result.stderr:
                    (args.output / (label + ".stderr")).write_text(result.stderr)
                result.check_returncode()
                records = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
                if len(records) != args.iterations:
                    raise RuntimeError(f"Unexpected record count for {label}: {len(records)}")
                for record in records:
                    identity = (record["width"], record["height"], record["channels"], record["bits"], record["fnv1a64"])
                    expected = fingerprints.setdefault(sample, identity)
                    if identity != expected or identity[2:4] != (3, 16):
                        raise RuntimeError(f"Full RGB16 output differs: {label}: {identity} != {expected}")
                medians = {key: statistics.median(record[key] for record in records)
                           for key in ("total_ms", "cpu_ms", "total_thread_allocated_bytes", "heap_used_bytes")}
                medians["maxrss_kb"] = max(record["maxrss_kb"] for record in records)
                entry = {"configuration": configuration, "sample": sample, "arm": arm, "fork": fork + 1,
                         "file": label + ".jsonl", "command": command, "medians": medians}
                results.append(entry)
                write("runs.json", results)
                print(json.dumps({"completed": label, **medians}), flush=True)

comparisons = []
for configuration in args.configurations:
    for sample in args.samples:
        arms = {}
        for arm in ("baseline", "candidate"):
            rows = [entry["medians"] for entry in results
                    if (entry["configuration"], entry["sample"], entry["arm"]) == (configuration, sample, arm)]
            arms[arm] = {key: statistics.median(row[key] for row in rows) for key in rows[0]}
            arms[arm]["maxrss_kb"] = max(row["maxrss_kb"] for row in rows)
            arms[arm]["fork_total_ms"] = [row["total_ms"] for row in rows]
        comparisons.append({"configuration": configuration, "sample": sample, **arms,
                            "time_reduction_percent": 100 * (1 - arms["candidate"]["total_ms"] / arms["baseline"]["total_ms"]),
                            "output_identity": fingerprints[sample]})
for path, digest in source_hashes.items():
    if sha(args.repo / path) != digest:
        raise RuntimeError("Source changed during comparison: " + path)
write("summary.json", {"comparisons": comparisons, "all_outputs_equal": True})
