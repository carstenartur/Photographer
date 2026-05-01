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

### 5.9 Round-4 follow-up — tile-leading-state is the real bottleneck (Update (h))

Driven by Update (g) Finding #4 ("tile1 dominates tile0 in correlation
under every variant"), `CrxPlaneFollowupRound4IT` measures four
hypothesis families on `sample.cr3`. Output dir
`canon-cr3-imageio/target/crx-followup-round4/`. Strict test scope.

**Section E — Tile-leading-state.** Holding predictor = `P_LEFT_BASE`
and varying only the very first decoded pixel of each tile (one
residual is still consumed so K-state is unchanged):

| variant         | tile0 p0 |r| | tile1 p0 |r| |
|-----------------|--------------|--------------|
| **`LEAD_ZERO`** | **0.4904**   | **0.5605**   |
| `LEAD_BIAS_LO`  | 0.1786       | 0.3999       |
| `LEAD_NEG_FIRST`| 0.1247       | 0.3903       |
| `LEAD_PREDINIT` (round-3 baseline) | 0.1241  | 0.3902       |

Setting the very first pixel to **0** lifts tile1/p0 from 0.39 → 0.56
and tile0/p0 from 0.12 → 0.49. This is the **first variant since the
cleanroom investigation began that materially exceeds the |r| ≈ 0.4
ceiling**, and the **first variant under which tile0 and tile1 reach
comparable magnitudes** — the persistent tile asymmetry was an
artefact of the wrong first-pixel rule, not a per-tile bitstream
difference.

The mechanism: in `P_LEFT_BASE` row 0 is `result[0] + Σ residuals`,
and the left-edge fallback for row 1+ is the pixel above. So the
first pixel's value propagates along the full first column and from
there into every row. A wrong `result[0]` shifts everything by a
constant, but the constant alone is invisible to Pearson r — what
the metric sees is the *clipping* induced by the wrong constant. With
`LEAD_ZERO` clipping is minimised because the propagated baseline
matches Canon's actual first-pixel coding convention.

**Section F — Warmup-prefix (negative result).** Forcing the first
N ∈ {1,2,4,8,16} samples of every row to use `predInit` instead of
the raster-left predictor collapses |r| from 0.39 to 0.01–0.06. There
is no row-start warmup region with a different predictor rule.

**Section G — Tile differential.** Without `LEAD_ZERO`, tile0 stays
near the noise floor on every plane; with `LEAD_ZERO` it matches
tile1's magnitude. Confirms the tile-asymmetry was a leading-state
artefact, not a structural bitstream difference.

**Section H — Local polarity / bias (sanity check).** Pearson r is
invariant under translation; sweeping additive bias in
{-2048, -1024, 0, +1024, +2048} produces no change in |r| beyond
clipping artefacts. Confirms the metric is correctly implemented and
the Section-E gain is structural.

**Net narrowing after Update (h).** The only remaining gap to the
0.7 promotion threshold is now likely the residual sign convention
(all top variants are *negative* r) and a possible per-row
leading-state rule analogous to the per-tile one. Concrete next
steps:

1. **`ROW_LEAD_ZERO`** — apply `LEAD_ZERO` per row, not just per tile
   (the warmup experiment ruled out forcing N row-start samples to
   `predInit` but not forcing only the very first row pixel to 0).
2. **K-reset per tile** combined with `LEAD_ZERO`.
3. **Sign-mapping flip** under `LEAD_ZERO`: negative-r persists in
   every Section-E winner, suggesting a single-bit sign-convention
   issue orthogonal to the leading-state issue.

Promotion threshold remains `|r| > 0.7`. Currently 0.56 ⟹ no
production code change.

### 5.10 Round-5 follow-up — first-sample semantics, second-sample rules, error localization (Update (i))

Driven by Update (h)'s next-step list, `CrxPlaneFollowupRound5IT`
measures five hypothesis families on `sample.cr3`. Output dir
`canon-cr3-imageio/target/crx-followup-round5/`. Strict test scope.

**Section E5 — `LEAD_ZERO` scope.** Holding predictor = `P_LEFT_BASE`
and varying *where* the forced-zero is injected (one residual is
always still consumed so K-state is unchanged):

