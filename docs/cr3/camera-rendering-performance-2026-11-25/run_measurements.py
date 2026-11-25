#!/usr/bin/env python3
"""Sequential, interleaved CR3 comparison; accepts all machine-specific paths as arguments."""
import argparse
import hashlib
import json
import platform
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
for name in ["repo", "baseline", "optimized", "java25", "java21", "harness_classes", "probe_classes", "output"]:
    parser.add_argument("--" + name.replace("_", "-"), required=True, type=Path)
args = parser.parse_args()
args.output.mkdir(exist_ok=True, parents=True)
flags = ["-Xmx768m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=4"]
sample_dir = Path("canon-cr3-imageio/src/test/resources/samples")
samples = {"g5": "sample.cr3", "eos": "eosr-iso-low.cr3", "g5-wide": "sample-wide.cr3"}
probe = "com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(label, java, arm, camera, stage=False, warmup=3, iterations=5, mode="rgb16camera"):
    jar = getattr(args, arm)
    sample = sample_dir / samples[camera]
    if stage:
        command = [str(java), *flags, "-cp", str(args.harness_classes) + ":" + str(jar),
                   "OpticalStagesProbe", str(sample), str(warmup), str(iterations), "4"]
    else:
        # The benchmark module is built on Java25. Compile the current probe once with
        # --release21 against the original JAR and use that same overlay for both Java21 arms.
        classpath = str(args.probe_classes) + ":" + str(jar) if java == args.java21 else str(jar)
        command = [str(java), *flags, "-cp", classpath, probe, "--input", str(sample),
                   "--mode", mode, "--warmup", str(warmup), "--iterations", str(iterations),
                   "--workers", "4", "--checksum"]
    result = subprocess.run(command, cwd=args.repo, capture_output=True, text=True, check=True)
    # Probe output is retained verbatim; relative input paths keep records portable.
    (args.output / (label + ".jsonl")).write_text(result.stdout)
    if result.stderr:
        (args.output / (label + ".stderr")).write_text(result.stderr)
    print("Finished " + label, flush=True)


source_files = [
    "canon-cr3-processing/src/main/java/com/photographer/focusstack/cr3/processing/OpticalCorrection.java",
    "canon-cr3-processing/src/main/java/com/photographer/focusstack/cr3/processing/Srgb16Encoder.java",
    "canon-cr3-processing/src/main/java/com/photographer/focusstack/cr3/processing/CameraToneCurve.java",
    "canon-cr3-core/src/main/java/com/photographer/focusstack/cr3/decode/Cr3Decoder.java",
    "canon-cr3-core/src/main/java/com/photographer/focusstack/cr3/decode/Cr3DecodeOptions.java",
    "canon-cr3-benchmark/src/main/java/com/photographer/focusstack/cr3/benchmark/Cr3ImportProbe.java",
]
original_ref = "baebb9c69eadf2a36cb99c4c52e9703a291967a7"
source_hashes = {}
for source in source_files:
    original = subprocess.run(["git", "show", original_ref + ":" + source], cwd=args.repo,
                              capture_output=True, check=True).stdout
    source_hashes[source] = {"baseline": hashlib.sha256(original).hexdigest(),
                             "optimized": sha(args.repo / source)}
versions = {}
for label in ["java25", "java21"]:
    version = subprocess.run([str(getattr(args, label)), "-version"], capture_output=True,
                             text=True, check=True)
    versions[label] = (version.stdout + version.stderr).strip().splitlines()
cpu_model = next((line.split(":", 1)[1].strip() for line in Path("/proc/cpuinfo").read_text().splitlines()
                  if line.startswith("model name")), "unavailable")
environment = {
    "baseline_ref": original_ref,
    "jars_sha256": {arm: sha(getattr(args, arm)) for arm in ["baseline", "optimized"]},
    "java_versions": versions,
    "os": platform.system(), "kernel": platform.release(), "architecture": platform.machine(),
    "cpu_model": cpu_model, "jvm_flags": flags, "workers": 4,
    "timed_jdks": [21, 25], "timed_forks": 2, "warmup_per_fork": 3, "measured_per_fork": 5,
    "timing_scope": "Fresh full-file reader/input per invocation; validation, full fingerprint, and diagnostics outside timer.",
    "java21_probe": "Current Cr3ImportProbe source compiled once with --release21 against the original public JAR; same helper overlay used for both arms.",
    "memory_metrics": "Probe total_thread_allocated_bytes includes all Java threads; maxrss_kb is process-lifetime Linux VmHWM.",
    "inputs_sha256": {str(sample_dir / name): sha(args.repo / sample_dir / name) for name in samples.values()},
    "sources_sha256": source_hashes,
}
(args.output / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")

# Reverse both camera and arm order in the second fork to reduce order bias.
for jdk, java, stages in [(25, args.java25, [False, True]), (21, args.java21, [False])]:
    for fork in [1, 2]:
        cameras = ["g5", "eos"] if fork == 1 else ["eos", "g5"]
        arms = ["baseline", "optimized"] if fork == 1 else ["optimized", "baseline"]
        for camera in cameras:
            for arm in arms:
                for stage in stages:
                    kind = "stages" if stage else "import"
                    run(f"jdk{jdk}-{kind}-{camera}-{arm}-fork{fork}", java, arm, camera, stage=stage)

# This compatibility record is excluded from the timed two-fork summary.
for arm in ["baseline", "optimized"]:
    run(f"jdk25-neutral-linear-g5-wide-{arm}", args.java25, arm, "g5-wide",
        warmup=0, iterations=1, mode="rgb16linear")

for source in source_files:
    if sha(args.repo / source) != source_hashes[source]["optimized"]:
        raise RuntimeError("Source changed during measurements: " + source)
