# CR3 pipeline investigation: candidate screens and packaged follow-up

Fusion was the only prototype that earned a packaged trial. The first packaged portable fusion candidate, JAR SHA-256 `d61b7edcbd1b3f64f5e1051cb31d113717a4c37c4adc3ef474860a35ccdf887a`, **will not be published as-is**: its Java 21 wide-capture regression repeated with both tested collectors. Numerical checks and complete-image fingerprints passed; the portable performance gate failed. The cause remains open and belongs to the separate stage diagnosis. [Packaged follow-up receipt](packaged-followup-summary.json)

## Six prototype candidates

The baseline was `9944288ebc222c4f3a788a9d71a0b7b0280381ba`, using the frozen JAR identified in [investigation.json](investigation.json). Each prototype changed one mechanism. All full imports used default RGB16 output, the full optical/tone pipeline, `workers=0`, four available processors, a 768 MiB heap and Serial GC. Screens used one fresh JVM per arm/input with five warmups and seven recorded imports. Optical confirmations used two forks, eight warmups and eleven recorded imports, with reversed arm/input order in the second fork.

Numbers below are **elapsed-time reductions against each group's own control**: positive is faster, negative is slower. Each pair is G5 `sample.cr3` / EOS R `eosr-iso-low.cr3`. Absolute timings across groups are not a ranking. [Complete summaries, fork results and identities](investigation.json)

| Candidate | Tested change | Java 21 screen | Java 25 confirmation | Historical decision |
|---|---|---:|---:|---|
| `rice-maskless` | Remove the ordinary Rice remainder mask using the unary-terminator identity. | +23.0% / −1.6% | Not run | Mixed, one fork; inconclusive and not promoted. |
| `srgb-compact` | Smaller exponent-dependent seed grid, retaining exact boundaries. | −3.4% / −11.7% | Not run | Rejected after both fixtures regressed. |
| `srgb-packed` | One packed long for the seed and exact boundary delta. | −15.8% / +7.3% | Not run | Rejected after mixed fixture results. |
| `srgb-packed32` | Uniform indexing with a packed seed and quantized threshold, protected by an exact fallback guard. | +5.1% / −9.6% | Not run | Rejected after mixed fixture results. |
| `optical-rotate` | Rotate owned RGB planes to remove copy commits. | +3.6% / +8.2% | −3.0% / −6.2% | Rejected after confirmation. |
| `optical-tone-fused` | Let tone processing consume the optical blue scratch and remove one blue-plane copy. | +8.0% / +11.9% | +16.6% / +16.8% | Advanced to a packaged trial; this was not production acceptance. |

All 14 historical standalone logs report success on their named JVMs. Per receipt, Rice checked 13,754 cases and 564,535 reader states; the compact, packed-long and packed-int encoders made 20,563,443, 20,563,477 and 23,070,236 exact-code comparisons respectively; rotation and fusion reported 117 and 130 stage checks. These count different operations, not photographs or complete imports. Runtime attribution, phase counts, fixture scope, synthetic fallback checks and provenance gaps are preserved with the receipts. [Exactness evidence](investigation.json)

These were **class overlays**, not rebuilt application evidence. The packed encoders retained old inlined memory-planner constants, and the scratch fusion preceded the production tone-table reserve. The two-screen-input coverage did not include `sample-wide.cr3`. Those limits justified a broader packaged gate before acceptance. [Recorded scope and memory limits](investigation.json)

## Why preliminary fusion advanced, then the portable version failed

Fusion improved both screening fixtures in both preliminary groups and passed the standalone sample-for-sample stage comparisons. The packaged successor added the tone-table memory reserve and was measured across Java 21 and 25, three inputs in the warm groups, Serial and G1 collectors, first imports, a constrained heap and an optional-vector smoke configuration. That broader coverage exposed regressions the two-input overlay screen had not measured. [Full packaged summaries](packaged-followup-summary.json)

