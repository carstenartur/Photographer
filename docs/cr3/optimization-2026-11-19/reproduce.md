# Reproducing the CR3 complete-import measurements

Use a Linux host with Git, Python 3, Maven, a JDK 25 build installation and a Java 21 runtime. The recorded Java 25 version is 25.0.5.1+2. The original processes used `taskset` and a matching `-XX:ActiveProcessorCount`. Run builds and verification before the timing series. Run one benchmark process at a time on idle CPUs, with no concurrent build, test, profiling or decode jobs.

## Unpack the evidence

Start at the repository root. Export `JDK25` and `JDK21` as absolute installation directories. Use a fresh directory for the replay so the original observations stay available for comparison.

```bash
export REPO="$(pwd -P)"
export REPORT="$REPO/docs/cr3/optimization-2026-11-19"
export STUDY="$(mktemp -d)"
export RUN="$STUDY/replay"
mkdir -p "$RUN" "$STUDY/recorded-timing" "$STUDY/recorded-checks"
tar -xzf "$REPORT/sources-and-patches.tar.gz" -C "$RUN"
tar -xzf "$REPORT/measured-panels.tar.gz" -C "$STUDY/recorded-timing"
tar -xzf "$REPORT/correctness.tar.gz" -C "$STUDY/recorded-checks"
```

| File | Contents |
| --- | --- |
| `provenance.json` | Archive SHA-256 digests and byte counts, measured binary and helper identities, input SHA-256s and panel completion details |
| `sources-and-patches.tar.gz` | Original drivers and configs in `qa-next/`, common runner and inputs in `qa-memory/`, probe sources in `probes/`, source patches and reconstruction map in `patches/` |
| `measured-panels.tar.gz` | Per-panel manifest, plan, raw iteration records, summaries and individual timed process commands, stdout, stderr and exit records |
| `correctness.tar.gz` | Separate full-fingerprint records, prior reference results, constrained-heap process output, `verification/final-reactor.log` and the CI verification receipt when present |
| `channel-audit.json` and `results-summary.json` | Directly readable full-output audit and per-panel timing summaries |

Compare each archive digest with its entry in `provenance.json`. Each archive also includes `SHA256.json`, a path-to-SHA-256 map for its other members. The original host details are in `qa-next/environment.json` in the source archive.

## Rebuild the production code

The frozen timing control corresponds to public runtime source `14fafdc3fbc3dd25793b94c63096cd6abb8d770a`. The measurement helper is supplied separately below. The reconstruction base for the experiments is public commit `74812b83df3a226688c0f856c200e6fd3f1cd368`, tree `378f4fb680584429afb016c3158ec3321f5248e7`.

The example builds the measured runtime tree, before the build-only follow-up described below. For an individual experiment, change `CASE` to its key in `patches/reconstruction.json` and use a fresh worktree. Apply its entire `apply_in_order_from_public_base` list. For example, the grouped C-RAW build includes both preceding codec patches, and the corrected-row build includes the earlier RGB16 encoder patch.

```bash
export CASE=final-runtime
export WKT="$STUDY/worktree-$CASE"
git -C "$REPO" worktree add --detach "$WKT" 74812b83df3a226688c0f856c200e6fd3f1cd368
python3 - <<'PY'
import json, os, subprocess
from pathlib import Path
run = Path(os.environ["RUN"])
wkt = os.environ["WKT"]
record = json.loads((run / "patches/reconstruction.json").read_text())["patches"][os.environ["CASE"]]
for patch in record["apply_in_order_from_public_base"]:
    subprocess.run(["git", "-C", wkt, "apply", "--index", str(run / patch)], check=True)
tree = subprocess.check_output(["git", "-C", wkt, "write-tree"], text=True).strip()
assert tree == record["source_tree"], (tree, record["source_tree"])
PY

export JAVA_HOME="$JDK25" PATH="$JDK25/bin:$PATH"
git -C "$REPO" worktree add --detach "$STUDY/control" 14fafdc3fbc3dd25793b94c63096cd6abb8d770a
mvn -f "$STUDY/control/pom.xml" -Pbenchmarks -pl canon-cr3-benchmark -am -DskipTests clean package
cp "$STUDY/control/canon-cr3-benchmark/target/benchmarks.jar" "$RUN/qa-memory/final-benchmarks.jar"

mvn -f "$WKT/pom.xml" -o -Pbenchmarks \
    -pl canon-cr3-imageio,cr3-imagej,canon-cr3-cli,canon-cr3-benchmark -am clean verify
cp "$WKT/canon-cr3-benchmark/target/benchmarks.jar" "$RUN/qa-next/final-benchmarks.jar"
```

