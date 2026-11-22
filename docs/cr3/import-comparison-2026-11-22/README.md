# CR3 import comparison with LibRaw 0.22.2 and 0.22.3

This comparison measures Photographer 0.1.8 on Java 21 and two prebuilt LibRaw
releases through their public interfaces. It uses the same four CR3 inputs as
the [November 19 comparison](../performance-2026-11-19/README.md). Each measured
call opens a file and returns a newly decoded, independently owned image.

## Results

Each cell reports median time in milliseconds followed by peak RSS in MiB.
The time is the median of three per-process medians. The memory figure is the
median of the corresponding process high-water marks.

### Full Bayer sensor

| Input | Photographer 0.1.8 | LibRaw 0.22.2 | LibRaw 0.22.3 |
| --- | ---: | ---: | ---: |
| PowerShot G5 X Mark II RAW | 101.2 / 91.7 | 86.6 / 84.1 | 90.2 / 84.1 |
| EOS R RAW | 118.8 / 113.2 | 98.9 / 123.8 | 96.6 / 123.8 |
| PowerShot G5 X Mark II C-RAW | 78.4 / 105.0 | 60.3 / 83.9 | 61.4 / 83.9 |
| EOS M50 C-RAW | 134.6 / 110.1 | 108.9 / 101.5 | 116.7 / 101.6 |

### Developed RGB8

| Input | Photographer 0.1.8 | LibRaw 0.22.2 | LibRaw 0.22.3 |
| --- | ---: | ---: | ---: |
| PowerShot G5 X Mark II RAW | 230.6 / 137.5 | 751.2 / 258.8 | 683.3 / 258.8 |
| EOS R RAW | 307.2 / 178.9 | 1201.4 / 382.4 | 1092.4 / 382.3 |
| PowerShot G5 X Mark II C-RAW | 183.1 / 144.0 | 651.4 / 258.5 | 663.2 / 258.7 |
| EOS M50 C-RAW | 244.2 / 162.1 | 840.3 / 307.0 | 804.6 / 307.1 |

### Developed sRGB16

| Input | Photographer 0.1.8 | LibRaw 0.22.2 | LibRaw 0.22.3 |
| --- | ---: | ---: | ---: |
| PowerShot G5 X Mark II RAW | 267.0 / 180.5 | 661.3 / 317.3 | 665.5 / 317.3 |
| EOS R RAW | 430.5 / 231.5 | 1179.4 / 469.2 | 1081.3 / 469.1 |
| PowerShot G5 X Mark II C-RAW | 256.6 / 180.1 | 705.3 / 317.1 | 633.1 / 317.1 |
| EOS M50 C-RAW | 350.9 / 208.6 | 929.5 / 376.3 | 912.2 / 376.3 |

All native outputs were byte-for-byte identical across the three tested
versions. Separate, untimed checks compared complete 16-bit PGM files and
recorded a [SHA-256 of each big-endian Bayer payload](native-payloads.json).

Complete image sizes, color values and rendering policies differ across the
two engines. The RGB timings describe the cost of obtaining a developed image
at the specified output precision through each public interface. For that
comparison, LibRaw uses bilinear demosaicing, its camera white balance and sRGB
transfer, with automatic brightness disabled. [Output sizes and fingerprints](summary.json)

## Measurement setup

- AMD EPYC 9V74 Linux worker, x86-64, processes pinned to logical CPUs 0–3, with a
  limit of four workers. OpenJDK 21.0.13 used Serial GC and a 24–768 MiB heap.
- Separate open and full decode on each iteration with the file-system cache warm.
  Three fresh processes per combination used six warmups and nine recorded imports.
  The middle fork reverses engine order. JVM startup, provider scans, explicit
  between-iteration Java GC and checksums are outside the measured interval.
- The C client links prebuilt, thread-safe LibRaw and uses its public headers.
  It copies the native Bayer result into caller-owned storage before closing
  the decoder. The Java RGB8 case invokes `ImageIO.read(File)` and the RGB16 case
  selects 16-bit sRGB through the CR3 ImageIO reader. The Java native case uses
  the ImageIO reader's uncropped sensor mode.
- RSS includes allocations retained by each process, loaded libraries and VM
  activity including warmups. Per-import Java allocation in the [observations](observations.tsv)
  comes from JVM thread counters. Native allocation is marked `NA` in that
  column. These measurements describe this host and JVM
  configuration.

[Inputs, tool versions and SHA-256 pins](manifest.json) · [Process medians and output checks](summary.json)

## Reproduce

Use a checkout containing the [current C client](../../../scripts/benchmarks/libraw_bench.c),
JDK 25 for compiling the import probe and a Java 21 runtime for timing. Fetch the fixture and released Photographer JARs described
in the manifest. Put the six `io.github.carstenartur` version `0.1.8` JARs and
`slf4j-api` `2.0.20` in a temporary `jars` directory. Fetch the pinned prebuilt
Conda packages and extract each `pkg-*` archive using `unzip -p` and
`tar -x --zstd`. Keep the two LibRaw versions in separate directories, and add
the shared JPEG dependency to both runtime library search paths. The host
supplied the remaining system libraries recorded in the manifest.

From the repository root, the following commands run one sensor fork with one
LibRaw release. `bench_dir`, `libraw_dir`, `jpeg_dir` and `input` identify the
temporary run directory, the extracted package roots and the selected CR3 file.
`build_jdk` and `runtime_jdk` point to the Java installation directories.

```bash
probe='com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe'
mkdir -p "$bench_dir/classes"
"$build_jdk/bin/javac" --release 21 -cp "$bench_dir/jars/*" -d "$bench_dir/classes" \
  "canon-cr3-benchmark/src/main/java/${probe//.//}.java"

LD_LIBRARY_PATH="$libraw_dir/lib:$jpeg_dir/lib" \
  gcc -O2 -std=c11 -Wall -Wextra -Werror \
  -I"$libraw_dir/include" -L"$libraw_dir/lib" \
  scripts/benchmarks/libraw_bench.c -lraw_r -lstdc++ -fopenmp \
  -o "$bench_dir/libraw-bench"

taskset -c 0-3 "$runtime_jdk/bin/java" -Xms24m -Xmx768m -XX:+UseSerialGC \
  -XX:ActiveProcessorCount=4 -cp "$bench_dir/classes:$bench_dir/jars/*" "$probe" \
  --input "$input" --mode sensor --warmup 6 --iterations 9 --gc-between

LD_LIBRARY_PATH="$libraw_dir/lib:$jpeg_dir/lib" \
  OMP_NUM_THREADS=4 OMP_THREAD_LIMIT=4 OMP_DYNAMIC=FALSE \
  taskset -c 0-3 "$bench_dir/libraw-bench" --input "$input" --mode sensor \
  --transfer linear --quality 0 --wb camera --auto-bright 0 --orientation camera \
  --warmup 6 --iterations 9
```

Repeat for both libraries, all four inputs and three forks. Use Java mode
`default` with C mode `rgb8`, and `rgb16` in both clients for three-channel
16-bit output. Select `--transfer srgb` for both developed C output modes.
Use `--checksum --dump sensor.pgm` in separate invocations to reproduce the
full native comparisons. The PGM has a three-line header and a row-major
unsigned 16-bit big-endian payload.
