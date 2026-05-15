# Cleanroom Fixture Plan — CR3 / C-RAW Phase A continuation

**Status:** Active plan. Strictly *cleanroom*: no third-party decoder sources
may be referenced when designing or interpreting these fixtures. No
production code changes follow from this document — it only specifies the
*data* that needs to enter the corpus and the diagnostic ITs that will
consume it.

This plan is the entry document for Phase A (data-driven black-box
diagnosis). It exists because, after Round-9 (Update n) and Round 1 of the
Phase-A WP1–WP4 batch (Update p), the limit on further progress is no
longer "more decoder hypotheses" but **the corpus itself**. The single
true burst pair in the corpus is C-RAW, the production lossless decoder
cannot yet score on it, and several physical falsifiers (OB plausibility,
flat-field flatness, null-fixture pedestal stability) are starved of the
specific scenes they need.

The plan is split into

1. Goal of this round of fixture acquisition.
2. Cameras, scene types, capture parameters.
3. Per-fixture rationale (what each fixture proves or falsifies).
4. Gap analysis vs. the existing corpus.
5. How each fixture is automatically picked up by the existing ITs.
6. High-value missing burst fixtures (explicit list).
7. Hard rules.

The plan is intentionally minimal — every entry must be defensible by
*how it shifts a single, currently undecidable question*.

---

## 1. Goal

Maximum diagnostic gain per new file. The success criterion is *not* "more
samples" — it is "the matrix of currently-undecidable hypotheses gets
strictly smaller after the new fixtures land".

Concretely, after this round of capture should be possible:

* Compute burst-pair Pearson r / MAE / row-col stability on a **lossless**
  burst pair on at least one camera (today: blocked, only C-RAW burst pair
  exists).
* Compare an OB-pedestal estimate from a **dark / lens-cap-on** fixture
  against the IAD1 OB-rectangle decode of the same camera in a **flat**
  fixture (today: blocked — there is exactly one dark fixture per camera
  and not always one flat fixture per camera, so cross-fixture stability
  cannot be measured).
* Hold scene constant and toggle only `imageQualityRaw` to obtain a true
  RAW vs. C-RAW pair on the same camera body and same scene (today:
  blocked — the C-RAW pair is detail-only, the lossless fixtures are
  asis/dark/flat with very different scenes).

---

## 2. Cameras

Two cameras are in scope for this round:

| Camera        | Why                                                        |
|---------------|------------------------------------------------------------|
| Canon G5 X II | Already produces all five sample classes in `samples/research/`. Cleanroom decoder is currently calibrated against `g5xm2-*` lossless fixtures. CMP1.cfaLayout = `1=GRBG`, numPlanes = 4. |
| Canon EOS R   | Three EXIF-bearing lossless fixtures already exist (`eosr-iso-{low,mid,high}.cr3`), but no dark/flat/burst/C-RAW. Adding the missing classes here turns the EOS R from a single-axis ISO sweep into a proper second-camera baseline that lets the cleanroom decoder be tested across camera bodies. |

A third camera body should *not* enter the corpus until the matrix below is
complete on these two — adding a third body now would dilute every
diagnostic without resolving any open question.

---

## 3. Per-camera fixture matrix

Each row is one new CR3 file. "ISO / SS / f / focus / zoom / cap" are the
**body settings** to use. "Diagnostic value" lists which IT and which
falsifier the file unlocks. The naming convention follows the existing
`g5xm2-<scene>-iso<…>-ss<…>-ff<…>[-iq<hex>]-<runId>-<seq>.cr3` pattern.

### 3.1 Dark / null fixtures (lens cap on, no scene)

Goal: produce a near-information-free input so the decoder's **state
semantics** become visible — what is left after the predictor and the
entropy state have run on a flat sensor signal must be the pedestal,
plus read noise, plus any residual decoder state error.

