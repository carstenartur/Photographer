# Complete CR3 import probes

These tools measure a fresh file import into an owned image. The Java probe uses
the project's public ImageIO/decoder interfaces. The C probe is a standalone
black-box client of the documented LibRaw 0.21.4 API; it adds no native dependency
to Photographer or its Fiji importer.

The [QA and performance evidence](../../docs/cr3/performance-2026-11-19/README.md)
records input and tool hashes, complete output comparisons, measurement settings,
and results. Keep the exact measured harness version when reproducing a recorded
run. Later argument/provider guards are distinguished from those frozen sources.

## Java probe

Build with the project's normal build JDK:

```bash
mvn -DskipTests -Pbenchmarks -pl canon-cr3-benchmark -am package
java -Xms24m -Xmx768m -XX:+UseSerialGC -XX:ActiveProcessorCount=4 \
  -cp canon-cr3-benchmark/target/benchmarks.jar \
  com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe \
  --input canon-cr3-imageio/src/test/resources/samples/sample.cr3 \
  --mode rgb16 --warmup 3 --iterations 5 --gc-between
```

Run each configuration in a fresh process when comparing peak RSS. On Linux,
prefix the command with `taskset -c 0-3` to also constrain affinity, using CPU IDs
available on the host. `ActiveProcessorCount` controls the Java worker budget;
affinity constrains where those threads can execute. The probe requires the
HotSpot `com.sun.management` extensions. Linux supplies the reported `VmHWM`;
otherwise that field is `-1`.

The benchmark module is compiled for the build JDK (25). To run this standalone
helper on Java 21, compile its source separately with JDK 25's `javac --release 21`
against the benchmark JAR, and put the resulting class directory before that
JAR on the runtime classpath. The production CR3 modules themselves target Java
21. Recorded Java 25 and Java 21 runs identify their respective helper hashes.

| Mode | Public import and output |
| --- | --- |
| `default` | Actual `ImageIO.read(File)` with automatic options; RGB8 |
| `rgb8` | Explicit ImageIO processing options; RGB8 |
| `rgb16` | Explicit ImageIO processing options; three unsigned-short sRGB bands |
| `rgb16linear` | Same RGB16 pipeline with linear output transfer |
| `sensor` | ImageIO, uncropped native unsigned-short Bayer image |
| `sensor-path` | Direct `Cr3Decoder` file input, uncropped native Bayer image |

`--workers N` applies to the explicit modes. `default` requires zero (automatic),
because it deliberately uses the ordinary ImageIO call. Use the JVM processor
budget to control that mode. The probe checks the ImageIO provider before its
loop and validates output bands/precision afterward. Use its isolated benchmark
classpath, not an unrelated application's collection of ImageIO plugins.

`--memory-mode AUTO|LOW_MEMORY|FAST` selects the explicit modes' heap policy;
the default is `AUTO`. `--orientation 1` through `--orientation 8` overrides the
camera EXIF orientation for an explicit RGB import. Omit it to retain camera
metadata. Sensor modes reject orientation overrides because their output remains
in sensor coordinates. The `default` mode rejects a nonautomatic memory policy
or any orientation override, so it still measures the actual `ImageIO.read(File)`
call. JSON records `requested_memory_mode` and `orientation_override` (zero when
omitted); these fields do not claim to expose the effective automatic decision.

For a controlled comparison of compact rotation with the normal path, run the
following twice in fresh processes, changing `FAST` to `LOW_MEMORY` and keeping
the input, heap, processor budget, JVM and other arguments identical:

```bash
java -Xms24m -Xmx768m -XX:+UseSerialGC -XX:ActiveProcessorCount=4 \
  -cp canon-cr3-benchmark/target/benchmarks.jar \
  com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe \
  --input canon-cr3-imageio/src/test/resources/samples/sample.cr3 \
  --mode rgb16 --memory-mode FAST --orientation 6 \
  --warmup 3 --iterations 5 --gc-between
```

