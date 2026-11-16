# CR3 benchmark methodology

CR3 pull-request performance checks compare the base and head revisions on the same host. Longitudinal `main` history remains useful for trends and component localisation, but it is not used as the causal pull-request baseline.

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
- end-to-end ImageIO preview, full-sensor and processed-RAW imports, plus Bayer and RGB regions.

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