| File                                                       | Camera | Mode      | ISO  | SS    | f    | focus | zoom    | cap | Diagnostic value |
|------------------------------------------------------------|--------|-----------|------|-------|------|-------|---------|-----|------------------|
| `g5xm2-dark-iso100-ss1p4000-ff8-<runId>-01.cr3`            | G5XII  | RAW       | 100  | 1/4000| f/8  | manual @ ∞ | 24mm | yes | Lowest-noise pedestal reference. NullFixtureDiagnosticIT must show stdDev ≈ read-noise floor. Falsifier for any decoder that emits low-frequency banding. |
| `g5xm2-dark-iso12800-ss1p4000-ff8-<runId>-01.cr3`          | G5XII  | RAW       | 12800| 1/4000| f/8  | manual @ ∞ | 24mm | yes | High-ISO dark — pedestal stays ~equal but read noise scales. Discriminates "decoder breaks at high ISO" from "decoder breaks on bright scene". |
| `g5xm2-dark-iso100-ss30s-ff8-<runId>-01.cr3`               | G5XII  | RAW       | 100  | 30s   | f/8  | manual @ ∞ | 24mm | yes | Long-exposure dark — surfaces hot-pixel patterns. The hot-pixel positions must be **identical** across two consecutive exposures of this kind; if the decoder produces different hot-pixel positions, the decoder has frame-dependent state. |
| `g5xm2-dark-iso100-ss30s-ff8-<runId>-02.cr3`               | G5XII  | RAW       | 100  | 30s   | f/8  | manual @ ∞ | 24mm | yes | Pair with the above for hot-pixel-position stability. |
| `g5xm2-dark-iso1600-ss1p4000-ff8-iq3-<runId>-01.cr3`       | G5XII  | C-RAW     | 1600 | 1/4000| f/8  | manual @ ∞ | 24mm | yes | C-RAW dark — needed because `g5xm2-dark-iso125-…02.cr3` is lossless. The pair lets one ask: does the C-RAW pipeline preserve the same pedestal as the lossless pipeline on the same physical scene? |
| `eosr-dark-iso100-ss1p4000-ff8-<runId>-01.cr3`             | EOS R  | RAW       | 100  | 1/4000| f/8  | manual @ ∞ | any  | yes | Same role as the G5XII low-ISO dark, on the second camera. |
| `eosr-dark-iso25600-ss1p4000-ff8-<runId>-01.cr3`           | EOS R  | RAW       | 25600| 1/4000| f/8  | manual @ ∞ | any  | yes | High-ISO dark on EOS R — discriminates pedestal-vs-read-noise on a different sensor. |

For all dark fixtures the lens cap **must be on** and the viewfinder
covered. SS is set to the camera's fastest shutter except for the long-
exposure pair. Any incidental light leak invalidates the fixture.

### 3.2 Flat fixtures (uniform, in-focus, no scene structure)

Goal: produce a uniformly illuminated frame so that *spatial variance*
inside the active area becomes itself a falsifier — a correct decoder must
produce a frame whose intra-plane stddev is dominated by photon shot
noise plus PRNU, not by structured banding.

| File                                                       | Camera | Mode      | ISO  | SS    | f    | focus | zoom    | cap | Diagnostic value |
|------------------------------------------------------------|--------|-----------|------|-------|------|-------|---------|-----|------------------|
| `g5xm2-flat-iso100-ss1p60-ff5p6-<runId>-01.cr3`            | G5XII  | RAW       | 100  | 1/60  | f/5.6| manual @ ∞ | 24mm | no  | Mid-exposure low-ISO flat — reference for the FlatFieldDiagnosticIT row/col flatness metric. |
| `g5xm2-flat-iso100-ss1p60-ff5p6-iq3-<runId>-01.cr3`        | G5XII  | C-RAW     | 100  | 1/60  | f/5.6| manual @ ∞ | 24mm | no  | RAW/C-RAW flat pair — same scene, only `imageQualityRaw` toggled. Allows a *direct* metric comparison of lossy artefacts on a near-uniform field. |
| `g5xm2-flat-iso800-ss1p250-ff5p6-<runId>-01.cr3`           | G5XII  | RAW       | 800  | 1/250 | f/5.6| manual @ ∞ | 24mm | no  | Higher ISO flat — must show same flatness modulo noise. |
| `eosr-flat-iso100-ss1p60-ff5p6-<runId>-01.cr3`             | EOS R  | RAW       | 100  | 1/60  | f/5.6| manual @ ∞ | any  | no  | Cross-camera flatness baseline. |

How to achieve a flat field without a calibration light box: shoot a
**defocused** white wall illuminated by a single diffuse source, or a
plain LED panel through a diffuser sheet. Bracket exposure ±1 EV in
post-capture so that none of the pixels are saturated and none are at
pedestal — a plausible flat sits in the upper-mid range of the 14-bit
output (typ. 6 000–10 000 LSB).

### 3.3 Detail fixtures (rich texture, in focus)

Goal: maximise per-pixel entropy so that a *wrong* decoder output is
visibly different from the embedded preview.

| File                                                       | Camera | Mode      | ISO  | SS    | f    | focus | zoom    | cap | Diagnostic value |
|------------------------------------------------------------|--------|-----------|------|-------|------|-------|---------|-----|------------------|
| `g5xm2-detail-iso100-ss1p125-ff5p6-<runId>-01.cr3`         | G5XII  | RAW       | 100  | 1/125 | f/5.6| AF on  | 24mm | no  | Lossless detail — the lossless companion of the existing C-RAW detail pair. Without this, every detail-class observation is C-RAW-only. |
| `g5xm2-detail-iso100-ss1p125-ff5p6-iq3-<runId>-01.cr3`     | G5XII  | C-RAW     | 100  | 1/125 | f/5.6| AF on  | 24mm | no  | RAW/C-RAW detail pair on the same scene. Must coincide modulo lossy-quantisation noise. |
| `eosr-detail-iso100-ss1p125-ff5p6-<runId>-01.cr3`          | EOS R  | RAW       | 100  | 1/125 | f/5.6| AF on  | any  | no  | Detail on EOS R — the existing eosr-iso-* fixtures are presumably real scenes but no manifest documents the scene. |