| variant                      | tile0 p0 |r| | tile1 p0 |r| |
|------------------------------|--------------|--------------|
| `LZ_TILE_FIRST` (round-4)    | **0.4904**   | **0.5605**   |
| `LZ_PLANE_FIRST`             | 0.4904       | 0.5605       |
| `LZ_ROW_FIRST` (`ROW_LEAD_ZERO`) | 0.1009   | 0.0884       |
| `LZ_BLOCK_FIRST_64/128/256/512`  | ≤0.10    | ≤0.10        |

`LZ_PLANE_FIRST` is a control: for `sample.cr3` (1 subband per plane
per tile) it is structurally identical to `LZ_TILE_FIRST` and the
measurements confirm this. **`ROW_LEAD_ZERO` is decisively refuted**
— forcing zero at every row start collapses |r| from 0.49/0.56 to
0.10/0.09, the same regime as Update (g)'s `P_LEFT_LINE_RESET`.
Block-first variants are equally fatal. The forced-zero rule is
strictly a **per-tile** convention.

**Section F5 — Second-sample rule (negative result).** With
`LEAD_ZERO` active, replacing the second decoded sample's predictor
with one of {`ABOVE`, `ZERO`, `AVG=(left+above)/2`,
`GRAD=2·left−above`} changes |r| by at most **0.0004** — entirely
within numerical noise. There is no second special-case rule
immediately after the first pixel.

**Section G5 — First-row / first-column override (negative result).**
With `LEAD_ZERO` active, additionally overriding the entire first row
of the tile (`ROW0_PREDINIT`, `ROW0_ZERO`,
`ROW0_LINEAR_RAMP_FROM_LEFT`) gives at most Δ|r| = +0.0014 — within
noise. Overriding the entire first column collapses |r| toward zero
under every choice. Combining `ROW0_*` with `COL0_*` (Section I5)
collapses |r| to ≈0 on every plane and tile — strong negative result.

**Section H5 — Residual error localization.** With `LEAD_ZERO`
baseline, the per-pixel absolute error vs the preview reference
(after sign-fix and per-tile LSQ rescale) on plane 0 has:

| zone          | tile0 p0 RMS | tile1 p0 RMS |
|---------------|--------------|--------------|
| row 0         | 27.9         | (similar order — see TSV) |
| col 0         | 21.3         | (similar order) |
| bulk (interior) | 13.9       | (similar order) |
| any column mod 16 | 13.81–13.93 | flat |

**Findings.** (a) Row 0 has 2× the bulk RMS, col 0 has 1.5× the bulk
RMS — error *is* concentrated at the tile-leading boundary, exactly
where the forced-zero rule operates. (b) The column-mod-16 RMS is
**flat to within 0.7 %**, ruling out a 16-pixel-periodic
reconstruction artefact at the LEAD_ZERO baseline. (c) The bulk error
is the dominant integrated contributor and is *not* localized — it is
distributed across the whole interior, which is consistent with the
remaining gap to |r|=0.7 being a *global* sign / mapping issue rather
than a *local* boundary fix.

**Section I5 — Top-candidate refinement (negative result).** Combining
`LZ_TILE_FIRST` + `second=ABOVE` + `ROW0_PREDINIT` + `COL0_PREDINIT`
collapses |r| to ≤0.06 on every (tile, plane). Naive stacking of the
Section E5/F5/G5 best variants is destructive: each override consumes
a residual whose K-state evolution then no longer matches Canon's
encoder. This rules out any combination of these surface-level
interventions as the path to |r| > 0.7.

**Net narrowing after Update (i).** The remaining gap to 0.7 is
*not* explained by any small additional first-sample/early-sample/
first-row/first-column rule. The most likely remaining causes are:

1. The residual sign convention (all top variants remain *negative*
   — every Section-E5 winner is signed `-0.49` / `-0.56`).
2. A global Bayer-channel mapping mismatch between the decoded plane
   and the preview reference (compounded with the sign issue).
3. A non-`P_LEFT_BASE` predictor that is *also* `LEAD_ZERO`-friendly
   (none of the Round-1/2/3 alternatives qualify in this respect).

Concrete next steps:

1. **`mapSignLsb` flip experiment under `LEAD_ZERO`** — re-run the
   single-bit sign-convention sweep with the Round-4 winner active;
   negative-r persistence is now strong enough to attribute to a
   single bit in residual mapping, not a 50/50 convention guess.
