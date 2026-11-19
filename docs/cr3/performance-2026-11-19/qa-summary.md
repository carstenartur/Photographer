# CR3 QA and correctness summary

## Delivery state

[PR #422](https://github.com/carstenartur/Photographer/pull/422) merged after its required checks were green, as `7b7ff9c06f97db7c236b0bddbffd87fdaa0567c6`. The runtime optimization [PR #423](https://github.com/carstenartur/Photographer/pull/423) also merged after all checks passed, as `9b82827af545e062cf495b9d7f3a6db7b07da469`. The separate inclusive-boundary correction [PR #424](https://github.com/carstenartur/Photographer/pull/424) merged after all 14 exact-head checks completed successfully or with expected skips, as `1c73cd29a36e429f0217044d884be8f2b0543911`. No release is claimed.

## Reproduced defects and fixes

- **Stack fallback:** the fallback now inspects and decodes the selected embedded JPEG, preserving preview dimensions, pixels, memory accounting and the whole-stack source policy. Previously it queried RAW dimensions and retried RAW decoding. All three new fallback regressions failed before the fix; the final focused app group passed 15 tests.
- **Standard ImageIO destinations:** advertised RGB16 destinations now select the corresponding precision/encoding when no conflicting explicit CR3 request exists. Caller options remain unchanged, and incompatible supplied destinations are rejected before RAW decoding. Eight of thirteen new destination cases failed before the fix; all thirteen subsequently passed, and the final ImageIO group passed 71 tests.
- **Installed Fiji coverage:** the existing distribution workflow now triggers for changes to all six CR3 runtime modules and the root POM.
- **Inclusive active-area ends:** [PR #424](https://github.com/carstenartur/Photographer/pull/424) converts IAD1's inclusive last-pixel coordinates at the import boundary, retaining the existing half-open public metadata contract. The previous import omitted the final active row and column. Fourteen of 35 focused tests failed before the fix; all 35 passed afterward. Missing/invalid areas retain the full-sensor fallback, and long arithmetic protects endpoint conversion from overflow.

These are observed fail-before/fix-after results, not merely proposed tests.

## Verification scope and environment limits

The full local product run reported **1,666 Surefire tests, zero failures, zero errors and one skip**. Its integration phase was not fully green: the aggregated Failsafe records contain 79 tests and 56 errors because the local environment lacked usable Docker/Testcontainers support. This limitation is distinct from the required CI checks that were green before PR #422 merged.

The later **integrated optimized CR3/ImageJ module gate reported 649 tests, zero failures, zero errors and one skip**, with a successful reactor build. The separate crop correction then passed a **700-test CR3/CLI/ImageJ gate: 699 executed successfully, one optional Vector skip, no failures or errors**, including three DNG integration tests. These counts describe overlapping scopes and must not be added together as independent coverage. [Crop module counts](crop-module-counts.json).

The crop correction intentionally changes ordinary RGB8, RGB16 and RAW_LINEAR dimensions and output hashes. Previously artificial-edge demosaicing values can also change because real neighbors become available. The recorded G5 profile grows from 5495×3667 to 5496×3668, EOS R from 6743×4499 to 6744×4500, and M50 from 6023×4019 to 6024×4020. Full RAW_SENSOR, stored IAD1 values, writer and DNG geometry remain unchanged. This does not select another engine's crop.

The real-image histogram expectation was independently recalculated from the unchanged registered TIFF, first reproducing the previous histogram exactly. The corrected crop adds 9,163 sensor sites. [Reference calculation](crop-reference-histogram.json). The first installed-Fiji CI run confirmed exact full-sensor equality and the corrected three-channel 16-bit dimensions, then correctly rejected an outdated macro expectation. The macro and current observed research-profile dimensions were updated; historical claimed profiles were retained.

## Complete output preservation

The [baseline](baseline-output-hashes.json) and [optimized](optimized-output-hashes.json) manifests match for **52 complete outputs across 13 CR3 inputs**, covering **2,935,493,033 numeric channel samples**. They include native sensor, default RGB8, RGB16 linear and RGB16 sRGB output. Geometry, channels, storage convention, complete canonical sample SHA-256 and full FNV agree. This comparison includes every channel; it does not rely on sampled timing checksums or conversion through eight-bit `getRGB`.

The separate [EOS M50 C-RAW matrix](extra-output-hashes.json) adds four complete baseline/optimized matches and 243,362,061 numeric samples. The combined optimization check therefore covers **56 outputs, 14 inputs and 3,178,855,094 samples**. All these pairs use the same pre-correction active-area geometry; the separately identified IAD1 endpoint defect is not part of this pixel-preserving optimization.

The [independent native comparison](native-reference-verification.json) additionally matches Photographer baseline, LibRaw 0.21.4 and DNGLab 0.8.0 over four complete sensors: **98,606,416 photosites per engine**. All eight external comparisons agree in dimensions and canonical u16le SHA-256. The registered EOS M50 input and DNGLab PGM pins also match. Differences in valid PGM header formatting do not change sample equality. Sixteen-bit storage is not a claim of sixteen bits of sensor precision.

Java 21 gamma probes passed **917,490 rounding-boundary comparisons in each run**, under both [normal JIT execution](gamma-java21-jit.txt) and the [interpreter](gamma-java21-interpreter.txt). This documents the tested profiles rather than asserting universal bit-identical floating-point implementations.

## Constrained-heap robustness

The existing [Java 21 low-heap results](low-heap-results.json) use Serial GC, four available processors and both RGB16 transfer modes:

| Input | Heap cap | Baseline | Optimized |
| --- | ---: | --- | --- |
| `sample.cr3` | 160 MiB | OutOfMemoryError in linear and sRGB | Both completed; full-output checksums retained |
| `eosr-iso-low.cr3` | 224 MiB | OutOfMemoryError in linear and sRGB | Both completed; full-output checksums retained |

These are successful tests at the stated caps, not minimum-heap guarantees, RSS measurements or final performance rankings.

## Investigated CI performance incident

The original JMH decode comparison correctly failed its unchanged 15% gate at **+38.283%**, with nonoverlapping reported confidence intervals. A local reverse-order three-fork diagnostic was **STABLE at +1.9167%**. One [hosted retry](https://github.com/carstenartur/Photographer/actions/runs/44472156115/job/135390777979), on the unchanged QA head and gate policy, was green with all four tuples STABLE; decode changed **+0.3747%**.

The retry used Intel Xeon 6975P-C/AWS rather than the original AMD EPYC 9V74/Azure runner. The original separation's cause remains unknown. The later non-reproductions are not proof of generic noise, a decoder bug fix or relative CPU performance.

The later crop PR also had a decode-gate incident: +29.1112% with disjoint confidence intervals on one hosted run. The timed decoder source was unchanged; an earlier identical-product run was stable, and a local reverse-order three-fork diagnostic was stable at +3.6670%. An intermediate retry failed before measuring because Maven Central returned HTTP 429. The final unchanged-head hosted comparison passed all four tuples; decode was −2.2904%. The original hosted mechanism remains unidentified. [Complete comparison records and interpretation](ci-crop/README.md).

## Remaining boundaries

The **P2 ImageIO progress/cancellation finding remains open and source-reviewed only**. No runtime reproduction or fix is claimed for it; start/end events alone would not establish responsive mid-decode cancellation.

Physical camera, USB and end-user desktop workflows were not exercised by this automated QA. The selected inputs and tested environments do not establish universal correctness for every camera/firmware combination.

Final repeated timing results, exact timing-helper/JAR bindings and new public run data are maintained separately. The [setup](comparison-setup.md) preserves LibRaw owned-copy semantics, DNGLab CLI serialization boundaries, startup distinctions and crop/encoding limitations. This package preserves the checked [tool manifest](reference-tools/tool-manifest.json) unchanged; [evidence-manifest.json](evidence-manifest.json) records hashes and roles without including image dumps or binaries.
