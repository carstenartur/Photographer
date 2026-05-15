# CR3_FORMAT.md — Canon CR3 / CRX bitstream format reference card

This is a **concise standalone reference** for the Canon CR3 container
and the CRX raw bitstream, distilled from the cleanroom analysis in
`canon-cr3-core/SubbandFixtures.md` and the research updates in
`canon-cr3-imageio/src/test/resources/samples/research/README.md`.

It exists so that, after a total local data loss, the *minimum required
format knowledge* to keep working on the CR3 import is recoverable from
the public git repository alone — without needing the original Canon
SDK PDFs (which are copyrighted and **must not** be in this repo).

**Authoritative companion documents (in git):**
- `canon-cr3-core/SubbandFixtures.md` — running ledger of every
  fixture's measurements and per-subband layout (1399 lines, the
  primary cleanroom record).
- `canon-cr3-imageio/src/test/resources/samples/research/README.md` —
  research narrative with updates (a)–(p), including the Round-13
  outcome and the predictor-seed production fix.
- `canon-cr3-core/src/main/java/com/photographer/focusstack/cr3/**` —
  reference implementation (parser, BMFF reader, IDWT, decoder).

---

## 1. Container: ISO BMFF with Canon extensions

Canon CR3 is a standard ISO Base Media File Format file (ISO/IEC
14496-12) with Canon-specific child boxes and `uuid`-tagged extensions.

### 1.1 Top-level boxes (typical order)

| Box | Type | Purpose |
|---|---|---|
| `ftyp` | header | Brand `crx ` confirms CR3. |
| `moov` | container | Movie-level metadata (one track per stream). |
| `uuid`(`XMP`) | metadata | Adobe XMP packet. |
| `uuid`(`PRVW`) | preview | Embedded JPEG preview (~35–86 kB). |
| `mdat` | media data | Concatenated track samples (raw + previews). |

### 1.2 Tracks inside `moov`

CR3 typically contains **four tracks** under `moov/trak/mdia/minf/stbl`:

| Track | Sample type (`stsd`) | Contents |
|---|---|---|
| 0 | `vide` (CCDT/CRAW) | Embedded JPEG preview thumbnail (~160 kB). |
| 1 | `vide` (CCDT/CRAW) | Embedded JPEG full preview (~5–10 MB). |
| 2 | **`vide` (`CRAW`)** | **Main RAW (CRX bitstream)** — this is what we decode. |
| 3 | `meta` (`CTMD`) | Canon Timed Metadata (per-frame EXIF-like). |

The `CRAW` sample-description child boxes carry the codec-config:
- **`CMP1`** — Compression Parameters 1 (the only one our decoder uses).
- **`CDI1`** — Color Difference Information.
- **`IAD1`** — Image Active Data (sensor crop rectangle).

---

## 2. CMP1 box (compression parameters)

Reference implementation: `canon-cr3-core/src/main/java/com/photographer/focusstack/cr3/bmff/Cmp1Box.java`.

### 2.1 Layout (offsets relative to `CMP1` box payload)

| Offset | Size | Field | Notes |
|------:|---:|---|---|
| 0 | 2 | unknown | always 0. |
| 2 | 2 | header size | usually 0x0036 (54) for v1. |
| 4 | 2 | **version** | observed values: 1. |
| 6 | 2 | padding | 0. |
| 8 | 4 | **imageWidth** | sensor width including borders. |
| 12 | 4 | **imageHeight** | sensor height including borders. |
| 16 | 4 | **tileWidth** | width of each tile (typically `imageWidth/2`). |
| 20 | 4 | **tileHeight** | height of each tile (typically `imageHeight`). |
| 24 | 1 | **bitsPerSample** | 14 on every Canon body observed. |
| 25 | 1 | packed | high nibble: numPlanes (always 4); low nibble: cfaLayout (0…3). |
| 26 | 1 | packed | high nibble: encodingType; low nibble: **waveletLevels** (0 = lossless RAW; 3 = C-RAW). |
| 27 | 1 | tileFlags | bit 7 (0x80) = multipleHTiles. |
| 28 | 4 | **mdatHeaderSize** | bytes to skip at the start of the `mdat` slice for this track. |
| 32 | 1 | extHdr | bit 7 (0x80) = hasExtendedHeader. |

### 2.2 Extended header (when present, +52..+84)