2. **Plane→Bayer-channel permutation re-sweep** under `LEAD_ZERO` —
   Update (f) tested permutations under `predInit`; the |r| floor was
   too low to discriminate. With LEAD_ZERO at 0.49/0.56 the tile0/p2
   and tile1/p2 channels (B) are now also above 0.25, so a
   permutation that aligns p0=Gr↔Gb may push the green channels
   coherently.
3. **Per-tile K-reset combined with `LEAD_ZERO`** — the only
   remaining unmeasured surface-level intervention.

Promotion threshold remains `|r| > 0.7`. Currently 0.56 ⟹ no
production code change.

### 5.11 Round-6 follow-up — sign-bit flip, plane→Bayer re-sweep, per-tile K-init (Update (j))

Driven by Update (i)'s next-step list, `CrxPlaneFollowupRound6IT`
measures three hypothesis families on `sample.cr3`. Output dir
`canon-cr3-imageio/target/crx-followup-round6/`. Strict test scope.

**Section J6 — Sign-bit flip under `LEAD_ZERO_TILE_FIRST`.** Holding
predictor = `P_LEFT_BASE` and lead-zero scope = `LZ_TILE_FIRST`, vary
how the residual sign is mapped (`NONE`, `GLOBAL_FLIP`,
`PER_PLANE_FLIP` greens-only, `PER_TILE_FLIP` tile1-only,
`SYMBOL_LSB_FLIP`, `RESIDUAL_NEG`):

- The round-4/5 winner (sign=`NONE`, |r|=0.4904 / 0.5605 on tile0 /
  tile1 Gr) **remains the global maximum**.
- Flipping the residual sign does *not* simply negate r — the
  predictor depends on previously reconstructed pixels, so a sign
  flip yields a different reconstruction with generally lower |r|.
- A real R-channel signal emerges on **tile1 p1**: |r| jumps from
  0.055 (NONE) to 0.524 under `GLOBAL_FLIP` / `PER_TILE_FLIP` /
  `RESIDUAL_NEG`. But the same flip *destroys* the tile1 p0 signal
  (0.5605 → 0.034). No single global rule captures both — most
  naturally read as a ±sign ambiguity in the per-plane reference fit
  rather than as a Canon encoder convention.
- `SYMBOL_LSB_FLIP` (invert LSB *before* `mapSignLsb`) tracks
  `GLOBAL_FLIP` to within 0.02 |r|; symbol-near and residual-near
  conventions are not structurally independent on this fixture.

**Section K6 — Plane→Bayer permutation re-sweep under `LEAD_ZERO`.**
All 24 permutations evaluated, scored by Σ|r| over the four
(plane, channel) pairs:

| rank | tile0 perm                       | tile0 Σ\|r\| | tile1 perm | tile1 Σ\|r\| |
|------|----------------------------------|--------------|------------|--------------|
| 1    | `[1023]`                         | **0.9484**   | `[1023]`   | **0.9325**   |
| 2    | `[2013]`                         | 0.9484       | `[2013]`   | 0.9325       |
| 3    | `[1032]` (existing GRBG mapping) | 0.9402       | `[1203]`   | 0.9250       |

The new top permutation `[1023]` swaps p2↔p3 (Gb vs B) relative to
the existing `Cmp1Box`-derived GRBG mapping `[1032]`. Δ Σ|r| = +0.008
(tile0) / +0.013 (tile1) — small but reproducible across both tiles.
**The per-channel maxima (0.56 on Gr) are unchanged**, so this is
not a promotion event; updating production `cfaLayoutMap(GRBG)`
based on a Δ Σ|r| signal alone would not be defensible.

**Section L6 — Per-tile K-init under `LEAD_ZERO`.** Sweeping
`K_INIT ∈ {0, 1, 2, 3, 4, 5, 7}` and a `K_INIT_FROM_FIRST` variant:

- **Tile0 p0 (Gr) lifts from |r|=0.4904 → 0.5409 at K_INIT_4**
  (Δ = +0.05) — first per-tile improvement of Round-6 that exceeds
  noise on the dominant Gr plane.
- **Tile0 p1 (R) lifts from |r|=0.046 → 0.484 at K_INIT ∈ {2, 3, 4}**
  — almost a 10× jump on a previously near-uncorrelated plane,
  the largest single-step gain since the predictor was identified
  in Update (f). Confirms patent §1.8: K-update is per-symbol, but
  the *seed* matters when the encoder's true K* at tile start is
  not 0.
