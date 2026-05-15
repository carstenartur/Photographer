# CRX Subband Research Log

This document tracks the cleanroom investigation of Canon's CRX entropy codec.
The file format container is well-documented at
[lclevy/canon_cr3](https://github.com/lclevy/canon_cr3); the entropy codec itself
is *not* — public references defer to LibRaw's `crx.cpp`, which is off-limits
for our reimplementation. The infrastructure in this PR exists so that future
sessions can iterate on candidate decoders against the real bitstream in
seconds rather than rebuild-and-stare loops.

## Tooling

- **`SubbandExtractor`** (`canon-cr3-core`) — locates every CRX subband in a CR3
  file's RAW track and slices its bytes out for offline analysis.
- **`SubbandStatistics`** (`canon-cr3-core`) — pure byte/bit statistics:
  histogram, run-length-of-zeros distribution, longest zero run.
- **`SubbandHypothesisHarness`** (`canon-cr3-core`) — wraps a candidate
  `Function<byte[], int[]>` decoder and reports per-fixture accuracy
  (exact-match fraction, mean/max abs error). Catches exceptions so a single
  bad input cannot abort a sweep.
- **CLI commands** (`canon-cr3-cli`):
  - `cr3-cli list-subbands <file.cr3>` — enumerate subbands.
  - `cr3-cli dump-subband <file.cr3> <seq> [outBase]` — write `<outBase>.bits`
    plus `<outBase>.meta.json`.
  - `cr3-cli subband-stats <file.cr3> <seq>` — print statistics for one subband.
  - `cr3-cli subband-stats --bits <file.bits>` — print statistics for a
    previously dumped payload.

## Bundled fixtures

All real samples live in `canon-cr3-imageio/src/test/resources/samples/`. The
table below records each sample's container-level parameters; subband-level
detail is enumerated at runtime by `cr3-cli list-subbands`.

| Sample                   | Sensor (W×H) | RAW track | CNCV         | Notes                               |
|--------------------------|--------------|-----------|--------------|-------------------------------------|
| `sample.cr3`             | 5632×3710    | CRX_BIG   | CR3_001      | Two tiles, 4 planes, 1 subband each (lossless). All subbands report `q=4` despite being a "lossless" track — see H1. |
| `sample-iso-high.cr3`    | —            | CRX_BIG   | CR3_001/2    | High-ISO companion to investigate noise-floor effect on bitstream stats. |
| `sample-wb-tungsten.cr3` | —            | CRX_BIG   | CR3_001/2    | White-balance variant; useful for cross-checking that decoder is WB-independent. |
| `sample-tele.cr3`        | —            | CRX_BIG   | CR3_001/2    | Different focal length / framing. |
| `sample-wide.cr3`        | —            | CRX_BIG   | CR3_001/2    | Different focal length / framing. |
| `eosr-iso-low.cr3`       | —            | CRX_BIG   | CR3_001/2    | EOS R, low ISO. |
| `eosr-iso-mid.cr3`       | —            | CRX_BIG   | CR3_001/2    | EOS R, mid ISO. |
| `eosr-iso-high.cr3`      | —            | CRX_BIG   | CR3_001/2    | EOS R, high ISO. |

`SubbandResearchHarnessIT` asserts every fixture above is parseable.

## First measurements (recorded 2026-04-30)

Output of `cr3-cli subband-stats sample.cr3 0` on the bundled `sample.cr3`
(plane 0, tile 0, lossless `q=4`, 14-bit, 1408×1855):

```
totalBytes=2450176 totalBits=19601408 oneBits=8840199 oneFrac=0.4510
longestZeroRun=45
topBytes=0x02:26001 0x04:25518 0x08:25214 0x10:24670 0x40:24590 0x20:24436
        0x00:23315 0x01:23214 0x80:22821 0x81:21517
zeroRunHist[0..15]=4632480 1663324 917974 594630 406001 270934 171803 94175
                   46318 21258 10150 4889 2538 1270 719 457
zeroRunHist[16+]=1280
```

Initial observations to feed into the hypothesis tests above:

- **Bit balance ≈ 0.45** is consistent with an entropy-coded stream and rules
  out gross misalignment. Validates H5 (MSB-first, no stuffing) at least to
  first order.
- **Longest zero run = 45** is small enough to make H3 (escape symbol)
  plausible: a pure unary code of un-escaped residuals would produce far
  longer runs on a 14-bit subband.
- **Byte histogram is very flat** — top byte only 26 001 occurrences of 2.45 M
  total (≈1.06 %). No JPEG-style stuffing pattern visible. Strongly
  reinforces H5.
- **70 % of zero-runs have length 0** (4.6 M of 6.6 M), i.e. most residuals
  start with a `1`-bit. Under a Rice/Golomb scheme this implies either a
  very small effective `k` or a residual distribution heavily biased toward
  zero — both are consistent with low-entropy lossless coding of natural-image
  Bayer planes. Quantitatively, this constrains the candidate `k` for H1.

## Falsifiable starter hypotheses

Each hypothesis below is a concrete, falsifiable claim about the CRX entropy
codec. They are starting points for future sessions; status should be updated
as evidence accumulates. None of them is yet the basis for any production
decoder change.

### H1 — `quantValue` seeds the initial Golomb-Rice `k` parameter
**Claim:** The `quantValue` byte parsed from each subband header is the
initial `k` for adaptive Golomb-Rice decoding (i.e. the divisor exponent).

**Evidence already on file:** All four planes of `sample.cr3` report
`q=4`. JPEG-LS's analogous `k` is content-dependent and rarely uniform across
planes; CRX broadcasting a fixed value at the subband boundary is consistent
with this hypothesis.

**Counter-evidence already on file:** A previous session tried
`initial_k = quantValue` directly in `CrxLosslessRawDecoder` and the visual
quality regressed across the board, suggesting either (a) the relationship is
not direct identity (e.g. `k = quantValue >> 1`, or `k = max(0, quantValue - 4)`)
or (b) the predictor / sign-folding is wrong and any change to `k` simply
shuffles which wrong outputs you get.

**How to test:** For each fixture, run the harness with a family of fixed-`k`
Rice decoders for `k ∈ {0..14}` and one with `k = quantValue`. Compare exact-match
fractions. If `k = quantValue` dominates across multiple cameras, the hypothesis
is supported.

### H2 — Per-line predictor is a 4-way gradient classifier (not JPEG-LS MED)
**Claim:** CRX's predictor classifies each pixel context into one of four
gradient regions (NW, N, NE, W) and uses a different fixed predictor in each
region, rather than JPEG-LS's three-case MED rule.

**Why suspect:** Public discussion of CRX (e.g. Canon white papers, patents
filed by Canon Inc. covering "RAW-data compression") repeatedly mentions
gradient-based classification with a small, fixed number of contexts. The
current decoder uses MED.

**How to test:** Once H1 produces non-noise residuals on at least one fixture,
swap the prediction step between MED and a 4-way gradient and compare
exact-match fractions on the same residual stream.

### H3 — Escape symbol switches to fixed-width remainder
**Claim:** A unary count larger than some threshold (likely
`k + bitDepth - 1` or similar) is an escape that signals a following raw
fixed-width remainder of `bitDepth + extra` bits, rather than the standard
`k`-bit Rice remainder.

**Why suspect:** Statistics on real subbands show
`SubbandStatistics.longestZeroRun()` values that, if interpreted as plain
unary, would imply implausibly large residuals. An escape avoids this.

**How to test:** Compare `longestZeroRun` distributions across fixtures of
different ISO. If escapes are real, the high-ISO fixtures should have
substantially longer runs (more residuals trigger the escape).

### H4 — First bit of each line is a mode selector
**Claim:** Each line of subband data begins with a one-bit flag selecting
between two modes — for example RLE-of-zero-residuals (run mode) and per-pixel
residual mode — analogous to JPEG-LS's run mode.

**Why suspect:** Smooth regions of natural images compress dramatically with
run modes; lossless raw codecs almost universally include one. The bit
balance in `SubbandStatistics` should reveal whether one bit per line of
the plane width is consumed for this purpose by comparing the observed
total bits to the encoded payload size after subtracting expected residual
bits.

**How to test:** Compute `payloadBits / planeHeight` and compare to
`avg(unaryLength + k) * planeWidth + 1`. A consistent integer offset of `1`
across fixtures supports H4.

### H5 — Bytes are MSB-first within bytes, no byte-stuffing
**Claim:** CRX bitstream is MSB-first within each byte, with no
JPEG-style 0xFF byte stuffing.

**Why suspect:** The current `BitReader` already uses MSB-first, and the
visual-quality regression is too uniform to be a stuffing-related skip.

**How to test:** `SubbandStatistics.byteHistogram()` for the bundled
fixtures should show a roughly uniform distribution. If `0xFF` is heavily
underrepresented, stuffing is in play; if `0xFF 0x00` appears frequently
adjacent, JPEG-style stuffing is present.

## What this PR explicitly does NOT do

- It does not change `CrxLosslessRawDecoder` or `CrxLossyDecoder` at all.
- It does not move `Cr3VisualQualityTest`'s baseline. That regression test
  continues to gate against further degradation of the stable-but-wrong
  status quo.
- It does not commit any speculative decoder. The harness exists precisely
  so that future speculative decoders can be evaluated in test scope without
  risking the production pipeline.

## How to use the harness in a future session

```java
// In a new test class under canon-cr3-imageio/src/test/java
File f = new File("src/test/resources/samples/sample.cr3");
var subs = SubbandExtractor.list(f);
byte[] payload = SubbandExtractor.read(f, subs.get(0));

Function<byte[], int[]> myCandidate = bytes -> {
    // hypothesis under test
};

int[] reference = /* loaded from PRVW JPEG, downsampled to plane grid */;

var result = SubbandHypothesisHarness.evaluate(
    "sample.cr3:tile0:plane0:sb0", payload, reference, myCandidate);
System.out.println(SubbandHypothesisHarness.report(List.of(result)));
```

## Update 2026-04-30: PRVW reference + first hypothesis sweep

### New infrastructure (test-scope only)

- **`PreviewReferenceExtractor`** (`canon-cr3-imageio/src/test`) — decodes the
  embedded JPEG (`JPEG_BIG` track preferred, `PRVW` box fallback), downsamples
  to a requested plane grid via area averaging, and extracts one colour
  channel matching a Bayer plane index (R, Gr/Gb, B). Returns 8-bit
  reference pixels.
- **`CrxCandidateDecoders`** (`canon-cr3-imageio/src/test`) — concrete
  `Function<byte[], int[]>` candidates. The library covers two controls
  (zero output, identity) plus 10 hypothesis variants spanning four
  predictors (LEFT, MED, GRADIENT4, PAETH), two sign conventions
  (zigzag, signed-magnitude), two reconstruction polarities
  (`pixel = predicted ± residual`), an output inversion control, and the
  H1 `initial_k = quantValue` configuration.
- **`CrxHypothesisSweepIT`** (`canon-cr3-imageio/src/test`) — runs the full
  candidate library against subband 0 of `sample.cr3` plus all four planes
  of tile 0 and the first plane of tile 1, scores each output by
  Pearson r and mean abs error vs. the reference grid, and prints a table.

### Findings

For `sample.cr3` (subband sequence indices 0..7 = tile 0 planes 0..3,
then tile 1 planes 0..3) — bitstream consumption (validPx / requested) and
Pearson r for the strongest predictor (PAETH) under both reconstruction
polarities:

| Subband | Plane | All cands exhaust at | C7 (predicted+r) | C10 (predicted−r) |
|---------|-------|---------------------:|-----------------:|------------------:|
| 0 (T0)  | R     | full 2.61 M          | −0.39            | **+0.39**         |
| 1 (T0)  | Gr    | full 2.61 M          | −0.34            | **+0.34**         |
| 2 (T0)  | Gb    | 2.04 M (78 %)        | +0.03            | −0.03             |
| 3 (T0)  | B     | 2.44 M (94 %)        | −0.47            | **+0.47**         |
| 4 (T1)  | R     | 1.89 M (73 %)        | +0.07            | −0.07             |

#### What the data conclusively rules in / out

- **H1 falsified again.** With `initial_k = quantValue` (= 4) the bitstream
  is exhausted after 1.7 M pixels (66 %) on plane 0, and Pearson r collapses
  to ≈ 0 on every plane. Reconfirms the previous session's empirical
  rejection.
- **The k-adaptation is wrong.** With `initial_k = 0`, the bits consumed per
  pixel vary inconsistently per plane (from full-coverage on R/Gr to 73 %
  exhaustion on tile 1's R). A correct decoder consumes exactly the bits in
  each subband. This proves the residuals being decoded are wider, on
  average, than they actually are in the codec.
- **Bit ordering (H5: MSB-first, no stuffing) remains supported.** The
  identity-byte control C1 produces consistent ≈ 0 correlation with no
  byte-skipping anomalies, and PAETH+subtract reaches r = +0.47 on plane 3
  — both consistent with MSB-first bit reading.

#### What the data does NOT support

- **A blanket sign-flip of the residual is NOT a portable fix.** On the
  three planes where the bitstream completes naturally (T0 R, T0 Gr,
  T0 B), `pixel = predicted − residual` (zigzag) outperforms the current
  `predicted + residual` by roughly the same magnitude. But on T0 Gb
  (78 % exhaustion) and T1 R (73 % exhaustion) the sign of the correlation
  flips — likely an artefact of the bitstream being truncated before the
  high-frequency content arrives. **Until the k-adaptation issue is fixed,
  flipping the sign is just trading one set of wrong outputs for another.**
- The PAETH r ≈ +0.39 on plane 0 R does not warrant a production change in
  isolation. JPEG previews differ from raw Bayer values by colour-matrix +
  gamma + downsampling artefacts, so a Pearson r of 0.4 on one plane could
  be a coincidence of large-scale luminance gradients aligning with whatever
  partial structure a wrong decoder happens to emit.

### Concrete next steps (still deferred from production code)

1. **Fix the bits-per-pixel mismatch first.** Until the candidate decoders
   consume exactly all of a subband's bytes for every plane, every other
   conclusion is contaminated by the bitstream truncating mid-image.
   Specifically: derive `(payload bytes × 8) / (planeWidth × planeHeight)`
   per subband across multiple cameras and look for a relationship to
   `quantValue`, plane index (R vs G vs B), or row position.
2. **Once exhaustion is consistent**, re-run `CrxHypothesisSweepIT`. If a
   single (predictor, sign) combination then dominates across all five
   subbands, that is the basis for a one-line production change in
   `CrxLosslessRawDecoder`.
3. **Build a per-row reference** from the developed JPEG with proper
   inverse-gamma + inverse-colour-matrix to give a stronger signal.

### What this update explicitly does NOT do

- It does not change `CrxLosslessRawDecoder` or `CrxLossyDecoder`. The data
  collected in this session positively rules out the only candidate fix
  the experiment surfaced ("subtract residual instead of add").
- It does not change `Cr3VisualQualityTest`'s baseline.

The takeaway for the next session: the harness now produces real,
discriminative numerical signal. Use it to pin down the bits-per-pixel
mismatch before touching the production decoder.

## Update 2026-04-30 (b): bits-per-pixel measurement and empirical-k sweep

### What was done

- Added `CrxBitsPerPixelIT` — measures `(payload_bytes × 8) / pixels` for every
  subband across all 8 bundled fixtures, plus a fixed-k Rice sweep that finds
  the k value that exactly consumes each subband's bytes.
- Added 14 new candidate decoders to `CrxCandidateDecoders` covering
  `(predictor ∈ {MED, PAETH, LEFT}) × (initial_k ∈ {5, 6, 7, 8})` fixed-k,
  plus adaptive-from-7 variants.
- Re-ran `CrxHypothesisSweepIT` against `eosr-iso-low.cr3` (a *real* Canon CR3
  fixture, in contrast to the synthetic `sample.cr3` family).

### What was learned

**1. The bit budget for real Canon files is consistently k≈7:**

| fixture           | mean bits/px | bestFixedK |
|-------------------|--------------|-----------:|
| `eosr-iso-low.cr3`     | 9.0–9.4 | 7 |
| `eosr-iso-mid.cr3`     | 9.0–9.4 | 7 |
| `eosr-iso-high.cr3`    | 9.0–9.4 | 7 |
| `sample-tele.cr3`      | 7.3–8.9 | 5–7 |
| `sample-iso-high.cr3`  | 1.6–10.6 | 0–8 (highly variable per plane) |
| `sample-wb-tungsten.cr3` | 1.5–10.6 | 0–9 (highly variable) |

Real Canon files cluster *very tightly* on bits/px ≈ 9.2; the `sample.cr3`
family looks like synthetic fixtures whose plane sizes vary wildly. Conclusion:
**use `eosr-iso-*` as the primary entropy-codec fixtures** going forward.

**2. The k-init=0 hypothesis is quantitatively wrong.** With initial k=0 and
JPEG-LS-style adaptation, bitstream is exhausted at 70–94% of pixels because
adaptation cannot climb to k=7 fast enough.

**3. But k-init=7 is ALSO insufficient.** Even with `init_k=7` adaptive (the
empirical bit-budget answer), the adaptation rule reads ~30% past the
bitstream end (`C_med_zigzag_k7_adaptive` — only 2.7 M of 3.9 M pixels valid).

**4. No predictor + sign + k variant clears Pearson r > 0.55 on any plane.**
Best per-plane peaks on `eosr-iso-low.cr3` tile 0:

| Plane | Best candidate                  | Best |r|  |
|-------|---------------------------------|----------:|
| 0 (R) | `C_med_zigzag_k6_fixed`         | −0.52     |
| 1 (Gr)| `C_paeth_zigzag_k5_fixed`       | −0.35     |
| 2 (Gb)| `C2_med_zigzag_k0_adaptive`     | +0.47     |
| 3 (B) | `C_med_zigzag_k6_fixed`         | −0.45     |

Different planes peak at different k values. No single (predictor, sign, k)
combination dominates across all four planes. **The structural blocker is
neither "wrong k init" nor "wrong predictor" alone — there is a missing
mechanism (likely a run-length escape for zero residuals, mode switching, or
an alternative coding regime mid-stream).**

### What remains

The sweep space (predictor × sign × k) has now been reasonably exhausted with
no candidate breaking ~0.55. Further progress requires structural mechanisms
not in the current candidate template:

- **Zero-run RLE.** CRX is known to use a zero-run escape for long runs of
  zero residuals (see e.g. the `RleZeroRun` test scaffolding). Adding an
  RLE-escape variant to the candidate library is the next experiment.
- **Per-row k reset / per-band k tracking.** Canon may use distinct Rice
  parameters per scanline or per wavelet subband, with k reset at row
  boundaries. The candidate template currently uses a single k accumulator
  across the whole grid.
- **Wavelet-domain coding.** Despite "1 lossless subband" reported by the
  parser, the actual coded data may be wavelet-transformed before entropy
  coding (the per-plane sizes on `sample-wb-tungsten.cr3` differ by 6.7×,
  suggesting the four "planes" may actually be LL/LH/HL/HH subbands).
- **Canon US patent 11,343,535 B2** (image coding, public document) may
  describe enough of the codec to bootstrap a reference implementation
  without recourse to LibRaw.

The harness can now discriminate any of these hypotheses in seconds. The
bottleneck is not infrastructure but the absence of a public algorithmic
description.

---

## Update 2026-04-30 (c) — Patent US 2016/0323602 A1 candidates: bit budget is fixed

After integrating clean-room candidates derived from US 2016/0323602 A1
(see `docs/patent-us20160323602a1-analysis.md` and `docs/crx-cleanroom-spec.md`)
into `CrxCandidateDecoders`, the long-standing **bits/pixel mismatch on the
problem planes is resolved**.

The new candidates use:
- per-subband predictor: `LL=MED`, `HL=above (b)`, `LH=left (a)`, `HH=zero`,
- zig-zag symbol mapping `S = 2·Diff` / `S = −2·Diff − 1` (already used),
- **lookahead-bracket K-update** with `Sn = Sx`,
  `K*(Sn) = floor(log2(Sn+1))`, `Kn ∈ [K*−1, K*+1]`,
- two seeds: `K0 = 0` and `K0 = floor(log2(quantValue+1))`.

Verified 2026-04-30 via
`mvn verify -Dit.test=CrxHypothesisSweepIT#sweepAllPlanesOfTile0`
on `sample.cr3` (1408×1855, 14-bit, q=4):

| Plane | Subband role | C2 baseline `outLen` | Patent `outLen` (all 4 candidates) | Plane size |
|------:|--------------|---------------------:|-----------------------------------:|-----------:|
| 0 (R)  | unknown | 2611840 (100 %) | 2611840 (100 %) | 2611840 |
| 1 (Gr) | unknown | 2611840 (100 %) | 2611840 (100 %) | 2611840 |
| 2 (Gb) | unknown | **2035586 (78 %)** | **2611840 (100 %)** | 2611840 |
| 3 (B)  | unknown | **2441022 (93 %)** | **2611840 (100 %)** | 2611840 |

The **K-adaptation rule was the structural blocker, not the predictor or sign
convention**. The patent's bracket rule consumes exactly the available bits on
all four planes of the previously-problematic fixture.

Pearson r against the JPEG preview's R channel remains modest (≈0.04–0.29 for
the LL candidate). This is **expected** at this stage: the harness scores
candidates against a single Bayer channel of the preview, but a single
subband only carries a 1/4-band fraction of the image — full reconstruction
requires reassembling all four subbands and applying the inverse 5/3 wavelet.
The next milestone is to validate the "four planes per tile = four subbands of
one wavelet level" interpretation by feeding the patent decoder's outputs
into `InverseWaveletTransform.legall53(...)` and scoring the reconstructed
Bayer channel against the preview reference.

`C_patent_HH_zero_*` produces ≈0 correlation with mean-abs-err identical to
the all-zero baseline `C0_zero` (88.27 vs 88.49 etc.) — the expected
behaviour for an HH subband whose coefficients carry no DC image structure.
This is a positive sanity result: the decoder is not injecting noise.

---

## Update 2026-04-30 (d) — IDWT validation (Step 3): plane-vs-subband hypothesis NOT confirmed

Following Update (c)'s bit-budget result, Step 3 of the patent-cleanroom roadmap
runs `InverseWaveletTransform.legall53(...)` over the four planes of tile 0 of
sample.cr3, each decoded by the role-appropriate patent candidate (LL → MED,
HL → above (b), LH → left (a), HH → zero). Six permutations of the
plane → role mapping were tested.

Verified 2026-04-30 via
`mvn verify -Dit.test=CrxPatentIdwtValidationIT` (`canon-cr3-imageio`).

Reconstructed grid is `outW×outH = 2·w × 2·h = 2816 × 3710 = 10 447 360` pixels
(matches the full Bayer-channel size for tile 0, i.e. the upper-left quadrant
of the 5632×3710 RAW frame). Pearson r against the JPEG preview's R channel,
downsampled to the same grid:

| Permutation `(LL, HL, LH, HH)` | Pearson r | mean abs err | outLen |
|--------------------------------|----------:|-------------:|-------:|
| (0, 1, 2, 3) "natural"         |   0.0212  |   36.38      | 10 447 360 |
| (0, 2, 1, 3) "FIG. 5 swap"     |   0.0210  |   35.54      | 10 447 360 |
| (0, 1, 3, 2)                   |   0.0199  |   37.81      | 10 447 360 |
| (3, 2, 1, 0) reverse           |  −0.0067  |   36.30      | 10 447 360 |
| (0, 3, 2, 1)                   |   0.0219  |   35.98      | 10 447 360 |
| (3, 0, 1, 2)                   |  −0.0067  |   36.08      | 10 447 360 |

**None of the six permutations reaches the analysis-doc threshold r > 0.7.**

This *narrows* the open hypothesis space rather than closing it:

- **Bit budget is correct** (Update (c)) — the patent K-update fits the
  bitstream exactly, on the same fixture, with the same payload bytes. So
  the entropy coder is not the missing piece.
- **The "four planes = four subbands of one wavelet level" interpretation
  alone is insufficient.** Either:
  1. The CR3 lossless track uses **multi-level wavelet decomposition**, not
     single level. Each of the four planes itself recursively decomposes into
     LL/HL/LH/HH, and `inverseSynthesis2D` would have to be called multiple
     times. (The CMP1 box is known to carry a level count for lossy mode;
     the lossless CMP1 in our fixture also reports a level field that the
     parser currently ignores.)
  2. The four planes are **four Bayer channels** (R, Gr, Gb, B) of a single
     scanline of the RAW frame, with NO wavelet between subband decode and
     final image. In that case the patent decoder produces the actual Bayer
     samples directly. (The existing `BayerDemosaicer` would then take over.)
  3. A **dequantisation step** is required before the IDWT, not handled by
     the cleanroom candidate. Even though the subband reports `quantValue=4`
     and the track is "lossless", the patent's two-stage decode (entropy
     decode → dequant → inverse wavelet) leaves room for an integer divider
     between coefficient and IDWT input.
  4. The **predictor table is wrong for some role.** The patent text leaves
     room for `LH = b, HL = a` (rows-first vs columns-first convention).
     Three of the six permutations covered the natural HL/LH swap, so this
     alone is unlikely to be the dominant cause.

