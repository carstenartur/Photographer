# Further CR3 RGB16 throughput improvements

## Intent

Investigate and implement generally useful performance improvements to the full
CR3 RGB16 import after the default optical/tone change in PR #458. The user has
authorized both investigation and implementation, including the established
review/CI/merge workflow. Implementation choices below are engineering decisions,
not additional user requirements.

## Required behavior

- Preserve complete RGB16 output, the enabled supported optical/tone profiles,
  independent opt-outs, and the public ImageIO/ImageJ behavior.
- Preserve exact output for the existing arithmetic. No precision reduction,
  embedded-JPEG substitution, image-specific shortcuts or cross-import result cache.
- Keep Java 21 compatibility and the normal CPU-derived worker budget. Optional
  Java 25 Vector API kernels must retain a correct scalar fallback.
- Bound and account for additional workspace; measure cold-start effects where
  a new kernel or lookup table changes them.
- Ship only candidates with repeatable full-import improvement. Record rejected
  experiments and avoid calling a microbenchmark improvement a complete-import gain.

## Approaches

1. Reduce repeated scalar work and memory passes in optical and tone processing.
   This benefits the ordinary Java 21 launch and needs no application flags.
2. Add exact SIMD kernels to RGB16 development where arithmetic, layout and
   quantization permit it. Evaluate Java 25 with and without the Vector API using
   the existing packaged-kernel selection and fallback model.
3. Change image algorithms or precision for speed. Excluded by output compatibility.

The selected approach is to investigate 1 and 2 independently, then integrate only
measured winners. A SIMD prototype may be rejected if dispatch, gathers, startup
or temporary storage outweigh its gains.

## Evidence and delivery

Use main commit `567983ff58790dd1390c3bd89c986429d09e7cbf` as the baseline.
Use fixed G5 X II and EOS R inputs, plus other existing layouts for correctness.
Time fresh readers and inputs, with full-output checks outside the timer. Run
benchmark processes sequentially. Keep JVM flags, CPU budget and input identities
equal within each comparison. Commit compact raw measurements and rationale;
leave JFR recordings, binaries and build logs outside the repository.

Focused tests, exact-output parity, workspace checks, independent code review,
the canonical product gate and exact-head hosted CI precede merge.
