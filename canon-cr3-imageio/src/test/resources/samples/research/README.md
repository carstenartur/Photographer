# Cleanroom CR3 Decoder Research Fixtures

CR3 frames captured directly from a Canon **PowerShot G5 X Mark II** over
USB by `CleanroomCr3FixtureCaptureIT` (in the `camera-ptp` module). Each
file's exposure settings are recorded in [`manifest.tsv`](manifest.tsv) so
the cleanroom decoder can be cross-validated against bitstreams whose
sensor gain / shutter / aperture are *known* — not just inferred from EXIF
inside the same file under test.

## File naming

```
g5xm2-<phase>-iso<ISO>-ss<shutter>-f<aperture>-iq<imageQualityRaw>-<runId>-<seq>.cr3
```

The label fields are sanitized renderings of the value the camera reports
*after* the property write (with `/` and `.` stripped, `"` → `s`). The
`iq<...>` segment is the lower-case hex of the Canon EOS
`DPC_EOS_IMAGE_QUALITY` (0xD108) raw value as last broadcast in a GetEvent
batch — `iqx` means the camera had not emitted the property yet
(common on the first capture after USB connect; subsequent shots populate
it). The `runId` (UTC-ish wall clock at test start) keeps successive
runs from overwriting each other when the operator captures the same
settings twice.

## Capture phases

| phase | description |
|-------|-------------|
| `asis` | Camera was left in whatever PASM mode the operator dialed in; settings read back from the body. Always runs. |
| `isosweep` | Best-effort ISO sweep `0x4B..0x80` (ISO 125 → 12 800). On the **PowerShot G5 X Mark II this phase always fails** with `Device_Busy 0x2019` — the body refuses `SetDevicePropValueEx(0xD103)` regardless of mode dial position or event-loop suspension. The phase is kept for EOS-DSLR/R compatibility. |

### Why ISO writes are rejected on the G5X II

Canon's PSREC firmware on the G-series PowerShots gates the EOS vendor
property writes (`OC_EOS_SET_DEVICE_PROP / 0x9110`) much more aggressively
than the EOS DSLR/R lineup. Even with the camera in **M** mode and the
event loop suspended, the body returns response code `0x2019` (Device_Busy)
to writes against:

* `0xD101 EOS_APERTURE`
* `0xD102 EOS_SHUTTER_SPEED`
* `0xD103 EOS_ISO`

This was confirmed empirically across runs `20260504-182542`, `-183230`
and `-183410`. Reads of these properties (via `GetEvent / 0x9116`) work
fine — only the writes are blocked. The IT records the rejection in the
log and falls through; Phase A still collects the requested frames.

## Building a multi-ISO fixture panel manually

Because Phase B does not work on the G5X II, the supported workflow is:

1. **Mode dial → M.** Dial in the next ISO / shutter / aperture
   combination on the body itself.
2. **Run the IT.** It will capture a 5-frame burst at the current settings
   and tag every file + manifest row with the values it reads back from
   the body (so the operator does not have to remember what was set).

   ```cmd
   mvn -B verify -pl camera-ptp -am ^
       -Dptp.test.usb=true -DexcludedGroups= -Djava.awt.headless=true ^
       -Dit.test=CleanroomCr3FixtureCaptureIT
   ```

3. **Repeat** with the next ISO. Files accumulate in this directory and
   the manifest is appended (each run gets a fresh `runId`).

A useful starter panel is ISO 125 / 200 / 400 / 800 / 1600 / 3200 / 6400 /
12 800 — eight runs, ~5 frames each, ~40 fixtures total ≈ 720 MB. That is
enough to triangulate the relationship between `CMP1.quantValue`,
per-plane K-init seeds, and analog gain.

## Pairing fixtures with the decoder research harness

The cleanroom decoder research (under
`canon-cr3-imageio/src/test/java/com/photographer/focusstack/cr3/research`)
loads everything in `samples/` plus this `samples/research/` subfolder.
Use the manifest column `isoRaw` as the predicted gain bin when comparing
`CMP1.quantValue` across frames.

## What the cleanroom decoder still needs (priority-ordered)

The current ISO-200 / 1/30 s / f/2.5 panel is enough to start work on the
container parser, the CMP1 / IAD1 boxes, and the predictor seed, but the
bitstream decoder will move much faster with a few **scene-controlled**
captures. Set the scene with `-Dfixture.scene=<name>`:

```cmd
:: 1) lens cap on, manual focus → far, shutter speed ≥ 1/60 to avoid LV
mvn -B verify -pl camera-ptp -am ^
    -Dptp.test.usb=true -DexcludedGroups= -Djava.awt.headless=true ^
    -Dit.test=CleanroomCr3FixtureCaptureIT -Dfixture.scene=dark
```

