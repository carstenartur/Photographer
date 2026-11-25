# Exact RGB16 quantization: complete-import measurements

25 November 2026. Baseline: `567983ff58790dd1390c3bd89c986429d09e7cbf`, after
PR #458 enabled lens/tone defaults and optimized optical tiling. This study
measures a further change to the final sRGB16 quantizer. Both arms run the same
fully profiled default development; disabling a processing stage does not explain
any reported gain.

## Retained change

The quantizer obtains a candidate code directly from a bounded, certified seed
table, then checks the original double against the exact output boundary.
Selecting between the two possible codes uses the distance sign bit. The change
removes repeated seed interpolation and a frequent data-dependent choice.
[Algorithm, certification and memory lifetime](algorithm.md)

This implementation works on Java 21 and Java 25 without enabling the Vector API.
The explicit RGB16 SIMD prototypes investigated in this study were rejected;
the existing optional RGB8 matrix and C-RAW wavelet SIMD paths are unchanged.
The measurements below do not pass `--add-modules=jdk.incubator.vector`.

## Complete default imports

Every invocation uses a fresh `ImageIO.read(File)` with normal options and the
automatic worker setting (`0`). The JVM exposes four processors and the process
is pinned to logical CPUs 4–7 of an AMD EPYC 9V74. Both arms use `-Xmx768m`,
Serial GC and the same probe. The OS file cache is warm. Complete image hashing
and output validation occur outside the timed import.

For each runtime, input and arm, three fresh JVMs each perform five warmup and
seven measured imports. Arm order is reversed in the second fork, and input and
runtime order are rotated. Each table entry is the median of three per-fork
medians. There are **252 measured imports in 36 fresh JVMs**, in addition to
warmups; these are not 252 independent JVM forks.

The automatic memory policy is active. Each Java 25 EOS R launch logged four
transitions to compact processing with a one-worker limit, in both baseline and
updated arms. The notices have no iteration timestamps, so they cannot be
assigned to particular measured imports. These runs expose four processors;
they do not enforce four workers throughout. All launch diagnostics are retained.

| Java | Capture | Baseline, ms | Updated, ms | Elapsed reduction | CPU baseline → updated, ms | CPU reduction |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 21 | G5 X II, interior | 667.6 | 575.2 | 13.8% | 2250 → 1840 | 18.2% |
| 21 | EOS R, low ISO | 1067.6 | 977.3 | 8.5% | 3640 → 3260 | 10.4% |
| 21 | G5 X II, wide scene | 750.5 | 556.8 | 25.8% | 2480 → 1930 | 22.2% |
| 25 | G5 X II, interior | 639.3 | 581.6 | 9.0% | 2170 → 1880 | 13.4% |
| 25 | EOS R, low ISO | 1117.9 | 1038.5 | 7.1% | 3590 → 3190 | 11.1% |
| 25 | G5 X II, wide scene | 672.6 | 582.2 | 13.4% | 2380 → 1990 | 16.4% |

Every measured result has the same full-image fingerprint, dimensions, three
channels and 16-bit precision as its paired baseline. Both G5 images are
5496 × 3668; EOS R is 6744 × 4500. This comparison preserves the established
rendering, rather than assessing its agreement with Canon's JPEG.

The shared machine had visible scheduling variation. For example, Java 25 EOS
baseline fork medians were 1184.8, 1088.6 and 1117.9 ms; updated medians were
1038.5, 955.3 and 1261.2 ms. The final fork's elapsed time regressed even while
its process CPU time fell from 3640 to 3340 ms. All forks remain in the result.
The CPU reductions across the six aggregate comparisons are 10.4–22.2%; elapsed
reductions are 7.1–25.8%. These are observations for these inputs, JVMs and flags,
not universal hardware or per-photo speed guarantees.

- [Aggregate results and individual fork medians](warm-summary.json)
- [All measured records, tagged with runtime, arm and fork](warm-records.jsonl)
- [Commands and per-fork medians](warm-runs.json)
- [JVM/CPU configuration, source/JAR/input hashes and measurement scope](warm-environment.json)
- [Complete per-launch diagnostics, including AUTO worker reductions](warm-diagnostics.json)

The `file` names in the run manifest identify the original per-launch stdout
files produced by the runner. Their JSON records are consolidated unchanged,
with experiment grouping fields added, in `warm-records.jsonl`.

## Allocation and memory

