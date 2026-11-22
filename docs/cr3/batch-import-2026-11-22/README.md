# CR3 batch import with four available CPUs

This comparison imports eight distinct CR3 files using one, two, or four
concurrent imports. The per-file worker cap is four, two, or one respectively.
Within each mode the configurations use the same four logical CPUs and maximum
heap. RGB8 and Bayer use a 768 MiB cap. RGB16 uses a separate 512 MiB panel.

## Measured throughput and memory

| Output | Files × workers | Batch ms (fork range) | Images/s | File ms | Peak RSS MiB (fork range) |
| --- | ---: | ---: | ---: | ---: | ---: |
| RGB8 | 1 × 4 | 2330 (2294–3349) | 3.43 | 267 | 333 (228–363) |
| RGB8 | 2 × 2 | 2087 (1874–2298) | 3.83 | 430 | 513 (496–652) |
| RGB8 | 4 × 1 | 2025 (1829–2381) | 3.95 | 777 | 714 (651–721) |
| Bayer | 1 × 4 | 1697 (1546–2526) | 4.71 | 181 | 229 (163–239) |
| Bayer | 2 × 2 | 1603 (1446–2417) | 4.99 | 365 | 333 (290–369) |
| Bayer | 4 × 1 | 1414 (1183–1425) | 5.66 | 500 | 507 (470–547) |
| RGB16 | 1 × 4 | 3983 (3599–4080) | 2.01 | 319 | 487 (462–502) |
| RGB16 | 2 × 2 | 3865 (3489–3981) | 2.07 | 597 | 534 (532–535) |
| RGB16 | 4 × 1 | Java heap exhausted | — | — | — |

With four concurrent files, the median batch finished about 13% sooner for
RGB8 and 17% sooner for Bayer than with one file at a time. The median peak
RSS exceeded twice the one-file baseline in both modes. A file also spent
longer in its individual read under concurrency. Throughput varied across
the runs with different input order.

In a first RGB16 verification at `-Xmx768m`, one file at a time completed.
The 2 × 2 process received SIGKILL on two attempts and the 4 × 1 process
received SIGKILL on one attempt. Those processes exited without a Java heap
exception. A new common `-Xmx512m` comparison established identical full
output fingerprints for 1 × 4 and 2 × 2. The 4 × 1 verification at that limit
reported `OutOfMemoryError: Java heap space` while allocating an RGB16 output.
Several of its reads had already switched to compact mode.

One of the 2 × 2 timing JVMs at 512 MiB received SIGKILL after two measured
batches. A repeat of that fork and the remaining planned fork both completed.
The three completed forks gave a 3% lower median batch time than 1 × 4, within
the run-to-run variation, with more peak RSS and longer individual reads.
The automatic compact-mode diagnostic appeared 13 times for 1 × 4 and 46
times for 2 × 2 across the completed RGB16 forks, including warmup.

The batch time, individual read time and throughput use the medians from three
separate JVMs. The range shows the smallest and largest per-JVM median batch
time. RSS is the median of three Linux process high-water marks, including the
JVM, managed heap and both warm-up batches. The heap limit applies to the Java
heap. The process RSS also includes VM memory outside that heap.

The individual read timer starts when a file begins processing. Batch wall
time includes the full queue, reader startup and cleanup, decode and any
in-batch garbage collection. Each result is discarded after its import. The
benchmark represents a streaming consumer that lets the decoder move on to
the next file. Retaining decoded images for later processing adds live memory.

## Correctness and measurement scope

- Every file was opened and decoded independently. Each batch contains three
  repository captures and five public CC0 captures. The panel combines
  lossless RAW and V1/V2 C-RAW with multiple image sizes up to 48 MP.
- In each mode, the completed configurations produced the same dimensions,
  precision, channel count and full FNV-1a fingerprint on all eight inputs.
  The RGB8 results also match ordinary `ImageIO.read(File)` on every input.
  Dedicated verification batches computed these fingerprints outside the
  throughput runs.
- Each timing fork ran two warm-up batches and three measured batches. Engine
  configuration order alternated, and the input array was rotated by two places
  between forks to vary which files started together. Every configuration in a
  fork used the same input order. File-system caches were warm and the JVM used
  Serial GC with explicit GC between batches, outside the timed interval. The OS
  was Linux x86-64 on an AMD EPYC 9V74 host.

[Input hashes and measurement records](measurements.json) · [Timed observations](observations.tsv)

## Replay

Collect the five public captures listed in the measurement manifest. They are
registered in [V1](../../../scripts/research/public-crx/v1-cameras.tsv) and
[V2](../../../scripts/research/public-crx/adaptive-craw.tsv). The sample
preparer can retrieve those captures from a subset of these registries, or use
already downloaded originals with matching SHA-256 values. Define `cache` as
the directory containing those five files named by public ID. Run from the
repository root. `build_jdk` points to JDK 25 and `runtime_jdk` points to the
Java 21 installation used for timing.

```bash
mvn -B -DskipTests -Pbenchmarks -pl canon-cr3-benchmark -am package
bench=canon-cr3-benchmark/target/benchmarks.jar
probe=com.photographer.focusstack.cr3.benchmark.Cr3BatchImportProbe
src=canon-cr3-benchmark/src/main/java/com/photographer/focusstack/cr3/benchmark
classes=$(mktemp -d)
"$build_jdk/bin/javac" --release 21 -cp "$bench" -d "$classes" \
  "$src/Cr3ImportProbe.java" "$src/Cr3BatchImportProbe.java"

samples=canon-cr3-imageio/src/test/resources/samples
files=(
  "$samples/eosr-iso-low.cr3" "$cache/6587.cr3" "$samples/sample.cr3" "$cache/7882.cr3"
  "$cache/7896.cr3" "$samples/research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3"
  "$cache/6648.cr3" "$cache/4671.cr3"
)

taskset -c 0-3 "$runtime_jdk/bin/java" -Xms24m -Xmx768m -XX:+UseSerialGC \
  -XX:ActiveProcessorCount=4 -cp "$classes:$bench" "$probe" 2 2 rgb8 2 3 "${files[@]}"
```

Run each combination in a fresh JVM. Replace `2 2` with `1 4` or `4 1` for the
other concurrency levels, and select `sensor` or `rgb16` for the other modes.
Use `-Xmx512m` for RGB16.
Run `0 1 --checksum` in place of the warm-up and iteration counts to check all
output pixels. The [batch probe](../../../scripts/benchmarks/README.md#bounded-batch-import-probe)
keeps the decoder's normal automatic heap policy throughout these comparisons.
