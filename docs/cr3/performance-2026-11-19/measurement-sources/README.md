# Archived measurement drivers

The main Java25, LibRaw, Java21/G1, CLI and startup groups retain their exact
Python drivers, frozen Java/C probes and input manifests. The rejected RGB8
experiment used its own earlier driver and default-package Java helper, archived
separately under [legacy-rgb8](legacy-rgb8/provenance.json). Its recorded binary
identity and retained source-based replay are distinguished below.

Python drivers require Python 3.11 or newer and the standard library; figure
rendering additionally requires Matplotlib. They launch each child sequentially
and preserve per-process commands, exit status and observations. Do not run a
build, test, profiler or another decoder while timing.

The original manifests retain their historical absolute paths. To reproduce on
another machine, copy `inputs.json` and `extra-input.json` outside this directory
and change only each `path` to the matching original CR3 file. Preserve the
SHA-256 and all other identity fields. The first three files are repository test
fixtures; the fourth is the registered CC0 EOS M50 C-RAW download linked from
`extra-input.json`. Every driver checks original input hashes before importing.

Follow [the build recipe](../comparison-setup.md) for the two public runtime
commits, one externally compiled frozen Java helper and the frozen LibRaw
client. The classpath must select that same helper before either JAR. Rebuilt
artifacts need not be byte-identical to the recorded binaries. The original
JAR/class/library hashes identify the measured artifacts, while public commits
and frozen helper sources make their implementation reproducible.

## Complete matrices

Set these paths to the local equivalents produced by the build recipe:

```bash
drivers="$repo_root/docs/cr3/performance-2026-11-19/measurement-sources"
baseline_jar="$bench_root/baseline/canon-cr3-benchmark/target/benchmarks.jar"
optimized_jar="$bench_root/optimized/canon-cr3-benchmark/target/benchmarks.jar"
input_manifest="$bench_root/inputs.json"
extra_input="$bench_root/extra-input.json"
probe_classes="$bench_root/probe"
probe_classes21="$bench_root/probe21"
java25="$JAVA_HOME/bin/java"
java21=/path/to/jdk21/bin/java
libraw_client="$bench_root/libraw-bench"
dnglab_binary=/path/to/dnglab-0.8.0
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
mkdir -p "$probe_classes21"
"$JAVA_HOME/bin/javac" --release 21 -cp "$optimized_jar" \
  -d "$probe_classes21" "$drivers/Cr3ImportProbe.java"
```

Each output directory must be new. Run these commands one at a time:

```bash
# 192 processes: four inputs, four modes, one/four CPUs, two variants, three forks.
python3 "$drivers/public_measurements.py" --stage final \
  --baseline-jar "$baseline_jar" --final-jar "$optimized_jar" \
  --java "$java25" --probe-classes "$probe_classes" \
  --input-manifest "$input_manifest" --extra-input "$extra_input" \
  --output "$bench_root/java25"

# Native and color LibRaw were recorded in separate groups, 24 + 72 processes.
python3 "$drivers/public_measurements.py" --stage libraw --modes sensor \
  --libraw "$libraw_client" --input-manifest "$input_manifest" \
  --extra-input "$extra_input" --output "$bench_root/libraw-native"
python3 "$drivers/public_measurements.py" --stage libraw \
  --modes default rgb16linear rgb16 --libraw "$libraw_client" \
  --input-manifest "$input_manifest" --extra-input "$extra_input" \
  --output "$bench_root/libraw-color"

# 24 processes: G5/EOS R, four CPUs, both RGB16 transfers, Java 21/G1.
python3 "$drivers/java21_measurements.py" --stage final \
  --input-index 0 1 --cpus 4 --modes rgb16linear rgb16 \
  --baseline-jar "$baseline_jar" --final-jar "$optimized_jar" \
  --java "$java21" --probe-classes "$probe_classes21" \
  --input-manifest "$input_manifest" --extra-input "$extra_input" \
  --output "$bench_root/java21-g1"

# 72 fresh processes, full native sensor plus PGM serialization to /dev/null.
python3 "$drivers/cli_measurements.py" --jar "$optimized_jar" \
  --java "$java25" --probe-classes "$probe_classes" \
  --libraw "$libraw_client" --dnglab "$dnglab_binary" \
  --input-manifest "$input_manifest" --extra-input "$extra_input" \
  --output "$bench_root/cli-native"

# 24 fresh JVMs: first RGB16 sRGB import, no warmup and no explicit GC.
python3 "$drivers/first_import_measurements.py" \
  --baseline-jar "$baseline_jar" --final-jar "$optimized_jar" \
  --java "$java25" --probe-classes "$probe_classes" \
  --input-manifest "$input_manifest" --output "$bench_root/first-import"
```

