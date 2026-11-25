# CR3 RGB16 Throughput Implementation Plan

**Goal:** Reduce complete RGB16 import time with unchanged images and bounded memory.

**Architecture:** Independently investigate exact scalar optical/tone improvements
and optional SIMD RGB16 development. Integrate measured winners into the existing
processing module and preserve the default Java 21 path.

**Tech stack:** Java 21 runtime baseline, Java 25 build/optional Vector API, Maven,
JUnit, JFR and the repository's full-import probes.

**Spec:** [Design and constraints](../specs/2026-11-25-cr3-rgb16-throughput-design.md).

## Work ownership and shared resources

Use one feature branch in the existing checkout, with disjoint source ownership.
Do not duplicate the fixture corpus into additional working copies. Root owns
integration, docs, commits, publishing and complete-import measurements. Worker A
owns RGB16 development kernels/tests; worker B owns optical/tone kernels/tests.
Serialize builds and measured runs with the common scratch lock
`/workspace/scratch/80a209e8f5df/cr3-performance.lock`.

## Review focus

- Unsigned 16-bit values, quantization boundaries and negative matrix results:
  require exact equality to the original implementation.
- Worker seams, odd widths, small images and unsupported vector widths: retain
  scalar edge/fallback behavior and test those conditions.
- Owned Bayer reuse and caller-supplied destinations: preserve aliasing and bounds.
- Transposed/translated/interleaved layouts and ROI/subsampling: preserve outputs.
- Java 21, Java 25 without the module, and packaged Java 25 with the module:
  verify loading, fallback, activation and memory accounting.

## Task 1: Baseline and hypotheses

- [x] Freeze main `567983ff` and record executable, source and input identities.
- [x] Profile complete corrected RGB16 imports; repeated sRGB quantization is the main sampled hot path, especially after optical remapping.
- [x] Record candidate hypotheses before benchmarking them.

## Task 2: RGB16 SIMD investigation

**Files:** `canon-cr3-processing/.../Rgb16Developer.java`, any small package-private
Java 25 kernel/support helper, and relevant processing/parity/packaged tests.

- [x] Prototype fused interpolation/matrix and batched quantization at 256 and 512 bits.
- [x] Verify boundary, mixed-lane, direction and full-image equality for the prototypes.
- [x] Preserve scalar arithmetic order and avoid FMA/reduced precision.
- [x] Measure stage and full imports with paired module-on/off launches and pinned confirmation.
- [x] Reject the SIMD prototypes: the final 512-bit confirmation regresses two of three full imports, with no reliable CPU reduction. Remove all prototype production/test files.

## Task 3: Optical/tone investigation

**Files:** `canon-cr3-processing/.../OpticalCorrection.java`,
`CameraToneCurve.java`, related helpers and their owning tests.

- [x] Inspect optical/tone work and sRGB proposal cost against complete-import profiles.
- [x] Pin optical/tone golden outputs and preserve full-image fingerprints.
- [x] Integrate certified direct sRGB seeds if final paired measurements confirm the gain; reserve the shared first-use arrays at both active development and later copy peaks.
- [x] Compare scalar candidates on both camera fixtures/runtimes. Reject tone hoisting, optical method splitting, one-sided uniform interpolation and exponent-bucket interpolation.
- [x] Confirm the direct-seed candidate and its narrowly scoped branchless selection refinement; retain only the measured winner and compact rejected evidence.

## Task 4: Combined measurement and review

- [x] Build the integrated candidate and verify exact full-image outputs.
- [x] Run isolated paired measurements with fresh readers, alternating execution
  order and multiple JVM forks; record elapsed time, CPU, allocation and process RSS.
- [x] Verify relevant cold-start, fallback and workspace behavior.
- [x] Review the complete diff independently and resolve findings.
- [x] Update current docs and prepare compact reproduction material and results.

## Task 5: Product verification and delivery

- [x] Run focused processing/core/ImageIO and packaged-kernel tests.
- [x] Run `bash scripts/verify-product.sh` without skip flags. All final CR3 product modules passed; local application integration is blocked by unavailable Docker (57 initialization errors, no assertion failures).
- [ ] Require the complete product gate on the final hosted CI revision before merge.
- [ ] Publish a reviewable PR, inspect all applicable exact-head checks, and merge
  only the verified final revision.
- [ ] Confirm merged main CI and inspect the full benchmark log for hidden failures.

## Recorded implementation evidence

The retained scalar quantizer and shared-memory integration are frozen and reviewed.
[Measurements and verification](../../cr3/srgb-quantization-performance-2026-11-25/README.md)
include 368 complete imports, Java 21/25 packaged checks, G1 and 256 MiB checks,
and the mixed first-import results. Delivery gates above remain pending until
the exact PR and merged-main revisions have completed hosted CI.
