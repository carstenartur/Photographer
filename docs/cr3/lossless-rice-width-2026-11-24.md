# Lossless CRX Rice-width adaptation

Every lossless sensor row adjusts its Rice width `k` from the residual magnitude and the difference between the two neighbouring samples of the preceding row. The width falls by one below half the current scale, grows by one at three times the scale and by two at six times the scale. Expressing the same statistic in half-scale units, `(2 * value) >>> k`, puts all three thresholds at the constants 1, 6 and 12. The largest valid statistic is the sum of half a 21-bit symbol and the largest difference between two signed 21-bit coefficients, so doubling it fits in a Java `int`. The encoder, lossless decoder and adaptive quantization map reader use the same adaptation method.

## Complete imports

Compared with the [measured source tree](https://github.com/carstenartur/Photographer/tree/bd9367cee6d387534c85d5a4c798cbbb267efa32), only the width update changed. Elapsed and process CPU times are in milliseconds per freshly opened original. The table gives the median of process medians. [Individual process medians, allocations and peak RSS](lossless-rice-width-2026-11-24.tsv)

| Original and output | Alternate processes, elapsed | Alternate processes, CPU | Alternate each import, elapsed | Alternate each import, CPU |
| --- | ---: | ---: | ---: | ---: |
| EOS R RAW, Bayer | 145.7 → 109.6 | 405.0 → 325.0 | 128.7 → 104.3 | 380.0 → 320.0 |
| EOS R RAW, RGB16 | 351.0 → 319.6 | 1152.5 → 1042.5 | 342.3 → 369.2 | 1150.0 → 1150.0 |
| G5 X Mark II RAW, Bayer | 95.1 → 99.1 | 272.5 → 267.5 | 97.9 → 88.0 | 290.0 → 240.0 |
| G5 X Mark II RAW, RGB16 | 222.2 → 227.8 | 720.0 → 725.0 | 244.9 → 235.4 | 780.0 → 740.0 |

EOS R sensor decoding used less elapsed and process CPU time in both schedules. The RGB16 and G5 elapsed comparisons changed direction across schedules. For EOS R RGB16 with alternating imports, the three fork pairs were 368.3 → 369.2, 342.3 → 375.3 and 331.4 → 333.4 ms. The median process CPU time across those forks stayed at 1,150 ms. These controls establish an improvement for EOS R sensor decoding on this host. Full RGB16 import time varied with the run.

Independent call counts help interpret the [earlier sensor profile](import-comparison-2026-11-24/sensor-profile.md). Across all non-first rows, the EOS R original reached the flat-neighbour branch 514 times in 31,280,896 inner-loop visits and contained no positive run at those sites. The G5 original reached it 70,661 times in 20,855,569 visits and contained 10,492 positive runs. The profile's sampled method locations alone do not establish dynamic call counts. Altering the common Rice-width update therefore targets all ordinary symbols, including the EOS R input with rare run flags.

## Reproduction and output checks

Use the [public Java import probe](../../scripts/benchmarks/README.md#java-probe), the original fixture paths and input hashes from the [comparison manifest](import-comparison-2026-11-24/manifest.json). Both versions used AMD EPYC 9V74 logical CPUs 0–3, OpenJDK 21.0.13, `-XX:ActiveProcessorCount=4 -XX:+UseSerialGC -Xms24m -Xmx768m`, and a warm OS file cache. The `sensor` mode returns the complete Bayer mosaic. The `default` mode calls `ImageIO.read(File)` and develops full-resolution RGB16. Checksum calculation and the requested GC happen outside the measured import.

- For the alternating-process series, start a fresh JVM per version, camera and mode in each of four forks. Set `--warmup 7 --iterations 8 --gc-between`. Alternate which version starts for each fork and case, and rotate the case order by one per fork.
- For the alternating-import series, keep one JVM per version alive for each case and fork. Invoke `Cr3ImportProbe.main` with `--warmup 0 --iterations 1 --gc-between` in each JVM in turn. Reverse which version starts on successive pairs. Discard ten pairs and keep the next nine. Restart both JVMs between the three forks and between cases. This reduces the interval between timing the two versions.

Separate checks compared dimensions, precision, channels and full-image fingerprints in Bayer and default RGB16 modes across nine originals. They included three EOS R RAW exposures, two G5 RAW exposures, G5 C-RAW and the external G7 X Mark III, EOS R8 and EOS R5 Mark II APS-C C-RAW originals. All eighteen output comparisons matched. The codec threshold test exercises both sides of each adaptation boundary at every legal Rice width.
