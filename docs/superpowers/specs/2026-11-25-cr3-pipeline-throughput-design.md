# Further exact CR3 pipeline throughput

## Intent and baseline

Investigate and implement further generally useful improvements after PR #459,
using main `9944288ebc222c4f3a788a9d71a0b7b0280381ba` as the frozen baseline.
The user has requested implementation and the established verified PR/merge
workflow. Algorithm and experiment choices below are engineering decisions.

The previously outstanding main benchmark has completed: all 101 full-suite
combinations, two allocation profiles and the CPU focus-stack result are present
without hidden failures. The baseline product and packaging gates are green.

## Constraints

- Preserve every established RGB16 output code, supported optical/tone defaults,
  independent opt-outs, caller destinations, partial reads and orientation.
- Keep the normal worker limit derived from available CPUs and the automatic
  memory policy. No image-size or per-worker storage increase without a measured
  benefit and complete accounting.
- Keep Java 21 runtime compatibility; use Java 25 for the Maven build.
- No decoded-result cache, input-specific shortcut, JPEG substitution, lower
  precision or external decoder implementation source.
- Preserve signed codec intermediates, entropy consumption and invalid-input
  failures. Source-derived algebra is not a change to the CRX format model.
- Preserve existing APIs and image-ownership contracts. A consuming optical
  operation, if worthwhile, must have an explicit separate contract.

## Approaches to compare

1. **Reduce quantizer cache pressure.** Current Java 21/25 JFR captures again
   attribute most sampled top frames to the sRGB encoder, usually under optical
   rendering. Sampling attribution is not isolated instruction timing. Compare
   an exponent-dependent certified seed layout with the current uniform layout;
   preserve all exact boundaries, the 32-ULP guard and reference fallback.
2. **Reduce optical/tone memory traffic.** Independently compare rotating the
   existing channel buffers for a consumed image and combining the last optical
   channel commit with tone processing. Keep ordinary in-place APIs, caller
   destinations and selected-image work unchanged.
3. **Reduce ordinary Rice symbol work.** Apply the already established detail
   decoder's terminator algebra to the ordinary buffered Rice fast path. Keep
   refill, consumption, escapes and adaptation unchanged.

Each prototype is measured separately against the same baseline. Promote only
repeatable improvements in complete imports; reject isolated stage wins that
do not survive the full pipeline. More speculative C-RAW lifting and dequantizer
changes are deferred unless the selected candidates leave a concrete need.

The first compact-layout screens do not establish a consistent speed benefit.
One bounded follow-up will keep the existing uniform index and pack the seed and
a rounded boundary-distance estimate into one 32-bit entry. Rounding that
distance to a multiple of `2^26` introduces at most `2^25` input-bit steps of
uncertainty. Expanding the reference-expression guard to `2^25 + 32` keeps that
entire uncertainty interval on the exact fallback. The original two-code bucket
certification is unchanged; outside the guard, the sign is exact. This tests
whether removing the dependent boundary load helps without adding descriptor
lookups or variable shifts. Both first-use memory and every expanded guard edge
must be checked before any production change.

## Evidence and delivery

Use the existing complete-import probe with a fresh reader for every invocation.
Keep input identities, JVM/GC/heap/CPU settings and output validation identical
within each pair. Serialize CPU-heavy builds and measurements. Hash complete
outputs outside the import timer and retain slower forks and failed attempts.

Screen cheaply, then validate retained changes on both JVMs, the three existing
full-resolution captures, multiple JVM forks, G1 and constrained heaps. Include
C-RAW and entropy boundary cases if codec code is retained. First-import and
table initialization effects remain separate from warmed throughput claims.

Commit compact results, source identities, hypotheses, rejected outcomes and
reproduction steps. Keep binaries, JFR recordings and build logs out of Git.
Focused regression tests, independent review, the canonical product gate and
all exact-head hosted checks precede merge. Verify the merged main revision.

## Final disposition

None of the six additional throughput candidates met the portable acceptance
criteria. In particular, the frozen fusion candidate preserved all outputs but
regressed Java 21 full imports in the expanded trial. Bounded stage diagnosis
did not justify a compiler-specific dispatch. Preserve the original hot paths.

Retain the independently discovered shared tone-table memory reserve and its
regression tests; claim no further speedup for this correction. Keep every
positive and negative experiment in the dated result, separated from the final
memory-only artifact and its correctness/heap verification.
