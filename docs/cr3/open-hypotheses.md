# CR3 Open Hypotheses

This table records durable status, including rejected and completed questions so they are not silently reopened.

| ID | Hypothesis / observation | Status | Evidence / next step |
|---|---|---|---|
| B1 | Predictor row context is incorrect | PARTIAL | MED remains best supported; revisit only after genuinely new entropy evidence points back to predictor context. |
| B2 | Tile predictor state should continue | REJECTED | Continuation doubles optical-black variation. |
| B3 | Plane predictor state should continue | REJECTED | Cross-plane seeding has no useful effect. |
| B4 | Predictor family differs by plane | REJECTED | No systematic plane split. |
| D5 | Replace BIT0 sign mapping with zig-zag | REJECTED | Fixed reference panel regressed. |
| D6 | Use one universal logarithmic K bracket | REJECTED | Corpus medians and completion regressed. See [D6](../cr3-results/2026-07-20-D6-k-adaptation-discriminator.md). |
| D7 | Locate the first D6 K divergence | COMPLETE | First cause is zero-symbol K state. See [D7](../cr3-results/2026-07-20-D7-first-k-divergence.md). |
| D8 | Contract K after zero symbols | PARTIAL | Helps selected files; corpus and completion gates fail. See [D8](../cr3-results/2026-07-20-D8-zero-contracted-k.md). |
| D9 | Find one common first non-zero cause | COMPLETE: NO COMMON CAUSE | Three representatives produce different decision classes. See [D9](../cr3-results/2026-07-20-D9-first-nonzero-k-divergence.md). |
| D10 | One transition class predicts failure | COMPLETE: NO PREDICTIVE CLASS | Candidate classes also occur in improved files. See [D10](../cr3-results/2026-07-20-D10-k-transition-outcome-matrix.md). |
| D11 | Primary source defines an exact base K range | COMPLETE | The range is the finite-domain minimum of the disclosed code length. CRX context remains unresolved. See [D11](../cr3-results/2026-07-20-D11-canon-k-source-audit.md). |
| D12 | The exact range alone fixes D8 | PARTIAL | Optical-black median improves, but global, boundary, saturation, family and stream gates fail. See [D12](../cr3-results/2026-07-20-D12-exact-k-bracket.md). |
| D13 | Tentative-symbol context can be mapped from the fixed lossless topology | COMPLETE: INSUFFICIENT | All 88 records are lossless single-subband records. The panel exposes no deeper `Sd`, LL/HL/LH/HH role or `Kd` lifecycle. See [D13](../cr3-results/2026-07-20-D13-context-topology.md). |
| RG1 | LibRaw reference values can locate the first incorrect sample | COMPLETE: OBSERVATION ONLY | The first reference value removes symbol-0 offset, but 71/88 streams then fail at symbol 1 with predictor agreement and an incorrectly decoded zero residual. See [RG1](../cr3-results/2026-07-23-RG1-libraw-guided-first-divergence.md). |
| RG2 | A conditional low-frequency mode bit explains the earliest lossless error | REJECTED for the tested guard, zero boundary and polarity | All 88 payloads start with ten zero bits. One additional bit left all 88 first errors unchanged. See [RG2 experiment](../cr3-results/2026-11-14-crx/README.md). |
| RG3 | First native sample is predictable from bits 18–31 at the measured prefix; next same-plane site is a signed difference | SUPPORTED within the measured layout | Two exact sites in 120/120 streams. Controlled flips and transplants confirmed the first value, while second-site remainder flips were rejected. The five literal cases come from discovery only. See [RG3](../cr3-results/2026-11-14-crx-opening/README.md). |
| D14 acquisition | Controlled spatial stimuli can add causal payload evidence | PARTIAL / OPEN | Pattern PDFs, manifest and analysis primitives exist. The [D14 capture checklist](../manual-todo.md#d14-capture-series) tracks the camera inputs needed for JPEG calibration, repeat-noise analysis and independent stimulus reproduction. |
| Opening byte origin | CMP1 declares a shorter header than Photographer’s extractor consumes | OPEN grammar, measured four-byte difference | In all 15 files the two origins number the same first-value bits 50–63 and 18–31, respectively. The significance of the intervening zeros remains open. |
| RG4 | Native reconstruction beyond the two RG3 values | SUPPORTED for complete measured lossless rasters | [RG4 full-sensor comparison](../cr3-results/2026-11-14-crx-native/README.md) |
| RG5 | Reconstruct entropy-coded and quantized three-level C-RAW bands through the native importer | SUPPORTED on six complete V1 C-RAW files | [Results and scope](../cr3-results/2026-11-14-crx-wavelet/README.md) |
| RG6 | Apply the existing outer-edge wavelet rule at both sides of the only tile | SUPPORTED on the registered EOS RP crop capture | [V1 camera and crop results](../cr3-results/2026-11-22-crx-camera-coverage/README.md) |
| RG7 | Reconstruct a single tile using the adaptive quantization map and the existing inverse 5/3 transform | SUPPORTED on 14 registered C-RAW recordings | [Adaptive C-RAW results](../cr3-results/2026-11-22-crx-adaptive/README.md) |
| Historical third-site question | The third same-plane site can be read starting at the measured RG3 end bit | COMPLETE | RG4 verifies the rest of every row and every plane. |

## Open evidence-acquisition work

The controlled capture path is open because it can create evidence not present in the static corpus:

- a known display stimulus and its exact presentation state;
- a JPEG/embedded-preview observation showing whether and where the camera detected the change;
- the corresponding CR3/CRX payload from the same exposure;
- duplicate A/B captures that establish the noise floor;
- spatial, intensity and polarity changes chosen before looking at payload outcomes;
- independent reproduction with another pattern family.

This is an observation program, not an authorized decoder formula. A later semantic experiment must cite a stable result from that corpus and still change only one declared variable.

## Camera modes to check against the format description

The [16 September 2026 description by Laurent Clévy](https://github.com/lclevy/canon_cr3/blob/151c1a965775ef409ace17714c24ed491cfcf9c4/readme.md) supplies observations for test design (SRC-016 in the [reference register](../cleanroom/source-register.json)). The latest revisions extend the camera model ID table. Qualify newer captures by their observed container layout, `CMP1` version, sensor geometry and bit depth. Record the model, firmware and shooting mode with each fixture.

| Camera mode | Photographer verification to add |
| --- | --- |
| 1.6× crop | Compare a real capture with the reported out-of-bounds `IAD1` active area through RAW rendering and DNG export. The rendering path bounds the rectangle and the DNG exporter rejects coordinates outside the sensor. Check active area, visible dimensions and CFA phase before choosing a common treatment. [Sensor geometry](format.md#sensor-geometry-and-iad1) |
| RAW burst | Resolve per-frame byte ranges and timestamps through the BMFF sample tables. Compare the observed frame count and selected frame with the roll information in the `CMT3` MakerNote, tag `0x403f`. The current importer uses the first chunk offset and first sample size for a track. [Track and sample ranges](format.md#tracks-and-sample-ranges) |
| HDR PQ | Check a CR3 with an HEVC thumbnail or preview. Identify the codec and colour metadata, then verify that the JPEG preview and Bayer RAW paths report their own results for this container. [Embedded images](format.md#embedded-preview-and-thumbnail) |
| Dual Pixel RAW | Verify the second RAW track and its association with the main image using an owned or otherwise cleared capture. The parser already identifies a Dual Pixel track from `CCDT`. The current native Bayer path selects the main RAW track. [Track roles](format.md#tracks-and-sample-ranges) |

## Blocked semantic questions

Blocked without genuinely new evidence:

- causal `Sd` / `Kd` semantics;
- multi-subband role mapping and lifecycle;
- inference of a general residual grammar and state transitions from just the two RG3 sites;
- a production entropy candidate;
- C-RAW semantic sweeps without independent lossy ground truth.

## Deferred directions

Deferred without new evidence:

- quantization-first exploration;
- PRVW geometry and CFA rediscovery;
- generic alignment sweeps;
- predictor-family and sign-mapping sweeps;
- transition-frequency searches;
- current-symbol K brackets;
- combined `Sd`/`Kd`/reset searches on the same panel;
- further passive LibRaw score fitting that does not create a new causal observation.

Status values: `OPEN`, `SUPPORTED`, `REJECTED`, `BLOCKED`, `PARTIAL`, `DEFERRED`, `COMPLETE`.

Before changing a status, follow [Research](research.md) and update [Current state](current-state.md) plus the [Results index](reports/index.md).