The recorded final reactor used a populated Maven cache and `-o`. Omit `-o` to populate a new cache before timing. The final build receipt is `qa-next/final-build-binding.json`. For an individual screen build use the command in `qa-next/candidate-builds.json` and copy its JAR to the corresponding `qa-next/<variant>-benchmarks.jar`. Record the SHA-256 of each new JAR. ZIP metadata and build tools can change JAR bytes even for the same source tree.

## Build configuration follow-up

The measured source revision and JAR remain pinned in `final-build-binding.json`. A later change affects only the research-only Maven test selection and the research workflow path filter. It does not change Java sources. `qa-next/build-followup.json` records that revision, its validation and the comparison of rebuilt JAR runtime entries; `qa-next/research-profile-followup.patch` reconstructs the change. The main report distinguishes this build follow-up from the measured binary.

To add that configuration to the reconstructed measured tree before rebuilding:

```bash
git -C "$WKT" apply --index "$RUN/qa-next/research-profile-followup.patch"
```

Compare `git -C "$WKT" write-tree` with `followup_tree` in `build-followup.json`. The ordinary benchmark build does not activate `cr3-research-it`; the scoped setting lets an explicit downstream research test selector pass through the processing dependency while normal processing verification and the research module remain strict.

## Compile the measurement probes

Use the normal helper for default RGB8, sensor and ordinary RGB16. Use the measured extended helper for memory policy and orientation overrides. The archive also includes `probes/extended-repository/Cr3ImportProbe.java`, which documents a later malformed-input guard. The frozen extended helper used for measurement is `probes/extended-measured/Cr3ImportProbe.java`.

Compile both source sets for the intended runtime and put the resulting class directory **first** on the probe classpath. Java 21 accepts class major 65. The original Java 25 helper target is class major 69. The optional Java 25 vector backend resides in the versioned section of the final JAR.

```bash
for target in 21 25; do
    for role in normal extended; do
        src="$role"
        if [ "$role" = extended ]; then src=extended-measured; fi
        suffix=""
        if [ "$target" = 21 ]; then suffix=21; fi
        out="$RUN/qa-memory/${role}-probe${suffix}-classes"
        mkdir -p "$out"
        "$JDK25/bin/javac" --release "$target" -cp "$RUN/qa-next/final-benchmarks.jar" \
            -d "$out" "$RUN/probes/$src/Cr3ImportProbe.java"
    done
done
```

The manifests pin the actual helper class SHA-256 for each measured process family. `provenance.json` lists both compiler targets for each source. Record the digests of the helper classes used in a replay.

## Rebase the paths and run a panel

The first three inputs in `qa-memory/inputs.json` are repository fixtures under `canon-cr3-imageio/src/test/resources/samples/`. Input 3 is the registered CC0 EOS M50 C-RAW file 4656. Its URL, byte length and SHA-256 are in the same manifest. Make that file available and export `M50_FILE` as its absolute path. The following adjusts only local path fields in the replay copy. The archived inputs, scripts and configurations remain the record of the original run.

```bash
python3 - <<'PY'
import hashlib, json, os
from pathlib import Path
run = Path(os.environ["RUN"])
fixtures = Path(os.environ["REPO"]) / "canon-cr3-imageio/src/test/resources/samples"
inputs = run / "qa-memory/inputs.json"
items = json.loads(inputs.read_text())
for i, item in enumerate(items):
    file = Path(os.environ["M50_FILE"]) if i == 3 else fixtures / item["sample"]
    assert hashlib.sha256(file.read_bytes()).hexdigest() == item["sha256"], file
    item["path"] = str(file.resolve())
inputs.write_text(json.dumps(items, indent=2) + "\n")
for cfg in (run / "qa-next").glob("*.json"):
    c = json.loads(cfg.read_text())
    if not isinstance(c, dict) or "variants" not in c or cfg.name != c.get("name", "") + ".json":
        continue
    is21 = c.get("java") == "/usr/bin/java"
    c["java"] = str(Path(os.environ["JDK21" if is21 else "JDK25"]) / "bin/java")
    helper = Path(c.get("probe_classes", "normal-probe-classes")).name
    c["probe_classes"] = str(run / "qa-memory" / helper)
    for v in c["variants"].values():
        old = Path(v["jar"])
        v["jar"] = str(run / old.parent.name / old.name)
    cfg.write_text(json.dumps(c, indent=2) + "\n")
PY
```

