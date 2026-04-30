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

A reference grid extractor (PRVW → plane-grid pixel array) is the next
obvious piece of infrastructure to add and is left for the session that
first writes a candidate decoder good enough to be worth comparing against
ground truth.