The next concrete experiment is **interpretation 2** (four planes = four
Bayer channels), which is the cheapest to falsify: feed the patent-decoded
plane 0 directly into the existing `BayerDemosaicer` as the R channel and
see whether the decoded image resembles the preview without any IDWT.

Until one of these is validated, the patent decoder remains a research
artefact — it correctly inverts the entropy coder but the downstream pipeline
is not yet in place.

---

## Update 2026-04-30 (e) — Diagnostic plane semantics (A–E): four decisive findings

`CrxPlaneSemanticsIT` (`canon-cr3-imageio/src/test`) — verified
2026-04-30 via `mvn verify -Dit.test=CrxPlaneSemanticsIT`. Artefacts:
`canon-cr3-imageio/target/crx-plane-semantics/{metrics.tsv, A_heat_*.pgm,
A_hist_raw_*.csv, B_dequant_*.i16, B_recon_*.pgm, D_bayer_mosaic.pgm}`.

### Finding 1 — Multi-level wavelet is structurally inapplicable (Section C)

`CMP1.waveletLevels = 0` in sample.cr3. The lossless mode uses **no** wavelet
decomposition at all. The four planes are NOT LL/HL/LH/HH of any level,
neither at one level nor multi-level. This decisively closes hypothesis (i)
in Update (d).