Keep the `qa-memory` and `qa-next` path components on the JARs. Both the frozen control and the final build are named `final-benchmarks.jar`. The original panel configs, including variant-specific Java arguments and probe settings, are also present in each original manifest.

An individual ordinary RGB8 observation can be reproduced with the normal helper. The probe calls `ImageIO.read(File)` with the reader's default options. Each iteration opens the file again and creates a new image. Warmup and repeated reads may populate the OS filesystem cache. No decoded raster or result cache is shared between imports.

```bash
"$JDK21/bin/java" -version
taskset -c 0-3 "$JDK21/bin/java" -Xms24m -Xmx768m -XX:+UseSerialGC -XX:ActiveProcessorCount=4 \
    -cp "$RUN/qa-memory/normal-probe21-classes:$RUN/qa-next/final-benchmarks.jar" \
    com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe \
    --input "$REPO/canon-cr3-imageio/src/test/resources/samples/eosr-iso-low.cr3" \
    --mode default --warmup 8 --iterations 12 --gc-between
```

Use the driver to reproduce the three-fork paired comparison. For example:

```bash
python3 "$RUN/qa-next/measure_final.py" "$RUN/qa-next/final-java21.json"
```

| Final panel | Runtime and output | Warmups / measured imports per JVM |
| --- | --- | ---: |
| `final-java21` | Java 21 scalar, four files, default RGB8 and RGB16, four CPUs | 8 / 12 |
| `final-vectors` | Java 25, EOS R RAW and G5 C-RAW, default RGB8, same JAR with and without `--add-modules=jdk.incubator.vector`, four CPUs | 8 / 12 |
| `final-compact` | Java 21, EOS R RAW and G5 C-RAW, explicit LOW_MEMORY RGB8/RGB16, orientation 6, four CPUs | 3 / 5 |
| `final-one-cpu` | Java 21, EOS R RGB16, one CPU | 6 / 8 |

For another final panel, change only the configuration filename in the driver command. For the six original candidate screens, use `measure_panel.py` with the corresponding `screen-*.json` and the required screened JARs. Reproduce each panel in its own new output directory. The original runner pins CPUs 0–3 or CPU 0. If your CPU set differs, adjust the affinity in a working copy and record the change along with all JVM and host controls.

Each driver first runs a separate full-fingerprint process for each variant, input, mode and CPU setting. The timed processes subsequently alternate AB, BA, AB and hash sampled rows outside the timer. Use the median of the three process medians for wall time and the median of the three process-lifetime peak RSS values for RSS. Each `summary.json` also contains individual process medians. Preserve failed records and wide process ranges when interpreting a result.

## Verify all samples and constrained heaps

Copy the archived prior reference records to the location expected by the verification driver. Run this section on a host where `/usr/bin/java` is Java 21, or replace that literal path in a working copy of `verify_final.py` with the absolute Java 21 executable. The verification driver directly specifies CPU affinity, collector and heap for each case.

```bash
mkdir -p "$RUN/qa-memory/correctness"
cp "$STUDY/recorded-checks/qa-memory/correctness/results.json" "$RUN/qa-memory/correctness/results.json"
python3 "$RUN/qa-next/verify_final.py" --jar "$RUN/qa-next/final-benchmarks.jar" \
    --output "$RUN/qa-next/final-correctness"
```

Each verification process performs one fresh full import with `--checksum`. Compare actual width, height, `channels`, sample depth and complete row-major FNV-1a-64 against the pinned reference for that file, mode and orientation. The hash consumes an unsigned byte per RGB8 sample and two little-endian bytes per 16-bit sample. The probe validates the precision of every channel. Sensor reads have one 16-bit channel, RGB8 has three 8-bit channels and RGB16 has three 16-bit channels. Compare oriented dimensions as reported, including the axis swap for orientation 6 when it applies.

The original screen driver queried a missing `bands` key. For new original-screen replays, explicitly compare the emitted `channels` on every pair in `correctness.json` as well as the full fingerprint and other shape fields. The published `channel-audit.json` does this for the recorded panels. The final driver checks `channels` directly. For compact-mode runs, read the selected worker limit from the low-memory warning in stderr. The JSON fields `workers`, `requested_memory_mode` and `orientation_override` describe the request. Check warning counts against the total warmups and measured reads, then compare the equal-heap results. Expected FAST out-of-memory controls have their own recorded outcomes. Keep these one-import correctness diagnostics separate from the timing series.
