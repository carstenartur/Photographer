# canon-cr3-benchmark

JMH micro-benchmarks for the Canon CR3 stack
(`canon-cr3-core` parser, metadata reader, preview extractor and CRX RAW
decoder). A `maven-shade-plugin` build produces a single executable JMH JAR
at `target/benchmarks.jar`.

Pull-request checks compare the base revision and PR head on the **same runner**.
Scheduled and manually dispatched runs retain the full longitudinal history on
GitHub Pages. Historical measurements are useful as a trend, but are not used
as the sole causal regression gate for a pull request.

## What is measured

The full suite parametrises over the CR3 samples committed under
`canon-cr3-imageio/src/test/resources/samples/`.

| Class | Hot path |
|---|---|
| `Cr3BoxParseBenchmark` | `Cr3Parser.parse()` — pure ISO-BMFF box walk |
| `Cr3MetadataBenchmark` | `Cr3MetadataReader.read()` — EXIF / MakerNote |
| `Cr3PreviewExtractBenchmark` | `Cr3PreviewExtractor.extractPreview()` — embedded JPEG |
| `CrxLosslessDecodeBenchmark` | `CrxLosslessRawDecoder.decode()` — entropy hot path (no I/O) |
| `Cr3RawProcessedBenchmark` | end-to-end `Cr3Decoder.decode(rawProcessed())`, including demosaic, white balance, colour matrix and sRGB; also a memory-mapped variant |
| `Cr3RawLinearBenchmark` | end-to-end `Cr3Decoder.decode(rawLinear())` without demosaic or colour transformation |

The benchmark classes define their normal JMH warm-up, measurement and fork
configuration. Pull-request sentinels deliberately override those values to a
shorter comparison profile and use one representative sample so both revisions
fit into one hosted-runner job.

## Build

The benchmark module is intentionally not part of the normal `mvn verify`
critical path. Build it explicitly from the repository root:

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64
mvn -B --no-transfer-progress -DskipTests \
  -P benchmarks \
  -pl canon-cr3-benchmark -am \
  package
```

This produces `canon-cr3-benchmark/target/benchmarks.jar`.

## Run one revision

The runtime resolves fixtures in this order:

1. `-Dsamples.dir=...` system property;
2. `PHOTOGRAPHER_SAMPLES_DIR` environment variable;
3. `canon-cr3-imageio/src/test/resources/samples/` relative to the working directory or its parent.

Examples from the repository root:

```bash
# Run the complete suite.
java -jar canon-cr3-benchmark/target/benchmarks.jar

# Run a single benchmark class.
java -jar canon-cr3-benchmark/target/benchmarks.jar Cr3BoxParseBenchmark

# Limit execution to selected fixtures.
java -jar canon-cr3-benchmark/target/benchmarks.jar \
  -p sample=sample.cr3,eosr-iso-low.cr3 \
  CrxLosslessDecodeBenchmark

# Produce JMH JSON.
java -jar canon-cr3-benchmark/target/benchmarks.jar \
  -rf json -rff jmh-result.json
```

Useful JMH flags:

| Flag | Purpose |
|---|---|
| `-prof gc` | collect allocation rate and garbage-collection pressure |
| `-prof stack` | sample hot frames |
| `-prof perfasm` | inspect hot JIT-compiled assembly on a suitable Linux host |
| `-wi N -i N -f N` | override warm-up iterations, measurement iterations and forks |

## Compare two Git revisions locally

`scripts/run_jmh_two_ref.sh` runs the same sentinel comparison used by pull
requests without requiring GitHub Actions. It creates temporary detached Git
worktrees, so it neither checks out another revision in the current worktree nor
deletes uncommitted files.

```bash
# Compare main with the current HEAD on the same host.
bash scripts/run_jmh_two_ref.sh main HEAD

# Write raw JSON, environment metadata and reports to a chosen directory.
bash scripts/run_jmh_two_ref.sh origin/main feature/my-change target/jmh-review

# Reverse execution order to inspect order/thermal bias.
JMH_RUN_ORDER=head,base \
  bash scripts/run_jmh_two_ref.sh main HEAD target/jmh-head-first

# Increase forks for a confirmation run.
JMH_FORKS=2 JMH_WARMUP_ITERS=3 JMH_MEASURE_ITERS=5 \
  bash scripts/run_jmh_two_ref.sh main HEAD target/jmh-confirmation
```

The output directory contains:

- `base.json` and `head.json` with raw JMH results;
- `comparison.md` and `comparison.json` from `scripts/compare_jmh.py`;
- `environment.txt` with exact refs, SHAs, execution order, CPU, kernel, Java,
  Maven and benchmark parameters.

The default failure threshold is a head/base score ratio of `1.15`; override it
with `JMH_THRESHOLD`. A threshold breach is a signal for a confirmation run and
investigation, not proof by itself of a code-caused regression.

## CI

`.github/workflows/benchmark.yml` has two modes:

- **Pull requests:** a short parser/metadata/preview/RAW sentinel set runs for
  the base and head revisions on one pinned `ubuntu-24.04` runner via the same
  local two-ref script. Raw results, comparison reports and environment metadata
  are uploaded together.
- **Scheduled/manual main runs:** the full suite records longitudinal history
  under `dev/bench/` on `gh-pages` and uploads the raw JMH JSON.

The benchmark workflow is path-filtered to CR3 production modules, benchmark
infrastructure and comparator scripts. Normal application-only changes do not
consume this expensive workload.