| Offset | Size | Field |
|------:|---:|---|
| 52 | 4 | extendedHeaderSize |
| 56 | 1 | extFlags (bit 6 / 0x40 = medianBitDepth present) |
| 84 | 1 | medianBitDepth (when extFlags bit 6 is set) |

### 2.3 cfaLayout values

```
0 = RGGB    1 = GRBG    2 = GBRG    3 = BGGR
```

### 2.4 encodingType

`encodingType ∈ {0, 1, 2, 3}` observed; `0` correlates with lossless,
`>0` with C-RAW (lossy). The exact contract is not yet pinned down by
the cleanroom research; the decoder dispatches on `waveletLevels`
instead (`waveletLevels == 0` ⇒ lossless RAW).

---

## 3. mdat: tile / plane / subband layout

The `mdat` payload for the RAW track is structured as:

```
mdatHeaderSize bytes   (skip — described by CMP1.mdatHeaderSize)
┌─────────────────────────────────────────────────────────────┐
│ tile 0   │ tile 1   │  …                                    │
└─────────────────────────────────────────────────────────────┘

Each tile:
  [tile header]                marker = 0x?? (V1 vs V2 differ)
  ┌──────────────────────────────┬──────────────────────────────┐
  │ plane 0 (CFA Bayer slot 0)   │ plane 1 (CFA Bayer slot 1)   │
  │ plane 2 (CFA Bayer slot 2)   │ plane 3 (CFA Bayer slot 3)   │
  └──────────────────────────────┴──────────────────────────────┘

Each plane:
  [plane header]  marker = 0x??, dataSize, planeIndex
  for each subband (1 for L=0; 1 + 3·L for L > 0):
      [subband header]  marker = 0x??, dataSize, subbandIndex, quantValue
      <Rice-Golomb encoded coefficients>
```

The marker bytes for tile / plane / subband headers differ between
codec versions V1 and V2 — see `CrxHeader.CodecVersion` enum in
`canon-cr3-core`. All three header types share the same on-wire shape:

```
+0  uint16  marker
+2  uint16  headerSize  (always 8)
+4  uint32  dataSize
+8  uint8   index << 4  (low nibble = quantValue for subbands)
+9  uint8   ×3  (padding)
```

### 3.1 Number of subbands per plane

| `waveletLevels` (`L`) | Subbands per plane |
|---:|---:|
| 0 (lossless RAW) | 1 (just the LL band, decoded as raw pixels) |
| 1 (C-RAW low) | 4 (LL, HL, LH, HH at one scale) |
| 2 | 7 |
| 3 (C-RAW typical) | 10 |

### 3.2 Plane → CFA → preview channel mapping

The four planes (slots 0..3) map to CFA channels via `cfaLayout`. For
the half-resolution preview comparison the harness uses:

```
cfaLayout = 0 (RGGB) → preview channels [R, G1, G2, B] = [0, 1, 2, 3]
cfaLayout = 1 (GRBG) → [G1, R, B, G2]                  = [1, 0, 3, 2]
cfaLayout = 2 (GBRG) → [G2, B, R, G1]                  = [2, 3, 0, 1]
cfaLayout = 3 (BGGR) → [B, G2, G1, R]                  = [3, 2, 1, 0]
```

(Reference: `cfaLayoutMap()` in the research IT classes.)

---

## 4. Lossless RAW pixel coding (waveletLevels = 0)

Reference: `CrxLosslessRawDecoder.decodePlane()`.

### 4.1 Predictor

JPEG-LS / ITU-T T.87 **MED** (Median Edge Detection) on each plane:

```
a = pixel to the left          b = pixel above        c = pixel above-left

if  c ≥ max(a, b)  →  pred = min(a, b)
if  c ≤ min(a, b)  →  pred = max(a, b)
otherwise           →  pred = a + b − c
```

**First-pixel seed (CRX-specific):** `pred = 0`.
*Not* the JPEG-LS midpoint `1 << (bd − 1)`. This was empirically
validated by the dark/flat regression panel — see
`samples/research/README.md` Update (p) "Production change". The
first residual carries the OB pedestal.

**First-row predictor:** `pred = pixel to the left`.
**First-column predictor:** `pred = pixel above`.

### 4.2 Residual entropy coding

Adaptive **Rice-Golomb** code with parameter `K`:

- `K_INIT = 0`. Validated by `CrxPlaneFollowupRound9IT` on 100 % of
  160 subbands across two C-RAW frames. Earlier hypothesis
  `K_INIT = CMP1.quantValue` is **retracted** (Update (n)).
