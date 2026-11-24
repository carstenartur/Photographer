# RGB16 destination reuse and portrait development

Full RGB16 imports were compared with `f9c1121914b9375d7cb7948ec5a4b55fb8fb9da1` on Java 21.0.13, Linux and four logical CPUs of an AMD EPYC 9V74. Each import reopened its input and returned the developed image. The timings use a warm JVM and OS file cache. Full fingerprints were checked separately. [Per-JVM measurements](rgb16-destination-performance.tsv)

## Import into a supplied RGB16 image

Both revisions used the same advertised three-bank unsigned-short image type and the same preallocated target for a pair of distinct originals. The destination lives across reads and its initial allocation is excluded on both sides. In the staged case the old contents remain usable if decoding fails. A compatible destination with workspace permission can instead hold decoded sensor samples during the read. For an unrotated frame the final green bank holds the Bayer mosaic. [Choosing a compatible destination](imageio.md#reuse-an-rgb16-destination)

| Pair | Staged before → after (ms) | Workspace before → after (ms) | Java allocation per workspace read before → after (MiB) |
| --- | ---: | ---: | ---: |
| G5 X Mark II RAW | 289 → 235 | 312 → 178 | 116.90 → 1.49 |
| G5 X Mark II C-RAW | 301 → 215 | 319 → 201 | 118.22 → 2.81 |
| EOS R RAW | 423 → 381 | 439 → 313 | 175.42 → 1.69 |

These two comparisons isolate the respective destination implementations. Each arm used three fresh JVMs, 768 MiB heap, Serial GC, four warmups and six measured calls per JVM. Images alternated within a process. Every timed read returned the supplied target. The summary uses the median of per-JVM medians. Allocation counts are per call and exclude the live target.

With G1 and the final combined implementation, two G5 RAW originals of 5496 × 3668 pixels could reuse a full-resolution RGB16 target at `-Xmx160m`. Two EOS R originals of 6744 × 4500 pixels could do the same at `-Xmx218m`. The baseline exhausted the Java heap with the same target and settings. At 768 MiB it returned the same full-image fingerprints.

## Ordinary ImageIO imports

For EXIF orientations 5–8, the developer now processes small rectangles of source pixels so consecutive scanlines revisit the same group of destination rows. This comparison uses the combined changes. Copies of three original files differ only in the CMT1 orientation value, set to 6. The ordinary `ImageIO.read(File)` call uses automatic memory selection. The 768 MiB heap allowed normal development. Results use three fresh JVMs per arm with four warmups and five timed imports.

| Camera | Before (ms) | After (ms) |
| --- | ---: | ---: |
| G5 X Mark II RAW | 319 | 309 |
| G5 X Mark II C-RAW | 329 | 282 |
| EOS R RAW | 496 | 446 |

The landscape control uses six distinct unmodified files from five camera models. Its batch median changed from 1,908 to 2,008 ms. A repeat with the revision order reversed, the initial file list rotated by three places and three warmup batches changed from 1,641 to 1,640 ms. Each run used four fresh JVMs per arm, four measured batches, Serial GC and 768 MiB heap. The first run used two warmup batches. The first comparison favored the baseline and the repeat was effectively level. The six complete RGB16 fingerprints match. The [previous study](rgb16-import-2026-11-24.md) identifies the originals, their registries and the batch order.

## TIFF directory reads

Each CMT directory contains consecutive 12-byte descriptors. Reading these descriptors together leaves at most 12,000 bytes of temporary payload during a parse (1,000-entry limit). The TIFF byte order applies to descriptor fields and to out-of-line tag values. Parsing stops at the last complete descriptor inside the box. The caller’s byte order is restored on return and on I/O failure.

Java 21 Flight Recorder counted input-file `jdk.FileRead` events at zero threshold around one normal `ImageIO.read(File)` per file. Both arms read exactly the same byte total and produced identical full RGB16 fingerprints. The counter measures calls at the JVM file interface, not physical device reads. See the [JFR reproduction](rgb16-import-2026-11-24.md#tiff-metadata-file-reads).

| Original | Reads on main | Buffered IFD | Bytes in both arms |
| --- | ---: | ---: | ---: |
| G5 X Mark II RAW | 1,552 | 1,121 | 20,646,159 |
| G5 X Mark II C-RAW | 1,543 | 1,112 | 9,655,321 |
| EOS R RAW | 2,013 | 1,578 | 36,123,708 |
| EOS R8 C-RAW | 1,416 | 937 | 7,753,544 |
| G7 X Mark III C-RAW | 1,849 | 1,410 | 15,308,194 |
| EOS R5 Mark II C-RAW (APS-C) | 1,308 | 841 | 5,550,705 |

In an additional metadata-only probe each batch opened all six originals with fresh ImageIO readers in rotating order, called `ImageReader.getImageMetadata(0)`, inspected the camera model, and disposed the reader and stream. The median time for the six-file batch across six alternating fresh JVM pairs was 8.76 → 7.06 ms wall time and 4.45 → 3.34 ms current-thread CPU time. Five of six pairs favored the new implementation. The single pinned CPU shared other work during the trial. Wall results varied markedly between forks. These times cover metadata retrieval alone.

## Reproduction

Build both revisions using the [Java import probe instructions](../../scripts/benchmarks/README.md#java-probe). Compile the current `Cr3ImportProbe.java` and `Cr3BatchImportProbe.java` with `javac --release 21` and put those classes first on both runtime classpaths. Run the same commands on each version, alternating revision order at each fork. Example for the G5 RAW pair on four CPUs, with `cp` set to the selected classpath:

```bash
samples=canon-cr3-imageio/src/test/resources/samples
probe=com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe
for permission in --staged-destination --reuse-destination; do
    taskset -c 4-7 java -Xms24m -Xmx768m -XX:+UseSerialGC -XX:ActiveProcessorCount=4 \
        -cp "$cp" "$probe" --mode rgb16 "$permission" \
        --input "$samples/sample.cr3" --input "$samples/sample-wide.cr3" \
        --warmup 4 --iterations 6 --gc-between
done
```

The EOS R pair uses `eosr-iso-low.cr3` and `eosr-iso-high.cr3`. The G5 C-RAW pair uses `research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3` and its `-03.cr3` companion. Reverse the order of the pair in the second of the three forks. To check every output sample, use `--iterations 2 --checksum` in a separate process. For portrait files, use the [orientation-copy procedure](rgb16-import-2026-11-24.md#reproduce-the-rgb16-measurements) and run the public probe with `--mode default`. Adjust CPU affinity to the host.
