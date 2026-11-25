# CR3 throughput investigation and shared tone-memory accounting

25 November 2026. Baseline: `9944288ebc222c4f3a788a9d71a0b7b0280381ba`, after PR #459.

## Decision and retained code

**None of the six additional throughput candidates is retained.** The portable optical/tone fusion passed numerical checks but failed the broader performance gate: Java 21 wide-capture imports regressed with both tested collectors. The original optical implementation, decoder stage order, three-array tone kernel, sRGB encoder and Rice decoder are preserved.

The sole retained production change explicitly accounts for the existing shared camera-tone tables: **1,835,008 payload bytes (1.75 MiB)**. This corrects memory planning independently of fusion. It adds no throughput claim, image cache, precision reduction, JPEG shortcut or Java-version dispatch. No extra SIMD path is introduced; existing optional RGB8 and C-RAW vector paths remain unchanged.

All performance tables below concern the **rejected frozen fusion artifact**, not the retained memory-only code. Their numerical identities are kept separate:

| Artifact | SHA-256 |
| --- | --- |
| Baseline benchmark JAR | `90e5ef4bb6286103845f08f3fd548f3fae88059a14a9dd91270ff6f1bb29aba8` |
| Rejected portable fusion JAR | `d61b7edcbd1b3f64f5e1051cb31d113717a4c37c4adc3ef474860a35ccdf887a` |
| Retained memory-only JAR | `541ada0226781f11b9d036f17f0e11ec749ac65c048247c66ea9828ccae10b6c` |

[Source identities, separate validation scopes and review](verification.json)

## Complete-import protocol

Each invocation opens a fresh `ImageIO.read(File)` with default RGB16 development, supported optical correction, camera tone and automatic workers (`0`). Both arms expose four processors and run on logical CPUs 4–7 of an AMD EPYC 9V74. Runtimes are Debian Java `21.0.13.1+2-1-deb13u1-Debian` and Temurin Java `25.0.5+8-LTS`. Provider preflight precedes timing; format validation and complete-image hashing follow it. Filesystem caches are not flushed.

Warm Serial uses `-Xmx768m`, three fresh JVMs per runtime/input/arm, five warmups and seven measured imports. Warm G1 uses the same heap and iteration counts with two forks. Arm order reverses in the second fork; runtime/input order rotates. Each arm's result is the median of its fork medians, followed by `100 × (1 − candidate / baseline)`. Positive reductions mean faster; negative reductions mean slower. These are group statistics, not the median of paired percentage changes.

AUTO memory planning remains active: four exposed processors do not guarantee four workers in each import. No slower fork or memory-policy transition was excluded. Per-launch diagnostics preserve planner messages but do not associate them with individual iteration timestamps. RSS is a process-lifetime high-water mark including warmups, JIT and heap growth, not live processing workspace.

| Frozen-fusion protocol | Fresh JVMs | Recorded imports | Input/runtime coverage |
| --- | ---: | ---: | --- |
| Warm Serial, 768 MiB | 36 | 252 | Three inputs, Java 21 and 25 |
| Warm G1, 768 MiB | 24 | 168 | Three inputs, Java 21 and 25 |
| First import, Serial, 768 MiB | 24 | 24 | Two principal inputs, Java 21 and 25 |
| First import, G1, 256 MiB | 8 | 8 | Two principal inputs, Java 21 and 25 |
| First import, optional vector, 768 MiB | 4 | 4 | Two principal inputs, Java 25 |
| **Total** | **96** | **456** | **228 baseline and 228 candidate** |

Counts exclude configured warmups and separate stage diagnostics. Only eight of these 456 observations used 256 MiB. Wide-capture coverage belongs to the two warm protocols.

## Rejected fusion: all six warm Serial comparisons

Times are milliseconds. G5 is `sample.cr3`, EOS R is `eosr-iso-low.cr3`, and wide G5 is `sample-wide.cr3`.

| Runtime / input | Elapsed, baseline → fusion | Elapsed reduction | CPU, baseline → fusion | CPU reduction |
| --- | ---: | ---: | ---: | ---: |
| Java 21 / G5 | 691.6 → 662.8 | +4.17% | 2210 → 2100 | +4.98% |
| Java 21 / EOS R | 1168.6 → 1065.5 | +8.82% | 3750 → 3400 | +9.33% |
| Java 21 / wide G5 | 701.4 → 753.7 | **−7.45%** | 2180 → 2510 | **−15.14%** |
| Java 25 / G5 | 602.8 → 576.9 | +4.30% | 2060 → 1960 | +4.85% |
| Java 25 / EOS R | 1150.5 → 1041.5 | +9.48% | 3540 → 3300 | +6.78% |
| Java 25 / wide G5 | 671.2 → 623.4 | +7.12% | 2380 → 1990 | +16.39% |

