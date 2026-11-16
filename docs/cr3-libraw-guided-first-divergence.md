# LibRaw-guided first-reference-divergence analysis

This records the RG1 research decoder comparison from July 2026. Later RG4 and RG5 runs measured complete native reconstruction on the supported lossless and C-RAW layouts. [RG1 outcome](cr3-results/2026-07-23-RG1-libraw-guided-first-divergence.md) · [Current CR3 support](cr3/current-state.md)

This diagnostic connects two existing clean-room research assets:

1. the unchanged test-scope lossless CRX decoder trace, which exposes payload bit positions, Rice state, residuals, predictor values and reconstructed samples; and
2. the manifest-bound RAW-linear TIFF sidecars produced by LibRaw/rawpy as a black-box reference generator.

It answers a narrower and more useful question than a completed-image MAE comparison:

> At which decoded symbol does the current research interpretation first contradict the correct sensor mosaic, and which observable decoder stage is already inconsistent at that position?

## Clean-room boundary

The test consumes only registered TIFF output and its recorded provenance. It does not inspect LibRaw, dcraw or another third-party CR3/CRX decoder implementation. Reference pixels are observations, not an algorithm specification.

For each symbol, the project independently derives the reference W, N and NW samples, the predeclared MED predictor and the expected signed residual. No production decoder code or production support claim is changed by this diagnostic.

See [`cleanroom/README.md`](cleanroom/README.md) for the complete evidence policy.

## Initialization modes

Every tile/plane stream is analysed twice.

`CONFIGURED_BASELINE`
: Preserves the historical research decoder configuration, including its configured first-pixel seed. This records whether the current candidate already disagrees at stream initialization.

`REFERENCE_FIRST_SAMPLE_ORACLE`
: Uses the first correct reference sample as the seed for that stream. This deliberately neutralizes the already-known DC/pedestal discrepancy and asks where the same unchanged bitstream, sign mapping, K adaptation and predictor next contradict the reference. It is a diagnostic isolation technique, not a proposed way to decode unknown files and not eligible for direct production promotion.

The report keeps the two modes separate and records the effective seed and the number of samples that matched before the first divergence.

## Execution

The integration test is disabled by default. It requires both the research Failsafe profile and an explicit opt-in property:

```bash
mvn -B verify \
  -Pcr3-research-it \
  -pl canon-cr3-research -am \
  -Dit.test=LibRawGuidedFirstDivergenceIT \
  -Dcr3.libraw.first-divergence.enabled=true \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -Djava.awt.headless=true
```

Normal product verification and ordinary CI do not execute it.

The test reads all `RAW_LINEAR` TIFF entries whose geometry is declared to match `CRX_BIG` in `docs/cr3-reference-manifest.md`. Before analysis it verifies every sidecar SHA-256 against the manifest.

## Output

Reports are written to:

```text
canon-cr3-research/target/libraw-guided-first-divergence/
├── results.md
├── results.tsv
└── results.json
```

Output ordering is deterministic and no runtime timestamps are included.

For every lossless tile/plane stream and initialization mode the report records:

- initialization mode and effective first-pixel seed;
- matched-prefix length before the first contradiction;
- bounded result classification;
- symbol ordinal and local plane coordinate;
- corresponding full-sensor Bayer coordinate;
- payload bit start and end;
- K before/after, quotient and remainder;
- decoded unsigned symbol and signed residual;
- decoder predictor and reconstructed value;
- reference W/N/NW values and independently calculated MED predictor;
- expected reference residual and canonical bit-0-sign unsigned symbol;
- signed reconstruction error;
- stream completion, exhaustion state and consumed bytes.

## Classification

`PREDICTOR_CONTEXT`
: At the first mismatching sample, the decoder predictor differs from the predictor calculated from correct reference neighbours. Because all earlier samples in the reported prefix matched, this points to an edge/reset, coordinate or predictor-rule discrepancy rather than accumulated image error.

`SYMBOL_OR_RESIDUAL_INTERPRETATION`
: Predictor context agrees, but the decoded unsigned symbol or signed residual does not match the reference-derived residual.

`CLAMPING_OR_RECONSTRUCTION`
: Predictor and residual agree, but reconstruction still contradicts the in-range reference value. This is a defensive classification for clamp or assembly inconsistencies.

`STREAM_EXHAUSTED`
: The candidate stream ended before a retained sample mismatch was observed.

`NO_DIVERGENCE`
: Every traced reconstructed sample in the stream matched the reference and the stream did not exhaust.

## Interpretation limits

A first mismatch narrows the failure location but does not by itself prove a replacement entropy rule. In particular:

- LibRaw does not expose its internal CRX state;
- the reference cannot directly identify Canon's `Sd` or `Kd` lifecycle;
- the oracle-seeded run intentionally uses expected output to isolate initialization and therefore cannot serve as a standalone decoder algorithm;
- a production change still requires a separately pre-registered semantic candidate and the existing completion/reference gates.

The machine-readable report is intended as input for a subsequent bounded hypothesis or for an AI-assisted experiment loop. It is not an automatic decoder patch generator.

Tracking issue: [#276](https://github.com/carstenartur/Photographer/issues/276).