Any newsprint, a focus chart, or a leafy outdoor scene works as detail.
The only requirement is *no two adjacent fixtures in this row may share
the same scene*, so that a decoder that always reproduces a memorised
preview cannot fake correctness.

### 3.4 Burst fixtures (true body-side bursts, identical settings)

Goal: provide the *primary new metric* (burst stability) with actual data.
Today the BurstFrameStabilityIT writes an empty TSV because the only
burst pair in the corpus is C-RAW.

| File group                                                 | Camera | Mode      | ISO  | SS    | f    | focus | zoom    | cap | Diagnostic value |
|------------------------------------------------------------|--------|-----------|------|-------|------|-------|---------|-----|------------------|
| `g5xm2-burst-iso400-ss1p500-ff4-<runId>-{01,02,03,04}.cr3` | G5XII  | RAW       | 400  | 1/500 | f/4  | AF locked | fixed | no | Lossless 4-frame burst on a static outdoor scene. Unblocks WP1 in full. |
| `g5xm2-burst-iso400-ss1p500-ff4-iq3-<runId>-{01,02,03,04}.cr3` | G5XII | C-RAW | 400 | 1/500 | f/4 | AF locked | fixed | no | C-RAW burst on the *same scene* as the lossless burst. Lets WP1 measure the burst-stability *delta* between RAW and C-RAW pipelines. |
| `eosr-burst-iso400-ss1p500-ff4-<runId>-{01,02}.cr3`        | EOS R  | RAW       | 400  | 1/500 | f/4  | AF locked | any  | no | Lossless burst pair on EOS R. Even a 2-frame pair is enough for the IT. |

Hard requirement: AF is locked (manual focus or AF-S with focus locked
*before* the burst), the camera is on a tripod, the scene contains no
moving subjects, and the burst is captured in a single shutter press
(continuous high mode). Any user re-aim between frames invalidates the
pair — cleanroom analysis must not have to model registration error.

### 3.5 RAW/C-RAW paired captures (same scene, only IQ toggled)

Goal: hold the scene fixed and vary only the codec mode. This is the
single most useful experiment for separating "decoder bug" from "scene-
specific artefact".

The pairs needed are the three explicit `iq3` rows already listed in
§3.2 (flat) and §3.3 (detail), plus the dark pair listed in §3.1. No
additional pairings are required in this round, but if budget permits a
tele-scene pair would be useful too:

| File                                                       | Camera | Mode      | ISO  | SS    | f    | focus | zoom    | cap | Diagnostic value |
|------------------------------------------------------------|--------|-----------|------|-------|------|-------|---------|-----|------------------|
| `g5xm2-tele-iso400-ss1p250-ff4-<runId>-01.cr3`             | G5XII  | RAW       | 400  | 1/250 | f/4  | AF on  | 120mm | no | Tele scene RAW — companion of the legacy `sample-tele.cr3`. |
| `g5xm2-tele-iso400-ss1p250-ff4-iq3-<runId>-01.cr3`         | G5XII  | C-RAW     | 400  | 1/250 | f/4  | AF on  | 120mm | no | Tele scene C-RAW. |

---

## 4. Gap analysis vs. the current corpus

Inventory as of this writing (verified via `cr3-cli list-subbands` and the
`samples/research/manifest.tsv` plus `samples/`):

| Class                                  | Present today                                                                                  | Missing |
|---------------------------------------|-----------------------------------------------------------------------------------------------|---------|
| Lossless dark, G5XII                  | `g5xm2-dark-iso125-…02.cr3` (single fixture, ISO 125, SS 1/60)                                | low-ISO/high-ISO/long-exposure variants; long-exposure pair for hot-pixel stability |
| Lossless flat, G5XII                  | `g5xm2-flat-iso1600-…02.cr3` (single fixture, only ISO 1600)                                  | low-ISO flat, mid-ISO flat |
| Lossless detail, G5XII                | `g5xm2-asis-iso200-…01.cr3` (asis ≈ in-the-wild, but not "rich texture") + legacy `sample.cr3`| an explicitly-tagged detail fixture |
| C-RAW any class, G5XII                | `g5xm2-detail-iq3-…02.cr3` + `…03.cr3`                                                        | C-RAW dark, C-RAW flat, C-RAW low-ISO detail |
| Lossless burst, any camera            | none                                                                                           | **all** — primary blocker for WP1 |
| C-RAW burst, any camera               | exactly one pair: `g5xm2-detail-iq3-…02/03.cr3`                                               | a same-scene lossless companion to disambiguate codec from scene |
| Lossless dark / flat / detail, EOS R  | none — only `eosr-iso-low/mid/high.cr3` ISO sweep                                              | **all** scene classes |
| C-RAW any class, EOS R                | none                                                                                           | **all** |
| RAW/C-RAW pair on identical scene     | none                                                                                           | **all** |