- Tile1 p0 (the global maximum) is K-init-invariant for K ∈ {0, 1, 2}
  and decays gracefully above — bracket K-update converges quickly
  once a high-magnitude symbol arrives.
- **No single K-init wins across all (tile, plane) combinations**
  (tile0/p0 wants 4; tile1/p0 wants ≤ 2). A correct implementation
  needs either per-plane-derived K-init or a different bracket — both
  consistent with the patent's `kInit ∈ [K* − 1, K* + 1]` allowance.
- `K_INIT_FROM_FIRST` (derive K from the first decoded residual at
  K=0) is not a clean win on this fixture because the very first
  residual on most planes is small (typical magnitude ≤ 2), so the
  derived K duplicates K_INIT_0..2.

**Net narrowing after Update (j).** Ruled out:

- A single global residual-sign flip raising the maximum |r| or
  working consistently across all (tile, plane).
- A symbol-near (LSB-of-codeword) sign convention being structurally
  different from a residual-near one.
- A major plane→Bayer rearrangement under `LEAD_ZERO` (best
  permutation Δ = +0.008 Σ|r|, no per-channel gain on Gr/R).
- Per-tile K-reset alone exceeding |r| > 0.7 (best per-tile gain is
  Δ = +0.05).

New positive findings:

1. **Per-plane K-init is meaningful**: tile0 p1 lifts 10× from
   |r|=0.046 to 0.484 at K_INIT ∈ {2, 3, 4}.
2. **Tile-asymmetry resurfaces in K-space**: tile0 prefers K_INIT_4,
   tile1 prefers K_INIT_0–2. The Update (h) closure of the tile0/
   tile1 |r| asymmetry by `LEAD_ZERO` did *not* close the K-init
   preference asymmetry; the two are independent.

Concrete next steps:

1. **Per-plane K-init sweep** — vary K-init independently per plane
   within a tile (24 combinations per tile). The L6 result already
   suggests a non-trivial best assignment exists.
2. **K-init derived from `CMP1.quantValue`** — the patent allows
   `K_init ≈ floor(log2(quantValue))`; for `sample.cr3` (q=4)
   that gives K=2, which is in the L6 winning band on tile0 p1.
3. **Combined LEAD_ZERO + per-plane-K-init + permutation `[1023]`**
   — measure compositional effect; Round-5 Section I5 showed naive
   stacking is destructive when each override consumes a residual
   whose K-state then drifts, but the K-init knob does *not* consume
   any extra residual.

Promotion threshold remains `|r| > 0.7`. Per-channel max still 0.56 ⟹
still no production code change.

### 5.12 Round-7 follow-up — per-plane K-init, K-init from `quantValue`, combined refinement (Update (k))

Driven by Update (j)'s next-step list, `CrxPlaneFollowupRound7IT`
measures three hypothesis families on `sample.cr3`. Output dir
`canon-cr3-imageio/target/crx-followup-round7/`. Strict test scope.

**Section M7 — Per-plane K-init combinations.** Holding predictor =
`P_LEFT_BASE`, lead-zero = `LZ_TILE_FIRST`, sign = `NONE`, vary
K-init independently per plane in `[0..4]` via coordinate-greedy
descent followed by a 3⁴ neighbourhood sweep:

| variant            | tile0 K        | tile0 Σ\|r\| | tile1 K        | tile1 Σ\|r\| |
|--------------------|----------------|--------------|----------------|--------------|
| Round-6 baseline   | `[0,0,0,0]`    | 0.9484       | `[0,0,0,0]`    | 0.9325       |
| **best_for_tile0** | **`[4,2,0,3]`**| **1.5118**   | (-)            | -            |
| **best_for_tile1** | (-)            | -            | **`[0,4,2,2]`**| **1.5210**   |
| **best_combined**  | `[4,4,2,3]`    | 1.3476       | `[4,4,2,3]`    | 1.5035       |

- **Both tiles lift Σ|r| by Δ ≈ +0.57** vs the Round-6 K_INIT_0
  baseline — the largest Σ|r| single-step gain since the predictor
  was identified in Update (f).
- **The per-tile optima are different and disjoint multi-sets are
  rotated** — `{4,2,0,3}` vs `{0,4,2,2}`. A single per-plane K-init
  cannot capture both optima; the joint best `[4,4,2,3]` trails the
  per-tile optima by Δ Σ|r| = 0.16 / 0.02.
