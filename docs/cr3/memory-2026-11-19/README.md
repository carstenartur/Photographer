# Adaptive CR3 memory and ImageIO copy elimination — 2026-11-19

The 0.1.8-SNAPSHOT importer now selects a smaller working set when available
Java heap is tight, preserves the requested image, and reports the decision to
stderr. A separate ImageIO change avoids a second complete image allocation
when the requested destination type already matches the decoded image. These
changes do not reduce resolution, channel precision, crop or processing quality.

This study starts from `3f7312c679a7deca1ec831103c8eff5fbb814634`, after the
inclusive-IAD1 crop correction. Its baseline therefore differs from the earlier
[external-engine study](../performance-2026-11-19/README.md). No new release or
external-engine timing is implied by these results.

## Avoiding the extra RGB16 destination copy

The tested caller explicitly selects the reader's advertised RGB16 destination
type. Previously that request caused a second full image and pixel copy even
when its layout matched the already owned decoded image. The importer now
returns that image directly when destination, regions, bands, subsampling,
color model and sample layout permit it. Caller-supplied destinations and
different layouts retain their existing handling.

Four CPUs, Java 25, Serial GC, 768 MiB heap; three fresh processes per condition,
each with three warmups and five measured imports. Time is the median of the
three process medians. Allocation is a live-thread counter delta; RSS is the
median of the three processes' largest recorded `VmHWM` observations, sampled
after imports.

| Input | Time before → after (ms) | Time reduction | Allocation before → after (MiB) | Peak RSS before → after (MiB) |
| --- | ---: | ---: | ---: | ---: |
| G5 X II RAW | 734.1 → 480.1 | 34.6% | 232.2 → 116.7 | 334.9 → 218.3 |
| EOS R RAW | 1009.3 → 720.4 | 28.6% | 349.1 → 175.3 | 469.9 → 295.5 |
| G5 X II C-RAW | 580.1 → 400.5 | 31.0% | 233.6 → 118.2 | 340.1 → 225.7 |
| EOS M50 C-RAW | 871.5 → 605.2 | 30.6% | 280.4 → 141.7 | 391.3 → 254.1 |

This benefit applies to a compatible explicit destination type. It is not a
claim that every RGB16 import previously made this extra copy. The isolated
20 MP destination regression test also demonstrates a successful RGB16 import
at 192 MiB with G1 where the previous implementation exhausted heap allocating
the second destination.

## Automatic low-heap behavior

`Cr3DecodeOptions.MemoryMode.AUTO` is the default. Before image allocation, the
decoder compares an estimate of the ordinary image and scratch buffers with a
snapshot of available Java heap, retaining an additional reserve. When that
headroom is insufficient, it uses one worker and compact processing of supported
owned, full-frame images. A copied options object keeps the caller's options
and worker setting unchanged for subsequent reads.

Full RGB16 processing reuses Bayer storage as the green channel, develops the
unrotated image, and rearranges samples in place. RGB8 retains its packed pixel
array. EXIF orientations that exchange axes require one extra bit per pixel;
flips and a 180-degree rotation require constant extra storage. Partial RGB
reads and native/Bayer reads reduce worker scratch but keep their existing
output-storage layouts.

| Full image storage | Normal path | Compact path |
| --- | ---: | ---: |
| RGB16, identity orientation | 6 bytes/pixel | 6 bytes/pixel |
| RGB16, orientation exchanging axes | about 8 bytes/pixel | about 6.125 bytes/pixel |
| Packed RGB8, orientation exchanging axes | about 8 bytes/pixel | about 4.125 bytes/pixel |

These are principal simultaneous pixel buffers, not JVM heap requirements or
RSS estimates. The unavoidable final payload is 2 bytes/pixel for unsigned-short
Bayer, 4 for packed RGB8 and 6 for RGB16. Metadata, scratch, object headers, the
collector, other live images and application allocations require more space.

Selecting the compact path writes exactly one `CR3 low-memory:` line for that
RAW read, including an explicitly requested `LOW_MEMORY` read. For example,
the constrained EOS R import recorded:

```text
CR3 low-memory: limited heap headroom; using one worker and compact owned-image processing. Resolution and sample precision are preserved (estimated normal-path heap 245243584 bytes, available 220766064 bytes).
```