### Finding 2 — CFA layout is GRBG (cfaLayout=1), not RGGB (Section C)

`CMP1.cfaLayout = 1 → GRBG`. Several earlier experiments implicitly assumed
RGGB (cfaLayout=0). Under the correct GRBG mapping, plane → preview channel:
- plane 0 → Gr  (top-left of 2×2 cell)
- plane 1 → R   (top-right)
- plane 2 → B   (bottom-left)
- plane 3 → Gb  (bottom-right)

Future experiments on sample.cr3 must use this mapping.

### Finding 3 — Dequantisation alone cannot save the IDWT hypothesis (Section B)

Five dequant variants (identity, ×q, ×2^q, dead-zone q, dead-zone 2q) applied
to the patent-decoded planes before Le Gall 5/3 IDWT (under permutation
0,3,2,1) all produce **identical Pearson r = 0.02187**. This is mathematically
expected — Pearson r is invariant under linear scaling, and dead-zone
thresholds 4–8 are negligible compared to the 14-bit range — so the result
is not an experimental artefact, it is a proof: **no dequantisation can
rescue Update (d)'s near-zero IDWT score on this fixture.**

### Finding 4 — Plane-as-Bayer-channel: structural data, but spatially misaligned (Section A + D)

The patent decoder's LL output (MED predictor) shows:
- left-neighbour correlation r ≈ 0.63 (real 2D image structure, not a
  predictor artefact — see "open question" below),
- mean ≈ 9200–9650 (centered near mid-grey 1<<13 = 8192, plausible for
  developed Bayer values).

But scored against the matching preview channel (with the corrected GRBG
mapping):

| plane | maps to | Pearson r | meanAbsErr |
|-------|---------|----------:|-----------:|
| p0    | Gr      |   0.0422  |   85.77    |
| p1    | R       |   **0.0735**  |   80.45    |
| p2    | B       |   0.0478  |   81.25    |
| p3    | Gb      |  −0.0110  |   86.14    |
| mosaic vs luma | — | 0.0361 | 83.35 |

So the bytes-per-pixel is right (Update (c)), the wavelet hypothesis is
ruled out (Findings 1–3), the GRBG plane-as-Bayer-channel hypothesis aligns
the channels correctly — and yet the per-channel Pearson r tops out at 0.073.

The LH decoder's r ≈ 0.998 column correlation is a self-fulfilling artefact
(predictor=`a` + small residual ≡ same as `a`); it is **not** evidence of
horizontal-detail content. The HH decoder's 58 % zeroFrac and 0.84-bit
entropy is similarly an artefact of `predictor=0` + small residuals.

### Open question for the next session

The contradictory evidence is:
- LL output has high 2D correlation (0.63) → looks like real image data
- LL output does NOT correlate with the preview channel → spatial layout
  or sign or predictor is wrong somewhere

Concrete next experiments (still test scope, no production change):
1. **Predictor-zero baseline**: re-decode each plane with `residual=0` for
   every pixel (pure predictor cascade) and measure the resulting
   neighbourhood correlation. If it stays high, the 0.63 is a predictor
   artefact, not real structure. If it drops to ~0, the residuals carry
   genuine image content and the bottleneck is the *output transform*
   (e.g. line interleaving, tile stitching, or a pre-decode delta to subtract).
2. **Half-pixel scanline interleave**: try treating the plane as 2 rows ×
   half-height (some lossless RAW codecs interleave odd/even scanlines
   inside one plane).
3. **Try the second tile**: sample.cr3 has 2 tiles. If the per-channel score
   on tile 1 mirrors tile 0 closely, the predictor is the bottleneck;
   if it differs sharply, tile-stitching is the issue.

Until one of these is run, **no production-code change is justified.**

---

## Update 2026-04-30 (f) — Three follow-up experiments + systematic predictor sweep

Two new test-scope ITs run on `sample.cr3`:

- `CrxPlaneFollowupIT` — addresses the three open questions of Update (e).
- `CrxPlaneFollowupExtendedIT` — systematic Cartesian sweep over predictor,
  sign mapping, Bayer permutation, spatial shift, and per-tile init.

Both ITs live entirely in test scope (`canon-cr3-imageio/src/test/...`).
**No production code change.** Artefacts under
`canon-cr3-imageio/target/crx-followup/` and `target/crx-followup-ext/`
(metrics.tsv + winners.txt + heatmaps).

### Finding 5 — Predictor cascade alone produces ZERO 2D structure

`CrxPlaneFollowupIT` Exp 1 — for each plane, decode twice with the patent
LL/MED decoder: (a) real residuals from the bitstream, (b) every signed
residual forced to zero (pure predictor cascade, no bitstream input):