- **The dominant Gr per-channel max stays at 0.5409 / 0.5605** — the
  per-plane K-init mostly recovers the *non-Gr* planes (R, Gb, B
  jump from |r| ≤ 0.06 to |r| ∈ [0.39, 0.49]).

**Section N7 — K-init derived from `CMP1.quantValue`.** On `sample.cr3`
every subband has `q = 4`. Mappings tested:

| mapping       | k(green) | k(non-green) | tile0 Σ\|r\| | tile1 Σ\|r\| |
|---------------|----------|--------------|--------------|--------------|
| `Q_DIRECT`    | 4        | 4            | **1.3476**   | **1.4060**   |
| `Q_MINUS_1`   | 3        | 3            | 1.0413       | 1.2194       |
| `Q_PLUS_1`    | 5        | 5            | 1.2747       | 1.2193       |
| `Q_LOG2`      | 2        | 2            | 0.9722       | 1.3344       |
| `Q_LOG2_PLUS_1`| 3       | 3            | 1.0413       | 1.2194       |
| `Q_CLAMP_0_4` | 4        | 4            | **1.3476**   | **1.4060**   |
| `Q_PER_PLANE` | 4        | 2            | 1.3476       | 1.3168       |

- **`K_INIT = quantValue` (= 4) is the best metadata-near rule on
  this fixture**, matching the joint per-plane greedy optimum to
  within Δ Σ|r| ≤ 0.01 on both tiles without per-tile state.
- This is the **first positive empirical link between a CMP1 header
  field and the CRX entropy state** in the entire investigation.