Other libraries may write separate stderr diagnostics. Preview and thumbnail
reads do not invoke this RAW policy. `FAST` disables automatic adaptation for
controlled comparisons; it does not emit the CR3 low-memory warning. See the
[import guide](../import.md#limited-heap-018-snapshot) for API use.

### Demonstrated heap limits on real files

Each cell below is a fresh Java 21 process, explicit EXIF orientation 6, no
warmup, and a complete output fingerprint. AUTO succeeds and matches the
512 MiB FAST reference; FAST at the same cap and collector fails with an actual
heap-space `OutOfMemoryError`. These are demonstrated settings, **not measured
minimum heaps or guarantees for arbitrary hosts**.

| Input | Output | Serial GC heap | G1 heap |
| --- | --- | ---: | ---: |
| G5 X II RAW | RGB8 | 128 MiB | — |
| G5 X II RAW | RGB16 sRGB | 144 MiB | 144 MiB |
| EOS R RAW | RGB16 sRGB | 224 MiB | 208 MiB |
| G5 X II C-RAW | RGB16 sRGB | 144 MiB | 144 MiB |
| EOS M50 C-RAW | RGB16 sRGB | 184 MiB | 176 MiB |

All nine AUTO successes emitted one CR3 warning and matched the full reference
fingerprint. All nine same-cap FAST controls failed and emitted no CR3 warning.
Both unsuccessful AUTO attempts remain in the evidence: 96 MiB with Serial GC
for G5 X II RGB8, and 208 MiB with Serial GC for EOS R RGB16. AUTO warned and
then failed allocating required image storage. The EOS R case succeeded at
the same nominal heap with G1, illustrating why pixel payload alone is not a
sufficient heap guarantee.

There is no catch-and-retry after `OutOfMemoryError`, forced collection in the
production policy, or reservation against concurrent imports. Heap headroom
can change after the estimate. The completed output must still fit; host
allocations, concurrency and caller-owned input-stream caches can exhaust it.

## Equal-heap cost of compact processing

This panel forces FAST or LOW_MEMORY in the same frozen runtime at 768 MiB,
four CPUs and EXIF orientation 6. It measures explicit RGB reads with ordinary
destination selection. Three fresh processes per condition each perform three
warmups and five measured imports: 48 successful processes, 240 observations.
Every LOW_MEMORY process recorded eight CR3 warnings, including warmups; FAST
recorded none.

| Input | Output | FAST → LOW time (ms) | Time factor | Allocation FAST → LOW (MiB) | Recorded peak RSS FAST → LOW (MiB) |
| --- | --- | ---: | ---: | ---: | ---: |
| G5 X II RAW | RGB8 | 372.6 → 1000.1 | 2.68× | 154.7 → 80.0 | 222.1 → 144.9 |
| G5 X II RAW | RGB16 | 695.8 → 1842.3 | 2.65× | 154.9 → 118.5 | 258.3 → 218.9 |
| EOS R RAW | RGB8 | 638.3 → 1563.0 | 2.45× | 232.6 → 120.2 | 302.6 → 185.2 |
| EOS R RAW | RGB16 | 1001.6 → 3270.7 | 3.27× | 232.9 → 178.2 | 353.4 → 298.1 |
| G5 X II C-RAW | RGB8 | 362.1 → 830.7 | 2.29× | 156.1 → 81.4 | 227.8 → 151.3 |
| G5 X II C-RAW | RGB16 | 635.6 → 1689.1 | 2.66× | 156.4 → 119.9 | 264.8 → 225.5 |
| EOS M50 C-RAW | RGB8 | 513.4 → 1196.3 | 2.33× | 187.3 → 97.6 | 261.7 → 164.3 |
| EOS M50 C-RAW | RGB16 | 812.2 → 2517.7 | 3.10× | 187.6 → 143.9 | 299.3 → 254.9 |

In these conditions, compact processing takes 2.29–3.27 times the import time.
Recorded RSS decreases by 33.6–38.8% for RGB8 and 14.8–15.7% for RGB16.
Allocation falls by approximately 48% and 23% respectively. RSS includes JVM
and JIT memory, which explains why its percentage reduction differs from the
pixel-buffer model. AUTO uses the normal path when the headroom estimate allows
it; forcing LOW_MEMORY at this large heap isolates its time/memory trade-off.

## Ordinary ImageIO control

The unchanged frozen helper calls the actual `ImageIO.read(File)` API. The
initial four-CPU control has three fresh processes per variant/input, three
warmups and five measurements: 24 successful processes and 120 observations.
No final-runtime read selected the low-memory path. Median recorded allocation
differs by just eight bytes per import across all four inputs.

| Input | Baseline → final time (ms) | Time change | Recorded peak RSS baseline → final (MiB) |
| --- | ---: | ---: | ---: |
| G5 X II RAW | 269.0 → 312.6 | +16.2% | 144.8 → 143.8 |
| EOS R RAW | 460.9 → 434.0 | −5.8% | 183.7 → 184.6 |
| G5 X II C-RAW | 252.0 → 252.5 | +0.2% | 148.0 → 297.3 |
| EOS M50 C-RAW | 379.1 → 350.8 | −7.5% | 163.4 → 165.5 |

The apparent G5 RAW time increase prompted a separate confirmation: five fresh
processes per variant, eight warmups and twelve measured imports. Its medians
were **278.642 ms baseline and 280.812 ms final (+0.78%)**; process-CPU medians
were 960 and 970 ms. The larger time penalty did not reproduce in this longer
warmup protocol. This does not identify the original cause or establish universal
performance equality. Both protocols and every fork remain separate in the
evidence; the confirmation does not replace the original +16.2% observation.

The initial G5 C-RAW control also has a resident-memory excursion: final forks
1 and 3 report roughly 160 MiB heap-used on the first measured import, returning
to roughly 81–83 MiB afterward, while their recorded RSS high-water marks stay
elevated. Final fork 2 stays around 148 MiB RSS. Allocation per import remains
approximately 79.2 MiB in both variants. The baseline M50 panel also contains
one 346.3 MiB RSS peak among its otherwise much lower process maxima.

A separate G5 C-RAW follow-up kept the original three-warmup/five-measurement
protocol and increased to five fresh processes per variant. It recorded RSS
medians of **150.676 MiB baseline and 149.531 MiB final**. The five baseline
process maxima were 150.9, 148.4, 149.2, 151.7 and 150.7 MiB; the final maxima
were 149.5, 144.2, 149.8, 150.9 and 148.8 MiB. Sampled heap-used stayed at about
82 MiB or less. The initial excursion did not reproduce in this follow-up, but
its cause was not isolated. Original and follow-up records are both retained.

These controls do not establish unchanged peak resident memory for all ordinary
imports. The allocation, constrained-heap and compact-mode results have their own
specified workloads and should be interpreted separately from these process
high-water observations.

## Correctness and regression coverage

The local CR3/ImageIO/ImageJ/CLI verification reactor recorded 719 tests, zero
failures, zero errors and one optional Vector API skip, including two DNG
round-trip integration tests and one packaged ImageIO consumption test.
The final benchmark-helper suite passed all 24 tests. Build
JDK 25 and production runtime compatibility with Java 21 were checked.

The new tests cover all eight EXIF orientations, original backing-array reuse,
linear and sRGB output, RGB8/RGB16, partial and subsampled reads, optical-black
calibration, destination layouts, caller option ownership and warning counts.
A generated 4096 × 4096 native 14-bit RAW fixture demonstrates AUTO RGB16
success at 128 MiB with a matching complete canonical-u16le SHA-256 reference;
the FAST control fails at that heap. The destination-copy test separately
demonstrates the previous second-array allocation failure.

Real-file checks use four files: G5 X II RAW, EOS R RAW, G5 X II C-RAW and an
EOS M50 C-RAW holdout. RGB8, linear RGB16 and sRGB RGB16 were each checked at
orientations 1 and 6, plus native sensor output. All **28 FAST/LOW_MEMORY pairs**
match dimensions, channel count, precision and a full row-major numeric FNV
fingerprint. This scans **1,806,502,864 numeric samples per variant**, across the
tested outputs. These are complete fingerprints, not sampled hashes or a claim
that the real-file output was hashed with SHA-256. Inputs, binaries and helpers
have separate SHA-256 provenance pins.

The three correctness/heap result sets retain 76 processes: 65 successes and
11 OOMs (the nine FAST controls and two insufficient AUTO attempts). Their timing
fields are incidental diagnostics, not the performance measurements above.

## Experiments not retained

A cost-sorted tile scheduler was screened on four inputs and native/RGB8/RGB16
imports at four CPUs (one fresh process per condition, three warmups, five
measurements). Point estimates ranged from an 11.1% reduction to a 9.7% increase
in import time. No consistently useful improvement appeared; this pilot was
not treated as statistical proof of a regression. The change was not integrated.

A bounded-integer C-RAW detail multiplication was separately screened on native
sensor imports of two C-RAW files at one and four CPUs, with three fresh processes per condition.
Its arithmetic limits and generic overflow behavior passed focused tests and
independent source review, but the timing results did not justify retaining it:

| C-RAW input | CPUs | Baseline (ms) | Candidate (ms) | Time change |
| --- | ---: | ---: | ---: | ---: |
| G5 X II | 1 | 209.0 | 204.0 | −2.4% |
| G5 X II | 4 | 118.4 | 127.5 | +7.7% |
| EOS M50 | 1 | 383.3 | 384.2 | +0.2% |
| EOS M50 | 4 | 218.0 | 181.2 | −16.9% |

Fork results varied substantially and did not show a consistent improvement
across the tested conditions. Both candidate patches, source/binary pins and
all observations are retained with the evidence. Neither candidate is included
in the tested runtime or the copy/memory measurements.

## Measurement boundaries and reproduction

All timed panels execute serially with no concurrent local build, test or decode
job. Java 25, `-Xms24m -Xmx768m -XX:+UseSerialGC`, four visible CPUs and matching
Linux affinity are held constant, except the explicitly identified one-CPU
arithmetic panel. Every import creates a fresh decoded image; there is no
decoded-result cache. Filesystem caches can be warm. Explicit GC occurs between
imports, outside the timer. Policy warnings remain within the import path.

Time includes file opening, parsing, decoding, development, owned output and
input cleanup, but excludes JVM startup, provider preflight, statistics and
checksums. The primary panels run three warmups and five observations per
process, with three fresh processes per condition and AB/BA/AB order. The
scheduling pilot uses one process per condition. The targeted G5 RAW confirmation
uses five processes, eight warmups and twelve observations; the targeted G5 C-RAW
RSS confirmation uses five processes with the original three/five warmup/measurement
settings. Both five-process comparisons use AB/BA/AB/BA/AB order. Tables use
the median of process medians, not pooled iterations. RSS includes warmups,
the VM, stacks and JIT; it is not peak live heap or a separate final-at-exit
measurement. Allocation counters can miss
terminated threads. Full correctness fingerprints are collected separately.

The current processed dimensions at orientation 1 are 5496 × 3668 for G5 X II,
6744 × 4500 for EOS R and 6024 × 4020 for EOS M50. Native sensor dimensions
remain 5632 × 3710, 6888 × 4546 and 6288 × 4056 respectively. Orientation 6
exchanges the processed axes. Do not reuse the pre-correction dimensions from
the earlier report for these observations.

- [Source, input, binary, helper and archive provenance](provenance.json)
- [Recomputed measurements and complete-fingerprint summary](results-summary.json)
- [Verification and integrity checks](validation.json)
- [Source-based reproduction instructions](reproduce.md)
- [Correctness, constrained-heap attempts and validation evidence](correctness.tar.gz)
- [All measured panel commands, observations, stdout and stderr](measured-panels.tar.gz)
- [Rejected experiment evidence](rejected-experiments.tar.gz) and
  [candidate patches](experiments)

The compact readable tables above are derived from the retained observations.
The JSON and archive manifests pin every member by SHA-256. Archives preserve
the original failed attempts and absolute recorded command paths; the
reproduction instructions explain adapting those paths. They contain no RAW
inputs, decoded-image caches or benchmark binaries.

Runtime changes are reviewed in [PR 426](https://github.com/carstenartur/Photographer/pull/426)
and [PR 427](https://github.com/carstenartur/Photographer/pull/427). The current
public probe controls and their 24 tests are in
[PR 428](https://github.com/carstenartur/Photographer/pull/428). The frozen extended
probe used for this study predates its malformed-input null guard; the source,
Java 25 class and separately compiled Java 21 class are identified explicitly,
so valid-workload results are not attributed to an unmeasured helper binary.

Implementation, tests, measurement analysis and this report used AI assistance.
The maintainer remains accountable for the contribution.