The Java 21 wide aggregate regressed, although its three paired elapsed fork results were mixed. Baseline fork CPU medians were 3060, 2180 and 2150 ms; candidate values were 2510, 2660 and 2250 ms. The report therefore does not describe every Serial fork as a regression.

[Aggregates and every fork median](warm-summary.json) · [All 252 records](warm-records.jsonl) · [Launch commands and fork results](warm-runs.json) · [Environment and source/input identities](warm-environment.json) · [Complete diagnostics](warm-diagnostics.json)

Both arms use one common external probe. Its method/bytecode disassembly matches the probes in both packaged JARs; this does not require the complete class files to have identical hashes. Original per-launch stdout names remain in the manifests; consolidated JSONL retains their records with grouping fields added. [Probe provenance](probe-identity.json)

## Rejected fusion: all six warm G1 comparisons

The two-fork aggregate is the median of two fork medians, their average. All 168 measured outputs match.

| Runtime / input | Elapsed, baseline → fusion | Elapsed reduction | CPU, baseline → fusion | CPU reduction |
| --- | ---: | ---: | ---: | ---: |
| Java 21 / G5 | 658.0 → 683.6 | −3.89% | 2150 → 2330 | −8.37% |
| Java 21 / EOS R | 1059.4 → 1049.3 | +0.96% | 3615 → 3530 | +2.35% |
| Java 21 / wide G5 | 690.4 → 756.8 | **−9.62%** | 2165 → 2665 | **−23.09%** |
| Java 25 / G5 | 693.9 → 598.4 | +13.76% | 2320 → 2030 | +12.50% |
| Java 25 / EOS R | 1121.8 → 1151.1 | −2.61% | 3685 → 3700 | −0.41% |
| Java 25 / wide G5 | 719.9 → 668.0 | +7.22% | 2500 → 2160 | +13.60% |

Both G1 wide-capture fork pairs regress in elapsed and CPU time. Java 21 G5 and Java 25 EOS R also regress in this protocol. Together with Serial, these observations reject the portable candidate despite its favorable preliminary screens. [All additional results, commands and diagnostics](additional-results.json) · [All 204 additional records](additional-records.jsonl)

## First imports, constrained heap and vector compatibility

Three fresh JVMs per arm/input/runtime each perform one Serial import with zero warmup. These 24 observations include first-use processing but exclude whole-process startup and provider preflight. Unflushed filesystem caches mean “first import” is not a cold-storage measurement.

| Runtime / input | Elapsed, baseline → fusion (ms) | Elapsed reduction | CPU reduction |
| --- | ---: | ---: | ---: |
| Java 21 / G5 | 1713.0 → 1693.2 | +1.15% | +5.21% |
| Java 21 / EOS R | 2075.2 → 2193.2 | −5.69% | −6.47% |
| Java 25 / G5 | 1547.6 → 1614.3 | −4.31% | −2.95% |
| Java 25 / EOS R | 2101.9 → 2298.9 | −9.38% | −18.48% |

Three of four first-import elapsed comparisons regress; there is no general first-use benefit. Eight separate G1 imports at 256 MiB complete exactly with AUTO planning. They establish successful fit for the two principal inputs in those runs, not a minimum heap or repeatable performance advantage.

Four Java 25 imports with `--add-modules=jdk.incubator.vector` also match. Their single-observation elapsed reductions are −17.43% for G5 and +1.40% for EOS R; CPU reductions are −20.10% and −1.35%. These are compatibility observations, not repeated performance estimates. No additional SIMD implementation follows from this test. [Protocol details and preserved counters](additional-results.json)

## Exactness and historical candidate verification

All 456 frozen-fusion records preserve dimensions, three channels, 16-bit output and the expected complete-image numeric fingerprint. The checks establish agreement with the baseline rendering, not a Canon JPEG reference.

| Input | Dimensions | Full-image fingerprint |
| --- | --- | --- |
| G5 | 5496 × 3668 | `f334ac23d6d550ca` |
| EOS R | 6744 × 4500 | `293eb25bccb09add` |
| Wide G5 | 5496 × 3668 | `1940a760b67566d0` |

The rejected packaged candidate passed 48 focused production tests and 21 packaged processing checks on each of Java 21, Java 25 and Java 25 with vector enabled. Coverage includes sample-for-sample sequential/fused equality, bank identity, permuted and padded layouts, translated parents, odd multiworker stripes, small/identity cases, unsupported metadata, exceptions and existing optical/tone references. Passing numerical tests did not override the failed performance gate. [Historical verification](verification.json)