The "Missing" column is the actual shopping list. Of these, the highest-
priority items (by diagnostic value × current blocking power) are the
**lossless burst** rows and the **RAW/C-RAW pairs**.

---

## 5. How each new fixture is automatically picked up

The following ITs walk `samples/` and `samples/research/` recursively at
each run. Dropping a new `.cr3` into `samples/research/` is enough — no IT
edit is required:

| New fixture lands in `samples/research/` and …       | … is automatically consumed by                                                  |
|-----------------------------------------------------|----------------------------------------------------------------------------------|
| any new file at all                                  | `FixtureIntelligenceReportIT` — re-classifies it as NEW / DUP / BURST and emits one TSV row, including the `fixtureClass` and useful-for-* tags introduced in this round |
| any dark fixture (filename contains `-dark-`)        | `NullFixtureDiagnosticIT` — adds rows to `target/null-fixture/null.tsv` per plane |
| any flat fixture (filename contains `-flat-`)        | `FlatFieldDiagnosticIT` — adds rows to `target/flat-field/flat.tsv` per plane |
| any new burst pair (same camera + scene + exposure + runId, two consecutive seq numbers) | `BurstFrameStabilityIT` — adds rows to `target/burst-stability/burst.tsv` per plane |
| any fixture with an IAD1 OB rectangle                | `Iad1OpticalBlackProfileIT` — adds rows to `target/iad1-ob-profile/ob-profile.tsv` per (region, plane) |
| any fixture                                           | `BitstreamPlaneAlignmentIT` — adds rows to `target/bitstream-alignment/alignment.tsv` per (tile, plane) |

In other words: this plan does not require any further test-code changes
once it is executed. Capture the files, drop them in, run `mvn verify`.

---

## 6. High-value missing burst fixtures (explicit list)

Re-stated separately because this is the single biggest gap:

1. `g5xm2-burst-iso400-ss1p500-ff4-<runId>-{01,02}.cr3` — **lossless** burst pair. Unblocks `BurstFrameStabilityIT` end-to-end.
2. `g5xm2-burst-iso400-ss1p500-ff4-iq3-<runId>-{01,02}.cr3` — same scene as #1 but C-RAW. Directly compares the burst stability of the two codecs on a controlled scene.
3. `eosr-burst-iso400-ss1p500-ff4-<runId>-{01,02}.cr3` — second-camera burst pair. Required to attribute any burst-pair instability to *codec* vs. *G5 X II body*.

Even just #1 alone is enough to flip Round 1's burst-TSV from empty to
populated, which is the single most expensive blocker in the project.

---

## 7. Hard rules

* **Cleanroom.** No fixture is acceptable that was acquired with the help
  of, or whose interpretation references, a third-party CR3 decoder.
* **No production changes follow from this plan.** It only specifies *data
  to capture* and *how the existing diagnostic ITs will consume it*.
* **No broad sweeps.** No new IT introduced in this round may iterate over
  > 32 candidate decoder configurations on a single fixture. The only
  decoder calls are to the *existing* `CrxLosslessRawDecoder` as a
  measurement probe.
* **Negative results stay negative.** If a planned fixture cannot be
  captured (e.g. no lens cap available for the EOS R kit lens), it stays
  on the missing list. The plan must not silently substitute a different
  fixture and pretend it covers the same falsifier.

---

## 8. Acceptance check

This plan is "complete" when, after the fixtures listed in §3 have landed,
the following tests stop reporting their current corpus-gap log lines:

* `BurstFrameStabilityIT` — must produce ≥ 1 row in `target/burst-stability/burst.tsv` for a **lossless** burst.
* `NullFixtureDiagnosticIT` — must report at least one cross-fixture pedestal-stability row per camera.
* `FlatFieldDiagnosticIT` — must report at least one (camera, ISO) flat row whose row/col flatness can be compared *across* fixtures.
* `Iad1OpticalBlackProfileIT` — must show OB pedestal medians whose cross-fixture variance per camera is ≤ 64 LSB (today: 392 → 14 452 LSB on `eosr`, i.e. obviously broken).
* `FixtureIntelligenceReportIT` — must report ≥ 4 distinct `fixtureClass` values per camera and ≥ 1 burst pair per camera in `useful-for-burst`.
