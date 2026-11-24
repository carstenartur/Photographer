# C-RAW detail runs and Rice codes

The [Java 21 sensor profile](../import-comparison-2026-11-24/sensor-profile.md) identifies detail-coefficient decoding as a major cost on the PowerShot G5 X Mark II and EOS R8. This study counts the work inside the detail decoder on four complete C-RAW inputs. Camera and input hashes are in the [comparison manifest](../import-comparison-2026-11-24/manifest.json).

## Distribution of positive runs

Each measurement decoded a complete sensor mosaic once. A positive run copies one or more zero coefficients into a detail row. The first flag selecting run mode is already consumed before the run reader starts. The last column counts subsequent one-bit extension or termination flags. The count excludes the bits of the final numeric remainder.

| Input | Positive runs | Runs copying 1–3 sites | Runs copying at least 16 sites | Mean flags per run |
| --- | ---: | ---: | ---: | ---: |
| PowerShot G5 X Mark II | 109,617 | 16.2% | 60.0% | 1.82 |
| EOS R8 | 1,238,757 | 55.3% | 9.0% | 1.99 |
| EOS R5 Mark II (APS-C) | 983,583 | 69.2% | 3.0% | 1.96 |
| PowerShot G7 X Mark III | 81,388 | 57.8% | 15.4% | 1.94 |

The G5 camera file contains many long runs. The adaptive run index often lets the decoder describe those runs with one or two flag bits. Across the four files 77–80% of runs inspected at most two flags. Grouping more flags at a time therefore costs extra work on a common path. Local trials with an accumulated prefix sum, a leading-ones scan and a separate short-run fill loop produced mixed results across cameras. Each file is one recording of its camera.

The [per-bucket counts](positive-runs.tsv) give copied sites, flag counts, the initial adaptive index the flag counts at the end of a row, and the Rice width at each detail-code read (including interruptions). The input aliases follow the order above (`g5`, `r8`, `r5`, `g7`). The final bucket of each open-ended series combines larger values. An absent bucket has zero observations. The full sensor and developed image use the same detail decoding.

## Candidate timings

The two confirmation panels compare uninstrumented class overlays against the same revision. The [short-code overlay](short-detail-prefix.patch) uses 128 entries to read complete short Rice codewords and their updated width from five bits when the incoming width is 0 or 1. Those widths occur on 3.2% of G5 detail-code reads, 78.3% on the R8, 89.8% on the R5 Mark II APS-C and 16.4% on the G7. The [width-only overlay](detail-width-prefix.patch) uses the first six code bits to identify the adaptive transition for any Rice width in a complete, ordinary residual. It retains the existing decoder for escapes and incomplete reservoir reads.

The [50 paired process comparisons](candidate-forks.tsv) give the wall time, process CPU time, managed allocation and peak RSS for each fresh JVM. Linux pinned both arms to logical CPUs 4–7 of an AMD EPYC 9V74. Java 21.0.13 used Serial GC, a 24–768 MiB heap and four available CPUs. Each JVM reopened the input for six warmups and seven measured reads. Each fork rotated the initial camera and alternated which variant ran first. Small5 used four forks and included both the R5 Mark II APS-C input and a full-frame recording (public fixture 7882). Nextshift used three forks on G5, R8 and the APS-C recording. Full-image fingerprints were checked separately on all five inputs. Both overlays matched the complete output of the reference Java version for sensor and default RGB16 reads. [R5 Mark II full-frame input](../../../scripts/research/public-crx/adaptive-craw.tsv)

For the five-bit short-code experiment, native sensor imports on the full-frame R5 Mark II improved from 295 to 266 ms. The median ordinary RGB16 import increased by 5–6% on G5, R8 and R5 Mark II APS-C. The full-frame R5 RGB16 comparison was nearly level at 631 and 635 ms. These mixed results support keeping the existing full-code decoder.

The six-bit width experiment reduced R5 Mark II APS-C sensor time in all three pairs (129 to 99 ms across forks). On the same file its RGB16 median rose from 207 to 234 ms, with both wall and CPU time increasing in the last two pairs. G5 sensor time varied by pair, with median values of 82 and 87 ms. The R8 sensor median fell from 139 to 120 ms and RGB16 changed from 296 to 289 ms. These results favor keeping the existing detail decoder across the supported recording profiles.

For each of the five originals, the complete native sensor and RGB16 fingerprints of **both** candidates matched the baseline in a fresh, untimed read. The focused Java 21 test run for each overlay also exercised all 32 five-bit prefixes, both guard states and truncated final bytes against separate flag and Rice reads. The codec tests completed with 95 successes, four aborted optional tests, one skipped test and zero failures per overlay. The candidate patches below allow these experiments to be repeated without changing the shipped runtime.

## Reproduce the counts

Use an isolated checkout of revision `6cb25a7` and compile the [public Java import probe](../../../scripts/benchmarks/README.md#java-probe) for Java 21. Set `cp` to the probe classes and matching benchmark JAR. Apply the [counter instrumentation](count-positive-runs.patch) to the isolated checkout for this experiment. It collects per-band counters and prints their totals at process exit. The timing comparison uses uninstrumented classes.

```bash
git apply --unidiff-zero /path/to/count-positive-runs.patch
sources=canon-cr3-codec/src/main/java/com/photographer/focusstack/cr3/raw/codec
classes=$(mktemp -d)
javac --release 21 -cp "$cp" -d "$classes" \
    "$sources/CrxEntropyState.java" "$sources/CrxWaveletDetail.java"
java -XX:ActiveProcessorCount=4 -cp "$classes:$cp" \
    com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe \
    --mode sensor --input "$input" --warmup 0 --iterations 1
```

Run each original in a fresh JVM. The G5 input is the repository C-RAW fixture in the comparison manifest, and the R8, R5 Mark II APS-C and G7 inputs are public originals 6587, 7884 and 6054 respectively.


To reproduce the candidate timings, apply the selected overlay to a separate checkout of the same revision with `git apply --unidiff-zero /path/to/candidate.patch`. Compile its `BitReader.java` against the matching benchmark classpath. Use baseline classes for the reference arm and put the candidate classes first on the classpath for the other arm. For each input run both `--mode sensor` and `--mode default`.

```bash
taskset -c 4-7 java -Xms24m -Xmx768m -XX:+UseSerialGC -XX:ActiveProcessorCount=4 \
    -cp "$cp" com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe \
    --input "$input" --mode "$mode" --warmup 6 --iterations 7 --gc-between
```

The JVM and OS file cache were warm. ImageIO provider discovery, explicit GC between reads, checksums and disk export were outside the timed interval. File paths and CPU affinity can be adjusted for another host.