For Java 21 `sample-wide.cr3`, the declared median-of-fork-medians showed **−7.45% elapsed / −15.14% CPU reduction under Serial GC**, and **−9.62% / −23.09% under G1**. These are group statistics: the three Serial elapsed fork pairs were mixed, while both G1 fork pairs regressed. The evidence supports withholding this portable candidate; it does not identify a compiler, aliasing or memory cause. [Frozen candidate and comparison receipts](packaged-followup-summary.json)

All packaged comparisons with either an elapsed or CPU regression are retained below. The JSON also retains all favorable comparisons and their original summaries.

| Group | Runtime / input | Elapsed reduction | CPU reduction |
|---|---|---:|---:|
| Warm Serial, 3 forks | Java 21 / wide G5 | −7.45% | −15.14% |
| Warm G1, 2 forks | Java 21 / G5 | −3.89% | −8.37% |
| Warm G1, 2 forks | Java 21 / wide G5 | −9.62% | −23.09% |
| Warm G1, 2 forks | Java 25 / EOS R | −2.61% | −0.41% |
| First import, 3 forks | Java 21 / EOS R | −5.69% | −6.47% |
| First import, 3 forks | Java 25 / G5 | −4.31% | −2.95% |
| First import, 3 forks | Java 25 / EOS R | −9.38% | −18.48% |
| Optional-vector smoke, 1 fork | Java 25 / G5 | −17.43% | −20.10% |
| Optional-vector smoke, 1 fork | Java 25 / EOS R | +1.40% | −1.35% |

The smoke configuration has one recorded import per arm/input, so its timings are compatibility observations, not repeatable performance estimates. “First import” means a fresh JVM with unflushed filesystem caches, not cold storage. Wide-capture coverage is confined to the two warm groups. [Protocol details and all 22 comparison cells](packaged-followup-summary.json)

## Evidence inventory and relocation

| Phase | Completed groups | Fresh JVM processes | Recorded full imports |
|---|---:|---:|---:|
| Six-prototype investigation | 8 | 40 | 344 |
| Frozen packaged `d61…` follow-up | 5 | 96 | 456 |
| These two phases combined | 13 | 136 | 800 |

The 456 packaged observations comprise 228 baseline and 228 candidate imports. All retain the expected dimensions, RGB16 format and full-image numeric fingerprint for their input. These counts exclude warmup invocations and standalone stage checks. The historical observations and diagnostics are fully preserved here; the packaged appendix is summary-only, with its final raw records and stderr maintained in the separate packaged evidence phase. [Historical audit](investigation.json) · [Packaged audit](packaged-followup-summary.json)

Move this directory as a unit into the repository documentation. Its Markdown links and patch paths remain relative:

- [investigation.json](investigation.json): identities, hypotheses, source/driver/class hashes, exactness receipts, complete fork summaries and historical decisions, plus the later packaged disposition.
- [investigation-records.jsonl](investigation-records.jsonl): all 344 historical observations, with every original field retained and variant/protocol/fork/arm/source coordinates added.
- [investigation-stderr.json](investigation-stderr.json): 58 lossless diagnostic streams—40 process stderr files and 18 stage/compile logs—stored as 27 deduplicated text blobs. The filename is `investigation-stderr.json`; there is no `investigation-diagnostics.json` alias.
- [packaged-followup-summary.json](packaged-followup-summary.json): all five completed frozen-d61 summaries, all 22 comparisons, counts, identities and the failed portable-performance decision. Its 456 observations are not appended to the historical JSONL.
- Production-only diffs: [Rice](prototypes/rice-maskless.patch), [compact encoder](prototypes/srgb-compact.patch), [packed-long encoder](prototypes/srgb-packed.patch), [packed-int encoder](prototypes/srgb-packed32.patch), [plane rotation](prototypes/optical-rotate.patch), [optical/tone fusion](prototypes/optical-tone-fused.patch).

The data files distinguish bundle-relative artifact paths from original workspace paths retained as provenance. Historical commands and original source-receipt paths are not promised as runnable or navigable after relocation. JARs, compiled classes, the large standalone drivers and duplicate per-fork raw files are intentionally represented by identities rather than copied into this documentation bundle. The six patches contain only the frozen production-source changes against the stated baseline; they exclude later integration changes.
