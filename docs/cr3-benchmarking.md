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
  --jmh-arg=-f --jmh-arg=3 \
  --order head-base
```

When extra JMH arguments repeat an earlier option, JMH's last-option behavior applies. Record the exact command and both execution orders when promoting a noisy signal to an engineering conclusion.

## Longitudinal full-suite scope

Relevant pushes to `main`, the weekly schedule and manual workflow runs execute the complete suite and publish one historical series for every benchmark/parameter pair. The full suite includes:

- container parsing, metadata and preview extraction;
- complete lossless CRX decoding and RAW-linear/RAW-processed pipelines;
- CRX header, plane decoding, MED prediction, tile copying, plane interleaving and per-symbol entropy/adaptation stages;
- black-level, white-balance, demosaic, colour-matrix and sRGB stages;
- end-to-end ImageIO preview and processed-RAW imports.

Use the hierarchy to localise a change. For example, a slower ImageIO import with stable direct decoder and processing-stage series points toward import/integration overhead. A slower processed-RAW total accompanied by a stable entropy decoder but slower demosaic series points toward the processing layer.

The GitHub Pages history is an observational time series. Hosted-runner hardware, image, kernel or JVM changes can move all series at once. Every full-suite run therefore stores environment metadata alongside raw JMH JSON; broad simultaneous shifts should be checked against that metadata before attributing them to source changes.