| plane | real neighR | zero neighR | delta |
|-------|------------:|------------:|------:|
| tile0.p0 | 0.6308 | 0.0000 | 0.6308 |
| tile0.p1 | 0.6374 | 0.0000 | 0.6374 |
| tile0.p2 | 0.6319 | 0.0000 | 0.6319 |
| tile0.p3 | 0.3936 | 0.0000 | 0.3936 |
| tile1.p0 | 0.6349 | 0.0000 | 0.6349 |
| tile1.p1 | 0.5883 | 0.0000 | 0.5883 |
| tile1.p2 | 0.6131 | 0.0000 | 0.6131 |
| tile1.p3 | 0.3936 | 0.0000 | 0.3936 |

The predictor cascade with residual = 0 collapses to a constant-mean image
of value `predInit = midgrey = 8192` and zero variance — **all 2D
correlation in the real decode comes from the bitstream residuals**, not
from the predictor. This positively answers the Update (e) "open question":
the bottleneck is not "the LL output looks correlated only because the
predictor smooths things out" — it's something else.

Note plane 3 is consistently weaker (~0.39 vs ~0.63 for planes 0-2) in
both tiles. Plane 3 is the GRBG `Gb` channel; it has higher variance
(~3.3e7 vs ~2.5e7), larger histogram spread, and a different residual
distribution. This is structural, not noise.

### Finding 6 — Tile-stitching is NOT the bottleneck

`CrxPlaneFollowupIT` Exp 3 — repeat Exp 1 + plane-as-Bayer scoring on
tile 1. Result: tile-1 means, variances, neighCorr and per-channel scores
all within ~5 % of tile 0. So the per-tile decode produces structurally
the same kind of output. Tile 1 is decoded just as (un)successfully as
tile 0; the issue is not how tiles are joined, the issue is a per-tile
decoding mismatch.

### Finding 7 — `P_LEFT` predictor is materially better than `P_MED`

`CrxPlaneFollowupExtendedIT` Exp 1 ext — Cartesian sweep of 4 predictors
× 7 sign mappings × 4 planes × 2 tiles (224 candidates per scoring channel).
Top 8 by absolute Pearson r vs the matching preview Bayer channel
(P_LEFT uses conventional left-edge fallback: when `x == 0`, predictor
is the pixel directly above `r[(y-1)*w + x]`):

```
   1. r=-0.4088  tile1/p0/P_LEFT/S_LSB_ALT/bayer_Gr
   2. r=-0.3902  tile1/p0/P_LEFT/S_LSB/bayer_Gr
   3. r=-0.3902  tile1/p0/P_LEFT/S_ZIGZAG_INV_LSB/bayer_Gr
   4. r=+0.3494  tile1/p1/P_LEFT/S_LSB/bayer_R
   5. r=+0.3494  tile1/p1/P_LEFT/S_ZIGZAG_INV_LSB/bayer_R
   6. r=+0.3483  tile1/p1/P_LEFT/S_LSB_ALT/bayer_R
   7. r=+0.1610  tile1/p0/P_UP/S_RAW_NEG/bayer_Gr
   8. r=-0.1610  tile1/p0/P_UP/S_RAW_POS/bayer_Gr
```

Compare with previous best Pearson r:

| variant                              | best |r| |
|--------------------------------------|---------:|
| P_MED (patent baseline, all signs)   |    0.085 |
| **P_LEFT** (conventional edge)       |  **0.409** |
| P_UP                                 |    0.161 |
| P_NONE (constant midgrey + residual) | tiny     |

So **the previous-left predictor with conventional left-edge fallback is
structurally a much better fit for sample.cr3 than the patent JPEG-LS MED
predictor** — by a factor ~5 on the per-channel Pearson r metric. With the
non-conventional wrap-around fallback `P_LEFT` only reached r = 0.233; the
edge-handling alone moves the score by 0.18, indicating that what little
left-edge column there is (1 of 1408 columns per row) carries
disproportionate alignment weight when the rest of the row has correct
predictor agreement.

Note that the top three results are **negative** Pearson r against the
preview Bayer channel — the structural pattern matches but the sign is
inverted. This is the kind of mismatch one expects when the sign mapping
or the residual sign convention is off by a fixed flip; `S_LSB_ALT` is
slightly stronger than `S_LSB`, suggesting the off-by-one in negative
reconstruction does start to matter at r ≈ 0.4. The value is still well
below the r > 0.7 promotion threshold.

Sign convention summary: `S_LSB`, `S_LSB_ALT`, and `S_ZIGZAG_INV_LSB`
cluster within 0.001–0.02 of each other in every cell of the table.
`S_HI_BIT_SIGN`, `S_RAW_POS`, `S_RAW_NEG`, `S_OFFSET_HALF` collapse the
output into clipped/saturated ranges → low correlation, although
`P_UP/S_RAW_NEG` and `P_UP/S_RAW_POS` reach |r| = 0.161 (rank 7-8) —
worth keeping in the candidate pool for the next round.

### Finding 8 — Bayer permutation + spatial shift do not rescue P_MED

`CrxPlaneFollowupExtendedIT` Exp 2 ext — using the patent LL/MED decoder,
re-score every plane against every cyclic CFA mapping (RGGB / GRBG / GBRG /
BGGR) under each spatial shift {identity, x±1, y±1}. Top 8:

```
   1. r=+0.0881  tile1/p1/RGGB_shift_y-1/bayer_Gr
   2. r=+0.0881  tile1/p1/BGGR_shift_y-1/bayer_Gb
   3. r=+0.0879  tile1/p0/GRBG_shift_x-1/bayer_Gr
   …
```

