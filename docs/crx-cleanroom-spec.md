# CRX Lossless — internal clean-room decoder specification

> **Status.** Working draft, derived purely from public sources:
> - Canon patent **US 2016/0323602 A1** (analysed in
>   [`patent-us20160323602a1-analysis.md`](patent-us20160323602a1-analysis.md)) —
>   now used as a primary source. Statements taken directly from the patent are
>   tagged **`[patent]`**; CRX-specific inferences remain tagged **`[hypothesis]`**.
> - **ITU-T T.800** / ISO-IEC 15444-1 (JPEG 2000) for the wavelet filter,
> - **ITU-T T.87** / ISO-IEC 14495-1 (JPEG-LS) for the MED predictor used by the
>   LL subband and for general Golomb-Rice coding background,
> - **Exif 2.3** for the metadata tags surrounding the RAW track,
> - this repository's own measurements logged in
>   [`canon-cr3-core/SubbandFixtures.md`](../canon-cr3-core/SubbandFixtures.md).
>
> No third-party CRX decoder source code was consulted. Every CRX-specific
> assumption that is not yet validated against a real CR3 fixture is **explicitly
> tagged `[hypothesis]`** and is *not* binding on the production decoder until
> validated.

---

## 1. Pipeline overview (vermuteter Decoderpfad)

The decoder is structured as four logically separable stages:

```
   compressed mdat bytes
            │
            ▼
   ┌──────────────────────┐
   │ A. Container parser  │   (already implemented:
   │    ISO-BMFF + CRX    │    Cr3Parser, CrxHeader)
   │    header tree       │
   └──────────┬───────────┘
              │ tile / plane / subband byte-ranges + quantValue
              ▼
   ┌──────────────────────┐
   │ B. Entropy decoder   │   adaptive Golomb-Rice
   │    bytes → symbols   │   per (subband × context)
   └──────────┬───────────┘
              │ uint16 symbols, raster order within subband
              ▼
   ┌──────────────────────┐
   │ C. Symbol → coeff    │   sign fold + predictor inverse
   │    inverse           │   (causal neighbours within subband)
   └──────────┬───────────┘
              │ signed wavelet coefficients
              ▼
   ┌──────────────────────┐
   │ D. Dequantiser       │   per-subband scalar
   │                      │   (identity for lossless today)
   └──────────┬───────────┘
              │ dequantised coefficients
              ▼
   ┌──────────────────────┐
   │ E. Inverse wavelet   │   2-D Le Gall 5/3 synthesis
   │    LL,HL,LH,HH → ch  │   (already implemented in
   │                      │    InverseWaveletTransform)
   └──────────┬───────────┘
              │ Bayer-channel samples
              ▼
   ┌──────────────────────┐
   │ F. Channel interleave│   four channels → Bayer raster
   │    + downstream      │   (existing pipeline)
   │    processing        │
   └──────────────────────┘
```

The current production decoder collapses **B → F** into a single nested loop and
treats the four "planes" as the four Bayer channels directly. The patent analysis
in `patent-us20160323602a1-analysis.md` makes a strong case that the four planes
are instead the **four wavelet subbands of a single Bayer channel**; reorganising
the pipeline as above is the precondition for testing that interpretation.

---

## 2. Data structures (definierte Zustände)

### 2.1 Per-track (constant for the entire decode)

| Field | Source | Notes |
|------|--------|-------|
| `imageWidth`, `imageHeight` | CTBO / CRX header | full sensor RAW dimensions |
| `bitDepth` | CMP1 / Exif | 14 for current Canon bodies |
| `bayerPattern` | CMP1.cfaLayout | one of RGGB / GRBG / GBRG / BGGR |
| `numTiles`, `tileWidth`, `tileHeight` | CRX header / CMP1 | tile geometry |
| `waveletLevels` | CMP1 | 0 = no wavelet (lossless-today) / >0 = lossy |
| `qStep[s]` per subband `s` | CRX subband header | dequantiser step (currently ≡ 4 for lossless; see §8) |

### 2.2 Per-subband decoder state (during stage B)

| Field | Type | Initial value | Notes |
|------|------|--------------|-------|
| `k` | int | `kSeed(subband)` (see §4) | Rice parameter; reset at subband start `[patent]` |
| `coeffGrid` | int[width × height] | 0-filled | reconstructed coefficients of *this* subband, used as causal neighbours for the predictor (§5) |
| `deeperLLGrid` | int[…] (LL only) | from previously decoded LL(level+1) | needed for the `Sn = (Sx+Sd)/2` rule of §4 (LL only) `[patent]` |

The K state is **reset at the start of every subband** `[patent]`. There is **no
per-line K reset** in the patent rule — the per-symbol bracket update already
provides line-rate adaptation.

