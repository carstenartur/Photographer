# Horizontal C-RAW wavelet SIMD on JDK 25

The codec vectorizes the horizontal interior of its existing inverse 5/3
lift in eight 32-bit lanes. It uses the same input and output row buffers.
Scalar code handles the edges and tails. The arithmetic retains signed
right shifts, including the rounding of negative values.

The native integer path has an established bound for uniform detail factors
up to 64. The supported adaptive V2 profile has a separate bound derived
from its admitted gains and quantizer steps. That bound also covers the
horizontal prediction sum. The adaptive scalar fallback and vertical lift
continue to use their existing overflow checks when required.
[Range analysis for adaptive C-RAW](adaptive-decoding-performance.md#bounded-integer-lifting)

## Activation

A normal classpath application resolves the Vector API at startup with
`--add-modules=jdk.incubator.vector`. The codec uses the versioned kernel
on JDK 25 with at least 256-bit integer vectors. It uses the Java 21
implementation with the module disabled or when the runtime or CPU is
outside that configuration. The public ImageIO API and parameters are
identical for both cases. [ImageIO configuration](imageio.md#optional-simd-c-raw-wavelet-lifting-on-jdk-25)

## Complete imports

Temurin 25.0.5.1 and four available CPUs on an AMD EPYC 9V74 Linux host.
The timed interval opens a new file, decodes the complete image, produces
owned output and closes the input. The JVM, LibRaw and filesystem cache
were already warm. Checksums, serialization and explicit GC ran outside
that interval. Both Java builds resolved the incubator module.

The native panel uses three fresh processes per engine, ten warmup imports
and eight measured imports, with rotating engine order. The table reports
the median of the three per-process medians. The native LibRaw client is
the same public C API client used for the
[0.22.3 comparison](full-import-performance-2026-11-22/README.md).

| Native Bayer input | Before, ms | With SIMD, ms | LibRaw, ms | Before, MiB peak RSS | With SIMD, MiB peak RSS | LibRaw, MiB peak RSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| PowerShot G5 X Mark II C-RAW | 78.6 | 65.1 | 72.1 | 106.0 | 113.0 | 83.9 |
| EOS R8 C-RAW | 129.8 | 120.1 | 98.4 | 122.5 | 126.0 | 108.2 |
| EOS R5 Mark II C-RAW | 239.3 | 242.0 | 206.9 | 155.7 | 167.4 | 197.9 |

The complete 16-bit PGM outputs agreed byte for byte for both Java builds
and LibRaw on all three inputs. Warm Java allocation was the same within
measurement resolution. An earlier packaged run measured 265.4 ms before
and 242.5 ms after for the R5 Mark II. Its final medians were close, so the
R5 Mark II wall-time gain varied across runs.

The RGB8 panel compares ordinary `ImageIO.read(File)` on CPUs 4–7, with
sixteen warmups and nine measured reads in three interleaved pairs. It
uses the packaged kernel before the change that bypasses vector dispatch
earlier when the module is absent. Both arms had the module enabled.

| Developed RGB8 input | Before, ms | With SIMD, ms |
| --- | ---: | ---: |
| PowerShot G5 X Mark II C-RAW | 181.0 | 157.8 |
| EOS R8 C-RAW | 188.7 | 193.9 |
| EOS R5 Mark II C-RAW | 436.2 | 434.4 |

Full RGB8 fingerprints match on all three files. Shorter warmup screens
varied by input and sometimes favored the prior build. RGB8 processing
has a smaller share of time in wavelet reconstruction than Bayer decoding.
The R5 Mark II RGB16 images also had the same complete fingerprint
`d8007128984b1081` at 7932 × 5300.

## Startup and fallback

The two Java 25 modes decoded the R5 Mark II Bayer mosaic on a cold JVM
with one worker, G1 and a 128 MiB heap limit. This single-import check
measures a different memory regime from the warmed timing panel.

| R5 Mark II cold Java 25 | Peak process RSS | Cumulative Java allocation |
| --- | ---: | ---: |
| Scalar | 151.5 MiB | 96.3 MiB |
| SIMD | 184.3 MiB | 364.5 MiB |

The temporary Vector API allocations occur before JIT warmup. Cumulative
allocation includes objects collected during the import. Class loading
confirmed use of the vector class only on Java 25 with the module enabled.

An early Java 21 comparison on the R5 Mark II found an 11% higher scalar
wall median. Moving vector availability selection outside the row loop
gave 299 ms before and 288 ms after in the follow-up comparison, measured
as medians of three process medians.

[Fork medians, memory counters and output hashes](wavelet-simd-performance/comparison.json)

## Reproduction

Use the same JDK and module flag with and without the SIMD codec change.
This holds the optional RGB8 color matrix constant. Run the probe from
each source tree's benchmark JAR. The baseline was the head of
[PR #448](https://github.com/carstenartur/Photographer/pull/448/commits/96bb194df3af9c8378b1157aa8e141a48503f630).
The two adaptive public fixtures are
`6587.cr3` and `7882.cr3`. The V1 comparison uses
`research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3`
from the ImageIO test samples.
[Input hashes](full-import-performance-2026-11-22/README.md#pinned-inputs)

```sh
mvn -B -Pbenchmarks -pl canon-cr3-benchmark -am -DskipTests package
jar=canon-cr3-benchmark/target/benchmarks.jar
probe=com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe
taskset -c 0-3 java --add-modules=jdk.incubator.vector \
  -Xms24m -Xmx384m -XX:+UseSerialGC -XX:ActiveProcessorCount=4 \
  -cp "$jar" "$probe" --input "$input" --mode sensor \
  --warmup 10 --iterations 8 --gc-between
```

Use `--mode default` for ordinary `ImageIO.read(File)`. Compare complete
outputs with separate `--checksum --dump` calls. The shaded probe is built
with JDK 25. For Java 21 activation checks, build the probe class with
`javac --release 21` and put it before the JAR on the classpath.
[Import probe](../../scripts/benchmarks/README.md#java-probe)