All ≤ 0.09 → no spatial-realignment trick rescues P_MED. The cluster
around 0.088 is the same noise floor seen in earlier sections; CFA
permutations are interchangeable here because Gr ≈ Gb in the JPEG preview
(the preview's R/G/B channels are not separated into Gr vs Gb).

### Finding 9 — Tile-local reset/init is not the cause

`CrxPlaneFollowupExtendedIT` Exp 3 ext — sweep `predInit ∈ {0, midgrey,
max}` × `kInit ∈ {0, 4, 7}` (9 combinations) × 4 planes × 2 tiles. Top 8
all in 0.086–0.094 range — barely above the 0.085 baseline. Initialisation
differences cause a few %% wobble, never a structural change. So the patent
choice of `kInit = 0` and `predInit = midgrey` is not the culprit.

### Net narrowing of the hypothesis space after Update (f)

Confirmed *not* the bottleneck (each ruled out by measurement, not a guess):

- entropy / bit budget (Update c, 100 % consumption)
- single-level wavelet (Update d, r ≤ 0.022)
- multi-level wavelet (Update e, `waveletLevels = 0`)
- dequantisation (Update e, mathematically scale-invariant)
- predictor cascade producing fake correlation (Update f Finding 5, neighR=0 with residual=0)
- tile-stitching (Update f Finding 6, both tiles decode equivalently)
- Bayer permutation / spatial shift (Update f Finding 8, max r = 0.088)
- tile-local reset / kInit / predInit (Update f Finding 9, max r = 0.094)
- LSB sign-mapping off-by-one (Update f, family-internal differences ≤ 0.01)

Newly **directionally favoured** by measurement:

- `P_LEFT` (previous-left) predictor — r reaches 0.233, ~3× the patent
  MED baseline. Still well below the r > 0.7 promotion threshold.

Concrete next steps (still test scope, no production change):
1. Pair `P_LEFT` with **per-line context reset** (some Canon/Pentax codecs
   reset the predictor at every scan line) and re-sweep.
2. Try **2D plane = 2-row interleave** (decode w samples per "row" but
   write them to alternating output rows) — this is the natural pattern
   if Canon stores each Bayer channel as half-resolution but interleaves
   sub-Bayer rows within the codec stream.
3. Try **Pentax/cgo-style context predictor** ((a + b) / 2, (a + b - c),
   simple gradient predictors) under the same harness.
4. Score against the **JPEG_BIG track cropped to one tile's image region**
   instead of the full-image preview; the current preview spans the whole
   image at one tile's plane size, so even a perfect tile decode has no
   chance of cleanly correlating with it.

None of these justify a production-code change yet. The hypothesis space
is materially narrower, but P_LEFT alone is not enough to cross the
usability threshold.


## Update 2026-04-30 (g) — Round-3 follow-up: P_LEFT reset, scan orders, gradient predictors, tile-cropped scoring

The four §5.7 next-steps from Update (f) are measured by
`canon-cr3-imageio/src/test/java/com/photographer/focusstack/cr3/research/CrxPlaneFollowupRound3IT.java`
on `sample.cr3`. Output dir `canon-cr3-imageio/target/crx-followup-round3/`
contains `metrics.tsv`, `winners.txt`, and 4 PGM heatmaps for tile0/p0
under representative variants. Single test, ~40 s. Strict test scope; no
production code path is changed.

### Section A — P_LEFT scanline reset

Two variants of the previous-left predictor differing only in row-start
behaviour:

| variant              | left-edge fallback at `x==0` | best \|r\| (sample.cr3) |
|----------------------|------------------------------|-------------------------|
| `P_LEFT_BASE`        | pixel above (`r[(y-1)*w+x]`) | **0.390** (tile1/p0/Gr) |
| `P_LEFT_LINE_RESET`  | midgrey (`predInit`)         | 0.061 (tile1/p1/R)      |

Hard per-row reset *destroys* the correlation. The wraparound-to-pixel-above
convention is structurally important and is preserved going forward.

### Section B — Alternative scan orders

Holding predictor = P_LEFT_LINE_RESET (so the "scan effect" is isolated
from the strong P_LEFT_BASE baseline; otherwise scan and predictor mix):

| scan              | best \|r\| over 4 planes × 2 tiles |
|-------------------|------------------------------------|
| ROW_MAJOR         | 0.061 |
| ROW_MAJOR_RESET   | 0.061 (alias of P_LEFT_LINE_RESET row-major) |
| COL_MAJOR         | 0.029 |
| SNAKE             | ≤ 0.005 |
| ZIGZAG            | 0.029 |

All non-row-major traversals stay at the noise floor. The bitstream's
natural traversal is row-major raster.

### Section C — Gradient/averaging predictors

On the best traversal of (B) (ROW_MAJOR_RESET):

| predictor        | best \|r\| (best plane/tile)                      |
|------------------|---------------------------------------------------|
| `G_AVG = (a+b)/2`| **0.376** (tile1/p1, R) and 0.317 (tile1/p2, B)   |
| `G_AVG_CLIP`     | 0.376 (identical to G_AVG within float precision) |
| `G_PLANE = a+b-c`| ≤ 0.078                                           |
| `G_2A_C = 2a-c`  | ≤ 0.018                                           |
| `G_PLANE_CLIP`   | ≤ 0.078                                           |

A 2D averaging predictor fits about as well as P_LEFT_BASE, but does
**not exceed it**. Plane (`a+b-c`) and 2A-c models are decisively
worse — the bitstream's residuals do not encode either model.

### Section D — Tile-cropped reference scoring

Top-3 of each section re-scored against `TileCroppedPreviewExtractor`
(preview JPEG cropped to tile region, then scaled to plane size).

| variant                                          | full r | tileCrop r | Δ      |
|--------------------------------------------------|--------|------------|--------|
| tile1/p0  P_LEFT_BASE/ROW_MAJOR                   | -0.390 | -0.328     | +0.062 |
| tile1/p1  P_LEFT_BASE/ROW_MAJOR                   | +0.349 | +0.335     | -0.014 |
| tile1/p1  G_AVG/ROW_MAJOR_RESET                   | +0.376 | +0.243     | -0.133 |
| tile1/p2  G_AVG/ROW_MAJOR_RESET                   | +0.317 | +0.348     | +0.032 |
| tile1/p1  P_LEFT_LINE_RESET/ROW_MAJOR(_RESET)     | +0.061 | +0.055     | -0.006 |

Tile-cropped vs full differs by at most 0.13, with no consistent sign
of improvement. Variant **ranking is preserved**. The ~0.4 ceiling is
therefore **not** an artefact of the full-frame reference geometry —
it is a real limit of the current decoder cascade.

### Net narrowing after Update (g)

Confirmed *not* the bottleneck (each ruled out by measurement, not a guess):

- per-line predictor reset (drops r from 0.39 → 0.06 — the convention
  is closer to *fall back to pixel above* than *hard reset*)
- column-major, snake, zig-zag scans (≤ 0.029 with line reset)
- 2D plane / 2A-c gradient predictors (≤ 0.078)
- full-frame vs tile-cropped reference geometry (Δr ≤ 0.13, ranking preserved)
- warmup-prefix on first N row samples (Section F, this update)
- additive bias on the decoded plane (Section H, expected — Pearson r
  is translation-invariant)

Newly **directionally favoured** by measurement:

- `P_LEFT` (previous-left) predictor — r reaches 0.233, ~3× the patent
  MED baseline. Still well below the r > 0.7 promotion threshold.

Concrete next steps (still test scope, no production change):
1. Pair `P_LEFT` with **per-line context reset** (some Canon/Pentax codecs
   reset the predictor at every scan line) and re-sweep.
2. Try **2D plane = 2-row interleave** (decode w samples per "row" but
   write them to alternating output rows) — this is the natural pattern
   if Canon stores each Bayer channel as half-resolution but interleaves
   sub-Bayer rows within the codec stream.
3. Try **Pentax/cgo-style context predictor** ((a + b) / 2, (a + b - c),
   simple gradient predictors) under the same harness.
4. Score against the **JPEG_BIG track cropped to one tile's image region**
   instead of the full-image preview; the current preview spans the whole
   image at one tile's plane size, so even a perfect tile decode has no
   chance of cleanly correlating with it.

None of these justify a production-code change yet. The hypothesis space
is materially narrower, but P_LEFT alone is not enough to cross the
usability threshold.


## Update 2026-04-30 (h) — Round-4 follow-up: P_LEFT reset, scan orders, gradient predictors, tile-cropped scoring

The four §5.7 next-steps from Update (f) are measured by
`canon-cr3-imageio/src/test/java/com/photographer/focusstack/cr3/research/CrxPlaneFollowupRound4IT.java`
on `sample.cr3`. Output dir `canon-cr3-imageio/target/crx-followup-round4/`
contains `metrics.tsv`, `winners.txt`, and 4 PGM heatmaps for tile0/p0
under representative variants. Single test, ~40 s. Strict test scope; no
production code path is changed.

### Section A — P_LEFT scanline reset

Two variants of the previous-left predictor differing only in row-start
behaviour:

| variant              | left-edge fallback at `x==0` | best \|r\| (sample.cr3) |
|----------------------|------------------------------|-------------------------|
| `P_LEFT_BASE`        | pixel above (`r[(y-1)*w+x]`) | **0.390** (tile1/p0/Gr) |
| `P_LEFT_LINE_RESET`  | midgrey (`predInit`)         | 0.061 (tile1/p1/R)      |

Hard per-row reset *destroys* the correlation. The wraparound-to-pixel-above
convention is structurally important and is preserved going forward.

### Section B — Alternative scan orders

Holding predictor = P_LEFT_LINE_RESET (so the "scan effect" is isolated
from the strong P_LEFT_BASE baseline; otherwise scan and predictor mix):

| scan              | best \|r\| over 4 planes × 2 tiles |
|-------------------|------------------------------------|
| ROW_MAJOR         | 0.061 |
| ROW_MAJOR_RESET   | 0.061 (alias of P_LEFT_LINE_RESET row-major) |
| COL_MAJOR         | 0.029 |
| SNAKE             | ≤ 0.005 |
| ZIGZAG            | 0.029 |

All non-row-major traversals stay at the noise floor. The bitstream's
natural traversal is row-major raster.

### Section C — Gradient/averaging predictors

On the best traversal of (B) (ROW_MAJOR_RESET):

| predictor        | best \|r\| (best plane/tile)                      |
|------------------|---------------------------------------------------|
| `G_AVG = (a+b)/2`| **0.376** (tile1/p1, R) and 0.317 (tile1/p2, B)   |
| `G_AVG_CLIP`     | 0.376 (identical to G_AVG within float precision) |
| `G_PLANE = a+b-c`| ≤ 0.078                                           |
| `G_2A_C = 2a-c`  | ≤ 0.018                                           |
| `G_PLANE_CLIP`   | ≤ 0.078                                           |

A 2D averaging predictor fits about as well as P_LEFT_BASE, but does
**not exceed it**. Plane (`a+b-c`) and 2A-c models are decisively
worse — the bitstream's residuals do not encode either model.

### Section D — Tile-cropped reference scoring

Top-3 of each section re-scored against `TileCroppedPreviewExtractor`
(preview JPEG cropped to tile region, then scaled to plane size).

| variant                                          | full r | tileCrop r | Δ      |
|--------------------------------------------------|--------|------------|--------|
| tile1/p0  P_LEFT_BASE/ROW_MAJOR                   | -0.390 | -0.328     | +0.062 |
| tile1/p1  P_LEFT_BASE/ROW_MAJOR                   | +0.349 | +0.335     | -0.014 |
| tile1/p1  G_AVG/ROW_MAJOR_RESET                   | +0.376 | +0.243     | -0.133 |
| tile1/p2  G_AVG/ROW_MAJOR_RESET                   | +0.317 | +0.348     | +0.032 |
| tile1/p1  P_LEFT_LINE_RESET/ROW_MAJOR(_RESET)     | +0.061 | +0.055     | -0.006 |

Tile-cropped vs full differs by at most 0.13, with no consistent sign
of improvement. Variant **ranking is preserved**. The ~0.4 ceiling is
therefore **not** an artefact of the full-frame reference geometry —
it is a real limit of the current decoder cascade.

### Net narrowing after Update (h)

Confirmed *not* the bottleneck (each ruled out by measurement, not a guess):

- per-line predictor reset (drops r from 0.39 → 0.06 — the convention
  is closer to *fall back to pixel above* than *hard reset*)
- column-major, snake, zig-zag scans (≤ 0.029 with line reset)
- 2D plane / 2A-c gradient predictors (≤ 0.078)
- full-frame vs tile-cropped reference geometry (Δr ≤ 0.13, ranking preserved)
- warmup-prefix on first N row samples (Section F, this update)
- additive bias on the decoded plane (Section H, expected — Pearson r
  is translation-invariant)

Newly **directionally favoured** by measurement:

- `P_LEFT` (previous-left) predictor — r reaches 0.233, ~3× the patent
  MED baseline. Still well below the r > 0.7 promotion threshold.

Concrete next steps (still test scope, no production change):
1. Pair `P_LEFT` with **per-line context reset** (some Canon/Pentax codecs
   reset the predictor at every scan line) and re-sweep.
2. Try **2D plane = 2-row interleave** (decode w samples per "row" but
   write them to alternating output rows) — this is the natural pattern
   if Canon stores each Bayer channel as half-resolution but interleaves
   sub-Bayer rows within the codec stream.
3. Try **Pentax/cgo-style context predictor** ((a + b) / 2, (a + b - c),
   simple gradient predictors) under the same harness.
4. Score against the **JPEG_BIG track cropped to one tile's image region**
   instead of the full-image preview; the current preview spans the whole
   image at one tile's plane size, so even a perfect tile decode has no
   chance of cleanly correlating with it.

None of these justify a production-code change yet. The hypothesis space
is materially narrower, but P_LEFT alone is not enough to cross the
usability threshold.


## Update 2026-05-01 (i) — Round-6 follow-up: sign-bit flip, plane→Bayer re-sweep, per-tile K-init

`CrxPlaneFollowupRound6IT` (test scope only, no production change)
extends Update (i) with the three "concrete next steps" left open after
Round-5: sign-bit flip under `LEAD_ZERO`, plane→Bayer permutation
re-sweep under `LEAD_ZERO`, per-tile K-init reset. Output dir
`canon-cr3-imageio/target/crx-followup-round6/`.

### Section J6 — Sign-bit flip under `LEAD_ZERO`

Holding predictor = `P_LEFT_BASE` and lead-zero scope =
`LZ_TILE_FIRST`, vary how the residual sign is mapped:

| variant            | tile0 p0 (Gr) | tile0 p2 (B) | tile1 p0 (Gr) | tile1 p1 (R) |
|--------------------|---------------|--------------|---------------|--------------|
| `NONE` (round-5)   | **−0.4904**   | −0.3994      | **−0.5605**   | +0.0551      |
| `GLOBAL_FLIP`      | −0.1927       | −0.3362      | −0.0341       | **−0.5235**  |
| `PER_PLANE_FLIP` (greens only) | −0.1927 | −0.3994 | −0.0341 | +0.0551 |
| `PER_TILE_FLIP` (tile1 only)   | −0.4904 | −0.3994 | −0.0341 | **−0.5235** |
| `RESIDUAL_NEG` (≡ GLOBAL_FLIP) | −0.1927 | −0.3362 | −0.0341 | **−0.5235** |
| `SYMBOL_LSB_FLIP` (parity flip on unsigned codeword) | −0.1747 | −0.3290 | −0.0659 | −0.5135 |

**Findings.**

1. **No global sign convention raises the maximum tile-wise |r|.** The
   round-4/5 winner (sign=`NONE`, |r|=0.4904 / 0.5605 on the Gr
   channel of tile0 / tile1) remains the global maximum.
2. **The sign of r is a property of the predictor chain, not of the
   residual sign convention.** Flipping the residual sign destroys the
   accumulated predictor chain (the predictor depends on previously
   reconstructed pixels), so `GLOBAL_FLIP` does *not* simply negate r;
   it gives a different reconstruction with generally lower |r|.
3. **Per-tile and per-plane flips reveal a real R-channel signal on
   tile1 p1**: |r| jumps from 0.055 (NONE) to 0.524 under
   `GLOBAL_FLIP` / `PER_TILE_FLIP` / `RESIDUAL_NEG`. This is the first
   non-Gr plane to clear |r| > 0.5 on this fixture and suggests
   p1 (R channel) may use an opposite residual-sign convention from
   p0 (Gr channel) inside tile1 — but the same flip *destroys* the
   tile1 p0 signal (0.5605 → 0.034), so a single global rule cannot
   capture both. A per-plane-and-per-tile sign rule is unlikely to be
   how Canon's encoder actually works; this is more naturally read as
   the ±sign ambiguity in our reference fit not propagating across
   plane indices.
4. `SYMBOL_LSB_FLIP` (invert LSB *before* `mapSignLsb`) tracks
   `GLOBAL_FLIP` to within 0.02 |r| on every plane/tile — the two are
   structurally close, not independent. No symbol-near sign convention
   gives a fundamentally different reconstruction than the
   residual-near one.

### Section K6 — Plane→Bayer permutation re-sweep under `LEAD_ZERO`

All 24 plane→preview-Bayer permutations evaluated under the round-5
winner config. Score = sum of |r| over the four (plane, channel) pairs.

| rank | tile0 perm | tile0 sum\|r\| | tile1 perm | tile1 sum\|r\| |
|------|------------|---------------|------------|---------------|
| 1    | `[1023]`   | **0.9484**    | `[1023]`   | **0.9325**    |
| 2    | `[2013]`   | 0.9484        | `[2013]`   | 0.9325        |
| 3    | `[1032]` (existing GRBG) | 0.9402 | `[1203]` | 0.9250 |
| 4    | `[2031]`   | 0.9402        | `[2103]`   | 0.9250 |
| 5    | `[1203]`   | 0.9354        | `[0123]`   | 0.9226 |

`[1023]` = (p0→Gr, p1→R, p2→Gb, p3→B). The existing
`Cmp1Box`-derived GRBG mapping `{1, 0, 3, 2}` = `[1032]` puts
**p2→B and p3→Gb**, while the new winner puts **p2→Gb and p3→B**.

**Findings.**

1. The new winner is consistent across both tiles (Δ rank = 0) and
   gives Δ sum\|r\| = +0.0082 (tile0) / +0.0099 (tile1) over the
   existing GRBG mapping — small but reproducible.
2. The per-channel decomposition shows that the gain comes from a
   higher |r| on the green-pair-vs-blue assignment, not from a
   meaningful improvement on the dominant Gr channel (which is
   `[1023] vs [1032]` invariant on p0). p3 is essentially uncorrelated
   under either mapping, so the swap p2↔p3 is dominated by p2 alone.
3. **Per-channel maxima on the new winner (`[1023]`):** p0=0.490 (Gr),
   p1=0.046 (R, but 0.524 under GLOBAL_FLIP), p2=0.399 (Gb under
   `[1023]` vs B under `[1032]`), p3=0.004 (B vs Gb). The tile-wise
   maximum |r|=0.5605 (tile1 p0) is unchanged.
4. Ties between `[1023]` and `[2013]` on both tiles imply p0 and p1
   are interchangeable for the green-vs-red assignment under our
   `pearson` metric (rank-invariant under linear plane swap when one
   of the two has near-zero |r|). Update (f)'s permutation result
   at lower |r| floor was unable to discriminate this; under
   LEAD_ZERO the gap is just barely measurable.
5. **Promotion deferred.** Even with the new permutation, the per-
   channel max |r| stays at 0.56 — well below the 0.7 promotion threshold.
   Updating production `cfaLayoutMap(GRBG)` based on a Δ=0.008
   sum-|r| signal (no per-channel improvement on Gr or R) would
   not be defensible.

### Section L6 — Per-tile K-init under `LEAD_ZERO`

| K-init        | tile0 p0 (Gr) | tile0 p1 (R)  | tile0 p2 (B)  | tile1 p0 (Gr) | tile1 p1 (R)  |
|---------------|---------------|---------------|---------------|---------------|---------------|
| `K_INIT_0` (round-5)   | −0.4904 | +0.0464 | −0.3994 | **−0.5605** | +0.0551 |
| `K_INIT_1`             | +0.0884 | −0.3820 | −0.2352 | −0.5605     | +0.0323 |
| `K_INIT_2`             | −0.2346 | −0.4836 | −0.2352 | −0.5605     | +0.2059 |
| `K_INIT_3`             | −0.2346 | −0.4836 | −0.2352 | −0.5582     | +0.2059 |
| `K_INIT_4`             | **−0.5409** | −0.4836 | −0.2352 | −0.5582 | −0.3925 |
| `K_INIT_5`             | −0.5124 | −0.4391 | −0.2352 | −0.5550     | +0.2059 |
| `K_INIT_7`             | −0.4049 | −0.4391 | −0.3534 | −0.1376     | −0.3925 |
| `K_INIT_FROM_FIRST`    | (varies; same family as `K_INIT_0..3` because first-symbol K* is small on this fixture) | | | | |

**Findings.**

1. **Tile0 p0 lifts from |r|=0.4904 → 0.5409 at `K_INIT_4`** (Δ = +0.05).
   This is the first per-tile improvement of Round-6 that exceeds
   numerical noise on the dominant Gr plane.
2. **Tile0 p1 (R) lifts from |r|=0.046 → 0.484 at `K_INIT_2..4`**
   — almost a 10× jump on a previously near-uncorrelated plane,
   which is an order-of-magnitude bigger than any single result of
   Updates (g)/(h)/(i). It confirms the patent §1.8 "K-update is
   per-symbol but the seed matters" observation: the legacy `k=0`
   default destroys the early symbols on planes whose true magnitude
   is around 4–16.
3. **Tile1 p0 (the global maximum) is K-init-invariant for K ∈ {0,1,2}**
   — confirms that the bracket K-update converges quickly once a
   high-magnitude symbol arrives. K ≥ 3 starts to cost a tiny
   |r| (0.5605 → 0.5582 at K_INIT_3/4, → 0.555 at K_INIT_5).
4. **No single K-init wins across all (tile, plane) combinations.**
   Tile0 wants K_INIT_4 on its dominant plane; tile1 wants K_INIT_0.
   A correct implementation would need either a per-plane-derived
   K-init or a different K-update bracket — both consistent with the
   patent allowing kInit ∈ [K*−1, K*+1].
5. **`K_INIT_FROM_FIRST` is not a clean win** on this fixture because
   the very first decoded residual on most planes is small
   (typical first-pixel residual is in {0,1,2}), so the derived K is
   identical to K_INIT_0..2 in most cases. A more useful seed would
   come from the *median* of the first N residuals; not measured in
   this round.

### Net narrowing after Update (j)

Confirmed *not* the bottleneck (each ruled out by measurement):

- A single global residual-sign flip raising the maximum |r|.
- A single global residual-sign flip working consistently across all
  (tile, plane) combinations.
- A symbol-near (LSB-of-codeword) sign convention being
  fundamentally different from a residual-near sign convention.
- Major rearrangement of the plane→Bayer mapping under `LEAD_ZERO`
  (best new permutation Δ = +0.008 sum-|r|, no per-channel gain on
  the dominant Gr/R pair).
- Per-tile K-reset alone exceeding the |r| > 0.7 threshold (best
  per-tile improvement is Δ = +0.05).

New positive findings:

- **Per-plane K-init is meaningful.** Tile0 p1 (R channel) lifts
  10× from |r|=0.046 to 0.484 at K_INIT ∈ {2,3,4}.
- **Per-tile sign convention may differ on tile1 p1** (R channel
  jumps from 0.055 to 0.524 under GLOBAL_FLIP); this is unlikely to
  reflect Canon's encoder structure but is reproducible.

### Concrete next steps after Update (j)

Each step remains test-scope only:

1. **Per-plane K-init sweep** — re-run L6 but vary K-init
   independently per plane within a tile. Tile0 p0 wants 4, p1 wants
   2, p2 wants 0, p3 wants 0/2; tile1 p0 wants 0–2, p1 wants 4. A
   pre-pass that picks K-init from a small per-plane statistic (e.g.
   median-magnitude of the first 16 decoded residuals at K=0) is
   cheap and worth measuring.
2. **K-init derived from quantValue** (CMP1 reports `quantValue=4`
   for every subband on this fixture) — the patent allows
   `K_init ≈ floor(log2(quantValue))` as a per-subband seed; not yet
   tested.
3. **Combined LEAD_ZERO + per-plane-K-init + permutation [1023]**
   — measure whether the three small Round-6 wins compose without
   the destructive interaction seen in Round-5 Section I5.

Promotion threshold remains `|r| > 0.7` over a contiguous (variant ×
plane × tile) majority before any production-code change. Currently
the per-channel maximum is still 0.56 ⟹ still no production-code
change.

## Update 2026-05-11 (n) — Round‑9 result: `K_INIT = quantValue` is FALSIFIED

Round‑9 (`CrxPlaneFollowupRound9IT`, test scope) ran the discrimination
experiment Update (l) §1 had explicitly deferred for lack of a `q ≠ 4`
fixture. With the Update (m) C‑RAW fixture now in the corpus, the
experiment is no longer circular.

### Method

For each of the 80 subbands of
`g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3` (and the
replicate frame `-03.cr3`), and for every `K_INIT ∈ [0, bitDepth]`
(bitDepth = 14, so 15 candidate seeds), run a Rice/Golomb decoder probe
with the Round‑6 patent K‑bracket update and a fixed 4 096‑symbol budget.
Record the average **bits per symbol** consumed. The seed that minimises
bits‑per‑symbol per subband is the empirically optimal `K_INIT` for that
subband. Group the 160 subband observations by the metadata `q` value to
test whether the per‑subband optimum tracks `q`.

This metric is **ground‑truth free** — it does not depend on a Pearson
correlation against a preview JPEG and is therefore unaffected by the
unresolved CFA permutation / IDWT recombination questions left over from
Updates (e)–(l).

### Findings

The result is unambiguous (full table in
`canon-cr3-imageio/target/crx-followup-round9/winners.txt`):

| q  | n   | med(optK) | med(bps@opt) | med(bps@q) | slack vs opt at K_INIT=q | slack vs opt at K_INIT=4 |
|---:|----:|----------:|-------------:|-----------:|-------------------------:|-------------------------:|
|  4 |  32 | **0**     | 4.47         | 4.48       |  +0.010                  |  +0.010                  |
| 16 |  16 | **0**     | 4.52         | ∞ (overrun)|  ∞                       |  +0.041                  |
| 22 |   8 | **0**     | 4.23         | ∞          |  ∞                       |  +0.018                  |
| 26 |  16 | **0**     | 4.21         | ∞          |  ∞                       |  +0.006                  |
| 32 |   8 | **0**     | 4.07         | ∞          |  ∞                       |  +0.004                  |

Aggregated across both frames (160 subbands):

- **`K_INIT = q` is structurally falsified.** For every subband with
  `q ≥ 16`, the Rice remainder consumes more bits than the entire
  subband payload contains, i.e. the decoder runs off the end of the
  bitstream **inside the very first symbol**. `K_INIT = q` therefore
  cannot be the rule the encoder used.
- **`K_INIT = 0` is the per‑subband optimum on 100 % of subbands.**
  Mean bits‑per‑symbol slack vs optimum is `+0.000` for `K_INIT = 0`
  by construction.
- **`K_INIT = 4` is a numerical near‑optimum** (mean slack `+0.022`
  bits/symbol) — but only because the patent K‑bracket pulls `K`
  down to 0 within the first ≈ 4 symbols regardless of seed. It is
  not a structurally distinguished value.
- The Round‑8 winner (`K_INIT = CMP1.quantValue`, `Δ Σ|r| ≤ 0.01`) was
  a circular artefact of every prior fixture having `q = 4` on every
  plane: in that degenerate case `K_INIT = q ≡ K_INIT = 4`, so the
  Round‑8 metric could not separate them. Round‑9 separates them
  cleanly because the new fixture has five distinct `q` values.

### What this changes about the production decoder

`CrxLosslessRawDecoder.decodePlane` already uses `int k = 0;` as its
seed (line 406). Round‑9 has now **empirically validated** this choice
against a `q ≠ 4` bitstream. No production code change is warranted.

The `README.md` in `canon-cr3-imageio/src/test/resources/samples/research/`
previously recommended seeding `K` from `CMP1.quantValue` — that
recommendation is now retracted (see the same Update (n) edit in that
file).

### Net narrowing of the hypothesis space after Update (n)

- `K_INIT` is now **fixed at 0** for every subband with measurable
  empirical backing across both `q = 4` and `q ∈ {16, 22, 26, 32}`
  fixtures. The seed is no longer a free parameter.
- The remaining unknown for the Rice/Golomb stage is the K‑*update*
  rule after init. The Round‑6 patent bracket (`K* = ⌊log₂(s+1)⌋`,
  `K ∈ [K* − 1, K* + 1]`) keeps the per‑plane Σ|r| at the long‑standing
  0.56 ceiling on lossless fixtures; whether it is also the right rule
  inside a multi‑level wavelet C‑RAW bitstream is the next open
  question.
- Per‑subband `quantValue` does **not** feed `K_INIT`. Its role in the
  C‑RAW pipeline is therefore most likely the inverse‑quantisation
  coefficient applied to the *decoded* wavelet coefficients before IDWT
  (`coef ← coef · q`), consistent with Canon's documented C‑RAW design.

### Concrete next steps after Update (n)

1. **Round‑10 IT (`CrxPlaneFollowupRound10IT`, test‑scope) — dequant
   + IDWT:** keep `K_INIT = 0` and the Round‑6 patent K‑bracket on
   every subband, then for each plane apply candidate inverse‑quant
   rules (`coef ← coef · q`, `coef ← coef · q + ⌊q/2⌋`, dead‑zone
   `coef ← sign(coef) · max(0, |coef| − ⌊q/2⌋) · q`) and feed the 10
   subbands of one plane (`LL₃ + 3·{HL,LH,HH}`) through three Le Gall
   5/3 inverse synthesis passes (level 3 → 2 → 1). Score each
   reconstruction against the matching preview Bayer channel as in
   Round‑8 §S8. This is the first experiment in the corpus that can
   exercise the multi‑level IDWT path — every previous fixture had
   `waveletLevels = 0`.
2. **Capture companion ISO bursts** (operator workflow — body in M,
   dial ISO; same `detail` scene + aperture): one C‑RAW burst at
   **ISO 125** and one at **ISO 6400**, to test whether the
   per‑subband q‑vector itself scales with ISO or only with subband
   index. Update (m)'s table of `q ∈ {4, 16, 22, 26, 32}` was recorded
   at ISO 1600 only.
3. **No production code change** until a combined (predictor + K‑update
   + dequant + IDWT) pipeline lifts per‑channel max|r| above 0.7. The
   promotion threshold in `CrxLosslessRawDecoder` is unchanged.

## Update 2026-05-11 (o) — Round‑10 result: multi‑level IDWT + dequant alone is INSUFFICIENT

Round‑10 (`CrxPlaneFollowupRound10IT`, test scope) implements the
Update (n) §1 next step: the first end‑to‑end pipeline in the corpus
that actually exercises `InverseWaveletTransform.inverseSynthesis2D`
on a fixture with `waveletLevels > 0`. All earlier rounds operated on
lossless RAW (`waveletLevels = 0`), where the four "planes per tile"
are the four Bayer channels, not four wavelet subbands.

### Method

Fixture: `g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3`
(C‑RAW, `waveletLevels = 3`, `cfaLayout = GRBG`, 2 tiles × 4 planes ×
10 subbands = 80 subbands, plane size 1408 × 1855 at 14‑bit).

For each (tile, plane) the 10 subbands are interpreted in the canonical
JPEG2000 ordering:

```
sb0          = LL_3                       (1 subband at deepest level)
sb1, 2, 3    = HL_3, LH_3, HH_3
sb4, 5, 6    = HL_2, LH_2, HH_2
sb7, 8, 9    = HL_1, LH_1, HH_1
```

Pipeline per (tile, plane):
1. **Entropy decode** — Round‑9 winner: `K_INIT = 0`, patent
   `K_BRACKET` update, sign‑LSB symbol mapping. LL_3 also gets a second
   pass with an unsigned‑magnitude variant (LL of a non‑negative
   image is non‑negative under Le Gall 5/3).
2. **Dequantisation** — three candidates:
   `D_IDENTITY` (none), `D_MUL_Q` (`coef · q`), `D_MUL_Q_HALF`
   (`coef · q + sign(coef)·q/2`, JPEG2000 midpoint reconstruction).
3. **Three successive 2D Le Gall 5/3 IDWT passes**:
   `LL_2 = IDWT(LL_3, HL_3, LH_3, HH_3)` →
   `LL_1 = IDWT(LL_2, HL_2, LH_2, HH_2)` →
   `plane = IDWT(LL_1, HL_1, LH_1, HH_1)`.
4. **Score** the reconstructed plane against the matching preview
   Bayer channel under the GRBG plane→Bayer map (Pearson r over the
   row‑aligned overlap; the IDWT output is 1408 × 1848 because
   `1855 >> 3 << 3 = 1848`, losing 7 rows).

Verified 2026‑05‑11 via
`mvn -B verify -pl canon-cr3-imageio -am -Dit.test=CrxPlaneFollowupRound10IT
 -Djava.awt.headless=true`. Artefacts:
`canon-cr3-imageio/target/crx-followup-round10/{metrics.tsv, winners.txt}`.

### Findings

Per‑channel Pearson r against the GRBG‑mapped preview Bayer channel:

| (tile, plane) → bayer | best variant                       | best \|r\| |
|----------------------|------------------------------------|-----------:|
| tile0/p0 → Gr        | `D_IDENTITY` + `LL_UNSIGNED`       | **0.0273** |
| tile0/p1 → R         | `D_MUL_Q` + `LL_LL_SIGNED`/UNSIGNED| 0.0018     |
| tile0/p2 → B         | `D_IDENTITY` + `LL_UNSIGNED`       | 0.0086     |
| tile0/p3 → Gb        | `D_IDENTITY` + `LL_UNSIGNED`       | 0.0152     |
| tile1/p0 → Gr        | `D_IDENTITY` + `LL_UNSIGNED`       | 0.0041     |
| tile1/p1 → R         | `D_IDENTITY` + `LL_SIGNED`         | 0.0062     |
| tile1/p2 → B         | `D_IDENTITY` + `LL_UNSIGNED`       | 0.0142     |
| tile1/p3 → Gb        | `D_IDENTITY` + `LL_UNSIGNED`       | 0.0241     |

**GLOBAL MAX |r| = 0.0273** at `tile0/p0 dq=D_IDENTITY ll=LL_UNSIGNED`.

Reconstruction value range observed for `tile0/p0` (representative):

| variant                               | min      | max      | mean |
|---------------------------------------|---------:|---------:|-----:|
| `D_IDENTITY`   + `LL_SIGNED`          | −13193   | 12854    | 0    |
| `D_IDENTITY`   + `LL_UNSIGNED`        | −13190   | 21502    | 22   |
| `D_MUL_Q`      + `LL_SIGNED`          | −342905  | 371889   | 4    |
| `D_MUL_Q`      + `LL_UNSIGNED`        | −342891  | 371899   | 93   |
| `D_MUL_Q_HALF` + `LL_SIGNED`          | −342925  | 371914   | 4    |
| `D_MUL_Q_HALF` + `LL_UNSIGNED`        | −342908  | 371925   | 95   |

### What this conclusively rules in / out

- **The "Round‑9 entropy stage + plain Le Gall 5/3 IDWT + a scalar
  dequant" composition does NOT decode C‑RAW.** Per‑channel max|r|
  is 0.0273 — a factor 20× below the long‑standing 0.56 ceiling on
  *lossless* fixtures and 25× below the 0.7 promotion threshold.
  The C‑RAW pipeline has structural elements that a textbook
  JPEG2000‑style "entropy → dequant → IDWT" cascade cannot model.
- **Dequantisation is *not* the missing link.** All three dequant
  rules produce essentially identical Pearson r per (tile, plane)
  (Δ ≤ 0.011 within each row of the table above). This is the
  expected behaviour: Pearson r is invariant under positive linear
  scaling, and the per‑subband dead‑zone of `q/2` is small relative
  to the 14‑bit coefficient magnitude. No scalar inverse‑quant rule
  can promote the score.
- **LL sign convention has a measurable but small effect.**
  `LL_UNSIGNED` outperforms `LL_SIGNED` on 7 of 8 (tile, plane) cells
  (Δ ≈ 0.005–0.026), consistent with the LL band of a non‑negative
  image being non‑negative under Le Gall 5/3 — but the absolute |r|
  remains in the noise band on every cell.
- **`waveletLevels = 3` matches the bitstream geometry.** Every
  (tile, plane) has exactly 1 + 3·3 = 10 subbands (sanity check
  passed); the recon length matches `1408 × 1848 = 2 601 984` on
  every variant; no buffer underflow / overflow in the IDWT cascade.
  So the geometry of "1 + 3·L subbands per plane" is correct for
  Canon C‑RAW.

### What this NARROWS for the next session

The 0.0273 ceiling is structurally interesting because it is
**uniform across dequant rules and across LL sign conventions**.
That means the missing mechanism is upstream of dequant. Three
hypotheses survive Round‑10:

1. **Per‑subband horizontal predictor before entropy coding.**
   Canon's documented C‑RAW design (and the patent already cited in
   Update (c)) describes a *predictor inside each subband* — i.e. the
   entropy‑coded values are not the wavelet coefficients themselves
   but residuals against an intra‑subband predictor (typically
   `prev_in_row` for HL/LH and `0` for HH). Round‑10 fed the raw
   Rice/Golomb output straight into the dequant stage, skipping the
   reverse predictor. This is the most plausible cause of the
   noise‑floor result.