### 2.3 Per-coefficient ephemeral state (during stage C)

| Symbol | Meaning |
|-------|--------|
| `q` | unary quotient read in stage B (`= S >> K`) |
| `r` | K-bit remainder read in stage B (`= S & ((1<<K)-1)`) |
| `S = (q << K) \| r` | non-negative coded symbol (per §6 / §7) |
| `Diff` | signed prediction error after the §6 zig-zag inverse |
| `p` | per-subband predictor for current coefficient (per §5) |
| `x = p + Diff` | reconstructed coefficient |

---

## 3. Reconstruction step order (Reihenfolge der Rekonstruktionsschritte)

For each tile, in `tile.index()` order:
1. Determine tile geometry (width × height of each plane / subband).
2. For each subband in patent FIG. 5 order
   `LL(n), HL(n), LH(n), HH(n), HL(n−1), LH(n−1), HH(n−1), …, HL(1), LH(1), HH(1)`
   `[patent]`:
   1. Reset per-subband state (§2.2).
   2. For each line `y = 0 .. (subbandHeight − 1)`, in raster order `[patent]`:
      1. For each coefficient `x = 0 .. (subbandWidth − 1)`:
         1. Compute predictor `p` from causal neighbours of `(x, y)` *within
            this subband* using the **per-subband predictor rule** of §5
            `[patent]`.
         2. **Stage B (entropy decode):** read `q = unary()` (count zeros until
            the first `1`-bit) and `r = bits(K)`; form the non-negative symbol
            `S = (q << K) | r` `[patent]`.
         3. **Stage C (symbol → coefficient):** invert the zig-zag mapping of §6
            to obtain the signed prediction error `Diff`, then
            `c = p + Diff` `[patent]`.
         4. Store `c` in this subband's coefficient grid.
         5. **Update K** according to the lookahead-bracket rule of §4
            `[patent]`.
3. Stage D: dequantise each subband by its `qStep[s]` (no-op for lossless
   today; per-subband scalar for lossy `[patent]`).
4. Stage E: 2-D inverse wavelet of the level-1 subbands `{LL, HL, LH, HH}` →
   channel sample grid; for multi-level decompositions, repeat the synthesis
   from the deepest level outwards (LL(n) is reconstructed from LL(n+1) +
   HL(n+1) + LH(n+1) + HH(n+1), then promoted into the level-n LL slot, etc.)
   `[patent]`.
5. Stage F: interleave the four reconstructed channel grids into a Bayer raster
   in the order indicated by `bayerPattern`.

For the (current) one-level lossless CRX path, step 4 is a single application of
the inverse 5/3 lifting per channel, and the four "planes" feed the four
subbands directly.

The mapping `tile.plane[i].index() → {LL, HL, LH, HH}` is itself a `[hypothesis]`
that step 1 of §8 validates.

---

## 4. K-adaptation — patent rule

Marked `[patent]` (the algorithmic structure) and `[hypothesis]` (the exact
bracket constants, which still need to be transcribed precisely from the patent
text):

```text
On reading symbol Sx with current parameter Kx:

    if subband == LL:
        # one-step-lookahead estimate, averaged with the co-located
        # symbol Sd from the deeper level (LL(level+1))
        Sn = (Sx + Sd) / 2          # [patent]
    else:                           # HL, LH, HH
        Sn = Sx                     # [patent]

    if Kx < Kmin(Sn):
        Kn = Kx + 1                 # K too small for symbols of magnitude Sn
    elif Kx > Kmax(Sn):
        Kn = Kx - 1                 # K too large
    else:
        Kn = Kx                     # K is in the optimal bracket
```

The functions `Kmin(Sn)` and `Kmax(Sn)` together define the optimal K bracket
for a Golomb-Rice code on a symbol of magnitude `Sn`. The natural form (to be
*verified verbatim against the patent paragraphs*) is

```text
K*(Sn) = floor(log2(Sn + 1))                # central optimal K
Kmin(Sn) = max(0, K*(Sn) - δ_low)           # δ_low ≥ 1   [hypothesis: exact δ_low TBD]
Kmax(Sn) = min(Kmax_global, K*(Sn) + δ_hi)  # δ_hi ≥ 1    [hypothesis: exact δ_hi TBD]
```

The internal data model used by the research harness becomes:

```java
final class PatentKAdapter {
    int  k;              // current K (Kx)
    int  kMin = 0;
    int  kMax = 15;      // [hypothesis] for 14-bit, the actual cap is patent-text-TBD

    void reset(int seedK)               { this.k = seedK; }
    int  current()                       { return k; }
    /** @param sn  estimated next-symbol magnitude per §4 */
    void update(int sn) {
        int kStar    = 31 - Integer.numberOfLeadingZeros(sn + 1);   // floor(log2(Sn+1))
        int kMinLoc  = Math.max(kMin, kStar - 1);                    // [hypothesis]
        int kMaxLoc  = Math.min(kMax, kStar + 1);                    // [hypothesis]
        if      (k < kMinLoc) k++;
        else if (k > kMaxLoc) k--;
        // else: keep k
    }
}
```

For the LL subband the adapter additionally requires the co-located symbol `Sd`
from the deeper-level LL subband. For a *one-level* decomposition (which we
believe describes our current lossless fixtures) there is no deeper level, so LL
collapses to `Sn = Sx` exactly like the high-pass subbands.

The seed `K0` for the very first symbol of a subband is *not* uniquely
determined by the patent. Reasonable choices are:
- `K0 = 0`,
- `K0 = floor(log2(Q + 1))` where `Q` is the CRX header `quantValue` (the
  current `H1` direction; less critical now that the bracket rule converges
  rapidly).

Both are admissible; they should be empirically chosen so the *first* symbol's
unary prefix length fits the bitstream.

---

## 5. Per-subband predictor (Patent FIG./Beschreibung)

`[patent]` — for the current coefficient `x` with causal neighbours `a` (left),
`b` (above) and `c` (above-left):

| Subband | Predictor `p` |
|--------|---------------|
| **LL** | **MED(a, b, c)** — JPEG-LS Median Edge Detector |
| **HL** | **`p = b`** (vertical / above-only) |
| **LH** | **`p = a`** (horizontal / left-only) |
| **HH** | **`p = 0`** (no prediction) |

`MED(a, b, c)`:

```text
if c >= max(a, b):  return min(a, b)
if c <= min(a, b):  return max(a, b)
otherwise:          return a + b - c
```

Boundary rules `[hypothesis]` (the patent does not spell these out; the natural
clean-room conventions are):

| Position | LL predictor | HL predictor | LH predictor | HH predictor |
|---------|--------------|--------------|--------------|--------------|
| `(0, 0)` | `1 << (bitDepth-1)` (mid-range) | `0` | `0` | `0` |
| `(x, 0)`, `x > 0` | `a` (left) | `0` (no `b` available) | `a` | `0` |
| `(0, y)`, `y > 0` | `b` (above) | `b` | `0` (no `a` available) | `0` |
| general | MED(a, b, c) | `b` | `a` | `0` |

---

## 6. Symbol mapping (Patent)

`[patent]` — given the prediction error `Diff = x − p`, the non-negative symbol
fed into the Golomb-Rice coder is

```
S =  2 · Diff           if Diff ≥ 0
S = −2 · Diff − 1       if Diff < 0
```

Decoder direction (from `S`, recover `Diff`):

```
if (S & 1) == 0:        Diff =  S >> 1            //  0 → 0,  2 → +1,  4 → +2, ...
else:                   Diff = -((S + 1) >> 1)    //  1 → -1, 3 → -2,  5 → -3, ...
```

Reconstruction: `x = p + Diff`.

This matches the "zigzag" sign convention already implemented in
`RiceGolombDecoder.decodeSigned()` and in `CrxCandidateDecoders` — that part of
the existing code aligns with the patent and does not need to change.

---

## 7. Golomb-Rice encoding (Patent FIG. 10A/10B)

`[patent]` — for symbol `S` and parameter `K`:

- **Step 1 — unary prefix:** write `S >> K` zero bits, followed by one `1` bit.
- **Step 2 — binary suffix:** write the `K` low-order bits of `S`.

Codeword length: `(S >> K) + 1 + K` bits.

Decoder direction:

```
q = count zero bits up to (and consuming) the next 1-bit
r = read K bits
S = (q << K) | r
```

This is identical in shape to what `RiceGolombDecoder.decode()` already does;
the existing implementation is correct *for the entropy step alone*. What was
wrong was the K-adaptation rule and the assumption that the same predictor
applies to all subbands.

---

## 8. Quantisation / dequantisation

For the lossless tracks we have today, **`qStep[s] ≡ 1` is the operational
behaviour** (every coefficient round-trips exactly). The CRX header value `q = 4`
that we observe on every lossless subband is therefore **not** a multiplicative
quantiser. Two clean-room hypotheses are open:

1. `q` encodes a **bit-shift normalisation** that the integer 5/3 lifting applies
   to the high-pass subbands (`q = 4` ≡ `>> 2`).
2. `q` encodes the **K-adaptation seed** (§4) directly or with a fixed offset.