The new encoder tables occupy 1,114,120 payload bytes, an increase of 458,744
bytes (about 448 KiB) over the former arrays. They are allocated once by the lazy
holder and shared across imports and workers. No image-size or per-worker array
was added. The planner's extra shared-byte field can add a few bytes to each
planning record; measured per-import allocation medians differ by at most
40 bytes across these comparisons, with four comparisons showing +8 bytes.

Heap planning now carries these tables through development and later copies,
including lens-only RGB8. It deducts an already-counted optical table component
before comparing optical, selection and caller-copy needs. RAW and linear reads
and neutral RGB8 do not receive the shared encoder addition. The optical
workspace estimate still conservatively includes transfer tables for linear
optical processing, as it did before this change.

The recorded RSS is the process lifetime high-water mark, including warmups,
JIT compilation and heap growth. It is not the live workspace or a cold-start
measurement. The Java 25 runs grow the Serial-GC heap substantially in both
arms. Compare paired RSS as a process diagnostic and use the separately stated
array payload for the new tables. Raw heap/allocation/RSS counters remain in the
records. In `warm-summary.json`, RSS is the maximum across all forks; elapsed,
CPU, allocation and used-heap values are medians of the per-fork medians.

## G1, first import and constrained heap

[Additional environments, every fork, diagnostics and packaged checks](additional-results.json)
and [all 116 additional import records](additional-records.jsonl) preserve these
separate protocols. Combined with the primary study, all **368 measured imports**
have matching full-output identities.

### Warm Java 25 with G1

Two fresh JVMs per arm/input, five warmups and seven measurements per JVM use
the same default API, four exposed/pinned processors and 768 MiB heap. The
aggregate is the median of two per-fork medians (their average). All 84 measured
outputs match. This confirms a warm benefit with a different collector, with
a smaller elapsed improvement on EOS than the primary Serial-GC comparison.

| Capture | Baseline, ms | Updated, ms | Elapsed reduction | CPU reduction |
| --- | ---: | ---: | ---: | ---: |
| G5 X II, interior | 591.9 | 518.5 | 12.4% | 16.0% |
| EOS R, low ISO | 1008.4 | 970.5 | 3.8% | 10.8% |
| G5 X II, wide scene | 632.1 | 524.4 | 17.0% | 17.2% |

### First import in a fresh JVM

Three fresh JVMs per arm/input/runtime each perform one import with no warmup,
using Serial GC and 768 MiB. The OS file cache is not flushed; plugin discovery
and the probe's common input preparation precede the import timer. These 24
observations show **no consistent first-import improvement**. CPU measurements
are mixed as well and remain in the raw records.

| Java | Capture | Baseline, ms | Updated, ms | Elapsed reduction (negative means slower) |
| --- | --- | ---: | ---: | ---: |
| 21 | G5 X II, interior | 1398.9 | 1386.7 | 0.9% |
| 21 | EOS R, low ISO | 1963.7 | 2003.4 | -2.0% |
| 25 | G5 X II, interior | 1531.3 | 1641.4 | -7.2% |
| 25 | EOS R, low ISO | 2089.2 | 1951.4 | 6.6% |

A separate first-table-use probe, after common converter/logger initialization,
measured medians of 17.49 → 21.38 ms on Java 21 and 18.27 → 22.43 ms on Java 25
(three fresh JVMs per arm/runtime). The extra table setup is about four
milliseconds once; it does not explain all first-import variation from loading,
compilation and scheduling. The accepted benefit is repeated, warmed imports.

### 256 MiB G1 compatibility

One fresh first import per arm, camera and runtime tested the G5 interior and
EOS R with `-Xmx256m -XX:+UseG1GC`. All eight runs completed and retained full
RGB16 output equality. They exercise the normal AUTO memory policy and are
successful-fit checks, not a minimum-heap claim or a repeated latency study.
Their individual first-import times are mixed, including a slower Java 25 G5
candidate; all records and worker diagnostics are included above.

### Verification

The packaged candidate passed the six production encoder tests and the actual
owning-array payload test on Java 21, Java 25 and Java 25 with the Vector API
module enabled. The launch logs identify the frozen candidate JAR. No rejected
RGB16 vector class is packaged.

The local canonical `bash scripts/verify-product.sh` ran without skip flags. All
product modules through CR3 ImageIO, ImageJ and CLI passed on the final source;
the application integration phase was blocked by unavailable Docker and its
Testcontainers initialization failures (57 errors, no assertion failures).
The full product gate must pass on the final hosted CI revision before merge.
[Local verification scopes and review checkpoints](verification.json)