## Independent retained memory correction

`CameraToneCurve` already owns three `double[65536]` arrays and one `int[65536]`: `3 × 65536 × 8 + 65536 × 4 = 1,835,008` payload bytes. The fix accounts for these existing tables; it does not add that allocation. `CameraToneCurve.estimateTableBytes()` reports the payload without initializing the lazy tables.

The decoder reserves tone tables once, shared across workers, when processing requires both sRGB and camera tone. Together with the existing 1,114,120-byte encoder reserve, this is 2,949,128 shared payload bytes. The reserve applies to optical workspace and larger later copies, including selected regions and offset destinations. Optical estimates already include the encoder, so deduplication subtracts only the encoder and leaves the independent tone reserve intact.

Bayer, linear and tone-disabled reads receive no tone reserve. Planning conservatively includes the payload for unsupported profiles and already initialized tables. On a first sequential import, optical scratch may become unreachable before tone initializes; the estimate deliberately retains both rather than requiring collection at that point. Array headers and collector reserves remain outside this payload estimate; AUTO planning is an advisory estimate, not a heap reservation. The extra accounting may influence the selected worker budget, but introduces no new throughput assertion.

The regression first failed against baseline with 1,114,120 bytes instead of 2,949,128. An existing ImageIO later-peak expectation was subsequently increased by exactly 1,835,008 bytes, without widening a tolerance. The correction remains useful with the original sequential optics and tone loops. [Regression chronology, source hashes and independent review](verification.json)

## Bounded diagnosis of the Java 21 wide regression

Ten fresh JVMs cover two counterbalanced forks of baseline combined, fusion combined, candidate sequential, baseline tone and candidate tone. Each uses five warmups and seven measured stages: 70 measured and 50 warmup stage results, all matching their sequential oracle. The fixed input is wide G5 on Java 21, with 768 MiB Serial GC and the same CPU settings.

Tone-only uses the actual corrected pixels. Tables are preinitialized, and decoding, input reset and hashing are outside timing. Fusion's combined call uses one reflective invocation per image. These are warmed stage diagnostics, separate from complete-import acceptance and first-use memory.

| Stage comparison | Baseline/control → candidate elapsed (ms) | Elapsed reduction | CPU reduction |
| --- | ---: | ---: | ---: |
| Public tone, baseline → candidate | 40.170 → 37.867 | +5.73% | +23.08% |
| Combined, baseline → fusion | 534.813 → 514.181 | +3.86% | +4.51% |
| Candidate sequential → fusion | 490.392 → 514.181 | −4.85% | −0.89% |

Tone-only improves in both forks; the combined comparisons are mixed between forks. This does not reproduce a simple tone-kernel regression or identify the full-import cause. The evidence provides no basis for Java-version dispatch or a compiler, aliasing or GC attribution. [Every stage observation, configuration and diagnostic](stage-diagnosis.json)

## Six candidate decisions

The initial investigation contains eight groups, 40 fresh processes and 344 recorded full imports. Java 21 screens use one fork, five warmups and seven measured imports; optical Java 25 confirmations use two forks, eight warmups and eleven measurements. Each reduction below compares with its own group's control; pairs are G5 / EOS R. Cross-group absolute times are not a ranking.

| Candidate | Java 21 elapsed reductions | Java 25 confirmation | Final disposition |
| --- | ---: | ---: | --- |
| Mask-free ordinary Rice arithmetic | +23.0% / −1.6% | Not run | Reject: mixed one-fork screen, insufficient support to promote. |
| Compact exponent-dependent sRGB seeds | −3.4% / −11.7% | Not run | Reject: smaller storage, both imports slower. |
| Packed-long exact sRGB boundaries | −15.8% / +7.3% | Not run | Reject: mixed camera results and substantial G5 regression. |
| Packed-int guarded sRGB thresholds | +5.1% / −9.6% | Not run | Reject: EOS regression and more first-use memory. |
| Optical plane rotation | +3.6% / +8.2% | −3.0% / −6.2% | Reject: Java 25 confirmation failed to reproduce benefit. |
| Optical blue-plane/tone fusion | +8.0% / +11.9% | +16.6% / +16.8% | Reject: broader packaged portable gate failed. |

All 14 standalone exactness receipts passed. Per receipt, Rice checked 13,754 cases and 564,535 reader states; compact, packed-long and packed-int encoders made 20,563,443, 20,563,477 and 23,070,236 exact-code comparisons. Rotation and fusion reported 117 and 130 stage checks. These count different operations, not photographs. Runtime attribution, phase counts and fixture limits are preserved with the receipts.

