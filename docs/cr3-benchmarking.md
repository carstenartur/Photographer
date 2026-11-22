# CR3 benchmark methodology

CR3 pull-request performance checks compare the base and head revisions on the same host. Longitudinal `main` history remains useful for trends and component localisation, but it is not used as the causal pull-request baseline.

## Comparison with LibRaw 0.22.2 and 0.22.3 on 2026-11-22

The [release comparison](cr3/import-comparison-2026-11-22/README.md) measures
Photographer 0.1.8 on Java 21 through its public ImageIO entry points. It includes
byte-exact native Bayer comparisons, complete RGB8 and RGB16 imports, process
high-water memory and pinned prebuilt LibRaw versions.

The [RGB16 interpolation experiment](cr3/rgb16-pairs-2026-11-22.md) reports
the exact-output Bayer-pair production change. The [file access and C-RAW
experiments](cr3/io-benchmark-2026-11-22.md) record the other measured variants.

## Complete-import QA and comparison on 2026-11-19

The [four-file QA and performance report](cr3/performance-2026-11-19/README.md)
compares the pixel-preserving RGB16 optimization with its baseline, LibRaw
0.21.4 and DNGLab 0.8.0. It retains full `ImageIO.read(File)` RGB8 controls,
native and RGB16 paths, Java 21/G1 sensitivity, first-import and serialized CLI
boundaries, allocation/RSS evidence, exact input/output hashes and all repeated
observations. The later inclusive-IAD1 crop correction is identified separately
because it intentionally changes developed dimensions and boundary pixels.

[Public Java/C probes](../scripts/benchmarks/README.md) and
[source-based reproduction](cr3/performance-2026-11-19/comparison-setup.md)
make the complete-file study reproducible alongside the JMH regression gates.

The subsequent [adaptive-memory and ImageIO-copy study](cr3/memory-2026-11-19/README.md)
uses the corrected crop as its baseline. It checks automatic low-heap imports,
full numeric equivalence, compact-rotation trade-offs, and compatible-destination
copy elimination. Rejected scheduling and arithmetic experiments remain in its
evidence. Its timing tables are separate from the earlier external-engine study.

## Full RAW focus stacks

The Photographer benchmark measures a stack from opening the originals through writing the PNG. The CPU series in the history develops three copies of the repository's EOS R fixture. To measure a captured stack, pass a directory of CR3 originals whose filenames sort in acquisition order.

```bash
mvn -pl app -am -Pfocus-stack-benchmark -DskipTests test
```

To compare the CPU and TornadoVM paths on the same machine and series, use JDK 25 and an OpenCL GPU with double-precision support.

```bash
mvn -pl app -am -Pfocus-stack-benchmark,focus-stack-gpu -DskipTests \
  -Dfocus.stack.series=/absolute/path/to/cr3-series \
  test
```

Results are saved in `app/target/focus-stack-jmh.json`. The optional `focus.stack.device` property selects a TornadoVM backend and device by index, for example `0:1`. Tune the fork heap with `focus.stack.heap` and the shared decode budget in bytes with `focus.stack.maxBytes`. The GPU keeps the developed frame, focus scores and winning pixels in device memory. Its native buffers need about 18 bytes per pixel on the host and device, plus the Java decode and PNG buffers on the host. CPU prefetch overlaps GPU work when the decode budget allows a second frame. During benchmark setup the GPU run verifies every pixel of the resulting stack against the CPU result.

## Local two-ref comparison

The same orchestration used by the pull-request workflow is available from any full Git checkout:

```bash
python3 scripts/run_jmh_compare_refs.py \
  main \
  feature/my-change \
  --output-dir target/jmh-ref-comparison \
  --fail-on-regression
```

Requirements:

- Git with both revisions available locally;
- JDK 25;
- Maven;
- the checked-in CR3 sample fixtures.

The script does not modify the current checkout. It resolves both refs to immutable commit SHAs, creates a temporary detached worktree for one revision at a time, builds the benchmark reactor and executes the same CR3 sentinel set on the same machine.

Generated outputs:

- `base.json` and `head.json`: raw JMH results;
- `comparison.json`: machine-readable classification;
- `comparison.md`: human-readable comparison;
- `environment.txt`: refs, SHAs, order, CPU, platform, Java and Maven details.

Use `--order head-base` for a diagnostic reverse-order run when warm-cache, thermal or host-load drift is suspected. A result should not be treated as causal when reversing the order changes the conclusion.

## Regression rule

The comparator assumes average-time benchmarks, where lower is better. A pull request fails only when:

