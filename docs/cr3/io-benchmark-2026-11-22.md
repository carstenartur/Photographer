# CR3 file access benchmark, 22 November 2026

The published 0.1.8 ImageIO and direct file paths exchanged places on two warm-cache
Bayer imports. An experiment that reduced redundant seeks and retained bytes across
window refills gave mixed results on complete imports. The comparison used 16 KiB C-RAW
and 32 KiB lossless entropy windows.

| Input and output | ImageIO, ms | Direct path, ms | ImageIO experiment, ms |
| --- | ---: | ---: | ---: |
| EOS R RAW, Bayer | 149.67 | 190.14 | 165.66 |
| EOS R RAW, default RGB8 | 326.96 | | 375.83 |
| G5 X Mark II C-RAW, Bayer | 84.52 | 81.22 | 88.94 |
| G5 X Mark II C-RAW, default RGB8 | 200.24 | | 177.22 |

Each value is the median of three JVM medians. The EOS R default control fork
medians were 350.88, 291.36 and 326.96 ms. The experiment's were 375.83, 378.49
and 258.52 ms. On the G5 C-RAW, the default control medians were 171.91, 200.24
and 227.88 ms. The experiment's were 153.87, 177.22 and 216.51 ms. These
results support a workload-specific difference under this setup. Repeating on
dedicated hardware would be needed to estimate its size.

ImageIO measured allocation stayed at 60.34 MiB for EOS R Bayer, 116.82 MiB
for EOS R RGB8, 41.79 MiB for G5 Bayer and 79.20 MiB for G5 RGB8. The experiment
kept the same maximum buffer sizes. Its change to the median process RSS ranged
from about −6 MiB to +0.2 MiB. This includes the JVM and allocator overhead.

## Reproduction

Run the [complete-import probe](../../scripts/benchmarks/README.md) with the
published 0.1.8 CR3 JARs and a probe class compiled from revision
`271db071539de2a0a2e4f5cdbeaeb432af65fba1` with `javac --release 21`. The
benchmark used Debian OpenJDK 21.0.13 and four available CPUs on an AMD EPYC 9V74,
affinity `taskset -c 0-3`, `-Xms24m -Xmx768m -XX:+UseSerialGC
-XX:ActiveProcessorCount=4`, and `--warmup 6 --iterations 9 --gc-between`.
Run three fresh JVMs per mode and rotate their order per fork. Use
`--mode sensor`, `--mode sensor-path`, and `--mode default`. The default
mode calls `ImageIO.read(File)`. Each measured iteration opens the file and
returns a fresh image. The JVM and system file cache have been warmed.

The experimental class overlay changed only `CrxTrackInput`. For a crossing
word it copied the existing window suffix to the beginning of the same buffer
and read the remaining bytes directly after it. The stream reader called
`seek` only if its current position differed from the requested offset. The
channel reader used the requested file offset for the first byte and advanced
by the count returned by each read. The overlay passed all six
`CrxTrackInputTest` cases.

Run a separate process with `--checksum` for each input and variant. All
corresponding output dimensions, bands, sample depths and complete FNV-1a-64
values agreed.

| Project fixture, relative to `canon-cr3-imageio/src/test/resources/samples` | Input SHA-256 | Bayer FNV-1a-64 | RGB8 FNV-1a-64 |
| --- | --- | --- | --- |
| `eosr-iso-low.cr3` | `b919df484f0c3765f707974dc5d33a1c28143180981200cdf695133fb0e721b3` | `bfdafa4cdea7ee7c` | `4ec22e4a80bb1e4e` |
| `research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3` | `e3b30d62772103c8ef88008d084cc75036cb7dd6723b874e71bb782bab16034c` | `a90bf97e45a21c71` | `a53bb807df5d016f` |

## C-RAW wavelet experiment

A second comparison selected checked versus bounded arithmetic once per vertical
wavelet row instead of once per coefficient. In the bounded branch, the
existing signed integer formulas ran inside straight-line loops. The class
overlay preserved row count, edge extension, array sizes and coefficients.
It passed all six `CrxWaveletTileTest` cases.

This comparison used the same probe and JVM settings, with affinity 4–7. The
G5 fixture is the C-RAW file above. The EOS M50 fixture is
[raw.pixls.us file 4656](https://raw.pixls.us/getfile.php/4656/nice/Canon%20-%20EOS%20M50%20-%203:2.CR3),
SHA-256 `27a88fdae82516ba136e12c2cc9b934f25dbaaac24d64ab4e5517f8524463594`.
Its complete Bayer and RGB8 fingerprints were `9bfade480f63376f` and
`fe7f1afca1efc8ff` in both variants. The G5 fingerprints agreed as well.

| Input and output | Control, ms | Wavelet experiment, ms | Control forks, ms | Experiment forks, ms |
| --- | ---: | ---: | --- | --- |
| G5 X Mark II C-RAW, Bayer | 97.00 | 100.60 | 110.02 / 97.00 / 87.57 | 95.39 / 105.49 / 100.60 |
| G5 X Mark II C-RAW, default RGB8 | 228.69 | 193.68 | 218.75 / 269.90 / 228.69 | 221.68 / 193.68 / 191.29 |
| EOS M50 C-RAW, Bayer | 128.99 | 145.71 | 111.13 / 128.99 / 139.37 | 143.93 / 145.71 / 152.13 |
| EOS M50 C-RAW, default RGB8 | 251.27 | 248.56 | 223.56 / 300.01 / 251.27 | 249.45 / 248.56 / 228.94 |

The Bayer workload took longer with the experiment on both inputs. RGB8
timings varied by fork and measured allocation differed by less than 1 KiB.
The timing evidence supports leaving the arithmetic selection in the original
lifting loop.

## Two-tile scheduling experiment

A third comparison started the four Bayer positions on alternate tiles by
their x parity when the consumer allowed independent tiles. Its purpose
was to reduce simultaneous stores to adjacent sensor sites. For other
consumer and worker configurations the experiment used directory order.
It passed all nine `CrxSensorDecoderTest` cases, including the tile-order test.

This comparison used the same probe and JVM settings, with affinity 4–7.
The G5 RAW input was `sample.cr3`, SHA-256
`4d9f1d00d62d0c06a2448d6ea6d3f5820476c2d1a993eaa73a2352719f199a95`.
All eight output dimensions, band counts, sample depths and full
fingerprints matched between the two variants.

| Input | Bayer control, ms | Bayer experiment, ms | RGB8 control, ms | RGB8 experiment, ms |
| --- | ---: | ---: | ---: | ---: |
| G5 X Mark II RAW | 96.52 | 111.58 | 215.35 | 193.13 |
| EOS R RAW | 173.09 | 195.80 | 352.55 | 415.80 |
| G5 X Mark II C-RAW | 77.13 | 86.90 | 180.69 | 229.85 |
| EOS M50 C-RAW | 123.71 | 146.03 | 234.20 | 287.95 |

All four Bayer medians increased. Three of the four default RGB8 medians
increased. Allocated bytes differed by at most 32 bytes across the reported
medians.
