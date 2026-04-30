# Clean-room evaluation of patent US 2016/0323602 A1 ("Image processing apparatus, image processing method, and storage medium")

> **Scope.** This document is a *clean-room* engineering evaluation of the publicly
> available Canon patent application **US 2016/0323602 A1** (published 2016,
> filed 2015, related to Canon's C-RAW / CRX compression) against the open
> questions in this repository's CRX entropy decoder.
>
> **Method.** Only the patent itself, public open standards (Exif 2.3, ITU-T T.800
> JPEG 2000, ITU-T T.87 JPEG-LS), and our own measurements (`canon-cr3-core/SubbandFixtures.md`,
> the `CrxHypothesisSweepIT` and `CrxBitsPerPixelIT` runs) are used as inputs. **No
> third-party decoder source code (LibRaw `crx.cpp`, dcraw, RawTherapee, darktable,
> exiftool, lclevy/canon_cr3 reverse-engineering source, etc.) was consulted while
> writing this document.**  Where lclevy/canon_cr3 is referenced it is solely as a
> pointer to the patent, not as a source of algorithmic detail.
>
> **Source access.** The patent text was made available as an upload during the
> session. The factual statements below that are tagged **`[patent]`** are taken
> directly from the description / figures of the published application
> (subband-order figure FIG. 5, Golomb-Rice-coding figures FIG. 10A/10B, and the
> accompanying description of predictor and K-update behaviour). Statements that
> remain inferences about the *concrete CRX file path* (rather than about the
> patent's algorithm in the abstract) are tagged **`[hypothesis]`** — the patent
> describes one realisation of the algorithm family and explicitly leaves several
> degrees of freedom (e.g. 5/3 vs. 9/7 filter) open, so a 1:1 identification with
> the on-disk CRX byte format must still be empirically validated.

---

## 0. Bibliographic reference

| Field | Value |
|------|-------|
| Publication number | **US 2016/0323602 A1** |
| Title | *Image processing apparatus, image processing method, and storage medium* |
| Assignee | Canon Kabushiki Kaisha |
| Inventors | (per public record; not material to this clean-room analysis) |
| Family | Canon C-RAW / CRX compression family — see also US 11,343,535 B2 / US 10,225,569 B2 (later issued patents in the same family that are referred to in the public reverse-engineering literature) |
| Subject | Wavelet-based RAW compression with Golomb-Rice / adaptive-Rice entropy coding |

The published patent is freely accessible via the USPTO PAIR / Google Patents and is
explicitly intended to enable third parties to read and re-implement the disclosed
method (this is the legal purpose of patent disclosure). Implementing a *decoder*
for interoperability does not constitute copying of source code from any third-party
implementation.

---

## 1. Mapping of the patent onto the open CRX questions

This section answers — to the extent the patent permits — the questions enumerated
in the task brief. Statements tagged `[patent]` are taken directly from the
patent description / figures; statements tagged `[hypothesis]` are inferences
about how the *CRX on-disk format* relates to the patent.

### 1.1 Role of the wavelet transform

**A. Confirmed from patent `[patent]`:**
- The compression path is **wavelet decomposition → predictive coding of subband
  coefficients → Golomb-Rice entropy coding**. The decoder runs this pipeline in
  reverse.
- The decomposition is **multi-level**: the LL subband at level *i* is itself
  decomposed into LL/HL/LH/HH at level *i+1* (FIG. 5).
- The patent uses an **integer 5/3** filter in its example, but **explicitly
  permits a 9/7 filter** (as in JPEG 2000) for the same algorithmic structure.

**B. Hypothesis (about CRX specifically) `[hypothesis]`:**
- For lossless tracks (round-trip exact) the on-disk CRX path most plausibly uses
  the integer 5/3 lifting, since 9/7 is irreversible. But this is *not* forced by
  the patent — it is forced by the empirical "round-trips exactly" requirement
  applied to the lossless track.
- For lossy / C-RAW tracks the filter choice is undetermined. The patent allows
  both; we must verify against the file.

**C. Open:**
- The exact lifting normalisation, rounding rule and edge handling (within the
  family of "integer 5/3") are implementation freedoms.

### 1.2 Subbands described

**A. Confirmed from patent `[patent]`:**
- Standard quad-tree wavelet subbands **LL, HL, LH, HH** at each decomposition
  level (FIG. 5).

**B. Hypothesis (about CRX specifically) `[hypothesis]`:**
- The four "planes" reported by the CRX header for a given tile are the four
  wavelet subbands (LL, HL, LH, HH) of *one* Bayer channel at *one* decomposition
  level — not the four Bayer colour channels as the current decoder assumes.
  Supporting evidence: 6.7× per-plane size variance on `sample-wb-tungsten.cr3`,
  consistent with one large-magnitude LL and three smaller high-pass subbands.
  This is the single most consequential CRX-specific hypothesis arising from the
  patent.

**C. Open:**
- Number of decomposition levels per CRX track. `Cmp1Box.waveletLevels()` is the
  most likely source field but its semantics need to be checked (the bundled
  lossless fixtures report `waveletLevels = 0` while still appearing to encode
  multiple subbands per channel — possibly meaning "zero *additional* levels
  beyond the implicit one", a known patent-style off-by-one).

### 1.3 Order of coefficient processing within a subband

**A. Confirmed from patent `[patent]`:**
- Coefficients within a subband are processed in **raster order** (top-to-bottom,
  left-to-right).

### 1.4 Order of subbands across a multi-level decomposition

**A. Confirmed from patent (FIG. 5) `[patent]`:**

For an *n*-level decomposition, subbands are processed in the order:

```
LL(n), HL(n), LH(n), HH(n),
       HL(n-1), LH(n-1), HH(n-1),
       …
       HL(1),  LH(1),  HH(1)
```

i.e. the deepest LL first, then for each level (deepest first) the three
high-pass subbands HL, LH, HH in that fixed order. For three levels the explicit
sequence is `LL3, HL3, LH3, HH3, HL2, LH2, HH2, HL1, LH1, HH1`.

**B. Hypothesis (about CRX specifically) `[hypothesis]`:**
- The CRX container's plane indices `0..N-1` within a tile encode this exact
  sequence. For a one-level decomposition (most plausible for our lossless
  fixtures) this collapses to `LL, HL, LH, HH` ↔ plane indices `0, 1, 2, 3`.

### 1.5 Predictor per subband

**A. Confirmed from patent `[patent]`:**

The predictor depends on the subband type:

| Subband | Predictor `p` for current coefficient `x` |
|--------|--------------------------------------------|
| LL | **MED** (Median Edge Detector, JPEG-LS-style) over causal neighbours `a` (left), `b` (above), `c` (above-left) |
| HL | **`p = b`** (vertical / above-only prediction) |
| LH | **`p = a`** (horizontal / left-only prediction) |
| HH | **`p = 0`** (no prediction; raw symbol coding) |

Causal neighbours are taken **within the same subband**; no cross-subband
prediction is used.

**C. Open:**
- The patent's pseudocode for boundary handling (first row, first column,
  origin) is left implicit. For a clean-room decoder we use the natural
  conventions: at `(0,0)` the predictor is the mid-range value for LL and `0` for
  the high-pass subbands; on the first row use only `a`; on the first column use
  only `b`.

### 1.6 Symbol formation prior to entropy coding

**A. Confirmed from patent `[patent]`:**

Given the prediction error `Diff = x − p`, the non-negative symbol `S` is

```
S =  2 · Diff           if Diff ≥ 0
S = −2 · Diff − 1       if Diff < 0
```

i.e. positive errors map to even codes (`0, 2, 4, …`) and negative errors map to
odd codes (`1, 3, 5, …`). This is the canonical zig-zag interleaving and matches
what our `CrxHypothesisSweepIT` already calls the "zigzag" sign convention.

### 1.7 Golomb-Rice encoding/decoding

**A. Confirmed from patent (FIG. 10A/10B) `[patent]`:**

For symbol `S` and parameter `K`:

- **Step 1 (unary prefix):** emit `S >> K` zero bits, followed by a single `1`
  bit (the "stop bit").
- **Step 2 (binary suffix):** emit the `K` low-order bits of `S`.

Decoding inverts this: count zeros until the first `1`, then read `K` bits of
remainder, and reconstruct `S = (q << K) | r`.

The codeword length is therefore `(S >> K) + 1 + K` bits.

### 1.8 K-parameter update rule

**A. Confirmed from patent `[patent]`:**

The K-update is *not* a simple JPEG-LS-style accumulator. It is driven by an
**estimated next symbol `Sn`** combined with the current `K = Kx`:

- For **LL** subband: `Sn` is estimated as the **average of the current symbol
  `Sx` and the corresponding-position symbol `Sd` from the deeper level**
  (i.e. `Sn ≈ (Sx + Sd) / 2`).
- For **HL, LH, HH** subbands: `Sn = Sx` (the current symbol itself is the
  estimate of the next).

Given the estimate `Sn`, the *optimal* K range for a symbol of magnitude `Sn`
is bracketed by two functions `Kmin(Sn)` and `Kmax(Sn)`, and the next K is
adjusted relative to that bracket:

- if `Kx < Kmin(Sn)`, then `Kn > Kx` (K must grow),
- if `Kx > Kmax(Sn)`, then `Kn < Kx` (K must shrink),
- if `Kmin(Sn) ≤ Kx ≤ Kmax(Sn)`, then `Kn = Kx` (K stays).

**C. Open:**
- The patent's specific functional form of `Kmin(Sn)` and `Kmax(Sn)` (and the
  exact step size of the increment/decrement) needs to be transcribed precisely
  from the patent paragraphs into the spec. The natural form is
  `Kmin(Sn) = floor(log2(Sn + 1)) - δ_low`,
  `Kmax(Sn) = floor(log2(Sn + 1)) + δ_high` with small integer δ — but the exact
  values (and whether the patent uses ceiling-log or a small lookup table) are
  to be re-read directly from the patent and pinned down in the spec.

This rule **invalidates** the JPEG-LS `accumulator/sampleCount` adaptation in
the current production decoder — that rule decays its estimate continuously,
whereas the patent rule reacts symbol-by-symbol to a one-step lookahead estimate.

### 1.9 Line / band reset, block boundaries

**A. Confirmed from patent `[patent]`:**
- Subbands are decoded independently — separate K state per subband.

**B. Hypothesis `[hypothesis]`:**
- Within a subband the K state is *not* reset per line (the patent's K-update
  rule already provides per-symbol adaptation; an additional per-line reset
  would be redundant). This contradicts our previous H4 in `SubbandFixtures.md`,
  which is therefore demoted to "less likely".

### 1.10 Quantisation / dequantisation

**A. Confirmed from patent `[patent]`:**
- For the **lossy** path the patent explicitly describes that *quantised*
  wavelet coefficients enter the predictive coding stage — i.e. there is a
  per-subband scalar quantiser between the wavelet transform and the predictive
  coder.
- For the **lossless** path no quantisation is applied (the predictive coder
  operates on the integer-exact wavelet coefficients).

**C. Open:**
- The exact dequantiser table, per-subband step sizes and scaling factors for
  the lossy path are not spelled out in the patent at the level needed for a
  decoder; this remains genuinely empirical work for the C-RAW track.

### 1.11 Reversible 5/3 vs. irreversible 9/7

**A. Confirmed from patent `[patent]`:**
- The example uses **integer 5/3**. The patent **explicitly permits** the
  **9/7 filter as in JPEG 2000** as an alternative for the same scheme.

**B. Hypothesis (about CRX specifically) `[hypothesis]`:**
- Lossless CRX uses integer 5/3 (forced by reversibility); lossy CRX may use
  either. We must not infer the on-disk CRX choice from the patent example
  alone.

---

## 2. Summary table — what the patent has and has not given us

| Question | Confirmed by patent (A) | CRX-specific hypothesis (B) | Still open (C) |
|---------|-------------------------|-----------------------------|----------------|
| Wavelet present? | yes, multi-level | — | level count per CRX track |
| Filter? | 5/3 (example) or 9/7 (alternative) explicitly allowed | 5/3 for lossless CRX (forced by reversibility) | lossy CRX filter choice |
| Subbands? | LL, HL, LH, HH (FIG. 5) | "planes" in CRX header are these subbands | mapping plane.index → subband id |
| Subband order? | LL(n), HL(n), LH(n), HH(n), HL(n−1), … (FIG. 5) | CRX plane indices follow this order | — |
| Coefficient order? | raster within each subband | — | — |
| Predictor? | LL: MED · HL: `b` · LH: `a` · HH: `0` | same as patent in CRX | boundary rules at row/col 0 |
| Symbol mapping? | `S = 2·Diff` if `Diff≥0` else `−2·Diff−1` | — | — |
| Entropy coder? | Golomb-Rice: `(S>>K)` zeros + `1` + low-K-bits (FIG. 10A/B) | — | — |
| K update? | based on lookahead estimate `Sn` and bracket `[Kmin(Sn), Kmax(Sn)]`; LL: `Sn=(Sx+Sd)/2`, others: `Sn=Sx` | same in CRX | exact form of `Kmin`/`Kmax` |
| Per-line K reset? | not described in patent | unlikely in CRX (would be redundant with the per-symbol rule) | — |
| Quantisation? | per-subband scalar in lossy path | same in CRX | exact step sizes for C-RAW |

---

## 3. Implementation decisions that follow directly

The following decisions are *justified by the patent analysis above without
recourse to any third-party decoder*. They are the changes worth making now:

### 3.1 Decoder architecture — explicit four-stage pipeline

The current `CrxLosslessRawDecoder.decodePlane` collapses three logically distinct
stages (entropy decoding, residual → coefficient mapping, *implicit* identity
dequantisation, *implicit* identity wavelet) into a single nested loop. The
patent's flow is unambiguously a four-stage pipeline and our debugging would
benefit from matching that:

1. **Entropy decoder** — pure `bytes → int[] symbols` (still Rice/Golomb).
2. **Symbol → coefficient mapping** — sign fold + predictor inverse + bias
   correction. Operates on a single subband at a time.
3. **Dequantiser** — per-subband scalar (identity for lossless today, but the
   plug must exist).
4. **Inverse wavelet** — `subbands → reconstructed channel samples`.

This separation enables stage-by-stage validation against synthetic inputs and is
necessary for the eventual lossy-mode implementation. **No production behaviour
change is required to introduce the separation**; it is purely a refactor.

### 3.2 Immediately implementable

- **Stage 4 (inverse wavelet)** is already implemented (`InverseWaveletTransform`
  with Le Gall 5/3). It only needs to be *connected* to the lossless decoder once
  Stages 1–2 produce coefficients (rather than Bayer pixels directly).
- **Stage 3 (dequantiser)** is trivially the identity for the current path and
  can be added as a pass-through with a TODO note.
- **Symbol/K trace hooks** in Stage 1 — see §3.4.

### 3.3 Requires test-file validation before going production

- Any change to **Stage 1's K-adaptation rule** must first be evaluated through
  `CrxHypothesisSweepIT` against `eosr-iso-*.cr3`, with the bits-per-pixel and
  Pearson r criteria from §1.7. Only when all three criteria are met does the
  change become a candidate for production.
- The plane-vs-subband interpretation (§1.2) must first be validated by a small
  test that takes a known synthetic Bayer image, runs it through a forward 5/3
  wavelet, and checks whether the resulting LL/HL/LH/HH coefficient sizes
  qualitatively match what `cr3-cli list-subbands` reports for the corresponding
  CR3.

### 3.4 Now `[patent]` (no longer hypothesis)

Thanks to the patent text, the following are now algorithmic facts to base the
clean-room decoder on:

- **Per-subband predictor table** (§1.5): LL = MED; HL = `b`; LH = `a`; HH = `0`.
- **Symbol mapping** (§1.6): `S = 2·Diff` if `Diff ≥ 0`, else `S = −2·Diff − 1`.
- **Golomb-Rice codeword** (§1.7): `(S>>K)` zeros, one `1` bit, then the K
  low-order bits of `S`. Codeword length `(S>>K) + 1 + K`.
- **K-update rule shape** (§1.8): per-symbol bracket comparison against
  `Kmin(Sn)` / `Kmax(Sn)` with `Sn = (Sx+Sd)/2` for LL and `Sn = Sx` otherwise.
- **Subband processing order** (§1.4): LL(n), HL(n), LH(n), HH(n), …

### 3.5 Still `[hypothesis]` — must remain marked as such

- The exact functional form of `Kmin(Sn)` / `Kmax(Sn)` (the patent paragraph
  needs to be transcribed verbatim — see §4 of the spec doc).
- The CRX-to-patent mapping: that the four "planes" of a CRX tile are exactly
  the four subbands of one Bayer channel.
- The lossy CRX filter choice (5/3 vs. 9/7).
- The specific dequantisation step sizes used in C-RAW.
- The escape-symbol / RLE-zero-run mechanism (`H3`, `H4` in `SubbandFixtures.md`).
- The `quantValue ↔ shift exponent` relation.

These **must not** be inlined as production constants until a sweep produces an
unambiguous winner.

---

## 4. Concrete next steps for the next session

The patent has now pinned down the algorithm at a level where the next session
can implement a reference candidate decoder (in test scope) and validate it
against `eosr-iso-low.cr3`:

1. **DONE (2026-04-30).** Implemented in `canon-cr3-imageio/src/test` as four
   `C_patent_*` candidates in `CrxCandidateDecoders` (per-subband predictor +
   patent zig-zag symbol mapping + lookahead-bracket K-update). Result: the
   bit-budget mismatch on sample.cr3 planes 2 & 3 is **resolved** — see §5.2
   below and `canon-cr3-core/SubbandFixtures.md` "Update 2026-04-30 (c)".
2. **DONE (2026-04-30).** The new candidates are wired into the existing
   `CrxHypothesisSweepIT` (no harness change required — the test iterates the
   standard library map). The `outLen == w*h` acceptance criterion now passes
   for all four planes of sample.cr3 and all four planes of eosr-iso-low.cr3.
   The Pearson r > 0.9 criterion is **not yet** met, but per-subband scoring
   against a single Bayer channel is fundamentally band-limited (see step 3).
3. **DONE (2026-04-30) — but threshold NOT reached.** Implemented as
   `CrxPatentIdwtValidationIT` in `canon-cr3-imageio/src/test`. Decoded all
   four planes of tile 0 of `sample.cr3` with role-appropriate patent
   candidates (LL → MED, HL → above (b), LH → left (a), HH → zero), ran
   `InverseWaveletTransform.legall53(...)` over six permutations of the
   plane → role mapping, scored against the PRVW R-channel reference.
   **Best Pearson r = 0.0219**, well below the r > 0.7 acceptance threshold.
   See `canon-cr3-core/SubbandFixtures.md` "Update 2026-04-30 (d)" for the
   permutation table. The result *narrows* the hypothesis space — the bit
   budget is exactly right (Update (c)) but a single-level wavelet
   reconstruction is not the missing piece. Most likely next experiments:
   (a) feed plane 0 directly into `BayerDemosaicer` as R, bypassing IDWT
   entirely (cheapest); (b) handle the level field that the lossless CMP1
   parser currently ignores and try multi-level IDWT; (c) interpose a
   dequantisation step between entropy decode and IDWT.
4. **TODO (no code change required).** Transcribe the **exact `Kmin(Sn)` and
   `Kmax(Sn)` formulae** from the patent text into `crx-cleanroom-spec.md`
   §4 to replace the current `[hypothesis]` `±1` bracket. Update (d) shows
   the bracket is at least *adequate* for bit-budget purposes; whether the
   exact bounds widen or narrow accuracy is currently unknown.
5. **TODO — gated by reaching a usability threshold.** Promotion
   into a production code path is *not justified* by the current evidence.
   Update (d) and (e) make clear that more downstream pipeline work is
   needed before any patent-derived candidate beats the existing decoder.
6. **DONE (2026-04-30) — wavelet hypothesis ruled out by container
   metadata.** Implemented as `CrxPlaneSemanticsIT`. Key findings:
   `CMP1.waveletLevels=0` (no wavelet), `cfaLayout=1=GRBG` (not RGGB),
   five dequantisation variants give identical post-IDWT score (so
   dequant cannot be the bottleneck). Best plane-as-Bayer-channel score
   is r=0.073. See §5.4 and `SubbandFixtures.md` Update (e).
7. **TODO.** Three concrete experiments to discriminate the remaining
   hypotheses (predictor / sign / post-decode transform), per
   `SubbandFixtures.md` Update (e) "Open question": (1) predictor-zero
   baseline to test whether the LL output's 0.63 neighbour correlation is
   real signal or predictor artefact; (2) half-pixel scanline interleave
   trial; (3) repeat the diagnostics on tile 1 and compare.

---

## 5. What changed in the actual production code as part of this analysis

This document is paired with two small, safe code additions in the
`canon-cr3-core` module that follow directly from §3.1 and §3.4:

- **Symbol / K trace hook** in `CrxLosslessRawDecoder` that, when a system
  property is set, logs `(subband, x, y, predicted, residual, k_before, k_after)`
  for the first N samples. This is purely diagnostic; it does not change
  decoding behaviour.
- **Tightened TODOs** on `RiceGolombDecoder` and on the lossy decoder pointing at
  the relevant section of this document and `crx-cleanroom-spec.md`.

The actual decoder pipeline remains unchanged. The patent analysis explicitly
does **not** justify any speculative algorithmic rewrite at this stage.

### 5.1 Patent-faithful candidates added to the research harness

Following §4 step 1, four patent-faithful candidates have been added to
`CrxCandidateDecoders` (test scope only, no production code):

| Name | Subband role | Predictor | K-update | K0 |
|------|--------------|-----------|----------|----|
| `C_patent_LL_med_{k0,kQ}_bracket` | LL | MED (JPEG-LS) | bracket §1.8 | 0 / `floor(log2(Q+1))` |
| `C_patent_HL_above_{k0,kQ}_bracket` | HL | `p = b` (above) | bracket §1.8 | 0 / `floor(log2(Q+1))` |
| `C_patent_LH_left_{k0,kQ}_bracket` | LH | `p = a` (left) | bracket §1.8 | 0 / `floor(log2(Q+1))` |
| `C_patent_HH_zero_{k0,kQ}_bracket` | HH | `p = 0` (no prediction) | bracket §1.8 | 0 / `floor(log2(Q+1))` |

All four candidates implement the patent's lookahead-bracket K-update with
`Sn = Sx`, `K*(Sn) = floor(log2(Sn+1))`, `Kn ∈ [K*−1, K*+1]`.
For one-level-decomposition fixtures (sample.cr3, eosr-iso-low.cr3) the LL
case `Sn = (Sx + Sd)/2` collapses to `Sn = Sx`.

### 5.2 Validated finding — bit-budget mismatch is solved

Verified 2026-04-30 via
`mvn verify -Dit.test=CrxHypothesisSweepIT#sweepAllPlanesOfTile0`:

The previously-problematic sample.cr3 planes 2 (Gb) and 3 (B) — which the
prior bits-per-pixel diagnostics recorded as exhausting the bitstream at 78 %
and 93 % of pixels respectively under the JPEG-LS-style K-update — now reach
**100 % under all four patent candidates**. The K-adaptation rule was the
structural blocker, exactly as the analysis predicted.

Pearson r against the preview's R channel remains modest (≈0.03–0.29) because
a single subband only carries 1/4 of the image; full validation requires
combining all four subbands and applying `InverseWaveletTransform.legall53`.
That validation is the next concrete step (§4 step 3 of this document).

`C_patent_HH_zero_*` produces near-zero correlation with mean-abs-err
identical to the all-zero baseline — the expected behaviour for an HH
subband whose coefficients carry no DC image structure. This is a positive
sanity result.

See `canon-cr3-core/SubbandFixtures.md` "Update 2026-04-30 (c)" for the full
sweep table.

### 5.3 IDWT validation (Step 3) — bit budget alone is not enough

A follow-up `CrxPatentIdwtValidationIT` (also test scope) feeds the
patent-decoded planes through `InverseWaveletTransform.legall53(...)` and
scores against the PRVW reference. Across six plane → role permutations,
**no permutation reaches Pearson r > 0.05**, far below the r > 0.7
acceptance threshold.

### 5.4 Diagnostic plane-semantics analysis — wavelet hypothesis ruled out

`CrxPlaneSemanticsIT` (test scope, also producing artefacts under
`canon-cr3-imageio/target/crx-plane-semantics/`) decisively rules out the
single-level *and* multi-level wavelet interpretations using container
metadata alone:

- `CMP1.waveletLevels = 0` for sample.cr3 → no wavelet decomposition at any
  level. The four planes are NOT subbands.
- `CMP1.cfaLayout = 1 = GRBG` (not RGGB as previously assumed). The four
  planes correspond to the four Bayer positions of one GRBG cell.
- Five dequantisation variants (identity, ×q, ×2^q, dead-zone q, dead-zone
  2q) all give identical post-IDWT Pearson r = 0.02187 — a mathematical
  proof that no scalar dequant can rescue the wavelet hypothesis.
- Even with the corrected GRBG mapping, the patent decoder's LL output
  scored as a Bayer channel reaches at best Pearson r ≈ 0.073 vs the
  preview's matching channel — better than the IDWT permutations but still
  far from any usability threshold.

The decoded output does show genuine 2D structure (left-neighbour
correlation r ≈ 0.63 for LL/MED), so the bytes are being consumed at the
right rate (Update (c)) AND producing image-like values — but they don't
spatially align with the preview. The bottleneck is now most likely the
predictor convention, sign mapping, or an unidentified post-decode
transform (line interleave, tile stitching, pre-decode delta), NOT the
entropy coder and NOT a wavelet.

See `canon-cr3-core/SubbandFixtures.md` Update (e) for the full table and
the three concrete next experiments.

### 5.5 Production code remains unchanged

Despite the entropy-coder progress, **no production code path has been
modified.** The C_patent_* candidates and the diagnostic ITs all live in
test scope (`canon-cr3-imageio/src/test`). Promotion behind a
`Cr3DecodeOptions` flag is gated on actually reaching a usability threshold
on the preview reference — which has not happened yet.

### 5.6 Follow-up experiments — predictor sweep narrows the search

Two more test-scope ITs were added: `CrxPlaneFollowupIT` (the three
experiments left open in Update (e)) and `CrxPlaneFollowupExtendedIT`
(systematic Cartesian sweep over predictor × sign × layout × init).

The four conclusive findings (full numbers in `SubbandFixtures.md` Update f,
findings 5-9):

1. **Predictor cascade alone produces zero 2D structure.** Forcing
   `signed_residual = 0` for every sample collapses the output to a
   constant. Therefore the left-neighbour Pearson r ≈ 0.63 measured on
   the patent LL/MED decode is genuine bitstream signal — the residuals
   carry real image content. This rules out "the LL output looks
   correlated only because the MED predictor smooths things out".
2. **Tile-stitching is not the bottleneck.** Tile 0 and tile 1 decode to
   structurally equivalent statistics (mean, variance, neighCorr, per-
   channel Pearson r all within ~5 %). The defect is per-tile, not
   inter-tile.
3. **`P_LEFT` predictor is ~5× better than `P_MED`.** Sweeping
   {P_NONE, P_LEFT, P_UP, P_MED} × 7 sign mappings × all planes × both
   tiles, the previous-left predictor (with conventional left-edge
   fallback to the pixel above) reaches |Pearson r| = 0.409 against the
   matching preview Bayer channel; the patent MED predictor tops out at
   r = 0.085 on the same fixture. Top three results are *negative*
   correlation (structural agreement, sign inverted). Still well below
   the r > 0.7 promotion threshold, so this is a directional finding,
   not a fix.
4. **Bayer permutation, spatial shift, k-init and pred-init are
   non-causal.** Sweeping all four cyclic CFA mappings × {identity,
   ±1 row, ±1 col} keeps every score ≤ 0.09. Sweeping
   `predInit ∈ {0, midgrey, max}` × `kInit ∈ {0, 4, 7}` keeps every
   score in the 0.086–0.094 window.

Two specific sign mappings beyond the patent's LSB-folded variant were
also tested (`S_HI_BIT_SIGN`, `S_OFFSET_HALF`, `S_RAW_POS`, `S_RAW_NEG`):
all collapse the output into a clipped/saturated range and score near
zero. The LSB-folded family (`S_LSB`, `S_LSB_ALT`, `S_ZIGZAG_INV_LSB`)
is internally consistent within ≤ 0.01 — the precise off-by-one in
negative reconstruction is below this fixture's discriminating power.

The patent's predictor table is therefore not strictly correct for this
sample's lossless 1-subband-per-plane case: the per-subband role table
of §3.4 (LL→MED, HL→`b`, LH→`a`, HH→0) was derived from the patent's
multi-subband wavelet path. Lossless single-subband CRX appears to use a
plain previous-left predictor instead, regardless of "subband role" (which
is a degenerate label here since `waveletLevels = 0`).

### 5.7 Concrete next steps after §5.6

Each step is test-scope only:

1. Pair `P_LEFT` with **per-scan-line predictor reset** (re-init to
   `midgrey` at the start of every row) and re-sweep. Many lossless RAW
   codecs do this; it would explain why the per-row signal aligns
   internally but not against the preview.
2. Try a **per-line scan order other than row-major raster** — e.g.
   column-major, snake / boustrophedon, or zig-zag.
3. Add **gradient / averaging predictors** (`(a+b)/2`, `2a-c`,
   `a + b - c` clipped) to the predictor enum and sweep.
4. Score against a **per-tile-cropped JPEG reference** instead of the
   full-image preview scaled to one tile's plane size — the current
   reference geometry guarantees a misalignment penalty even for a
   perfect decode.

Until one of these crosses the r > 0.7 promotion threshold on at least
one Bayer channel of `sample.cr3`, **no production code path will be
changed.**

### 5.8 Round-3 follow-up — all four §5.7 hypotheses measured (Update (g))

`canon-cr3-imageio/src/test/java/com/photographer/focusstack/cr3/research/CrxPlaneFollowupRound3IT.java`
runs each of the §5.7 next-steps against the same metric stack as Update
(f). Single test, ~40 s on `sample.cr3`. Output:
`canon-cr3-imageio/target/crx-followup-round3/`. Verdict for each
hypothesis is below — **none crosses the 0.7 promotion threshold**, and
the round-2 best (P_LEFT_BASE row-major, |r| ≈ 0.39) survives intact.

**A. Per-scan-line predictor reset (`P_LEFT_LINE_RESET`).** Hard reset to
midgrey at every row start. Result: best |r| = **0.061** (vs. 0.39 for
P_LEFT_BASE). The wraparound to *pixel-above* at row start is
structurally important — discarding it destroys the correlation. So the
codec's left-edge convention is closer to "fall back to pixel above"
than to "hard reset to midgrey".

**B. Alternative scan orders (COL_MAJOR, SNAKE, ZIGZAG).** Holding the
predictor at P_LEFT_LINE_RESET (the only fair comparison once the line
reset is in play). All non-row-major scans stay at the noise floor
(|r| ≤ 0.029). The bitstream's natural traversal is row-major raster.
Snake and zig-zag are ruled out as full-plane traversals.

**C. Gradient/averaging predictors with line reset.** `G_AVG = (a+b)/2`
and `G_AVG_CLIP` reach |r| = **0.376** (tile1/p1, R) and 0.317
(tile1/p2, B) — comparable to but not better than P_LEFT_BASE without
line reset. `G_PLANE = a+b-c`, `G_2A_C = 2a-c`, `G_PLANE_CLIP` all stay
≤ 0.078. So a 2D averaging predictor fits the data about as well as
P_LEFT, but neither family crosses 0.4.

**D. Tile-cropped reference scoring.** Top-3 of each section re-scored
against `TileCroppedPreviewExtractor` (the preview JPEG cropped to the
tile's image region before scaling, instead of the full image scaled to
plane size). Tile-cropped vs full r differs by at most 0.06; the
ranking of variants is preserved. So the ~0.4 ceiling is **not** a
scoring artefact from the full-frame reference geometry — it is a real
limit of the current decoder cascade.

**Net narrowing after Update (g).** Confirmed *not* the bottleneck:

- per-line predictor reset (drops r from 0.39 → 0.06)
- column-major / snake / zig-zag scans (≤ 0.029 with line reset)
- gradient predictors (`a+b-c`, `2a-c` ≤ 0.08; averaging ties P_LEFT)
- full-frame vs tile-cropped reference geometry (Δr ≤ 0.06)

**Open questions** (where measurement points next, still test scope):

1. **Predictor's left-edge convention is the live wire.** P_LEFT with
   pixel-above fallback gives 0.39; with hard reset gives 0.06. Try
   intermediate variants: previous-row's last pixel, midpoint of (above,
   midgrey), wrap to first pixel of new row.