The archived Java25/G1 drivers are separate snapshots of the same protocol,
with explicit collector flags. Their defaults include the original workspace
layout; supply the path flags above. CPU affinity is `0` or `0–3`; adapt the
driver's affinity selection if those IDs are unavailable on another host and
record that change. Java's `ActiveProcessorCount` and OpenMP/Rayon budgets must
remain consistent with the chosen affinity.

The Java 25 groups used the frozen helper compiled for Java 25. The G1 group
uses the same source compiled with `--release 21`; each pair always shares one
helper binary. [Both class hashes](../java21-helper-binding.json) are recorded.

The main Java matrix alternates baseline/optimized order AB/BA/AB. Each process
performs three untimed warmup imports and five measured imports. Summaries use
the median of three process medians, and the median of three process peak RSS
values. Native/color LibRaw groups use the same warmup/fork counts. First-import
and native CLI groups include startup in a separate external process timer;
their records must not be pooled with warmed API observations.

## Rejected RGB8 experiment: original driver and helper

The [48-process RGB8 matrix](../rgb8-experiment/summary.json) used
[`legacy-rgb8/final_measurements.py`](legacy-rgb8/final_measurements.py), whose
SHA-256 still matches the original run manifest, and the earlier default-package
`Cr3ImportProbe` class. That measured class has SHA-256
`10661aa49a9776305058265a93be55dbd8bbae633ecc33e8eaa1b4a23f37db13` and class-file
version 65. The retained [Java source](legacy-rgb8/Cr3ImportProbe.java) supports
source-based replay; the original run manifest pinned the compiled class rather
than its source, so its newly archived source digest is not a prior source-to-class
proof. [The provenance record](legacy-rgb8/provenance.json) makes this distinction
explicit. The binary is not included.

Apply the [isolated RGB8 patch](../rgb8-rejected.patch) to a clean baseline
worktree, build its benchmark JAR with the same Maven command, and assign that
JAR path to `rgb8_jar`. Compile the retained helper separately and use it for both
variants:

```bash
legacy_classes="$bench_root/probe-rgb8"
mkdir -p "$legacy_classes"
"$JAVA_HOME/bin/javac" --release 21 -cp "$baseline_jar" \
  -d "$legacy_classes" "$drivers/legacy-rgb8/Cr3ImportProbe.java"
python3 "$drivers/legacy-rgb8/final_measurements.py" --stage rgb8 \
  --baseline-jar "$baseline_jar" --rgb8-jar "$rgb8_jar" \
  --java "$java25" --probe-classes "$legacy_classes" \
  --input-manifest "$input_manifest" --extra-input "$extra_input" \
  --output "$bench_root/rgb8-replay"
```

The newer `public_measurements.py --stage rgb8` can repeat the same experimental
protocol with the packaged public helper. That is a new measurement, not the
helper/driver combination used for the recorded rejected experiment.

## Raw data and figure

`results.json.gz` decompresses byte-for-byte to the original `results.json`;
each group's `raw-data-binding.json` records the uncompressed SHA-256 and counts.
`observations.csv` exposes every measured import. The raw records also retain
commands and process-level summaries. The CLI and first-import groups retain
uncompressed records and nonempty stderr. Sampled checksums diagnose liveness;
full output equality is established in separate correctness manifests.

To regenerate the chart, use a scratch copy of the completed Java25 data:

```bash
mkdir -p "$bench_root/figure-data"
gzip -dc "$drivers/../java25/results.json.gz" > "$bench_root/figure-data/results.json"
cp "$drivers/../java25/summary.json" "$bench_root/figure-data/summary.json"
python3 "$drivers/render-performance-figure.py" \
  --input-dir "$bench_root/figure-data" --output-dir "$bench_root/figure"
```

The renderer rejects incomplete or inconsistent matrices. Range bars describe
the three observed fork medians or peaks; they are not confidence intervals.

AI assistance: ChatGPT assisted the measurement tools, analysis and documentation.
