# Complete CR3 imports with LibRaw 0.22.3 as a reference

The predictor calculates the next adaptive Rice width from the sign bits of
its existing thresholds and clamps the result to 0–15. Lossless planes and
the coarsest C-RAW band share this update. The decoder uses the same row
buffers and per-symbol rules. LibRaw is an external reference accessed
through its published C API.

## Measurement boundary

Photographer uses the public ImageIO reader in native `sensor` mode. LibRaw
uses its public C API to open and unpack the file, then copies the full Bayer
array into consumer-owned storage before closing the context. Both timings
include opening a new input, decoding the complete uncropped Bayer mosaic,
producing independently owned output and closing the input. The operating
system's file cache was warm. JVM startup, the ImageIO provider preflight,
explicit Java GC between iterations, output hashing and serialization were
outside the timed interval.

The five full 16-bit PGM files matched byte for byte, including dimensions and
all **146,508,848 sensor sites**. All fixtures contain one native
Bayer sample per pixel. The `16` in the output format describes unsigned-short
storage of native sensor codes.

Each import processed one file. Java and C processes used the same four logical CPUs
on an AMD EPYC 9V74 x86-64 Linux host. OpenJDK 21.0.13 used a 384 MiB heap cap,
Serial GC and four available processors. LibRaw used a four-thread OpenMP
limit. Three fresh processes per engine and input ran four warmups followed by
seven measured imports. The three configurations rotated their order for each
input. Times are medians of the per-process medians. Peak RSS is the median of
the three process high-water marks and includes runtime and warmed heap
memory. The process CPU time can exceed the elapsed wall time when several
workers run concurrently.

## Native Bayer results

| Input | Photographer before, ms | Photographer after, ms | LibRaw, ms | Photographer after, MiB peak RSS | LibRaw, MiB peak RSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| PowerShot G5 X Mark II RAW | 92.71 | 92.76 | 69.53 | 91.7 | 84.0 |
| EOS R RAW | 156.66 | 137.56 | 112.98 | 112.9 | 123.9 |
| PowerShot G5 X Mark II C-RAW V1 | 101.57 | 95.33 | 74.48 | 99.8 | 83.8 |
| EOS R8 C-RAW V2 | 149.05 | 138.20 | 108.30 | 106.7 | 108.3 |
| EOS R5 Mark II C-RAW V2 | 310.67 | 314.75 | 216.37 | 147.8 | 197.8 |

In this matched run, median wall time fell by 12.2% for the EOS R RAW
and by 7.3% for the EOS R8 C-RAW. The G5 X Mark II RAW wall median was
flat and spent 7.7% less process CPU. The G5 X Mark II C-RAW wall median
fell by 6.1% with higher process CPU. The R5 Mark II C-RAW wall median
increased by 1.3%. The per-fork results show variation across runs.
LibRaw had the lowest wall median on all five files.

Measured Java allocation per import matched the control on all five files.
Photographer used about 50 MiB less process RSS than LibRaw for the R5
Mark II C-RAW. Both Photographer builds also decoded that file exactly
on Java 21 with G1, one worker and a 128 MiB maximum heap. The output was
8480 × 5650 at 16-bit storage, full Bayer FNV-1a `af32b45357b534e5`.

[Per-fork wall/CPU/RSS data and complete output hashes](native-results.json)

## Developed RGB comparison

Photographer uses ordinary `ImageIO.read(File)` to develop RGB8. LibRaw uses
bilinear demosaicing (`--quality 0`), camera white balance, sRGB transfer and
camera orientation, with automatic brightness disabled. The engines use
different active crops and color processing. These are timings of the
rendered import as delivered by each engine.

| Input | Photographer before, ms | Photographer after, ms | LibRaw, ms | Photographer RGB8 size | LibRaw RGB8 size |
| --- | ---: | ---: | ---: | --- | --- |
| EOS R RAW | 268.35 | 316.78 | 914.17 | 6744 × 4500 | 6742 × 4498 |
| EOS R8 C-RAW V2 | 227.79 | 255.56 | 735.65 | 5844 × 3904 | 6022 × 4024 |