Screens were class overlays, not rebuilt applications; packed encoder trials retained stale inlined planner constants, and scratch fusion preceded the production tone reserve. They covered two inputs and omitted wide G5. Their provenance and memory limitations remain explicit. [Hypotheses and receipts](investigation.json) · [All screen records](investigation-records.jsonl) · [58 diagnostic streams](investigation-stderr.json) · [Six frozen prototype patches](prototypes)

Four baseline JFR recordings frequently attribute sampled frames to the sRGB encoder, especially under optics. Hashing stacks are excluded. Inlined sampled frames do not establish the cost or cause of an individual branch or copy. [Profile configuration and summary](profile-summary.json)

## Retained-code validation and product gate

The memory-only artifact passes **30 focused tests, with zero failures, errors or skips**, and **15 packaged processing checks on each of Java 21, Java 25 and Java 25 with vector enabled**. Tests cover lazy/actual table payload, core memory boundaries, ImageIO memory planning and existing optical/tone behavior. Fusion-specific historical checks are not presented as tests of the retained API.

Its separate **28 first-import observations in 28 JVMs** all retain the expected full-output identities: 12 default checks at 768 MiB Serial (three inputs × two runtimes × two arms), 12 checks at 256 MiB G1 with the same input/runtime coverage, and four Java 25 vector checks on the two principal inputs. These are correctness and successful-fit evidence, with no retained-code performance claim. [Retained commands and checks](verification.json) · [All retained import records](retained-records.jsonl)

The unqualified local `bash scripts/verify-product.sh` reached application integration tests and encountered unavailable Docker while initializing PostgreSQL Testcontainers. No local product-gate success is claimed. The full unqualified gate, including those integration tests, must pass on the exact hosted PR revision before merge. The associated PR records completed local and hosted results; [verification.json](verification.json) preserves the local environment observation separately from the passing focused and packaged checks.

## Reproducing the rejected trial versus the retained code

To reconstruct the historical experiment, use two isolated checkouts of baseline `9944288ebc222c4f3a788a9d71a0b7b0280381ba`; apply [the final portable-fusion patch](prototypes/final-portable-fusion.patch) only to the experimental checkout. This patch includes the integrated fusion and memory reserve and is distinct from the earlier scratch prototype. Its reconstruction against baseline reproduces all nine frozen source hashes, verified without changing the active checkout. [Patch identity receipt](portable-patch-identity.json) Build both benchmark JARs with the same JDK 25/Maven setup and preserve each output separately:

```sh
mvn -B --no-transfer-progress -Pbenchmarks -pl canon-cr3-benchmark -am clean package -DskipTests
```

Set `CR3_BASELINE_JAR`, `CR3_CANDIDATE_JAR`, `CR3_JDK21`, `CR3_JDK25`, `CR3_MEASURE_CLASSES` and `CR3_RESULTS` to absolute paths; JDK variables name JDK homes. Compile one common probe from the recorded unchanged source, then use the existing comparison runner from the repository root:

```sh
mkdir -p "$CR3_MEASURE_CLASSES"
"$CR3_JDK25/bin/javac" --release 21 -cp "$CR3_CANDIDATE_JAR" -d "$CR3_MEASURE_CLASSES" \
  canon-cr3-benchmark/src/main/java/com/photographer/focusstack/cr3/benchmark/Cr3ImportProbe.java
python3 docs/cr3/srgb-quantization-performance-2026-11-25/run_comparison.py \
  --repo "$PWD" --baseline "$CR3_BASELINE_JAR" --candidate "$CR3_CANDIDATE_JAR" \
  --baseline-ref 9944288ebc222c4f3a788a9d71a0b7b0280381ba \
  --java21 "$CR3_JDK21/bin/java" --java25 "$CR3_JDK25/bin/java" \
  --classes "$CR3_MEASURE_CLASSES" --output "$CR3_RESULTS" \
  --cpus 4-7 --workers 4 --configurations jdk21 jdk25 --forks 3 --warmup 5 --iterations 7
```

Choose permitted CPUs on another machine and keep both arms identical. Here `--workers` sets `ActiveProcessorCount`; the ImageIO request stays AUTO. Use fresh output directories. G1 adds `--gc g1 --forks 2`; first imports use `--warmup 0 --iterations 1`; constrained checks additionally use `--gc g1 --heap 256m --forks 1`. The historical first-import and constrained protocols select only the two principal inputs. Exact commands and identities are preserved in the manifests.

For the retained memory-only correction, build the current source without applying any throughput prototype. Use its focused command and `ToneMemoryChecks` recipe in [verification.json](verification.json), followed by the canonical product gate. Record new artifact identities and results separately: the historical fusion timings and its `OpticalToneChecks` are not evidence of a throughput improvement in the retained code.
