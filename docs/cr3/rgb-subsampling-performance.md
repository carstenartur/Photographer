# RGB source subsampling performance

The ImageIO reader develops only the RGB sample sites selected by
`ImageReadParam.setSourceSubsampling`. The requested grid stays in oriented
output coordinates. Reconstruction retains full-resolution Bayer neighbours,
EXIF orientation and the optical-black calibration used by ordinary reads.
For the selected sites, RGB8 and RGB16 use their usual interpolation and
rounding. [ImageIO source regions and destinations](imageio.md#standard-imageio-regions-and-destinations)

## Complete imports with a 2 × 3 source grid

The previous and sampled paths used the same camera decoder and RGB16
Bayer-pair implementation. Each import opens the original through ImageIO,
selects every second source column and every third source row, and returns a
complete image at the sampled size. Measurements use Java 21.0.13, four
logical CPUs on an AMD EPYC 9V74, Serial GC and a 768 MiB Java heap. Each arm ran
in three alternating fresh JVM forks with four warmups and seven measured
imports per fork. The OS file cache was warm. JVM startup, explicit GC
between imports and output fingerprinting are outside the timed interval.

| Capture and output | Previous wall time | Sampled wall time | Previous allocated / peak RSS | Sampled allocated / peak RSS |
| --- | ---: | ---: | ---: | ---: |
| PowerShot G5 X Mark II RAW, RGB8 | 254.6 ms | 154.1 ms | 90.6 / 160.9 MiB | 90.4 / 156.1 MiB |
| PowerShot G5 X Mark II RAW, RGB16 | 361.9 ms | 187.7 ms | 174.1 / 303.1 MiB | 58.8 / 150.1 MiB |
| EOS R8 V2 C-RAW, RGB8 | 311.3 ms | 171.3 ms | 104.5 / 169.6 MiB | 104.2 / 161.1 MiB |
| EOS R8 V2 C-RAW, RGB16 | 423.5 ms | 201.1 ms | 199.0 / 339.5 MiB | 68.5 / 141.3 MiB |

Wall time and Java allocation use the median of the three fork medians.
Peak RSS is the median of each process's highest observed RSS. In the G5 X
Mark II RGB8 case, per-fork wall medians were 253.5, 254.6 and 300.3 ms
before, and 170.3, 154.1 and 140.1 ms after. In the R8 RGB16 case, they were
362.0, 438.1 and 423.5 ms before, and 169.5, 203.9 and 201.1 ms after.

| Capture | SHA-256 input | Output dimensions | Full RGB8 FNV-1a | Full RGB16 FNV-1a |
| --- | --- | --- | --- | --- |
| G5 X Mark II RAW, `sample.cr3` | `4d9f1d00d62d0c06a2448d6ea6d3f5820476c2d1a993eaa73a2352719f199a95` | 2748 × 1223 | `569554d8225e1156` | `fda5b95ad84f151d` |
| R8 C-RAW, archive ID 6587 | `df33cf394573645ce03dea1b2e9f0b5cc2b7734e9e3391ee7114151414cf2812` | 2922 × 1302 | `b59de6490b6971b5` | `0194e7fbcc9782c4` |

With Java 21, G1, one available CPU and a 128 MiB heap, the sampled R8 RGB16
import returned the same 2922 × 1302 image and full-output fingerprint. The
previous path exhausted the Java heap at that limit.

## Reproduce

Build the benchmark JAR with JDK 25. The [import probe instructions](../../scripts/benchmarks/README.md#java-probe)
show how to compile the probe for Java 21. The R8 original is pinned by the
[adaptive C-RAW registry](../../scripts/research/public-crx/adaptive-craw.tsv).
For a controlled comparison use the same probe class on both runtime
classpaths, the same CPU affinity and GC, and alternate their process order.

```sh
taskset -c 4-7 java -Xms24m -Xmx768m -XX:+UseSerialGC -XX:ActiveProcessorCount=4 \
  -cp "$java21_probe_classes:canon-cr3-benchmark/target/benchmarks.jar" \
  com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe \
  --input "$cr3" --mode rgb16 --subsample-x 2 --subsample-y 3 \
  --warmup 4 --iterations 7 --gc-between --checksum
```