Each configuration ran three warmup reads and five measured reads in each
of three forks. The Photographer images match the preceding build in every
sample. The first panel produced higher Java median elapsed times with the
new predictor on both inputs.

[Per-fork RGB8 timings and complete output hashes](rgb8-results.json)

Each follow-up used four fresh JVMs per build and nine timed reads per JVM,
alternating the order of the builds. There were eight or ten complete
warmup reads. The intervals between imports still included untimed GC.

| Input | CPUs | Warmups | Wall ms before / after | Process CPU ms before / after |
| --- | --- | ---: | ---: | ---: |
| EOS R RAW | 4–7 | 10 | 301.6 / 273.4 | 925 / 810 |
| EOS R8 C-RAW V2 | 0–3 | 8 | 247.1 / 263.2 | 750 / 750 |
| EOS R8 C-RAW V2 | 4–7 | 10 | 234.2 / 214.8 | 735 / 700 |

The longer EOS R run used less CPU and had a shorter elapsed median with
the new predictor. On the EOS R8, elapsed medians moved in both directions
across the two repeat panels. The ordinary ImageIO speedup varies with the
workload and test run.

[Per-fork timings with longer warmup](rgb8-warmup-results.json)

## Other experiments

- A run-length lookup for C-RAW reduced Bayer wall time by 8–19% on the EOS R8
  in two screening pairs. The EOS R5 Mark II and the two V1 C-RAW inputs had
  mixed results. Limiting the lookup to nonzero remainder widths increased
  wall and CPU time on the EOS R8. The predictor keeps its existing run reader.
- An RGB16 quantizer indexed by the double exponent and mantissa preserved all
  output codes on two fixtures. Its paired timings changed direction across
  forks. A one-sided lookup increased wall and CPU time on both inputs. The
  RGB16 path keeps the existing quantizer.
- A specialized writer for full-width Bayer rows gave mixed results in the
  one-worker screening. The same row writer continues to handle full and
  sampled Bayer images.

## Pinned inputs

The first three paths are relative to the Photographer checkout. The public
files are available from raw.pixls.us under CC0.