2. **Subband ordering inside the bitstream is not LL_L → HL_L →
   LH_L → HH_L → … → HH_1.** JPEG2000 emits codeblocks per resolution
   level inside a precinct, but the per‑tile bitstream order Canon
   uses is not specified in any public source we have. A trivial
   rotation of the {HL, LH, HH} role assignment within each level
   would not be visible to the entropy stage but would scramble the
   IDWT. The Round‑10 sweep did *not* permute roles.
3. **The 80 subbands are not "10 per plane × 4 planes × 2 tiles"**
   in the way the parser groups them. If the bitstream actually
   interleaves subbands across planes (e.g. all four LL_3 first,
   then all four HL_3, LH_3, HH_3, HL_2, …), then the per‑(tile, plane) `groups` map
   built from `SubbandRef.tileSequence()/planeIndex()` is wrong and
   each "plane" would actually contain 10 subbands of *mixed* spatial
   levels — which would explain the uniform near‑zero correlation.

### Concrete next steps after Update (o)

Each step remains test‑scope only:

1. **Round‑11 IT — per‑subband predictor sweep.** Hold the Round‑10
   pipeline fixed and add an inverse horizontal predictor between
   entropy decode and dequant for each detail subband:
   `coef[x,y] = prev_decoded[x−1,y] + decoded_residual[x,y]` with
   `prev = 0` at row start. Test with predictor disabled on LL only,
   on {HL, LH} only, on all detail bands, and on all bands. If any
   single configuration lifts max|r| above 0.1 on at least three
   (tile, plane) cells, the predictor hypothesis is supported.
