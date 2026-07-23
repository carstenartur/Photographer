# LibRaw-guided first-reference-divergence analysis

This diagnostic connects two existing clean-room research assets:

1. the unchanged test-scope lossless CRX decoder trace, which exposes payload bit positions, Rice state, residuals, predictor values and reconstructed samples; and
2. the manifest-bound RAW-linear TIFF sidecars produced by LibRaw/rawpy as a black-box reference generator.

It answers a narrower and more useful question than a completed-image MAE comparison:

> At which decoded symbol does the current research interpretation first contradict the correct sensor mosaic, and which observable decoder stage is already inconsistent at that position?

## Clean-room boundary

The test consumes only registered TIFF output and its recorded provenance. It does not inspect LibRaw, dcraw or another third-party CR3/CRX decoder implementation. Reference pixels are observations, not an algorithm specification.

For each symbol, the project independently derives the reference W, N and NW samples, the predeclared MED predictor and the expected signed residual. No production decoder code or production support claim is changed by this diagnostic.

See [`cleanroom/README.md`](cleanroom/README.md) for the complete evidence policy.

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

For every lossless tile/plane stream the report records:

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
: The decoder predictor already differs from the predictor calculated from correct reference neighbours. Later residual differences are therefore not treated as the first causal stage.

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
- a predictor-context mismatch may itself be downstream of an earlier incorrectly reconstructed neighbour;
- a production change still requires a separately pre-registered semantic candidate and the existing completion/reference gates.

The machine-readable report is intended as input for a subsequent bounded hypothesis or for an AI-assisted experiment loop. It is not an automatic decoder patch generator.

Tracking issue: [#276](https://github.com/carstenartur/Photographer/issues/276).
