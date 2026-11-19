# Complete CR3 import comparison: methods and reproduction

This comparison measures newly opened, fully decoded files and consumer-owned
outputs. The [results](README.md) distinguish ordinary `ImageIO.read(File)`
RGB8, explicitly requested RGB16, native sensor decoding, and whole-process CLI
work. Embedded previews, small regions and cached decoded images are excluded.
Input reads were OS-cache-warm; no cold-storage claim is made.

## Versions, inputs and provenance

The Java baseline is commit
[`83a687e7680405b96e0ce35b82802b1f03a3d764`](https://github.com/carstenartur/Photographer/commit/83a687e7680405b96e0ce35b82802b1f03a3d764).
The optimized runtime is the tree merged by
[PR #423](https://github.com/carstenartur/Photographer/pull/423), commit
[`9b82827af545e062cf495b9d7f3a6db7b07da469`](https://github.com/carstenartur/Photographer/commit/9b82827af545e062cf495b9d7f3a6db7b07da469).
The measured build also contained the frozen timing helper; its production
runtime matches #423. [The artifact binding](final16-benchmark-manifest.json)
records its original local source ID and JAR/class/source digests. That local
source ID is not needed for the public source-based reproduction below.

Both measured runtimes precede the separate inclusive-IAD1 crop correction.
Their developed images have identical dimensions: G5 X Mark II
5495 × 3667, EOS R 6743 × 4499, and EOS M50 6023 × 4019. A current-main build
with that correction has different developed dimensions and edge samples;
it cannot silently replace the measured optimized revision.

The four timing inputs comprise the repository's `sample.cr3`,
`eosr-iso-low.cr3`, G5 X Mark II C-RAW research fixture, and the registered
[CC0 EOS M50 C-RAW, public ID 4656](https://raw.pixls.us/getfile.php/4656/nice/Canon%20-%20EOS%20M50%20-%203:2.CR3).
[The run manifest](java25/manifest.json) contains original-byte SHA-256, sizes
and the M50 download/license binding. Full sensors contain 5632 × 3710,
6888 × 4546, 5632 × 3710 and 6288 × 4056 sites respectively.
Reference-TIFF digests are not CR3 input digests.

External engines are **LibRaw 0.21.4-Release** and **DNGLab 0.8.0**.
[The preserved tool manifest](reference-tools/tool-manifest.json) pins release
executables, matching public headers, shared libraries, client source and
packages. The measured client links thread-safe `libraw_r.so.23.0.0`, SHA-256
`d7f9c71246a262895cc27c42f7447dbd5107867a8f74875299975ede0246d523`.
Obtain the corresponding binaries from:

- [DNGLab v0.8.0 Linux x64 release](https://github.com/dnglab/dnglab/releases/download/v0.8.0/dnglab_linux_x64).
- [LibRaw Debian runtime package](https://deb.debian.org/debian/pool/main/libr/libraw/libraw23t64_0.21.4-2+deb13u1_amd64.deb).
- [Matching Debian public development package](https://deb.debian.org/debian/pool/main/libr/libraw/libraw-dev_0.21.4-2+deb13u1_amd64.deb).

SRC-017 registers this LibRaw profile separately from earlier 0.22.1 reference
images. Only binary tools, public API declarations/documentation and observable
outputs were used; no third-party decoder implementation, disassembly or Canon
SDK was consulted.

## Rebuild both Java runtimes and one identical helper

Use JDK 25 for Maven and compilation, with `JAVA_HOME` and `PATH` selecting it.
Run from a Photographer checkout containing this evidence directory:

```bash
repo_root="$(git rev-parse --show-toplevel)"
bench_root="$(mktemp -d "${TMPDIR:-/tmp}/cr3-import.XXXXXX")"
git worktree add --detach "$bench_root/baseline" 83a687e7680405b96e0ce35b82802b1f03a3d764
git worktree add --detach "$bench_root/optimized" 9b82827af545e062cf495b9d7f3a6db7b07da469
for variant in baseline optimized; do
  mvn -f "$bench_root/$variant/pom.xml" -DskipTests -Pbenchmarks \
    -pl canon-cr3-benchmark -am package
done
mkdir -p "$bench_root/probe"
"$JAVA_HOME/bin/javac" --release 25 \
  -cp "$bench_root/baseline/canon-cr3-benchmark/target/benchmarks.jar" \
  -d "$bench_root/probe" \
  "$repo_root/docs/cr3/performance-2026-11-19/measurement-sources/Cr3ImportProbe.java"
```

The same external class must precede either JAR on the classpath. This preserves
the measured helper's original boundary. The newer public probe adds
provider/output/argument guards; use it for new studies, not as an undisclosed
replacement. Rebuilt JARs need not have identical binary hashes because
packaging metadata, timestamps or toolchain details can differ. Recorded hashes
identify the actual measured artifacts.

The main Java 25 runs used a class-file-69 helper. For the Java 21/G1 subset,
compile the same frozen source again with `--release 21` into a separate
directory and put that class-file-65 helper first on both classpaths. The
[helper binding](java21-helper-binding.json) records both hashes. Initial Java
21 launches rejected the Java 25 helper before decoding; those setup failures
are excluded from timing results. Both production runtime JARs support Java 21
and were unchanged.

For one configuration, reproduce three independent processes with alternating
baseline/optimized order:

```bash
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
input_file="$repo_root/canon-cr3-imageio/src/test/resources/samples/sample.cr3"
for fork in 1 2 3; do
  variants="baseline optimized"
  if [ "$fork" = 2 ]; then variants="optimized baseline"; fi
  for variant in $variants; do
    taskset -c 0-3 "$JAVA_HOME/bin/java" \
      -Xms24m -Xmx768m -XX:+UseSerialGC -XX:ActiveProcessorCount=4 \
      -cp "$bench_root/probe:$bench_root/$variant/canon-cr3-benchmark/target/benchmarks.jar" \
      com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe \
      --input "$input_file" --mode rgb16 --warmup 3 --iterations 5 --gc-between \
      > "$bench_root/$variant-rgb16-cpu4-fork$fork.jsonl"
  done
done
```

Use available CPU IDs on another host. The recorded panel uses CPU 0 or 0–3,
matching `ActiveProcessorCount=1` or `4`; repeat for `sensor`, `default`,
`rgb16linear` and `rgb16`. `default` executes actual `ImageIO.read(File)`.
Every decode creates a new result; explicit GC is outside the import timer.
Do not run builds, tests or other decoders concurrently with timing.

## LibRaw API profile and ownership

Compile [the frozen measured C client](measurement-sources/libraw_bench.c)
against the matching extracted packages, setting `libraw_root` to their shared
extraction directory:

```bash
gcc -O2 -std=c11 -Wall -Wextra -Werror \
  -I"$libraw_root/usr/include" \
  "$repo_root/docs/cr3/performance-2026-11-19/measurement-sources/libraw_bench.c" \
  -L"$libraw_root/usr/lib/x86_64-linux-gnu" \
  -Wl,-rpath,"$libraw_root/usr/lib/x86_64-linux-gnu" \
  -lraw_r -lstdc++ -fopenmp -o "$bench_root/libraw-bench"
OMP_NUM_THREADS=4 OMP_THREAD_LIMIT=4 OMP_DYNAMIC=FALSE \
  taskset -c 0-3 "$bench_root/libraw-bench" \
  --input "$input_file" --mode rgb16 --transfer srgb --quality 0 \
  --wb camera --auto-bright 0 --orientation camera --warmup 3 --iterations 5
```

Native sensor mode calls fresh initialization/open/unpack, copies every
`raw_width × raw_height` site using `raw_pitch` into consumer-owned storage,
then closes the context. Copy and close are timed. It performs no crop,
black subtraction, demosaic or encoding. A borrowed library pointer would have
a different lifetime contract.

RGB mode additionally calls `dcraw_process` and `dcraw_make_mem_image`; the
owned image survives context close. All those operations are timed.
These contracts follow the public [C API](https://www.libraw.org/docs/API-C.html),
[data structures](https://www.libraw.org/docs/API-datastruct.html) and
[postprocessing/output API](https://www.libraw.org/docs/API-CXX.html).

The profile explicitly selects bilinear quality 0, camera WB, sRGB primaries,
8- or 16-bit output, full size, no auto brightness, no adaptive maximum,
brightness 1, highlight 0, camera matrix, normal scaling, no wavelet/FBDD noise
reduction, and zero median passes. Camera orientation is followed; Fuji geometry
correction is disabled. RawSpeed and DNG SDK unpacking are disabled. ICC,
green-matching, exposure-correction, DCB and `cropbox` fields retain library
initialization defaults; they are not independently configured claims.

| Java mode | LibRaw mode and transfer |
| --- | --- |
| `sensor` | `--mode sensor --transfer linear` |
| `default` | `--mode rgb8 --transfer srgb` |
| `rgb16linear` | `--mode rgb16 --transfer linear` |
| `rgb16` | `--mode rgb16 --transfer srgb` |

Always specify transfer: bare C `rgb16` defaults to linear. Similar stages do
not imply identical pixels. Libraries choose different crops, black/WB
normalization, matrices and numerical conventions. Report actual dimensions
alongside RGB comparisons. Full native sample equality is established
separately in [the native comparison](native-reference-verification.json).

## Boundaries and aggregation

Warmed API timing includes input open/read, parsing, decoding, development,
owned output and cleanup. It excludes plugin scanning, optional GC, statistics,
checksums and image serialization. Each condition has three fresh processes,
three warmup decodes and five measured decodes per process. Time is the median
of the three process medians; ranges retain process-to-process variation.
Java baseline/optimized order alternates AB/BA/AB. External engine groups run
in separate windows, not paired simultaneously.

Peak RSS is the median of three process-lifetime high-water marks. Linux's
`maxrss_kb` uses 1024-byte units; divide by 1024 for MiB. RSS includes warmups,
VM/JIT, stacks and allocator retention. Java allocated bytes are live-thread
counter deltas, may miss terminated threads, and are neither native allocation
nor peak live heap. Heap snapshots and array payload remain separate metrics.
Full hashes establish correctness; sampled timing hashes are only liveness
diagnostics and differ between the Java and C clients.

DNGLab's `RAYON_NUM_THREADS=N taskset ... dnglab analyze --raw-pixel FILE`
writes a full PGM. Its comparison uses externally timed fresh Java/C sensor
processes with `--warmup 0 --iterations 1 --dump /dev/null`, and DNGLab stdout
redirected to `/dev/null`. Startup and serialization are included; per-child
RSS and complete wall time belong in a separate table. `/dev/null` removes
persistent output I/O, not buffering or write calls. First-import measurements
are likewise separate from warmed API latency and cold-storage speed.

AI assistance: ChatGPT assisted investigation, implementation, verification,
review and preparation of this evidence.
