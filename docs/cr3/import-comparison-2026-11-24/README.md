# CR3 import comparison with LibRaw 0.21.4, 24 November 2026

The ordinary Photographer `ImageIO.read(File)` call develops a new, full-resolution sRGB image with three unsigned 16-bit channels. Across six RAW and C-RAW originals its median import time was 215–342 ms, compared with 642–1,040 ms for LibRaw 0.21.4. The comparison used [Photographer revision 6cb25a7](https://github.com/carstenartur/Photographer/tree/6cb25a7ea38461e4141ca61ab23461099c2d630a). The [per-file results](summary.tsv) include output dimensions, wall time, fork ranges, peak process memory and output megapixels per second.

Both probes time opening the file, decoding it and returning an independently owned image in memory. Photographer used automatic memory selection. LibRaw used bilinear demosaicing, camera white balance and matrix, sRGB transfer and camera orientation, with automatic brightness disabled. The renderers use different crops and color processing. On the EOS R, the output dimensions were nearly identical at 6,744 × 4,500 and 6,742 × 4,498. On the R5 Mark II APS-C file, LibRaw rendered 20% more output pixels. Across the six files Photographer developed 2.36–3.22 times as many output megapixels per second. These measurements describe the cost of each renderer’s output. [Earlier release comparison with LibRaw 0.22.2 and 0.22.3](../import-comparison-2026-11-22/README.md)

## Native sensor output

The sensor comparison returns a full-resolution array with one measured intensity per sensor site, before white balance, demosaicing or cropping. The C probe copies LibRaw's data before closing the decoder so the caller owns the result. A separate check compared the complete 16-bit PGM files byte for byte. The [input, tool and output hashes](manifest.json) include the SHA-256 of each verified PGM.

| Input | Photographer (ms) | LibRaw 0.21.4 (ms) |
| --- | ---: | ---: |
| PowerShot G5 X Mark II RAW | 78.75 | 85.72 |
| PowerShot G5 X Mark II C-RAW | 81.66 | 57.93 |
| EOS R RAW | 136.20 | 102.18 |

The G5 X Mark II RAW fork medians overlap (75.60–99.85 ms for Photographer and 77.08–92.68 ms for LibRaw). LibRaw had the lower median time on the G5 C-RAW and EOS R RAW inputs. [Photographer decoder CPU profiles](sensor-profile.md)

## Measurement method

Both processes ran on the same four logical CPUs (4–7) of an AMD EPYC 9V74 Linux worker. Java 21.0.13 used Serial GC and a 24–768 MiB heap. LibRaw used its thread-safe library with a four-thread OpenMP limit. Each engine and file ran in three fresh processes, with three warmups and five measured calls per process. Each fork rotated the input order and alternated which engine went first. Timings describe a warm JVM and file cache. Process startup, ImageIO provider discovery, explicit between-read Java GC, checksumming and file export were outside the timed interval.

The [270 timed reads](observations.tsv) identify the engine, input and fork. Empty fields indicate metrics specific to the other probe. The reported wall time is the median of the three within-fork medians. Peak RSS is the median of the three process high-water marks and includes runtime and warmup memory. The source registrations identify the original public files. [Adaptive C-RAW inputs](../../../scripts/research/public-crx/adaptive-craw.tsv) · [V1 inputs](../../../scripts/research/public-crx/v1-cameras.tsv)

## Reproduce

Use the measured revision linked above for the Java and C probes. Build the benchmark JAR with JDK 25. Compile `Cr3ImportProbe.java` separately with `javac --release 21` against that JAR and put the resulting classes first on the Java 21 runtime classpath. Compile `libraw_bench.c` against the matching public headers and thread-safe library for LibRaw 0.21.4. [Java and C build commands](../../../scripts/benchmarks/README.md)

The [Python 3.11+ driver](compare.py) reads the three repository fixtures and the external originals named `6587.cr3`, `6054.cr3` and `7884.cr3`. Set the paths below and run it twice from this study directory. `CR3_CPUS` selects four available logical CPUs. Each mode writes its results into a new subdirectory.

```bash
export PHOTOGRAPHER_REPO=/path/to/measured-checkout
export CR3_EXTERNAL=/path/to/external-originals
export LIBRAW_BENCH=/path/to/libraw-bench
export CR3_JAVA=/path/to/jdk21/bin/java
export CR3_CLASSPATH=/path/to/probe-classes:/path/to/benchmarks.jar
export CR3_RESULT_DIR=/path/to/new-results
export CR3_CPUS=4-7
python3 compare.py rgb16
python3 compare.py sensor
```
