# Camera-rendering performance, 25 November 2026

This evidence compares the original camera-rendered RGB16 pipeline at
`baebb9c69eadf2a36cb99c4c52e9703a291967a7` with the bounded-tile optical sampler.
Both arms explicitly request `rgb16camera`, open a fresh reader and input for
every import, and develop the RAW sensor pixels. Full-image validation and
fingerprinting run outside the import timer.

The baseline source tree is also the merged PR #457 commit
`6ef4f1a7ce20e44af63722405b39e0ccb77640a4`. The original executable was built
before the edits; its JAR hash and the optimized implementation's source hashes
are recorded in [environment.json](environment.json).

The G5 input is **`sample.cr3`, the interior scene**. It is not the
`sample-wide.cr3` wall used in older camera-cost measurements. The EOS R input
is `eosr-iso-low.cr3`. Only the before/after measurements here share the same
files, hardware, flags and worker limit.

## Primary result: Java 21

The statistic is the **median of the two per-fork medians**, following the
benchmark protocol. Each fresh JVM has three warmups and five measured imports.
Fork 2 reverses the camera and arm order. `summary.json` retains every fork's
median and range; the JSONL files retain every measured record. These are warm
imports on a shared host with no explicit CPU affinity or GC between iterations.

| Input | Original import | Optimized import | Reduction | Original CPU time | Optimized CPU time |
| --- | ---: | ---: | ---: | ---: | ---: |
| G5 X II, `sample.cr3` | 744.150 ms | 671.359 ms | 9.78% | 2670 ms | 2315 ms |
| EOS R, `eosr-iso-low.cr3` | 1207.583 ms | 1023.144 ms | 15.27% | 4220 ms | 3525 ms |

The Java 21 G5 fork medians were 781.452 / 706.847 ms before and
661.643 / 681.076 ms after; the spread is visible in the retained records.
The Java 25 corroborating comparison reduces full-import time by 19.12% on
the G5 input and 16.57% on the EOS R input. Isolated optical-stage measurements
were collected on Java 25 only.

Every full camera-import fingerprint matches between the original and optimized
code on both runtimes. The complete optical-only and optical-plus-tone SHA-256
values also match. Ten deterministic optical fixtures match on Java 21, including
8/16-bit values, linear/sRGB transfer, partial shading, and strong distortion.
The processing tests additionally cover translated/interleaved rasters and
one-versus-multiple workers with stripe boundaries that do not align to the tile
grid. The `sample-wide.cr3` **explicit neutral linear benchmark mode** retains
fingerprint `f403f4543749ba88`; its timings are excluded from the performance table.

## Algorithm and memory

A 32×32 output tile records the original source coordinates and interpolation
fractions. Its source rectangle is derived from all actual taps. When that
rectangle fits 4096 doubles, the sampler calculates each shaded linear source
value once and uses the original bilinear and sRGB-quantization arithmetic.
Larger source footprints use the original scalar sampler.

Each worker's fixed arrays contain 56 KiB of payload. The memory planner reserves
64 KiB per actual worker, along with one output-channel buffer, the radial
tables and axes, a generic-raster write row, and first-use sRGB lookup tables.
The worker cap still comes from the shared CPU scheduler. The arrays are reused
across the three sequential channel passes within an import.

In Java 21 the added measured allocation is 229,880 bytes per import, about
224.5 KiB for four workers. Maximum process RSS across the two forks was
390,388 → 392,364 KiB for G5 and 373,164 → 375,300 KiB for EOS R.
RSS is the Linux process-lifetime high-water mark, including warmups; it is not
the live size of the optical workspace.

`preliminary-experiments.json` retains the rejected adjacent-tap reuse attempt
and the 16×16 tile comparison. The smaller tile helped EOS R but slowed G5
relative to 32×32, so the implementation uses one common 32×32 size.

## Environment and identities

See `environment.json` for the full input, JAR, implementation-source and harness
SHA-256 values. The machine was an AMD EPYC 9V74; all processes used:

```text
-Xmx768m -XX:+UseSerialGC -XX:ActiveProcessorCount=4
--workers 4 --warmup 3 --iterations 5 --checksum
```

The Java 21 runtime was OpenJDK 21.0.13.1+2-1-deb13u1-Debian; Java 25 was
Temurin 25.0.5+8. Allocation uses the probe's total-all-Java-threads counter.
Elapsed and CPU figures include the full import; output checks, diagnostics and
hashing are outside the timer. Secondary pooled medians are labelled separately
in `summary.json` and are not the primary statistic.

## Reproduce

Build the original and optimized revisions with JDK 25, using each checkout's
benchmark profile, and preserve both resulting JARs:

```bash
mvn -B -Pbenchmarks -pl canon-cr3-benchmark -am package -DskipTests
```

The runner inspects the original PR head for source identities. In a checkout
that contains only the squash-merged history, fetch it first:

```bash
git fetch origin refs/pull/457/head
```

The benchmark module's bundled helper is Java 25 bytecode. For the Java 21
comparison, compile the **current `Cr3ImportProbe.java` once against the original
public JAR**, with `--release 21`, and use that same helper for both arms. The
runtime library code remains in its respective original or optimized JAR.

From this evidence directory, with shell variables pointing to the repository,
two frozen JARs, JDK executables, and a new output directory:

```bash
mkdir -p measurement-classes
"$javac25" --release 21 -cp "$baseline_jar" -d measurement-classes \
  "$repo/canon-cr3-benchmark/src/main/java/com/photographer/focusstack/cr3/benchmark/Cr3ImportProbe.java" \
  OpticalStagesProbe.java OpticalGoldenProbe.java

python3 run_measurements.py \
  --repo "$repo" \
  --baseline "$baseline_jar" \
  --optimized "$optimized_jar" \
  --java25 "$java25" \
  --java21 "$java21" \
  --harness-classes "$PWD/measurement-classes" \
  --probe-classes "$PWD/measurement-classes" \
  --output "$results"
```

Use absolute values for those path variables. The runner executes the Java 25
imports and stage measurements, the Java 21 imports, and the neutral-linear
fingerprint check sequentially. Its recorded input paths are relative to the
repository. Compile and run `OpticalGoldenProbe` on Java 21 with each JAR to
reproduce `jdk21-optical-goldens.json`; the production golden regression tests
use the same deterministic fixtures.

## Files

- `summary.json`: primary aggregate, fork medians and correctness findings.
- `environment.json`: JVM/environment settings and cryptographic identities.
- `jdk21-import-*.jsonl`: primary full-import measurements, two forks per arm/input.
- `jdk25-import-*.jsonl`: corroborating full-import measurements.
- `jdk25-stages-*.jsonl`: isolated optical/tone measurements and complete SHA-256 outputs.
- `jdk25-neutral-linear-*.jsonl`: compatibility fingerprints, excluded from timing conclusions.
- `jdk21-optical-goldens.json`: original/optimized synthetic optical fingerprints.
- `preliminary-experiments.json`: retained exploratory outcomes.
- `run_measurements.py`, `OpticalStagesProbe.java` and `OpticalGoldenProbe.java`: portable measurement harness.

The existing [Cr3ImportProbe.java](../../../canon-cr3-benchmark/src/main/java/com/photographer/focusstack/cr3/benchmark/Cr3ImportProbe.java)
supplies the shared Java 21 import helper. Its recorded source hash identifies
the measured version; the evidence does not duplicate this production probe.

Large JFR recordings, class files, build logs and binaries are excluded.