- The result is **inconsistent with the patent's literal `K* =
  floor(log2(s + 1))` reading** (which would give K=2 for the
  smallest plausible first symbol) and with `Q_LOG2 = floor(log2 q)`
  (= 2). The `K_INIT = q` form rather suggests Canon either uses
  `quantValue` directly as a Rice-K seed at tile start, or uses a
  K* computed from a per-subband variance estimate that happens to
  equal `q` on lossless fixtures.
- Note this resurrects hypothesis `H1` from §6 in narrowed form:
  *"`initial_k = quantValue` directly"* was demoted in Update (c)
  because the K-update bracket dominates after the first symbol;
  Update (k) shows that on `sample.cr3` the early-symbol leakage
  caused by `init_k = 0` is large enough to *halve* Σ|r| on the
  R/Gb/B planes, so the seed is in fact significant.

**Section O7 — Combined refinement.** LZ + per-plane-K-init + perm
composition test using `[4,4,2,3]`:

| tile  | composition                                        | Σ\|r\|     | max\|r\|   |
|-------|----------------------------------------------------|------------|------------|
| tile0 | LZ + k=`[0,0,0,0]` + perm=`[1023]` (R6 control)    | 0.9484     | 0.4904     |
| tile0 | LZ + k=`[4,4,2,3]` + perm=`[1032]`                 | **1.3476** | 0.5409     |
| tile0 | LZ + k=`[4,4,2,3]` + perm=`[1023]`                 | 1.3389     | 0.5409     |
| tile1 | LZ + k=`[0,0,0,0]` + perm=`[1023]` (R6 control)    | 0.9325     | 0.5605     |
| tile1 | LZ + k=`[4,4,2,3]` + perm=`[1032]`                 | 1.5035     | 0.5582     |
| tile1 | LZ + k=`[4,4,2,3]` + perm=`[1023]`                 | **1.5166** | 0.5582     |

- Composition is **non-destructive** — the Round-5 §I5
  destructive-stacking effect does not recur, confirming the patent
  §1.8 prediction that adjusting K-init alone consumes no extra
  bitstream state.
- **Per-channel max is unchanged**: still **0.5409 / 0.5605** on
  Gr — *below* the 0.7 promotion threshold. The Round-7 lever is
  Σ|r|-positive, not max|r|-positive.
- Permutation `[1023]` vs `[1032]` is at the noise level on tile0
  and a small gain on tile1 — consistent with Round-6's K6 finding.

**Net narrowing after Update (k).** Ruled out:

- A single K-init quadruple that captures both tiles' best
  assignments (the per-tile optima are disjoint).
- A `floor(log2(q))` mapping (K=2 for q=4) being the correct
  metadata-near rule — beaten by `K_INIT = q` on tile0 by 0.38
  Σ|r|.

New positive findings:

1. **`K_INIT = CMP1.quantValue` is a defensible metadata-near rule**
   that lifts both tiles' Σ|r| by Δ ≈ +0.4 vs `K_INIT_0`.
2. **The per-tile K-init asymmetry is structural**: tile0 wants
   `[4,2,0,3]`, tile1 wants `[0,4,2,2]` — a rotation of the same
   multi-set `{0,2,3,4}`, suggesting the two tiles may consume
   planes in a different order at the bitstream level.

Concrete next steps:

1. **Per-plane K-init on `eosr-iso-*.cr3` fixtures** to test whether
   `K_INIT = q` generalises to subbands with `q ≠ 4` — the only
   experiment that can promote the rule from "fixture-specific" to
   "candidate production change".
2. **Tile-plane ordering audit**: cross-check `SubbandExtractor`'s
   per-tile plane order against the `{4,2,0,3}` ↔ `{0,4,2,2}`
   rotation to see whether a single permutation reconciles the two
   K-init optima.
3. **Wider K range** (`[0..7]`) for the greedy search; Round-6 saw
   non-destructive behaviour at K=5/7 on some planes that the
   `[0..4]` Round-7 search clipped out.

Promotion threshold remains `|r| > 0.7`. Per-channel max still
**0.56** ⟹ still no production code change.

### 5.13 Round-8 follow-up — K-evolution after init, multi-file `quantValue`, header-link audit (Update (l))

Driven by Update (k)'s next-step list, `CrxPlaneFollowupRound8IT`
shifts the focus from K_INIT sweeps to the *K-evolution* after
init. Output dir
`canon-cr3-imageio/target/crx-followup-round8/`. Strict test scope.

**Section P8 — K-evolution after init.** Holding
predictor=`P_LEFT_BASE`, lead-zero=`LZ_TILE_FIRST`, sign=NONE,
`K_INIT = CMP1.quantValue` (= 4), vary the K-update rule:

| K-update variant       | tile0 Σ\|r\| | tile1 Σ\|r\| | comment                                  |
|------------------------|--------------|--------------|------------------------------------------|
| **K_BRACKET_PATENT**   | **1.3476**   | **1.4060**   | Round-7 baseline — winner.               |
| K_CONST                | 0.5184       | 0.5062       | −0.83 / −0.90 — bracket is essential.    |
| K_PER_ROW_RESET        | 0.4845       | 0.3904       | Destructive.                             |
| K_PREV_SYMBOL          | 0.4199       | 0.2395       | Destructive.                              |
| K_BRACKET_TIGHT (±0)   | 0.4199       | 0.2395       | Identical to K_PREV_SYMBOL.              |
| K_BRACKET_HI_ONLY      | 0.2232       | 0.1936       | Destructive — k can only drift down.     |
| K_BRACKET_WIDE (±2)    | 0.1910       | 0.2107       | Destructive — bracket too loose.          |
| K_BRACKET_LO_ONLY      | 0            | 0            | Bitstream exhausts (k drifts up only).   |
| K_RUNNING_MEAN (α=1/16)| 0            | 0            | Bitstream exhausts.                       |

- **The patent §1.8 ±1 bracket is structurally load-bearing.**
  Every alternative tested degrades Σ|r|; the tightest
  competitor (`K_CONST`) loses ~0.85 Σ|r|. This promotes the
  bracket from "best of four §1.8 variants in Update (c)" to
  "necessary, not just sufficient".
- **Two strictly monotone variants exhaust the bitstream**
  (`K_BRACKET_LO_ONLY`, `K_RUNNING_MEAN` — both let `k` grow
  unboundedly). This is the Update (b) `init_k = 7` over-reading
  failure mode in a different guise: any K-rule that lets `k`
  exceed the symbol magnitude by more than ±1 consumes too many
  bits per pixel.
- **No K-evolution variant lifts max|r| above 0.56.** The
  per-channel ceiling is unchanged → the missing state is *not*
  in the post-init K-update rule.
- **`K_PER_ROW_RESET` is decisively worse than no per-row reset**
  (Δ Σ|r| = −0.86 / −1.02 vs PATENT). This empirically
  re-confirms the §6 demotion of `H4` ("per-line K reset").

**Section Q8 — Multi-file validation of `K_INIT = quantValue`.**
Three fixtures whose subband list extracts cleanly:

| fixture                | tile0 q-vector  | best Q8 rule          | Σ\|r\| | Δ vs `K_INIT=0` |
|------------------------|-----------------|-----------------------|--------|------------------|
| `sample.cr3`           | `[4,4,4,4]`     | **`K_INIT=q`**        | 1.3476 | +0.41            |
| `eosr-iso-low.cr3`     | `[4,4,4,4]`     | `K_INIT=q`            | 0.1449 | +0.002           |
| `sample-iso-high.cr3`  | `[4,4,4,4]`     | `K_INIT=floor(log2 q)`| 0.0573 | +0.05            |

- **Negative result — the rule is not falsifiable on the
  available fixture set.** Every fixture reports `quantValue = 4`
  on every tile-0 plane, so `K_INIT = q` is observationally
  indistinguishable from `K_INIT = 4` (constant).
- The two non-`sample.cr3` fixtures fail at a more fundamental
  level (`Σ|r| < 0.15` for any K-init rule) — consistent with
  Update (b) — so they cannot serve as discriminators even if
  their `q` were different.

**Section R8 — Header-link audit.** Across the three fixtures
*every* CMP1 field (`encodingType`, `waveletLevels`,
`bitsPerSample`, `numPlanes`, `cfaLayout`) and *every*
per-subband field (`quantValue`, `roundedBits`,
`supportsPartial`, `encodingType`, `waveletLevels`) is identical.

- **Negative result — no per-tile / per-plane CMP1 or header
  variability exists in the available test corpus that could
  correlate with the Round-7 tile-asymmetry of K-init optima
  (`{4,2,0,3}` ↔ `{0,4,2,2}`).** The asymmetry must be
  explained by something *not* exposed in the parsed header (e.g.
  bitstream order, an undecoded subband-header byte, or an
  entropy state carried across tile boundaries).

**Section S8 — Combined refinement.** With the best K-update
from P8 (= PATENT bracket = Round-7 baseline) the combined
composition reproduces the Round-7 numbers exactly (Σ|r| =
1.3476 / 1.4060, max|r| = 0.5409 / 0.5582). Round-8 introduces
no new winner. Note that under `K_INIT = q = 4` the permutation
choice flips: `[1032]` (existing GRBG) marginally beats `[1023]`
by Δ Σ|r| ≈ +0.009 / +0.002, *reversing* the Round-6 K6 finding.
The K-init change therefore absorbs the small Round-6
permutation gain.

**Net narrowing after Update (l).** Ruled out:

- Constant K, per-row K reset, prev-symbol-snap K, ±2 bracket,
  ±0 bracket, monotone-up bracket, monotone-down bracket, and
  16-tap running mean — all worse than the Round-7 patent ±1
  bracket.
- A per-tile / per-plane CMP1 or per-subband header field that
  could explain the Round-7 K-init tile-asymmetry — none exists.

New positive findings:

1. **The patent ±1 bracket K-update is necessary, not just
   sufficient.** Promoted from "best of §1.8 variants" to
   "load-bearing component of the best known path".
2. **The K-state is not the source of the remaining 0.56
   ceiling.** Round-8 closes the K-evolution lever as a
   significant explanatory factor for the gap to |r| > 0.7.

Concrete next steps:

1. **Find or synthesise a CR3 fixture with `quantValue ≠ 4`**
   (lossy-compressed CRX track or a different camera body). The
   `K_INIT = q` rule cannot be promoted past "fixture-specific"
   without a counter-`q` fixture.
2. **Re-audit `SubbandExtractor` on `eosr-iso-*.cr3`** — the
   Σ|r| ≈ 0.14 there (vs ≈ 1.35 on `sample.cr3`) under
   *identical* `K_BRACKET_PATENT` + `K_INIT = q` decoder
   suggests the subband-extraction step is returning the wrong
   bytes for those fixtures, not that the codec hypothesis is
   wrong.
3. **Investigate the tile-asymmetry at the bitstream-byte
   level** (per-tile MDAT offsets, subband-header parse) since
   Update (l) shows no metadata-level link to it.

Promotion threshold remains `|r| > 0.7`. Per-channel max is
still **0.56** ⟹ still no production code change.

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
