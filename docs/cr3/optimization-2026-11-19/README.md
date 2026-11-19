# Further CR3 import optimization — 2026-11-19

The retained changes accelerate exact RGB16 quantization, make compact RAW processing use the workers that fit the available heap, and ship the optional Java 25 color-matrix accelerator in the normal processing JAR. Full-image numeric fingerprints match the preceding implementation. These changes are in 0.1.8-SNAPSHOT; the measurements describe the recorded Linux host and inputs.

Implementation: [optional vector packaging](https://github.com/carstenartur/Photographer/pull/430), [exact RGB16 quantization](https://github.com/carstenartur/Photographer/pull/431), and [adaptive compact worker limits](https://github.com/carstenartur/Photographer/pull/432). Usage: [ImageIO integration](../imageio.md#optional-simd-color-matrix-on-jdk-25) and [limited heap](../import.md#limited-heap-018-snapshot).

## Complete RGB16 imports on Java 21

This comparison uses the frozen preceding implementation and the combined retained changes. Four CPUs were available to each JVM. The control and changed processes each decoded a newly opened file and returned a fresh full-resolution RGB16 image on every measured iteration.

| Input | Control ms | Changed ms | Time change | Control peak RSS MiB | Changed peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| G5 X Mark II RAW | 414.04 | 382.66 | -7.58% | 170.55 | 170.95 |
| EOS R RAW | 630.60 | 589.76 | -6.48% | 232.00 | 235.85 |
| G5 X Mark II C-RAW | 365.05 | 345.68 | -5.31% | 182.96 | 180.18 |
| EOS M50 C-RAW | 559.28 | 482.89 | -13.66% | 208.01 | 207.13 |

The exact quantizer accepts a proposed output code directly when the input lies safely inside both original rounding boundaries. Values near a transition follow the original correction and reference fallback. Independent forward-transfer tests cover all 65,535 output transitions and both sides of the guard; native-depth, seeded continuous, clipping and special-value tests remain.

For the EOS R RGB16 import with one available CPU:

| Input | Control ms | Changed ms | Time change |
| --- | ---: | ---: | ---: |
| EOS R RAW | 1543.80 | 1521.14 | -1.47% |

## Optional vector acceleration of ordinary RGB8

The following comparison uses the **same combined JAR** on Java 25, with the module disabled and enabled. The measured entry point is `ImageIO.read(File)`. This accelerates the packed RGB8 color matrix. RGB16 uses its separate exact path.

| Input | Control ms | Changed ms | Time change | Control peak RSS MiB | Changed peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| EOS R RAW | 401.63 | 356.66 | -11.20% | 182.63 | 193.74 |
| G5 X Mark II C-RAW | 254.91 | 222.83 | -12.58% | 151.46 | 160.00 |

Enable it when launching a Java 25 application:

```sh
java --add-modules=jdk.incubator.vector ...
```

The JAR keeps Java 21 base classes and a Java 25 versioned implementation. Activation also requires supported packed rows and at least a 256-bit preferred vector width. Other configurations retain the scalar implementation. Packaging verification launches the real artifact with and without the module and checks activation and numerical output.

The scalar Java 21 default RGB8 control remained close to the prior implementation, with differences in both directions:

| Input | Control ms | Changed ms | Time change |
| --- | ---: | ---: | ---: |
| G5 X Mark II RAW | 288.00 | 294.11 | +2.12% |
| EOS R RAW | 426.66 | 432.73 | +1.42% |
| G5 X Mark II C-RAW | 270.37 | 263.30 | -2.61% |
| EOS M50 C-RAW | 346.46 | 350.68 | +1.22% |

These scalar RGB8 observations do not establish a general acceleration. The separate corrected-row experiment below was not retained.

## Faster compact imports at the same heap limit

Both arms explicitly use `LOW_MEMORY`, orientation 6, Java 21, four available CPUs and the same 768 MiB maximum heap. The control always caps compact processing at one worker. The new planner selects a limit that fits the estimated pixel and working storage while respecting the caller's CPU cap. The stderr record identifies the selected limit for each read. In these unconstrained comparison runs the new limit is four.

### Packed RGB8

| Input | Control ms | Changed ms | Time change | Control peak RSS MiB | Changed peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| EOS R RAW | 1725.89 | 1256.20 | -27.21% | 179.09 | 181.70 |
| G5 X Mark II C-RAW | 913.96 | 648.47 | -29.05% | 146.83 | 145.68 |

### Three-channel RGB16

| Input | Control ms | Changed ms | Time change | Control peak RSS MiB | Changed peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| EOS R RAW | 3346.75 | 2464.78 | -26.35% | 234.14 | 242.21 |
| G5 X Mark II C-RAW | 1749.01 | 1384.69 | -20.83% | 181.81 | 184.05 |

Each full-image orientation still uses compact owned-image storage. Extra worker row buffers add 0.19–0.77 MiB of measured managed allocation per fresh import in these four comparisons and may increase RSS. RSS is the median of three process-lifetime high-water marks, including startup, warmup and JVM/native memory; it is not the heap needed by one image.

`AUTO` uses the normal path when its estimate fits, otherwise selects compact processing. `LOW_MEMORY` always selects compact processing. `FAST` retains the normal path. Compact reads write one `CR3 low-memory:` line to stderr per read. The message reports a worker limit, rather than claiming every stage actually runs that many workers.

The planner compares the working peak with retained output plus any later ImageIO destination. It leaves allocation of that destination after decoding. This preserves the lower peak for a rotated partial image and preserves caller-buffer ownership on malformed input. Standard raster layout estimates include stride and banks; unknown custom or sparse layouts receive a conservative one-worker choice and an explicit unknown-estimate diagnostic. The estimate is an advisory snapshot; concurrent allocation and an oversized final image can still exhaust heap.

## Output and constrained-heap verification

The combined reactor reported **756 tests, 755 passed, one existing optional-vector unit skip, zero failures and zero errors**. This includes ImageIO, ImageJ, CLI, benchmark helpers and packaged-artifact integration tests. The packaged test separately exercises the optional backend even though the ordinary vector unit profile is not enabled.

The final Java 21 verification ran **67 fresh processes**: 56 FAST/LOW_MEMORY full-output checks across four RAW/C-RAW files, RGB8, linear RGB16, sRGB RGB16 and sensor output, including identity and axis-swapping orientation; nine AUTO reads at constrained heaps; and two expected FAST out-of-memory controls. All 65 successful reads matched the preceding full numeric fingerprints, dimensions, channel counts and sample depths. Every compact/AUTO fallback emitted exactly one CR3 warning per read.

| Input and output | Successful AUTO heap, Serial GC | Successful AUTO heap, G1 GC |
| --- | ---: | ---: |
| G5 X Mark II RAW, RGB8 | 128 MiB | — |
| G5 X Mark II RAW, RGB16 | 144 MiB | 144 MiB |
| EOS R RAW, RGB16 | 224 MiB | 208 MiB |
| G5 X Mark II C-RAW, RGB16 | 144 MiB | 144 MiB |
| EOS M50 C-RAW, RGB16 | 184 MiB | 176 MiB |

All heap examples use orientation 6 and full resolution. The G5 RGB8/Serial 128 MiB and M50 RGB16/G1 176 MiB FAST controls fail with `OutOfMemoryError`, while AUTO succeeds. These are demonstrated caps, not minimum heap requirements. The earlier [memory study](../memory-2026-11-19/README.md) preserves additional controls and failed lower-cap attempts.

Focused regression tests also cover worker/reserve boundaries, saturated arithmetic, option copies, a rotated near-full RGB8 region with offset at 192 MiB, and a custom destination factory that wraps caller-held pixels when entropy decoding fails. No decoded-image or result cache is introduced.

## Method and limits

Each main panel uses three fresh JVMs per arm, alternating AB, BA, AB. Default comparisons use eight warmups and twelve timed fresh imports per JVM. The compact comparison uses three warmups and five measured imports; the one-CPU comparison uses six and eight. JVMs use Serial GC, a 24–768 MiB heap, `taskset` affinity and a matching `ActiveProcessorCount`. Full correctness checks use separate processes and include both Serial and G1 collectors for constrained heaps.

The timer covers the complete ImageIO read, including reader selection on each import. JVM startup, the explicit provider preflight, requested garbage collection between imports and output checks take place outside that timer. The JVM and operating-system file cache are warm. Every iteration still opens, decodes and develops the file anew. Wall time is the median of three process medians, not a pooled average of correlated iterations. Some process ranges are wide; all individual values and outliers remain in the raw records. Results should not be generalized to different hardware, image content, heap or concurrency without measurement.

Before timing, separate reads compare all output dimensions, actual `channels`, precision and full FNV-1a-64 values. The original screen driver accidentally queried a missing `bands` field; the probe itself checked the format, and the independent published audit explicitly checks the emitted `channels` in every pair. The final driver checks `channels` directly. Timed row-sampled hashes serve only as liveness diagnostics. External reference tools were not rerun in this study; the older [import comparison](../performance-2026-11-19/README.md) and [corrected memory/crop study](../memory-2026-11-19/README.md) retain their own boundaries.

## Isolated candidate screens on Java 25

The initial screens used the same three-fork process method and four CPUs. They determined which changes warranted integration; their controls and source lineage remain separate from the combined Java 21 comparisons.

### Entropy-decoder experiments

Each timing is control → candidate in milliseconds. The C-RAW experiments were built cumulatively and compared with their immediate predecessor. Each row uses a freshly measured control.

| Experiment | Input | Native sensor ms | Default RGB8 ms |
| --- | --- | ---: | ---: |
| Combine prediction flag and Rice residual | G5 X Mark II RAW | 128.05 → 131.75 | 285.94 → 282.38 |
| Combine prediction flag and Rice residual | EOS R RAW | 184.46 → 203.73 | 386.58 → 398.70 |
| Refill an empty detail reservoir before inspection | G5 X Mark II C-RAW | 103.40 → 106.53 | 249.07 → 255.75 |
| Refill an empty detail reservoir before inspection | EOS M50 C-RAW | 159.38 → 170.43 | 330.98 → 341.13 |
| Consume four consecutive run flags together | G5 X Mark II C-RAW | 100.95 → 108.20 | 248.07 → 246.32 |
| Consume four consecutive run flags together | EOS M50 C-RAW | 167.90 → 163.62 | 334.93 → 332.10 |

Combining RAW prediction and residual reads improved one of four medians and slowed the EOS R sensor read by 10.4%. Early detail refill took longer in all four C-RAW cases. Grouping runs changed default imports by less than 1% on both files, while its sensor effect ranged from 2.5% less time on the M50 to 7.2% more time on the G5. These results supported retaining the existing decoder.

### RGB16 processing

The encoder accepts an interpolated code directly when the original input lies more than 32 representable doubles inside both exact output-rounding boundaries. Inputs near a transition follow the existing guarded path. An independent forward-transfer test covered every one of the 65,535 rounding transitions, including the edges of the guard.

| Input | Control ms | Candidate ms | Time change | Control peak RSS MiB | Candidate peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| EOS R RAW | 675.45 | 626.91 | −7.19% | 296.4 | 296.7 |
| G5 X Mark II C-RAW | 396.51 | 369.86 | −6.72% | 223.6 | 225.0 |

### Corrected-row RGB8 experiment

Combining black-level and white-balance correction with sliding demosaic rows preserved the output but did not establish a repeatable complete-import improvement across the two files. EOS R control process medians were 879.918, 404.018 and 445.455 ms; the candidate medians were 401.935, 414.024 and 401.495 ms. G5 C-RAW control medians were 260.556, 248.766 and 275.604 ms, against 268.766, 258.994 and 250.633 ms. The G5 median changed by only −0.60%, with pairwise changes in both directions. The noisy EOS control does not justify a reliable 10% gain claim. This candidate and all three codec candidates remain outside the delivered code.

## Source and evidence

The measured combined source is `e956a5ad5f8aa03e637e2037dd0154534caadb33`, tree `c831fcf62b90da97dfeb64c2ef1c11d61aab9c37`. Its frozen benchmark JAR SHA-256 is `323e08e4820ead3dd739a3040acafd8723ecc0db48141f085dede2bbcaa814b6`. The control JAR SHA-256 is `55c44c673b3a9dee8e32c0b05e8148fec39669a29c1f4e072374e2c93af2ec3e`, corresponding to public runtime source `14fafdc3fbc3dd25793b94c63096cd6abb8d770a`.

After the measured binary was frozen, `9998f220d875cfe1f1e70f06800c739dc7c65278` changed only the research-only Maven test-selection configuration and the workflow path trigger. The build follow-up receipt records the successful selected research tests, ordinary packaged verification and rebuilt-runtime comparison. All 2,080 runtime entries outside Maven metadata match the measured JAR; only the embedded processing POM differs. The measured source/JAR identity is preserved rather than relabeled as the later build revision.

- [Reproduction guide](reproduce.md)
- [Results and individual process medians](results-summary.json)
- [Shape, channel and full-value audit](channel-audit.json)
- [Checksums, build/CI binding and source reconstruction](provenance.json)
- [All timed process records](measured-panels.tar.gz)
- [Correctness and verification records](correctness.tar.gz)
- [Drivers, probe sources, configurations and experiment patches](sources-and-patches.tar.gz)

AI assistance was used for implementation, test design, analysis and report drafting. The source revisions and recorded executions provide the evidence for the claims above.