1. the head/base score ratio exceeds the configured threshold (15% by default);
2. both JMH results provide confidence information; and
3. the head confidence interval is entirely above the base interval.

A large point estimate with missing or overlapping confidence data is reported as `NOISY_WARNING`, not as a confirmed regression.

Each benchmark/parameter pair is classified independently. Results are never averaged into one project-wide number that could conceal a single regression.

## Sentinel scope

Pull requests run the shorter parser, metadata, preview extraction and complete lossless-decoder sentinels. They compile the complete benchmark module, but do not execute every expensive component and import benchmark for both revisions.

The default pull-request command currently uses one fork to control hosted-runner cost. The raw JSON and environment artifacts are retained so suspicious changes can be repeated locally with additional JMH arguments, for example:

```bash
python3 scripts/run_jmh_compare_refs.py \
  main feature/my-change \
  --forks 3 \
  --order head-base
```

Use `--forks` for the JVM count. JMH 1.37 rejects repeated `-f` options.
Use `--jmh-arg` for additional options such as a profiler. Record both
execution orders when investigating a noisy signal.

## Unchanged-code calibration

Run `python3 scripts/calibrate_cr3_sentinels.py` from a clean checkout.
It builds once and measures the four PR sentinels on the same revision in
both execution orders at one, two and three forks. Each case includes raw
JMH JSON, per-tuple verdicts and environment information. Elapsed build
and measurement times are recorded separately in `target/jmh-calibration`.
On GitHub the **Calibrate unchanged CR3 sentinels** job runs when its driver
changes or when selected in the manual benchmark workflow. The downloadable
`cr3-jmh-calibration` artifact contains the measurements.

[Calibration measurements and gate policy](cr3/benchmark-calibration-2026-11-15.md)


## Longitudinal full-suite scope

Relevant pushes to `main`, the weekly schedule and manual workflow runs execute the complete suite and publish one historical series for every benchmark/parameter pair. The full suite includes:

- container parsing, metadata and preview extraction;
- complete lossless CRX decoding and RAW-linear/RAW-processed pipelines;
- CRX header, plane decoding, MED prediction, tile copying, plane interleaving and per-symbol entropy/adaptation stages;
- black-level, white-balance, demosaic, colour-matrix and sRGB stages;
- end-to-end ImageIO preview, full-sensor and processed-RAW imports, plus Bayer and RGB regions. `importDefaultRgb16ViaImageIo` measures full 16-bit sRGB development through `ImageIO.read(File)`. Earlier `importDefaultRawViaImageIo` measurements used RGB8.

Use the hierarchy to localise a change. For example, a slower ImageIO import with stable direct decoder and processing-stage series points toward import/integration overhead. A slower processed-RAW total accompanied by a stable entropy decoder but slower demosaic series points toward the processing layer.

The [GitHub Pages history](https://carstenartur.github.io/Photographer/dev/bench/) is an observational time series. Hosted-runner hardware, image, kernel or JVM changes can move all series at once. Every full-suite run stores environment metadata alongside raw JMH JSON. Check that metadata when several series move at the same time.

## LibRaw comparison on 2026-11-16

Photographer at `4a816c8` and LibRaw 0.21.4 returned identical unsigned Bayer values for three 64 × 64 rectangles from the same 6888 × 4546 EOS R RAW file. The file SHA-256 was `89bd55532b0cceb5efa360e97827ba35c2076bce0de4675f3a4413a883dac880`.

Photographer used ImageIO `rawSensor()` with `setSourceRegion`. LibRaw used `libraw_open_file`, `libraw_unpack`, then copied the requested rectangle from the full Bayer buffer using the reported row pitch. Each timed call included opening and closing the file. Pixel comparisons ran outside the timed calls. Both processes used one logical CPU on an AMD EPYC 9V74, a warm file cache, six warmups and nine alternating timed pairs per rectangle. Java used OpenJDK 21.0.13.

| Sensor origin | Photographer median | LibRaw median | LibRaw / Photographer |
| --- | ---: | ---: | ---: |
| (32, 32) | 41.6 ms | 881.4 ms | 21.2× |
| (512, 128) | 34.8 ms | 633.5 ms | 18.2× |
| (6792, 4450) | 554.4 ms | 638.0 ms | 1.15× |

The rows report wall-clock time and the ratio of medians for each position. The decoder needs a longer tile-plane prefix for the rectangle near the bottom of this image. The same-day full-sensor comparison used three files, with median wall-clock time lower for LibRaw on all three. These results describe separate paths through the two APIs on the measured host and versions.