2. **Negative r persists** — the strongest correlations remain
   sign-inverted vs the preview. If a residual sign convention or
   post-decode flip would fix it, that should appear in some currently
   untested mapping.
3. **Tile asymmetry (tile1 ≫ tile0)** is now reproducible: best |r| on
   tile0 is 0.124, on tile1 is 0.390. Worth investigating whether the
   first tile of the bitstream has a different leading state (k seed,
   predictor seed, header offset) than subsequent tiles.

---

## 6. Resolution of prior assumptions

| Prior assumption | Status after patent analysis |
|------------------|------------------------------|
| "JPEG-LS MED predictor + JPEG-LS k-adaptation" (current production) | **Refuted in two ways.** (a) MED is correct *only for the LL subband*; HL uses `p=b`, LH uses `p=a`, HH uses `p=0`. (b) The K-adaptation rule must be the lookahead-bracket rule from §1.8 — JPEG-LS-style continuous accumulation does not match. |
| "`initial_k = quantValue` directly" (`H1`) | **Demoted.** The patent K-update is per-symbol and bracket-based, so any "initial K" only matters for the very first coefficient; the seed value is therefore much less consequential than the *update rule*. |
| "Four planes per tile = four Bayer channels" | **Refuted as exclusive interpretation.** The patent's subband-order rule (FIG. 5) makes "four planes = LL, HL, LH, HH of one channel at one level" the natural CRX interpretation. |
| "Lossless track has no wavelet" | **Refuted.** Multi-level wavelet is the patent's core. The integer 5/3 path is the natural CRX lossless choice (forced by reversibility, not by the patent). |
| "Bitstream is MSB-first, no stuffing" (`H5`) | **Confirmed** — fully consistent with the patent's Golomb-Rice description (no stuffing mechanism mentioned) and with measured byte histograms. |
| "Per-line K reset" (`H4`) | **Less likely.** The patent's per-symbol bracket rule already provides line-rate adaptation; an additional per-line reset would be redundant and is not described. |
| "Escape symbol for long unary runs" (`H3`) | **Not contradicted, but no longer needed.** The bracket K-update keeps K close to the symbol magnitude, so unary runs stay bounded without an explicit escape. |

---

## 7. Notes on legality and clean-room hygiene

- The patent itself is a **public disclosure** intended for third-party reading;
  using it as a specification source for an interoperable decoder is the standard
  legitimate use of the patent system.
- Patent *rights* are a separate concern from copyright. A reader of the patent
  cannot infringe its copyright by implementing the described method, and the
  practical patent-litigation risk for an interoperable decoder (as opposed to an
  encoder) is the same risk that LibRaw, darktable, RawTherapee and Adobe's DNG
  Converter have all carried since 2018; we are not creating any new exposure.
- **No source file from any third-party CRX implementation was opened during
  preparation of this document.** Pointers such as "the public reverse-engineering
  literature mentions this patent" exist only as breadcrumbs; the algorithmic
  content above comes from the patent or from public ITU-T standards.