## Investigated alternatives

[Exploratory hypotheses, paired screening summaries and output identities](investigation.json)
are retained separately from the final multi-fork comparison. Different screen
protocols and host contention make their absolute times unsuitable for cross-
experiment rankings.

| Experiment | Decision |
| --- | --- |
| RGB16 SIMD interpolation/matrix, 256 bits | Rejected. Fixing vector boxing removed a large allocation increase, but complete imports remained slower. |
| SIMD with batched exact quantization, 256 bits | Rejected. No useful CPU reduction. |
| SIMD with batched exact quantization, 512 bits | Rejected after pinned confirmation. G5 CPU was unchanged; EOS and wide-scene elapsed times regressed. |
| Tone peak/cutoff hoisting | Rejected. No repeatable general gain; G5 regressed. |
| Splitting optical tile methods | Rejected. Stage results were mixed and one fork had contention affecting unchanged tone work too. |
| One-sided uniform interpolation, with reference or one-increment correction | Rejected. Exactness held, but complete-import results regressed or remained mixed. |
| Smaller exponent-based interpolated seed table | Rejected. No G5 benefit and an EOS regression. |
| Direct certified seeds with signed return branches | Promising precursor; superseded by the measured absolute-distance/sign-bit version. Its mixed first-import results remain in the investigation record. |

Profiling attributed most sampled encoder calls to optical remapping. These were
Java execution samples, including some untimed hashing in the recording, rather
than isolated instruction timings. We did not infer that the encoder's rare
correction loop alone consumed the attributed time.

## Reproduction

Build the baseline and candidate from their respective revisions with the same
JDK 25 and Maven setup:

```sh
mvn -B --no-transfer-progress -Pbenchmarks -pl canon-cr3-benchmark -am clean package -DskipTests
```

Keep each `canon-cr3-benchmark/target/benchmarks.jar` outside a build directory
before building the other revision. Set `CR3_BASELINE_JAR`, `CR3_CANDIDATE_JAR`,
`CR3_JDK21`, `CR3_JDK25`, `CR3_MEASURE_CLASSES` and `CR3_RESULTS` to absolute paths.
The two JDK variables name JDK homes. From the candidate checkout, compile one
identical probe for both arms and both runtimes:

```sh
mkdir -p "$CR3_MEASURE_CLASSES"
"$CR3_JDK25/bin/javac" --release 21 -cp "$CR3_CANDIDATE_JAR" \
  -d "$CR3_MEASURE_CLASSES" \
  canon-cr3-benchmark/src/main/java/com/photographer/focusstack/cr3/benchmark/Cr3ImportProbe.java
python3 docs/cr3/srgb-quantization-performance-2026-11-25/run_comparison.py \
  --repo "$PWD" --baseline "$CR3_BASELINE_JAR" --candidate "$CR3_CANDIDATE_JAR" \
  --java21 "$CR3_JDK21/bin/java" --java25 "$CR3_JDK25/bin/java" \
  --classes "$CR3_MEASURE_CLASSES" --output "$CR3_RESULTS" \
  --cpus 4-7 --workers 4 --configurations jdk21 jdk25 \
  --forks 3 --warmup 5 --iterations 7
```

Choose four permitted CPUs on another host; retain the same affinity for both
arms. `--workers` sets `ActiveProcessorCount` for this experiment; the normal
ImageIO worker option stays automatic. The runner rejects injected JVM options,
checks every measured output identity and preserves failed launches too. It
records commands, individual observations and aggregation inputs.

Use a separate output directory for each follow-up protocol. G1 uses `--gc g1`;
first-import comparisons use `--warmup 0 --iterations 1`; constrained-heap checks
add `--heap 256m`. None of these flushes filesystem caches. The optional
`jdk25-vector` configuration is a runtime compatibility check, not a requirement
for the retained optimization.

`Srgb16Checks.java` runs the production encoder and payload tests against a
packaged JAR. Compile it together with `Srgb16EncoderTest.java` and
`Rgb16MemoryEstimateTest.java`, using their resolved JUnit test dependencies and
`--release 21`; put the packaged JAR first on the launch classpath. The separate
`Srgb16StartupProbe.java` measures one first table use after common converter and
logger initialization, in a fresh JVM for each observation.