| File | SHA-256 |
| --- | --- |
| `canon-cr3-imageio/src/test/resources/samples/sample.cr3` | `4d9f1d00d62d0c06a2448d6ea6d3f5820476c2d1a993eaa73a2352719f199a95` |
| `canon-cr3-imageio/src/test/resources/samples/eosr-iso-low.cr3` | `b919df484f0c3765f707974dc5d33a1c28143180981200cdf695133fb0e721b3` |
| `canon-cr3-imageio/src/test/resources/samples/research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3` | `e3b30d62772103c8ef88008d084cc75036cb7dd6723b874e71bb782bab16034c` |
| [EOS R8 C-RAW 6587](https://raw.pixls.us/getfile.php/6587/nice/Canon%20-%20EOS%20R8%20-%203:2.CR3) | `df33cf394573645ce03dea1b2e9f0b5cc2b7734e9e3391ee7114151414cf2812` |
| [EOS R5 Mark II C-RAW 7882](https://raw.pixls.us/getfile.php/7882/nice/Canon%20-%20EOS%20R5%20Mark%20II%20-%20CRAW%20%283:2%29.CR3) | `b3c08a5c7a97299dfd58896a12b939e7b43ec78682761812524825e6e5d087be` |

## Tool provenance

The Photographer control used the source tree from [PR #447](https://github.com/carstenartur/Photographer/pull/447/commits/5090101a502770f9a2563618ff8d426a5b17658a).

| Photographer benchmark JAR | SHA-256 |
| --- | --- |
| Control | `40e75a328449966405cfbf0e421c89ce279b39840ccfb7a304170441135e57db` |
| Adaptive Rice width update | `983a64cd61e10b3237674687887727b657bd9b2632e34bffb29ecd9a199b848e` |

The import helper was compiled using JDK 25.0.5.1 with `--release 21`, with
class SHA-256 `1dcb6244eb099f2ef2104c1c905054f3b5c197460ab3ef10e367e956a909c198`.

The LibRaw binary was the thread-safe x86-64 Conda-forge build. The package
SHA-256 values matched the published repository metadata. The client used
matching public headers and called the library as a black box.

| Artifact | SHA-256 |
| --- | --- |
| [Conda-forge LibRaw package](https://conda.anaconda.org/conda-forge/linux-64/libraw-0.22.3-hab3bfc1_0.conda) | `998bd633349c75ff01549c398e990d0e0b34df1f3673f63c880becd99d8fcc90` |
| [Conda-forge libjpeg-turbo package](https://conda.anaconda.org/conda-forge/linux-64/libjpeg-turbo-3.2.0-hb03c661_1.conda) | `bba8538e6538ed58a8479b332337b96986561f975d06cfa2039a016c2d246ee4` |
| Loaded `libraw_r.so.25.0.0` | `acc9aa0b27bc6b6712ec3c07854807aadb930790c8f0384c18a063081c15d055` |
| Loaded `libjpeg.so.8.3.2` | `27d5bc7b9c9674cc073e71e899e7d5f6dedf86f0ad4d93947f60aaee2c2b25c8` |
| C benchmark client source | `a6088ea36db2f3e8493cc7f64b4756a5d719f2047cda18e57ad98ed5069fd503` |
| Compiled client, GCC 14.2.0 | `4fdf0369a5464155f15154d615831ef08b457f7915728df70314a2595f4d32d1` |

## Replay one fork

Run from the matching Photographer checkout. Set `build_jdk` to JDK 25,
`runtime_jdk` to Java 21, `bench_dir` to a scratch directory, and `input` to
one pinned CR3 file. Fetch and verify the two prebuilt Conda archives above.
Extract each package into its own directory with
`unzip -p "$package" 'pkg-*' | tar -x --zstd -C "$directory"`.
Set `libraw_dir` and `jpeg_dir` to the extracted package roots. Build the C
client from the checkout with these public headers and libraries.

```bash
JAVA_HOME="$build_jdk" mvn -B -DskipTests -Pbenchmarks -pl canon-cr3-benchmark -am package
jar=canon-cr3-benchmark/target/benchmarks.jar
probe=com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe
mkdir -p "$bench_dir/classes"
"$build_jdk/bin/javac" --release 21 -cp "$jar" -d "$bench_dir/classes" \
  "canon-cr3-benchmark/src/main/java/${probe//.//}.java"

export LD_LIBRARY_PATH="$libraw_dir/lib:$jpeg_dir/lib"
gcc -O2 -std=c11 -Wall -Wextra -Werror -I"$libraw_dir/include" -L"$libraw_dir/lib" \
  -Wl,-rpath-link,"$jpeg_dir/lib" scripts/benchmarks/libraw_bench.c \
  -lraw_r -lstdc++ -fopenmp -o "$bench_dir/libraw-bench"
"$bench_dir/libraw-bench" --version

unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
java_sensor() {
  env -u LD_LIBRARY_PATH taskset -c 0-3 "$runtime_jdk/bin/java" \
    -Xms24m -Xmx384m -XX:+UseSerialGC -XX:ActiveProcessorCount=4 \
    -cp "$bench_dir/classes:$jar" "$probe" --input "$input" --mode sensor "$@"
}
libraw_sensor() {
  OMP_NUM_THREADS=4 OMP_THREAD_LIMIT=4 OMP_DYNAMIC=FALSE \
    taskset -c 0-3 "$bench_dir/libraw-bench" --input "$input" --mode sensor \
    --transfer linear --quality 0 --wb camera --auto-bright 0 --orientation camera "$@"
}

java_sensor --checksum --dump "$bench_dir/java.pgm"
libraw_sensor --checksum --dump "$bench_dir/libraw.pgm"
cmp "$bench_dir/java.pgm" "$bench_dir/libraw.pgm"

java_sensor --warmup 4 --iterations 7 --gc-between
libraw_sensor --warmup 4 --iterations 7
```

For RGB, replace the Java mode with `default` or `rgb16`, the C mode with
`rgb8` or `rgb16` respectively, and use `--transfer srgb`. Keep health checks
and any output dumps in separate processes from timed forks.