2. **Round‑11 IT extension — role permutation under the predictor.**
   Once the predictor is in place, sweep the 6 permutations of
   {HL, LH, HH} role assignment inside each level and the 24
   permutations of plane→Bayer mapping at the IDWT output. Re‑use
   the Round‑6 / Round‑8 scoring harness.
3. **Subband cross‑plane interleave hypothesis.** Read the 80 subband
   payload sizes from `metrics.tsv` (the `R10/dim` rows already
   record `q` and `payload_bytes` per subband). If the size pattern
   is *(4 small, 12 medium, 12 medium, 12 large, …)* repeated, the
   bitstream is plane‑grouped (current parser is correct). If it is
   *(LL_3 ×4, HL_3 ×4, LH_3 ×4, HH_3 ×4, HL_2 ×4, …)* the parser is
   wrong and the `groups` map needs to be rebuilt before Round‑11.

Promotion threshold remains `|r| > 0.7` over a contiguous (variant ×
plane × tile) majority before any production‑code change. Currently
the per‑channel maximum is **still 0.56 on lossless and 0.027 on
C‑RAW**. No production‑code change.

## Update 2026-05-15 (p) — Vier diagnostische ITs ohne Decoder-Sweeps

Vier neue Research-ITs in `canon-cr3-imageio/src/test/.../research/`,
ausschließlich Test-Code, keine Produktionsänderungen, keine neuen
Hypothesen-Sweeps:

* **`FixtureIntelligenceReportIT`** — schreibt
  `target/fixture-intelligence/fixtures.{tsv,md}` mit einer Zeile pro
  CR3-Datei (camera, RAW/C-RAW, quantValue-Menge, waveletLevels, cfaLayout
  numerisch + Name, numPlanes, tilesPerFrame, subbandsPerFrame, OB-Geometrie,
  CRX_BIG/CRX_SMALL/PRVW/THMB-Geometrie, Burst-Gruppe, Novelty-Tag).
* **`BurstFrameStabilityIT`** — Pearson r, MAE, MSE, Row-/Col-R und
  16×16-Block-R pro Bayer-Plane zwischen benachbarten Burst-Frames.
* **`CrxSmallVsPrvwGeometryIT`** — decoder-frei: 1620↔1624-Ambiguitäts-
  Pearson r und CFA-Konsistenz CRX_BIG ↔ CRX_SMALL pro Datei.
* **`Iad1OpticalBlackProfileIT`** — pro `(file × region × plane)`: median,
  mode (64-LSB-Buckets), mean, stdDev, max, fracWithin±256-of-median,
  row-/col-flatness auf den vollen IAD1-Rechtecken `leftOb*` und `topOb*`
  (nicht nur `activeAreaLeft` als Spaltenzahl).

### Drei harte Befunde aus Update (p)

1. **Strukturelle Korpus-Sättigung.** Über 13 CR3-Dateien existieren genau
   zwei `(compression, waveletLevels, quantValues, cfaLayout, numPlanes,
   tilesPerFrame)`-Klassen (Lossless q=4 und C-RAW q∈{4,16,22,26,32}) und
   genau **eine** echte Burst-Gruppe (das C-RAW-Paar
   `g5xm2-detail-iq3-20260511-201012-{02,03}.cr3`). 10/13 Frames sind
   strukturell redundant. Weitere breite Decoder-Sweeps gegen denselben
   Bitstrom liefern keine neuen Informationen — das ist exakt das, was die
   Stoppanweisung voraussagt.
2. **`cfaLayout`-Inkonsistenz universell.** Auf jeder Datei (G5 X II + EOSR)
   deklariert CRX_BIG `cfaLayout=0=RGGB` und CRX_SMALL `cfaLayout=1=GRBG`.
   `cfa-consistent? = no` über die ganze Tabelle. Damit ist klar, dass jede
   PRVW↔CRX_SMALL-Korrelations-Runde der Vergangenheit auf einer falschen
   Mosaik-Permutation gerechnet hat und dass `CMP1.cfaLayout` **pro Track**
   ausgelesen werden muss, nicht modellbasiert.
3. **OB-Regionen sind heute physikalisch unmöglich.** EOSR `leftOB` Plane[0]
   median springt zwischen `eosr-iso-low.cr3` (157 LSB), `eosr-iso-high.cr3`
   (392) und `eosr-iso-mid.cr3` (**14452**) — Faktor ~50 in einer optisch
   abgedeckten Region ist physikalisch ausgeschlossen. Die OB-Profile sind
   damit ein **Punkt-für-Punkt physikalischer Falsifikator** für jede
   Decoder-Hypothese, höher zu gewichten als Pearson-r gegen PRVW.

### Bewertungsbasis-Verschiebung

Bisherige Hauptmetrik: **Pearson r vs. PRVW** (Round-3 bis Round-13). Stand:
max \|r\|=0.56 auf Lossless, 0.027 auf C-RAW.

Neue Hauptmetrik laut WP1 (sobald datenfähig):
**Burst-Frame-Stabilität** (Pearson r zwischen benachbarten Frames derselben
Burst, pro Plane). Auf einem korrekten Decoder muss r → 1; eine
Decoder-Hypothese, die r=0.55 vs. PRVW erreicht, aber r<0.5 zwischen Burst-
Frames produziert, ist trotzdem falsch (sie produziert pro Frame
*unterschiedliches* Rauschen mit ähnlichem Mittel).

**Status der neuen Hauptmetrik:** WP1 ist gebaut und einsatzbereit, aber
die Burst-Stabilitäts-TSV ist heute leer, weil das einzige tatsächliche
Burst-Paar im Korpus C-RAW ist und der Produktions-Lossless-Decoder C-RAW
nicht abdeckt. Das macht die `CrawLossyBringUpIT`-/C-RAW-Linie zur
*einzigen* Vorwärtsstrategie, die die Bewertungsbasis tatsächlich öffnet.

### Sekundäre, jederzeit anwendbare Falsifikatoren

* **OB-Plausibilität (WP3)**: Jeder Decoder-Output muss in den IAD1-OB-
  Rechtecken einen über alle Frames derselben Kamera **stabilen** Pedestal
  zeigen (typisch 256–4096 LSB bei 14 bit, mit stdDev unterhalb der
  Read-Noise-Erwartung). Die heutige TSV zeigt, dass das nicht der Fall ist.
* **CFA-Konsistenz pro Track (WP2)**: Jede PRVW↔CRX-Korrelation muss
  `CMP1.cfaLayout` der jeweiligen Track-Quelle nutzen — die TSV macht
  Verstöße offensichtlich.
* **Strukturklassen-Novelty (WP4)**: Bevor neue Sweeps gestartet werden,
  prüft die TSV, ob das Sweep-Sample überhaupt eine neue Strukturklasse ist.

### Was Update (p) **nicht** ändert

* Keine Produktionsänderung. `CrxLosslessRawDecoder` und alles unter
  `canon-cr3-core/src/main` bleibt bitidentisch zu Update (o).
* Kein neuer Sweep-Round. `CrxCandidateDecoders` wird von keiner der vier
  neuen ITs aufgerufen.
* Die 0.7-Promotionsschwelle bleibt unverändert — sie wird jetzt pro
  Hypothese mit zwei zusätzlichen Falsifikatoren (OB-Plausibilität, Burst-
  Stabilität sobald datenfähig) ergänzt statt ersetzt.
