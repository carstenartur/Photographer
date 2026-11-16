# canon-cr3-benchmark

JMH micro-benchmarks for the Canon CR3 stack: container parsing, metadata,
preview extraction, CRX decoding, RAW processing and complete ImageIO import.
The module mirrors the
[`carstenartur/sandbox/sandbox-benchmarks`](https://github.com/carstenartur/sandbox/tree/main/sandbox-benchmarks)
pattern: `maven-shade-plugin` produces a single executable benchmark JAR and
`benchmark-action/github-action-benchmark` publishes longitudinal charts on
GitHub Pages.

## What is measured

The suite deliberately contains three measurement levels. This makes a trend
useful not only as an alarm, but also as a way to locate the layer where a
performance change originated.

### File and decoder pipeline

| Class | Measured path |
|---|---|
| `Cr3BoxParseBenchmark` | `Cr3Parser.parse()` — ISO-BMFF box walk |
| `Cr3MetadataBenchmark` | `Cr3MetadataReader.read()` — EXIF and MakerNote extraction |
| `Cr3PreviewExtractBenchmark` | embedded JPEG extraction |
| `CrxLosslessDecodeBenchmark` | complete CRX lossless entropy decoder, excluding file I/O |
| `Cr3RawLinearBenchmark` | complete RAW-linear decode |
| `Cr3RawSensorFileBenchmark` | file open, parsing, full native Bayer decode and close, through `Path` or `FileImageInputStream` |
| `Cr3RawProcessedBenchmark` | direct full RAW-to-sRGB decode, including stream and file-path inputs |

### CRX decoder components

| Class | Measured path |
|---|---|
| `CrxDecoderStagesBenchmark` | real-payload CRX header parsing, first-plane decoding, MED prediction, tile-plane copying and positional-plane interleaving |
| `CrxDecoderMicroStagesBenchmark` | deterministic per-symbol Rice decoding, residual-sign mapping and Rice adaptation |

The per-symbol operations report nanoseconds per symbol. Reader and adaptation
state are reconstructed in dedicated `@Setup(Level.Invocation)` methods outside
the timed region. Structural stages use real CRX fixture payloads and report
milliseconds per stage; they do not share invocation setup with the synthetic
microbenchmarks.

### RAW processing and complete image import

| Class | Measured path |
|---|---|
| `Cr3RawProcessingStagesBenchmark` | black-level subtraction, white balance, Bayer demosaic, colour matrix and sRGB conversion as separate measurements |
| `Cr3ImageImportBenchmark` | complete public ImageIO import: file open, reader discovery, parsing, metadata, decoding, processing and cleanup |

The ImageIO benchmark records native Bayer, processed RGB and embedded preview imports. Comparing these with the direct decoder benchmarks shows
whether a change comes from the codec/processing pipeline or from integration
and import overhead.

The broad parser/metadata/decoder benchmarks retain all eight committed sample
CR3 files. The more expensive fixture-backed component and full-import
benchmarks use one PowerShot and one EOS sample (`sample.cr3` and
`eosr-iso-low.cr3`) to preserve both camera families without making the
longitudinal job unbounded. Synthetic micro-stage series are intentionally not
duplicated by camera fixture.

Common annotation policy:

```text
@BenchmarkMode(AverageTime)
@Fork(1)
```

Warmup and measurement duration are longer for complete pipelines and shorter
for isolated components. Mutable processing inputs are restored using JMH
invocation setup, outside the timed region.

## Build

The benchmark module is intentionally outside the default verification path,
but is available through the `benchmarks` Maven profile:

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64
mvn -B -DskipTests -P benchmarks -pl canon-cr3-benchmark -am package
```

This produces `canon-cr3-benchmark/target/benchmarks.jar`.

## Run

The runtime resolves CR3 fixtures in this order:

1. `-Dsamples.dir=...` system property;
2. `PHOTOGRAPHER_SAMPLES_DIR` environment variable;
3. `canon-cr3-imageio/src/test/resources/samples/` relative to the repository
   root or benchmark module.

From the repository root:

```bash
# Run the complete suite
java -jar canon-cr3-benchmark/target/benchmarks.jar

# Fixture-backed decoder components
java -jar canon-cr3-benchmark/target/benchmarks.jar CrxDecoderStagesBenchmark

# Synthetic per-symbol decoder components
java -jar canon-cr3-benchmark/target/benchmarks.jar CrxDecoderMicroStagesBenchmark

# RAW processing stages only
java -jar canon-cr3-benchmark/target/benchmarks.jar Cr3RawProcessingStagesBenchmark

# Complete ImageIO import only
java -jar canon-cr3-benchmark/target/benchmarks.jar Cr3ImageImportBenchmark

# Limit a parameterised benchmark to one fixture
java -jar canon-cr3-benchmark/target/benchmarks.jar \
    -p sample=sample.cr3 Cr3ImageImportBenchmark

# Time per import and allocated bytes per import for lossless RAW and C-RAW.
# gc.alloc.rate.norm counts allocation; process RSS and peak live heap are separate measures.
java -jar canon-cr3-benchmark/target/benchmarks.jar \
    Cr3ImageImportBenchmark.importSensorViaImageIo -prof gc \
    -p sample=sample.cr3,research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3

# Time and bytes allocated per full RAW-to-RGB import, two warmups and five measured images.
java -jar canon-cr3-benchmark/target/benchmarks.jar \
    Cr3ImageImportBenchmark.importProcessedRawViaImageIo -prof gc -bm ss -wi 2 -i 5 -f 1 \
    -jvmArgs '-Xms24m -Xmx512m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC' \
    -p sample=sample.cr3,eosr-iso-low.cr3 -rf json -rff rgb-import-profile.json

# JSON output used by the chart publisher
java -jar canon-cr3-benchmark/target/benchmarks.jar \
    -rf json -rff jmh-result.json
```

The file-input benchmark includes two lossless RAW fixtures and one C-RAW
fixture. The `workers` parameter uses the decoder's automatic sizing at zero
and caps the number of decoder workers at a positive value. For a local
comparison, hold the JVM processor count and fixture fixed.

```bash
java -jar canon-cr3-benchmark/target/benchmarks.jar \
    Cr3RawSensorFileBenchmark -p sample=eosr-iso-low.cr3 -p workers=1,2,4 \
    -jvmArgs '-XX:ActiveProcessorCount=4' -prof gc
```

To compare automatic worker sizing, run the same full import on the same runner with different JVM processor counts. The benchmark includes opening the file and closing the reader.

```sh
for cpus in 1 2 4; do
    java -jar canon-cr3-benchmark/target/benchmarks.jar \
        Cr3ImageImportBenchmark.importProcessedRawViaImageIo \
        -p sample=sample.cr3 -bm ss -wi 3 -i 5 -f 1 -prof gc \
        -jvmArgs "-Xms24m -Xmx512m -XX:+UseSerialGC -XX:ActiveProcessorCount=$cpus" \
        -rf json -rff "cr3-rgb-${cpus}cpu.json"
done
```

Useful JMH flags:

| Flag | Purpose |
|---|---|
| `-prof gc` | allocation rate and GC pressure |
| `-prof stack` | sampling profiler for hot frames |
| `-prof perfasm` | inspect hot JIT-compiled assembly on supported Linux hosts |
| `-wi N -i N -f N` | override warmup, measurement and fork counts |

## Longitudinal charts

The workflow publishes the full benchmark suite to the `gh-pages` branch under
`dev/bench/`. Charts are served at:

> <https://carstenartur.github.io/Photographer/dev/bench/>

A new historical point is produced when a relevant CR3 implementation or
benchmark change reaches `main`, during the weekly scheduled run, and when the
workflow is started manually. Each benchmark method and sample parameter is a
separate series, so a local regression cannot be hidden by a project-wide
average.

The workflow also stores the raw JMH JSON and environment metadata (runner
image, kernel, CPU, Java and Maven). The `jmh-result` artifact includes
`rgb-import-profile.json` from a separate run of the command above. Its primary
metric is milliseconds per RGB import, and `gc.alloc.rate.norm` is bytes
allocated per import. Environment changes must be considered
when interpreting long-term movement on hosted runners.

## Pull-request comparison

Pull requests use a shorter sentinel set and run base and head revisions on the
same runner. This is the causal regression check and avoids treating unrelated
host changes in the GitHub Pages history as proof that a pull request caused a
regression. The current threshold is 15%, with confidence-interval checks in
the comparison script.

See [`docs/cr3-benchmarking.md`](../docs/cr3-benchmarking.md) for the comparison
methodology and local two-ref command.