| priority | scene tag | what to point at | why the decoder needs it |
|---------:|-----------|------------------|--------------------------|
| 🔴 high  | `dark`    | **lens cap on** (record once at base ISO and once at high ISO) | Pins the optical-black pedestal value, lets us verify that the inverse wavelet collapses HH/HL/LH to ≈0, and confirms the rice-golomb decoder produces almost-only short unary runs. This is the single most diagnostic frame for a cleanroom CR3 codec. |
| 🔴 high  | `flat`    | defocused white wall (or the inside of a softbox) | Uniform LL, near-zero high-freq. Isolates lens shading / vignetting from sensor non-uniformity, and gives the LL band a known-flat ground truth for IDWT validation. |
| 🟠 med   | `clip`    | bright window or sun (auto exposure off, > 4 stops over) | Pins the upper saturation branch in the predictor; lets us verify the white-level clip and the Bayer per-channel ceilings. |
| 🟠 med   | `detail`  | textured fabric, foliage, fine print | Exercises the full k-update range in the rice-golomb adaptive coder — covers all the mid-range branches that `dark`/`flat` skip. |
| 🟢 low   | `asis`    | *current panel* | Already covered (15 frames). |

For each scene, take the burst at the **lowest available ISO (125)** and
again at **a high ISO (3200 or 6400)**. That gives the decoder a noise
floor at both ends without needing a USB-driven sweep.

### Reproducible focus / exposure tips

The G5X II's PSREC firmware refuses USB writes for ISO/Tv/Av (see above).
For the scenes above, dial them in on the body and verify the manifest
records the values the body actually uses. For `dark`, make the cap-on
shot easier by:

1. **Mode dial → M**, ISO 125, shutter 1/60 s, f/8.
2. **Switch to manual focus** (touchscreen or dial), focus to infinity.
3. **Lens cap on**, then run the IT with `-Dfixture.scene=dark`.

### CCAPI fallback

If you are willing to switch the camera into Canon CCAPI mode (Wi-Fi /
USB-CDC HTTP API) you can drive the body with full property write access
including ISO. The current `camera-ptp` module only speaks PTP; a CCAPI
backend would be a separate module / transport. Until that lands, the
manual mode-dial workflow above is the supported path.

## Capturing C-RAW (lossy CRX) fixtures — *strongly desired*

Update (l) of `canon-cr3-core/SubbandFixtures.md` shows that the cleanroom
research is currently blocked by the absence of a fixture with
`CMP1.quantValue ≠ 4`. Every existing lossless fixture in the corpus
reports `q = 4` on every plane, so the strongest current empirical rule
(`K_INIT = CMP1.quantValue`) cannot be discriminated from
`K_INIT = 4 (constant)`.

The G5X II's **C-RAW** mode is the cheapest source of `q ≠ 4` bitstreams
because Canon's lossy CRX wavelet quantiser scales the per-subband `q`
with both ISO and target ratio. A panel that includes one C-RAW burst at
each of ISO 125 / 1600 / 6400 against a `detail` scene would unblock the
hypothesis check.

### Recommended C-RAW workflow on the G5X II

The PSREC firmware on the G-series PowerShots **rejects writes to most
EOS properties** including `0xD103 ISO`, `0xD102 Tv`, `0xD101 Av`. Empirical
data on `0xD108 ImageQuality` is not yet available — the IT now exposes
a best-effort probe so the operator can measure the result on their body:

```cmd
:: 1) On the camera body, dial in M mode + ISO 125 + 1/60 s + f/8
::    + record-quality "C-RAW" (Menu → Image Quality → C RAW).
:: 2) Optionally probe whether the body lets us flip ImageQuality
::    over USB. The rawValue you should pass is the value the camera
::    reports back (visible in the `iq<...>` segment of an existing
::    asis fixture filename, or in `manifest.tsv`'s imageQualityRaw
::    column). Common Canon EOS values:
::      0x10010003   RAW (lossless)
::      0x10130003   C-RAW (lossy)
mvn -B verify -pl camera-ptp -am ^
    -Dptp.test.usb=true -DexcludedGroups= -Djava.awt.headless=true ^
    -Dit.test=CleanroomCr3FixtureCaptureIT ^
    -Dfixture.scene=detail ^
    -Dfixture.imageQualityRaw=0x10130003
```

After the run, inspect the `iq<...>` segment of the resulting filenames
and the `imageQualityRaw` column in `manifest.tsv`:

* If the IT logs `ImageQuality probe write ACCEPTED` and the captured
  files carry `iq10130003` (or whatever C-RAW raw value the body
  reports), the over-USB flip works on this body — record the raw value
  in this README so future runs can reuse it.