`LOW_MEMORY` emits one `CR3 low-memory:` warning to stderr for every RAW read,
including warmups. Preserve stderr with the measurement. The
[adaptive-memory study](../../docs/cr3/memory-2026-11-19/README.md) separates
constrained-heap success checks from timing and RSS comparisons at equal heap.

Each JSON line is one measured import after the requested warmups. `total_ms`
includes opening, parsing, decoding, development, owned output and input cleanup.
Plugin scanning, provider preflight, optional GC, statistics, hashing and output
serialization are outside that timer. Provider preflight opens the file before
the loop and can warm the cache. An external process timer is required to measure
JVM startup as well. Every warmup and measured iteration decodes a new image.

`allocated_bytes` is a delta of allocation counters from live Java threads,
including small instrumentation costs; terminated threads may be missed. It is
not native allocation or peak live heap. `heap_used_bytes` is a snapshot.
`maxrss_kb` is the process-lifetime resident high-water mark, including warmups,
VM, stacks, JIT and allocator retention. `--gc-between` runs explicit GC outside
the timer; retain this setting when comparing recorded runs.

For full correctness checks add `--checksum --dump output.ppm` (or `.pgm` for
sensor output). Full FNV hashes every row-major band value as u8 or canonical
u16le; PNM files use the format's big-endian u16 encoding. The default sampled
hash covers selected complete rows and is only a liveness diagnostic.

## LibRaw C probe

Use matching public headers and a thread-safe LibRaw **0.21.4** runtime. With
the matching development package installed in standard Linux locations:

```bash
mkdir -p target
gcc -O2 -std=c11 -Wall -Wextra -Werror \
  scripts/benchmarks/libraw_bench.c -lraw_r -lstdc++ -fopenmp \
  -o target/libraw-bench
target/libraw-bench --version
OMP_NUM_THREADS=4 OMP_THREAD_LIMIT=4 OMP_DYNAMIC=FALSE \
  taskset -c 0-3 target/libraw-bench \
  --input canon-cr3-imageio/src/test/resources/samples/sample.cr3 \
  --mode rgb16 --transfer srgb --quality 0 --wb camera \
  --auto-bright 0 --orientation camera --warmup 3 --iterations 5
```

For an extracted binary package, add its include/library directories and an
appropriate runtime library search path. The evidence manifest pins the tested
Debian packages, loaded library, client source and executable. Run `--help` for
the complete argument list and `--selftest` for a small public-API Bayer input.

| Java workload | LibRaw settings |
| --- | --- |
| `sensor` | `--mode sensor` |
| `default` or `rgb8` | `--mode rgb8 --transfer srgb` |
| `rgb16linear` | `--mode rgb16 --transfer linear` |
| `rgb16` | `--mode rgb16 --transfer srgb` |

Specify the transfer explicitly: the C client's bare `rgb16` default is linear.
Quality 0 requests bilinear demosaicing. Camera WB, no auto brightness and a
similar transfer class do not make the libraries' crop, black-level, color-matrix
or numerical processing policies identical. Compare actual output dimensions.

Each iteration creates a new LibRaw context and includes open, unpack, optional
development, consumer-owned materialization, and context close. Native sensor
output includes a full copy before the context is closed; borrowing the
library-owned mosaic would be a different lifetime contract. RGB uses the
independently owned memory image. Stage timings are retained alongside the total.
Output serialization and checksums occur afterward.

Both clients' full `--checksum` formats agree. Their default sampled algorithms
differ: Java selects complete rows; C selects approximately 64 scalar samples.
Do not compare those sampled hashes as evidence of image equality.

## DNGLab and whole-process comparisons

DNGLab 0.8.0 exposes a native-sensor CLI:

```bash
RAYON_NUM_THREADS=4 taskset -c 0-3 dnglab analyze --raw-pixel input.cr3 > /dev/null
```

This operation includes process startup and PGM serialization. Compare it with
fresh Java/C processes using `--mode sensor --dump /dev/null`, timed externally,
and collect each child's own peak RSS. `/dev/null` removes persistent output I/O
but keeps serialization, buffering and write calls. Keep this table separate
from warmed API imports. Hash complete PGM sample payloads in a separate run:
valid header formatting can differ while every sensor value agrees.
