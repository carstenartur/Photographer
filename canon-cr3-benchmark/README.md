# canon-cr3-benchmark

JMH micro-benchmarks for the Canon CR3 stack
(`canon-cr3-core` parser, metadata reader, preview extractor and CRX RAW
decoder). The module mirrors the
[`carstenartur/sandbox/sandbox-benchmarks`](https://github.com/carstenartur/sandbox/tree/main/sandbox-benchmarks)
pattern: a `maven-shade-plugin` build produces a single uber-jar
(`target/benchmarks.jar`) with `org.openjdk.jmh.Main` as the manifest
`Main-Class`, and the `.github/workflows/benchmark.yml` workflow consumes
the JSON output through
[`benchmark-action/github-action-benchmark`](https://github.com/benchmark-action/github-action-benchmark)
so results are visualised on GitHub Pages and regressions ≥ 15 % alert on
the PR.

## What is measured

Every benchmark uses the JMH defaults below and parametrises over **all
eight** sample CR3 files committed under
`canon-cr3-imageio/src/test/resources/samples/`.

| Class | Hot path |
|---|---|
| `Cr3BoxParseBenchmark`        | `Cr3Parser.parse()` — pure ISO-BMFF box walk |
| `Cr3MetadataBenchmark`        | `Cr3MetadataReader.read()` — EXIF / MakerNote |
| `Cr3PreviewExtractBenchmark`  | `Cr3PreviewExtractor.extractPreview()` — embedded JPEG |
| `CrxLosslessDecodeBenchmark`  | `CrxLosslessRawDecoder.decode()` — entropy hot path (no I/O) |
| `Cr3RawProcessedBenchmark`    | end-to-end `Cr3Decoder.decode(rawProcessed())` (incl. demosaic, WB, colour matrix, sRGB); also a `*Mmap` variant using the new `Cr3Decoder.setInput(Path)` overload |
| `Cr3RawLinearBenchmark`       | end-to-end `Cr3Decoder.decode(rawLinear())` (no demosaic / colour transform) |

JMH annotation set:

```
@BenchmarkMode(AverageTime)
@OutputTimeUnit(MILLISECONDS)
@Warmup(iterations = 3, time = 2 or 5)
@Measurement(iterations = 5, time = 2 or 5)
@Fork(1)
@State(Scope.Benchmark)
@Param(...) // 8 sample CR3 files
```

## Build

The benchmark module is intentionally not part of the CI's `mvn verify`
critical path, but it lives in the parent reactor so a top-level build
still compiles it:

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64
mvn -B -DskipTests package -pl canon-cr3-benchmark -am
```

This produces `canon-cr3-benchmark/target/benchmarks.jar`.

## Run

The runtime needs to find the eight sample CR3 fixtures. Resolution order:

1. `-Dsamples.dir=...` system property
2. `PHOTOGRAPHER_SAMPLES_DIR` environment variable
3. `canon-cr3-imageio/src/test/resources/samples/` relative to the working
   directory (or its parent)

From the repo root:

```bash
# Run everything (~15-30 minutes wall clock)
java -jar canon-cr3-benchmark/target/benchmarks.jar

# Run a single class
java -jar canon-cr3-benchmark/target/benchmarks.jar Cr3BoxParseBenchmark

# Limit to a subset of samples
java -jar canon-cr3-benchmark/target/benchmarks.jar \
    -p sample=sample.cr3,eosr-iso-low.cr3 CrxLosslessDecodeBenchmark

# JSON output for github-action-benchmark
java -jar canon-cr3-benchmark/target/benchmarks.jar \
    -rf json -rff jmh-result.json
```

Useful JMH flags:

| Flag | Purpose |
|---|---|
| `-prof gc`           | collect allocation rate / GC pressure |
| `-prof stack`        | sampling profiler — find hot frames |
| `-prof perfasm`      | inspect hot JIT-compiled assembly (Linux + perf) |
| `-wi N -i N -f N`    | override warmup / measurement / fork counts |

## CI

`.github/workflows/benchmark.yml` runs the full suite on every push / PR
to `main` that touches `canon-cr3-core/**`, `canon-cr3-imageio/**` or
`canon-cr3-benchmark/**` (plus on-demand via `workflow_dispatch`). It
publishes results to the `gh-pages` branch under `dev/bench/`, which is
served at:

> <https://carstenartur.github.io/Photographer/dev/bench/>

Regressions ≥ **15 %** post a comment on the offending PR.
