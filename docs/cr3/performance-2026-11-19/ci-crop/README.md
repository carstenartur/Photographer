# Crop PR: investigated JMH gate and final CI

[PR #424](https://github.com/carstenartur/Photographer/pull/424) merged as
`1c73cd29a36e429f0217044d884be8f2b0543911` after the final head
`fb0bad87cd3902237b7871a24ed7db4353f45dab` passed all required checks.
The [exact-head snapshot](all-green.json) records six successful workflows and
14 completed checks: 11 successes and three expected release/schedule skips.
The calibration scope check succeeded; its actual measurement steps were
skipped because the calibration script was unchanged. No new calibration is claimed.

## Decode comparison evidence

All rows compare against base `9b82827af545e062cf495b9d7f3a6db7b07da469`.
The tuple is `CrxLosslessDecodeBenchmark.decode(sample=sample.cr3)`.
The existing gate fails when the head/base ratio exceeds 1.15 and the reported
confidence intervals are available and disjoint. That policy remained unchanged.

| Evidence | Head | Forks/order | Base ms/op | Head ms/op | Change | Result |
| --- | --- | --- | --- | --- | --- | --- |
| [Earlier CI](first-green/comparison.json) | `cfd424` | 1, base→head | 249.452116 | 250.735512 | +0.5145% | STABLE |
| [Failed CI](first-red/comparison.json) | `fb0bad87` | 1, base→head | 245.778361 | 317.327416 | +29.1112% | REGRESSION |
| [Local diagnostic](local-reverse/comparison.json) | `fb0bad87` | 3, head→base | 321.170281 | 332.947703 | +3.6670% | STABLE |
| [Final CI](final-green/comparison.json) | `fb0bad87` | 1, base→head | 252.904741 | 247.112151 | −2.2904% | STABLE |

The failed pair's 99.9% intervals were `[238.256122,253.300600]` and
`[266.339835,368.314997]` ms/op. All three head iterations were slower, so the
failure is not explained by dropping one outlier. Its rejection follows the
recorded inputs. Single-fork iteration intervals do not measure variation
between independent JVM processes.

Production, benchmark and input-fixture sources were identical between the two
PR heads: `cfd424`→`fb0bad87` only corrected the Fiji macro and research test
catalogue. The timed method calls `CrxLosslessRawDecoder.decode` on a previously
loaded full native CRX payload; it does not call the changed active-area
normalization. Its codec, benchmark and build-source files were unchanged from
the base. This is evidence against the crop operation adding the measured work,
but it does not explain the hosted process/runner mechanism.

The earlier and final successful hosted comparisons ran on Intel Xeon
6975P-C/AWS, four visible CPUs, Temurin 25.0.5+8, Maven 3.10.0. The failed
comparison used AMD EPYC 7763/Azure, four CPUs, the same Java version and Maven
3.10.1. The local diagnostic used nine visible CPUs, Temurin 25.0.5.1+2 and
Maven 3.9.11. Each pair shared its own host; changing hosts between runs prevents
an absolute CPU-speed comparison or a controlled explanation of the failure.
The original mechanism remains unidentified. All raw JMH observations and
environment files are retained in the four linked directories.

## Retry boundaries

After the source audit, earlier identical-product pass and local reverse-order
diagnostic, a targeted retry was requested at the unchanged final head. It
failed **before JMH ran**: Maven Central returned HTTP 429 for
`org.apache.maven.plugins:maven-shade-plugin:pom:3.6.2`. The comparison script
returned 2 after the build failure. Its artifact contained only environment
information and supplies no timing evidence. [Failure record](retry-build-429.json).

After the other builds ended and a pause, that infrastructure failure was
retried. The [final job](https://github.com/carstenartur/Photographer/actions/runs/44482790433/job/135424038285)
completed with all four tuples STABLE: parse −3.1169%, metadata +0.5066%,
preview −0.5147%, decode −2.2904%. The final decode intervals overlap:
`[213.629287,292.180194]` and `[217.384540,276.839761]` ms/op.

The earlier successful head and local diagnostic did not substitute for the
final-head gate. Merge waited for that gate and the full Maven, Docker/Chromium,
frontend, research, external Java 21 consumer and installed-Fiji checks.

AI assistance: ChatGPT assisted diagnosis, evidence checks and documentation.