- `K` update rule: JPEG-LS accumulator/sampleCount adaptation
  (T.87 Annex A.5). The patent literature suggests a per-symbol
  bracket `K* = floor(log2(s + 1)), K ∈ [K* − 1, K* + 1]`; that has
  no production-code A/B evidence yet.
- Residual is **zigzag** mapped to a non-negative integer:
  `even → +n/2`, `odd → −((n−1)/2 + 1)`.
- Pixel is clipped to `[0, (1 << bitDepth) − 1]`.

### 4.3 Bit order

MSB-first within each byte. The `BitReader` class is the canonical
implementation.

---

## 5. C-RAW (waveletLevels > 0) — *partially understood*

The C-RAW path uses an inverse 2D wavelet transform to assemble the
plane from its `1 + 3·L` subbands. The cleanroom decoder has the IDWT
machinery (`InverseWaveletTransform.LEGALL_5_3`, validated against
synthetic patterns by `CrxPatentIdwtValidationIT`) but **the per-subband
entropy decoding and post-processing chain is not yet correct** —
Round-13 deep refinement plateaued at `|Pearson r| = 0.6573` and is
documented as a research dead-end.

What is known:

- **LL band** (lowest-frequency subband) uses the same Rice + MED
  pipeline as lossless RAW, with `LL_SIGNED` mapping; best Round-13
  predictor is `P_MED` for `bit=MSB_FIRST, k=1` on the
  `g5xm2-detail-…-02 / tile1/p0` plane.
- **HL/LH/HH detail bands** use Rice with adaptive `K` (initial
  `K_INIT = 0`), zigzag sign mapping, and per-subband `quantValue`
  scaling. The exact dequantisation rule and the band-role-assignment
  permutation are *unresolved*.
- The IDWT itself is canonically Le Gall 5/3 (CDF 5/3 lifting).
  CDF 9/7 is mentioned in the patent but no fixture has surfaced that
  requires it.

What is still missing (next research milestones):

1. **LibRaw-backed JNI ground truth.** Promote the existing fixture
   panel to pixel-exact regression tests by pinning the expected
   per-plane output against LibRaw's decoder. Without ground truth,
   the harness's hypothesis space is exhausted.
2. **Entropy-lock-loss in `decodePlane`.** Three of four Bayer planes
   in the flat fixture saturate at 16320 even after the predictor-seed
   fix; this is README Finding 1 ("Per-Bayer-plane breakdown") and is
   the single remaining production bug.

---

## 6. Test corpus (fixtures in git)

Located under `canon-cr3-imageio/src/test/resources/samples/`:

| File | Source | Codec | Use |
|---|---|---|---|
| `sample.cr3` | EOS R | lossless | smoke test for the full reader pipeline. |
| `sample-iso-high.cr3`, `sample-tele.cr3`, `sample-wb-tungsten.cr3`, `sample-wide.cr3` | EOS R | lossless | parameter coverage. |
| `eosr-iso-low/mid/high.cr3` | EOS R | lossless | ISO panel for predictor research. |
| `research/g5xm2-detail-…-iq3-…-02.cr3` | G5X II | **C-RAW** (L=3, q≠4) | the discriminating fixture for K_INIT and the Round-13 sweeps. |
| `research/g5xm2-detail-…-iq3-…-03.cr3` | G5X II | C-RAW | second copy for cross-validation. |

Provenance for every fixture is in
`canon-cr3-imageio/src/test/resources/samples/research/manifest.tsv`.

The three locally-captured but untracked fixtures
(`g5xm2-asis-*`, `g5xm2-dark-*`, `g5xm2-flat-*`) should be committed —
see `BACKUP.md` §"Action: commit the missing CR3 fixtures".

---

## 7. References

External references that informed this document (none redistributed):

- ISO/IEC 14496-12 — ISO Base Media File Format.
- ITU-T T.87 (JPEG-LS) — MED predictor and adaptive Golomb coding.
- US patent application US 2016/0323602 A1 (Canon) — CRX wavelet
  bitstream description.
- Canon CCAPI / EDSDK reference PDFs (private, not redistributed).

In-repo references that contain the actual measurements:

- `canon-cr3-core/SubbandFixtures.md` updates (a)–(n).
- `canon-cr3-imageio/src/test/resources/samples/research/README.md`
  updates (a)–(p).
