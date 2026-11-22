# Adaptive C-RAW performance on EOS R8 and R5 Mark II

The profiling panel uses the CC0 EOS R8 file 6587 and the EOS R5 Mark II full-frame file 7882. Both use the qualified single-tile adaptive C-RAW profile. Each measured import opens and closes a new reader and input through the ImageIO probe. Native Bayer and ordinary `ImageIO.read(File)` RGB8 reads return complete images. The JVM and operating-system file cache are warm. [Fixture registry](../../scripts/research/public-crx/adaptive-craw.tsv) · [Import probe](../../scripts/benchmarks/README.md)

## CPU profile

JDK 25.0.5.1, Serial GC, 768 MiB maximum heap and four available logical CPUs on an AMD EPYC 9V74. Each recording covers eight warmups and fourteen measured imports using the 2 ms JFR execution sampling period. These percentages count the top Java frame of each execution sample. They describe where profiles sampled execution, with warmup included.

| Fixture and mode | Java samples | Detail and run decoding | Horizontal lifting | Quantization expansion |
| --- | ---: | ---: | ---: | ---: |
| R8 Bayer | 3530 | 55.1% | 23.7% | 5.0% |
| R5 Mark II Bayer | 6962 | 55.2% | 17.2% | 3.5% |
| R8 RGB8 | 6828 | 28.9% | 7.0% | 2.8% |
| R5 Mark II RGB8 | 11573 | 31.3% | 0.3% | 2.0% |

In the R5 Mark II RGB8 profile, `Math.toIntExact` accounts for another 10.4% of top samples, principally under checked lifting. Quantization-map expansion uses a few percent of Java samples, so the experiment focused on the lifting loops. The baseline already used integer lifting on the R8, which supplies a control.

## Bounded integer lifting

The adaptive detail gains and largest admitted quantization step bound the signed coefficient magnitudes at each scale. With the magnitude unit `C = 2^20`, the bounds on the three detail bands are `(1, 1, 1)`, `(51, 51, 102)` and `(102, 102, 204)`. Each horizontal low row is bounded by `LL + 1.5X` and each horizontal high row by `Y + 1.5XY`. The vertical update adds at most half the high-row bound. The prediction adds at most another high row.

| Scale | Input LL bound ÷ C | Horizontal low bound ÷ C | Horizontal high bound ÷ C | Vertical even bound ÷ C | Output bound ÷ C |
| --- | ---: | ---: | ---: | ---: | ---: |
| Coarse | 1 | 3 | 3 | 5 | 8 |
| Middle | 8 | 85 | 204 | 187 | 391 |
| Fine | 391 | 544 | 408 | 748 | 1156 |

The table rounds upward in units of `C`. The largest predictor sum is bounded by `1496C` and the largest update sum by `816C + 2`. Negative limits and repeated edge values satisfy the same bounds. All intermediates fit in a signed Java `int` for the admitted adaptive profile.

## Candidate screens

The four-CPU screens used two fresh JVMs per arm, interleaved in AB and BA order with seven warmups and nine timed imports per JVM. The first adaptive arithmetic candidate selected integer lifting for every admitted V2 profile and removed the now-unused maximum-quantizer scan. The independent horizontal candidate separated the single-tile loop from horizontal neighbor handling and preserved even and odd edge extension. The four-CPU results varied with process and import mode.

The one-CPU R5 Mark II RGB8 confirmation for the first adaptive candidate reduced median wall time by 8–16% across three pairs. The R8 Bayer control increased process CPU time from 350 to 420 ms and from 380 to 430 ms. A narrower experiment kept the maximum scan and widened its guard to `step(maximum) <= 102`. The revised R8 Bayer control used 360 and 350 ms CPU versus baseline medians of 360 and 340 ms. Median managed allocation was identical across all pairs for this narrower candidate.

| R5 Mark II RGB8 pair | Order | Baseline wall (ms) | Candidate wall (ms) | Baseline CPU (ms) | Candidate CPU (ms) |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | AB | 2765.515 | 3724.670 | 1350 | 1480 |
| 2 | BA | 1626.720 | 1354.897 | 1590 | 1350 |
| 3 | AB | 1432.822 | 1430.472 | 1420 | 1410 |
| 4 | BA | 1368.389 | 1295.858 | 1370 | 1290 |

The first pair ran under marked scheduling contention. The quieter pairs ranged from near parity to 17% lower wall time. The arithmetic candidates and the horizontal candidate received an inconclusive performance verdict. [All paired process medians](adaptive-decoding-performance/paired-process-medians.tsv)

## Reproduce

Build the benchmark JAR and download the hash-checked fixtures. The baseline source was revision `6ff5e34920f943354bf843cd72cba16a734ae54d`. The measured baseline JAR SHA-256 was `b56efea79b4009a2d9b0d2d5c23b60f01c411f426983092c7d3ad04630302f91`.

```sh
cache=/absolute/path/to/cr3-reference-cache
bash scripts/research/public-crx/prepare-sensor-fixtures.sh "$cache" \
  scripts/research/public-crx/adaptive-craw.tsv
mvn -B -Pbenchmarks -pl canon-cr3-benchmark -am -DskipTests package

java -XX:+UseSerialGC -XX:ActiveProcessorCount=4 -Xms24m -Xmx768m \
  '-XX:StartFlightRecording=filename=adaptive.jfr,settings=profile,dumponexit=true,jdk.ExecutionSample#period=2ms' \
  -cp canon-cr3-benchmark/target/benchmarks.jar com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe \
  --input "$cache/7882.cr3" --mode default --warmup 8 --iterations 14 --gc-between
jfr print --json --events jdk.ExecutionSample adaptive.jfr
```

Run the same command with `--mode sensor` to isolate Bayer decoding. For timing, omit the recording flag, pin both arms to the same CPUs and alternate fresh processes. The one-CPU confirmations set `ActiveProcessorCount=1` and used four warmups and five timed imports per JVM. The table reports each process median, including congested pairs. Managed allocation covers all observed Java threads. RSS is the process high-water mark. The screens use sampled image checksums for liveness.