Both are testable by encoding a known synthetic image through the forward 5/3
lifting and comparing the resulting subband magnitudes against the value range
implied by each interpretation.

For the lossy track (`waveletLevels > 0`), the patent explicitly describes that
**quantised wavelet coefficients can enter the predictive coding stage** (i.e. a
per-subband scalar quantiser sits between Stages E and B in the encoder). The
decoder therefore must apply the inverse scalar between Stages D and E. We will
leave this as a TODO (`Cr3DecodeOptions` flag, `UnsupportedOperationException`
for now) until the lossless path is solid; the patent does not pin down the
specific dequantisation table for C-RAW so this is genuine empirical work.

---

## 9. Subband-order summary

`[patent]` (FIG. 5) — for an *n*-level decomposition:

```
LL(n), HL(n), LH(n), HH(n),
       HL(n-1), LH(n-1), HH(n-1),
       …
       HL(1),  LH(1),  HH(1)
```

| Source | Order produced | Confidence |
|-------|----------------|-----------|
| Patent FIG. 5 | LL(n), HL(n), LH(n), HH(n), … | `[patent]` |
| CRX header iteration | `tile.planes[]` in storage order | confirmed (parser) |
| Empirical sizes (`sample-wb-tungsten.cr3`) | one plane is ~6.7× larger than the others | consistent with LL being largest (lower entropy) |

The mapping `plane.index() → {LL, HL, LH, HH}` for our one-level fixtures is a
`[hypothesis]` to be validated via step 1 of §10.

---

## 10. Validation gaps (offene Validierungspunkte)

After the patent analysis the *algorithmic* unknowns have collapsed to a small
set; the dominant remaining work is **CRX file-format mapping** plus a precise
transcription of the K-bracket constants. In priority order:

1. **Plane-vs-subband interpretation.** Apply forward integer 5/3 to a known
   synthetic Bayer image of the same plane dimensions; compare the resulting
   LL / HL / LH / HH magnitudes against the observed CR3 plane sizes. A match
   confirms the four "planes" of a CRX tile are the four wavelet subbands of
   one channel. *(`[hypothesis]` → `[empirical-fact]` after step.)*
2. **Transcribe `Kmin(Sn)` and `Kmax(Sn)` exactly** from the patent paragraphs
   into §4. The natural form is `floor(log2(Sn+1)) ± δ`; the exact δ values
   (and whether the patent uses ceiling-log or a small lookup) must be lifted
   verbatim from the patent text.
3. **Reference candidate decoder.** Implement `CrxPatentCandidateDecoder` in
   test scope strictly per §3–§7 above. Acceptance: exactly consumes the
   subband bytes *and* reaches Pearson r > 0.9 against the PRVW reference for
   every plane of `eosr-iso-low.cr3`.
4. **K seed.** With the bracket rule converging quickly, the seed becomes a
   minor tuning detail. Sweep `K0 ∈ {0, floor(log2(quantValue+1))}` only after
   step 3.
5. **Boundary rules** for `(0,0)`, first row, first column (§5 table) — fine
   tune once a candidate is reaching r > 0.5.
6. **Multi-level decomposition.** If our CRX fixtures ever expose
   `waveletLevels > 0`, validate the `Sn = (Sx + Sd) / 2` rule for LL using the
   deeper-level co-located symbol.

Each of these closes one degree of freedom independently of the others,
*provided* they are tackled in this order.

---

## 11. Known good measurements that any future implementation must match

These are *facts*, not hypotheses, and must hold for any candidate decoder:

- **`eosr-iso-{low,mid,high}.cr3` mean bits/pixel = 9.0 – 9.4.** A correct decoder
  will consume exactly this many bits across each subband.
- **`sample.cr3` plane 0 byte histogram is approximately flat;** longest zero
  run = 45. Rules out byte-stuffing.
- **Bit fraction ≈ 0.45** (slightly more zeros than ones), consistent with an
  unstuffed Rice-coded geometric distribution.
- **`Cmp1Box.cfaLayout` correctly determines Bayer pattern** (verified by
  existing `Cmp1BoxTest`).
- **`CrxHeader.parse()` returns 2 tiles × 4 planes × 1 subband** for the bundled
  fixtures.

Any candidate decoder that violates any of the above is wrong on its face and
should be rejected before it even reaches the harness.

---

## 12. Out of scope for this spec

- The lossy / wavelet-multi-level path (`waveletLevels > 0`) — covered only at
  the level of the architectural pipeline above; algorithmic detail deferred.
- The CRX *encoder* — not a project goal.
- Other Canon RAW formats (CR2, CRW) — handled elsewhere.
- Legal review of patent licensing — see §7 of the analysis document; this is
  an engineering specification, not a legal opinion.
