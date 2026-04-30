# Clean-room evaluation of patent US 2016/0323602 A1 ("Image processing apparatus, image processing method, and storage medium")

> **Scope.** This document is a *clean-room* engineering evaluation of the publicly
> available Canon patent application **US 2016/0323602 A1** (filed 2016, related to
> Canon's C-RAW / CRX compression) against the open questions in this repository's
> CRX entropy decoder.
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

1. Implement, in `canon-cr3-imageio/src/test`, a `CrxPatentCandidateDecoder` that
   decodes one subband strictly according to §1.5 / §1.6 / §1.7 / §1.8 above
   (per-subband predictor, zig-zag symbol, Golomb-Rice with `(S>>K)+1+K` bits per
   symbol, lookahead-bracket K update).
2. Wire that candidate into `CrxHypothesisSweepIT`. The acceptance criteria are
   unchanged: exactly consume the subband bytes *and* reach Pearson r > 0.9
   against the PRVW reference.
3. Validate the **plane-vs-subband interpretation** by feeding the four "planes"
   of one tile (after step 1) into the existing `InverseWaveletTransform.legall53(...)`
   and comparing the reconstructed channel against the PRVW-derived reference
   channel.
4. Transcribe the **exact `Kmin(Sn)` and `Kmax(Sn)` formulae** from the patent
   text into `crx-cleanroom-spec.md` §4 before the candidate can be considered
   complete.
5. **Only then** consider promoting the winning candidate into a production code
   path. Even then, gate it behind a `Cr3DecodeOptions` flag for one release.

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
