# Exact CR3 Pipeline Throughput Implementation Plan

**Goal:** Further reduce complete default RGB16 import cost without changing images.

**Architecture:** Compare isolated quantizer, optical-dataflow and Rice fast-path
prototypes; integrate only measured winners into their existing owning modules.

**Tech stack:** Java 21 runtime, Java 25 build, Maven, JUnit, JFR and full-import probes.

**Spec:** [Design and constraints](../specs/2026-11-25-cr3-pipeline-throughput-design.md).

## Shared resources and ownership

Use the existing checkout on `perf/cr3-pipeline-throughput`, with no additional
fixture copies or worktrees. Scratch experiments live outside the checkout in
`cr3-pipeline-throughput`. Root owns production integration, measured runs,
documentation, commits and publishing; exploratory workers have disjoint scratch
directories. Serialize builds and measurements through `cr3-performance.lock`.

## Review focus

- Exact output around all sRGB transition guards, input bucket edges and clipping.
- Exclusive image ownership, logical channel order, caller destinations and aliases.
- Odd/small/transposed images, worker seams, generic rasters and selected reads.
- Entropy escape thresholds, zero/maximal remainders, slice tails and exact EOF state.
- First-use versus retained memory, overflow-safe planning and bounded heap behavior.

## Task 1: Baseline and profiling

- [x] Verify main, source/JAR identities and the completed previous benchmark log.
- [x] Capture Java 21/25 full-default-import JFR profiles for G5 X II and EOS R.
- [x] Record hypotheses before building each isolated candidate.

## Task 2: Independent prototypes

- [x] Compile the compact certified encoder and its boundary/reference driver.
  Owning source: `canon-cr3-processing/.../Srgb16Encoder.java`.
- [x] Compile the optical rotation and final-channel/tone fusion prototypes with
  equivalence and ownership drivers. Owning sources: `OpticalCorrection.java`,
  `CameraToneCurve.java`, `Cr3Decoder.java`.
- [x] Compile the ordinary Rice fast-path prototype and compare deliberate edge
  symbols and stream state with sequential reads. Owning source: `BitReader.java`.
- [x] Compile an independent compact packed-boundary encoder; verify both JVMs.
- [x] Test one final uniform packed-entry variant. A conservative expanded guard
  sends every ambiguous quantized threshold to the unchanged forward expression;
  only a measured complete-import gain can justify promotion.
- [x] Measure each candidate independently; retain raw observations and rejection
  reasons, and choose the smallest useful production change.

## Task 3: Production integration

- [x] Pin the relevant output, boundary, ownership and memory contracts in tests
  before integration. Existing behavior tests stay unchanged where possible.
- [x] Integrate the selected candidate(s), preserving existing APIs and fallbacks.
- [x] Run owning-module tests with upstream dependencies and package the candidate.
- [x] Review the complete source change independently and resolve findings.

## Task 4: Final evidence

- [x] Freeze the candidate executable/source identities.
- [x] Run paired complete imports on Java 21/25, with multiple forks and full hashes.
- [x] Check G1, first-import cost and constrained heaps; codec changes were not retained.
- [x] Verify packaged Java 21/25 execution, including the optional Vector API module.
- [x] Preserve compact raw evidence, scope, limitations and reproduction instructions.

## Task 5: Delivery

- [x] Invoke `bash scripts/verify-product.sh`; disclose the local Docker block and
  require the complete unqualified gate on the exact hosted PR revision.
- [x] Validate documentation, changed-file scope and source/evidence identities.
- [ ] Commit, publish a reviewable PR and inspect every applicable exact-head gate.
- [ ] Merge the verified revision and check merged main including full benchmark logs.

## Historical selection checkpoint

The optical/final-tone prototype advanced to packaged evaluation after a Java 21
screen and two Java 25 confirmation forks improved both initial captures. This
was preliminary: the final packaged comparison remained the acceptance gate.

Reject the compact seed, compact packed, and uniform packed32 variants for mixed
or regressed full-import results. Reject plane rotation after its Java 25
confirmation failed to reproduce a benefit. Leave ordinary Rice algebra
unchanged: its one-fork screen was mixed and did not justify changing codec code.

## Final decision

The broader frozen fusion trial completed 456 measured imports in 96 fresh JVMs
across both runtimes, three captures, two collectors, first imports and constrained
heaps. All output checks passed, but Java 21 wide-capture group medians regressed
7.45% under Serial and 9.62% under G1. Reject portable fusion as well.

A bounded ten-process stage diagnostic found no tone-only slowdown and mixed
combined-stage results. It did not identify the full-import cause. Do not invent
a Java-version switch or continue speculative variants after this failed gate.
Restore the original optical implementation, three-array tone loop and decoder
sequence. No additional throughput improvement is claimed in this round.

Retain only the independent tone-memory accounting correction. Its regression
failed against baseline: tone-enabled RGB8 reserved 1,114,120 rather than 2,949,128
bytes of shared first-use arrays. Add the actual 1,835,008-byte tone payload and
deduct only the encoder from optical workspace. Keep the estimate lazy and
conservative through later copies. This can change AUTO's layout/worker choice
near a heap boundary; verify complete outputs and constrained heaps, without
repeating the rejected throughput exploration.

The [result report](../../cr3/optical-tone-performance-2026-11-25/README.md) keeps
rejected prototype, frozen fusion and retained memory-only evidence separate.
The associated PR records exact hosted gates and merge/main status.