* If the IT logs `ImageQuality probe write REJECTED` (most likely on the
  G5X II based on the ISO/Tv/Av precedent), fall back to the manual
  workflow:
  1. Mode dial → M, ISO 125, 1/60 s, f/8.
  2. Menu → Image Quality → **C RAW** (the CR3 file size will drop from
     ~18 MB to ~7–9 MB — visible in `manifest.tsv`'s `bytes` column).
  3. Run the IT with `-Dfixture.scene=detail`. The captured frames will
     carry the C-RAW `imageQualityRaw` in their filename automatically.
  4. Repeat steps 1–3 at ISO 1600 and ISO 6400 (panel of three).
  5. Switch the body back to **RAW** for normal operation.

The first C-RAW frame committed to this directory should ideally be paired
with a same-scene RAW frame at the same ISO so that the two `quantValue`
vectors can be compared side-by-side.

```

## Findings against the operator-shot fixtures (2026-05-04)

### Burst-level histogram (whole-image)

| scene | mode bucket | fraction within ±1024 of mode | std dev (LSB) | physical expectation |
|-------|-------------|-------------------------------|---------------|----------------------|
| `dark` (cap on, ISO 125, 1/60 s, f/7.1) | **16320** | 0.41 | — | mode ≈ 512–2000 (OB pedestal); ≥ 0.80 within ±1024 |
| `flat` (defocused white, ISO 1600, 0.4 s, f/1.6) | **16320** | 0.18 | **4956** | mode somewhere mid-grey; ≥ 0.50 within ±256; std dev ≤ 0.05 × mode |

The current `CrxLosslessRawDecoder` (JPEG-LS approximation) puts both
scenes' histogram peak at near-saturation (16320 of 16383). That is the
"wrong predictor / wrong K-update" symptom predicted by the patent-spec
analysis.

### Per-Bayer-plane breakdown (key diagnostic)

Plane 0 = even row / even col, plane 1 = even/odd, plane 2 = odd/even,
plane 3 = odd/odd. CRX emits these four planes independently, so a
plane-by-plane diagnostic isolates plane-uniform bugs from plane-specific
ones.

The per-plane breakdown across 4 dark and 4 flat fixtures (logged by
`DarkFixtureLosslessDecoderIT` / `FlatFixtureLosslessDecoderIT`) reveals
two patterns the cleanroom decoder must reproduce:

1. **Most planes saturate at 16320** with std dev 5000–7000 LSB — entropy
   decoder lock loss after a few hundred symbols.
2. **One plane in one flat fixture decoded almost correctly** (mode 8704,
   std dev 1050, ±1024 coverage 0.68). This is empirical proof that the
   *bitstream segmentation* and the *per-plane subband layout* are correct
   — the corruption is the per-symbol entropy state, not the plane
   addressing. That narrows the cleanroom search drastically: the bug is
   in the predictor / K-update inside `decodePlane`, not in
   `CrxHeader.parse` or `interleavePlanesByPosition`.

### Implication for next-iteration decoder work

Because the plane-uniform pattern dominates and one near-correct plane
exists, the next concrete production change should be:

* **In `CrxLosslessRawDecoder.decodePlane`:** replace the JPEG-LS K
  accumulator with the patent's per-symbol K bracket (`K* = floor(log2(s
  + 1))`, K ∈ [K* − 1, K* + 1]). The seed `K_INIT = 0` is correct (see
  Update (n) below); a previous edit of this README recommended seeding
  K from `CMP1.quantValue`, but that recommendation has been falsified
  by `CrxPlaneFollowupRound9IT` and is retracted.
* **Predictor seed:** the current `1 << (bitDepth − 1)` (= 8192 at 14-bit)
  is wrong for a flat-field input near the OB pedestal — change to `0`
  (matching the Round-8 `LZ_TILE_FIRST` baseline) and let the first
  residual carry the pedestal value.

These two changes together should drop the dark `mode bucket` from 16320
to something near the OB pedestal and push the flat `fractionWithin1024`
above 0.5 — the first hard regression-tightening that the
`Dark/FlatFixtureLosslessDecoderIT` tests are pre-wired to assert as soon
as it lands.

### Update 2026-05-11 (n) — `K_INIT = quantValue` retracted

`CrxPlaneFollowupRound9IT` ran a ground-truth-free bits-per-symbol
discrimination on the first C-RAW fixture in the corpus
(`g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3`,
`q ∈ {4, 16, 22, 26, 32}` per subband — Update (m) of
`canon-cr3-core/SubbandFixtures.md`).

Result: **`K_INIT = 0` is the empirical optimum on 100 % of 160
subbands across two frames**. Seeding `K_INIT = q` for `q ≥ 16`
overruns the Rice remainder past end-of-stream inside the first
symbol (`bps = ∞` for 128 / 160 subbands). The previous
"Round-8 winner" `K_INIT = CMP1.quantValue` was a circular artefact
of every earlier fixture having `q = 4`, in which case
`K_INIT = q ≡ K_INIT = 4 ≡ K_INIT = 0`-after-bracket-decay.

The production decoder already uses `int k = 0;` (line 406 of
`CrxLosslessRawDecoder.java`); Round-9 has now empirically validated
that choice. No production code change is warranted.
