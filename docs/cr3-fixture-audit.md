# CR3 Fixture Audit

Strukturierte Bestandsaufnahme aller in `canon-cr3-imageio/src/test/resources/samples/` vorhandenen CR3-Testdateien und der daraus ableitbaren Header-/Payload-Informationen.

Die in diesem Dokument zitierten Werte wurden direkt aus den Fixtures extrahiert mit dem produktiven Parser-Stack (`Cr3Parser`, `Cmp1Box`, `Iad1Box`, `CrxHeader`, `Cr3MetadataReader`). Es wurden **keine** Produktionscode-Pfade verändert und es wurden **keine** neuen Decoder-Sweeps durchgeführt — Ziel ist nur die Auswertung des bestehenden Korpus.

Reproduzierbar (z. B. nach `mvn -B test-compile -pl canon-cr3-core,canon-cr3-imageio -am`) durch ein kleines Hilfsprogramm, das `Cr3Parser.parse`, `CrxHeader.parse` und `Cr3MetadataReader.read` aufruft (siehe Abschnitt 7).

---

## 1. Fixture-Inventar

### 1.1 Übersicht

| Datei | Kamera | Bytes | Mode | CNCV |
|---|---|---:|---|---|
| `eosr-iso-low.cr3`  | Canon EOS R | 40 282 915 | RAW lossless (CRX) | `CanonCR3_001/00.09.00/00.00.00` |
| `eosr-iso-mid.cr3`  | Canon EOS R | 40 252 603 | RAW lossless (CRX) | `CanonCR3_001/00.09.00/00.00.00` |
| `eosr-iso-high.cr3` | Canon EOS R | 40 243 665 | RAW lossless (CRX) | `CanonCR3_001/00.09.00/00.00.00` |
| `sample.cr3`               | PowerShot G5 X Mark II | 22 920 507 | RAW lossless (CRX) | `CanonCR3_001/01.09.00/00.00.00` |
| `sample-tele.cr3`          | PowerShot G5 X Mark II | 25 002 592 | RAW lossless (CRX) | `CanonCR3_001/01.09.00/00.00.00` |
| `sample-wide.cr3`          | PowerShot G5 X Mark II | 24 968 389 | RAW lossless (CRX) | `CanonCR3_001/01.09.00/00.00.00` |
| `sample-iso-high.cr3`      | PowerShot G5 X Mark II | 12 527 195 | RAW lossless (CRX) | `CanonCR3_001/01.09.00/00.00.00` |
| `sample-wb-tungsten.cr3`   | PowerShot G5 X Mark II | 12 324 900 | RAW lossless (CRX) | `CanonCR3_001/01.09.00/00.00.00` |
| `sample-embedded.jpg`  | — | 952 248 | externer JPEG | n/a |
| `sample-thumbnail.jpg` | — | 3 775 | externes JPEG-Thumb | n/a |

### 1.2 EXIF-/Aufnahme-Metadaten

| Datei | Modell | ISO (EXIF) | f | t (s) | Brennweite | Sensor (CMP1) | Visible (IAD1-Crop) | Capture |
|---|---|---:|---:|---:|---:|---|---|---|
| `eosr-iso-low.cr3`  | Canon EOS R | **12 800** ⚠ | 4.0 | 0.004 | 24 mm | 6888 × 4546 | 6743 × 4499 | 2026-04-17 18:30:31 |
| `eosr-iso-mid.cr3`  | Canon EOS R | **12 800** ⚠ | 4.0 | 0.004 | 24 mm | 6888 × 4546 | 6743 × 4499 | 2026-04-17 18:30:37 |
| `eosr-iso-high.cr3` | Canon EOS R | **12 800** ⚠ | 4.0 | 0.004 | 24 mm | 6888 × 4546 | 6743 × 4499 | 2026-04-17 18:30:43 |
| `sample.cr3`             | G5 X Mark II | 125  | 2.8 | 0.3 | 8.8 mm | 5632 × 3710 | 5495 × 3667 | 2026-04-15 21:18:45 *(siehe Hinweis)* |
| `sample-tele.cr3`        | G5 X Mark II | 125  | 2.8 | 0.3 | 8.8 mm | 5632 × 3710 | 5495 × 3667 | 2026-04-15 21:18:51 |
| `sample-wide.cr3`        | G5 X Mark II | 125  | 2.8 | 0.3 | 8.8 mm | 5632 × 3710 | 5495 × 3667 | 2026-04-15 21:18:57 *(siehe Hinweis)* |
| `sample-iso-high.cr3`    | G5 X Mark II | 1600 | 2.8 | 0.3 | 8.8 mm | 5632 × 3710 | 5495 × 3667 | 2026-04-15 21:19:12 |
| `sample-wb-tungsten.cr3` | G5 X Mark II | 1600 | 2.8 | 0.3 | 8.8 mm | 5632 × 3710 | 5495 × 3667 | 2026-04-15 21:19:26 |

> ⚠ **Wichtigste neue Beobachtung:** alle drei `eosr-iso-*.cr3`-Dateien melden in EXIF identisch **ISO 12800** — der Dateiname suggeriert eine ISO-Variation, die im Korpus tatsächlich **nicht vorhanden** ist. Der Variations-Korpus für ISO existiert nur auf der G5 X II (125 ↔ 1600). Siehe §4.4.

> *Hinweis:* `sample-tele.cr3`, `sample-wide.cr3`, `sample.cr3` haben dieselbe Brennweite 8.8 mm in EXIF, sind also vermutlich Burst-Frames mit gleicher Optik (siehe §4.1) — die Dateinamen `tele`/`wide` spiegeln nicht zwingend reale Brennweitenunterschiede wider.

### 1.3 Preview / Thumbnail / Track-Layout

Alle CR3-Dateien haben **dieselbe** 4-Track-Struktur:

| trk | TrackType | Codec | Compression |
|---|---|---|---|
| 0 | `JPEG_BIG` (Full-size embedded JPEG) | `CRAW` (Container-Tag) | RAW_LOSSLESS |
| 1 | `CRX_SMALL` (1624 × 1080 RAW-Preview) | `CRAW` | RAW_LOSSLESS |
| 2 | `CRX_BIG`   (Sensor-RAW)              | `CRAW` | RAW_LOSSLESS |
| 3 | `UNKNOWN` / Codec `CTMD` (Canon Timed Metadata) | — | — |

PRVW: **alle** Fixtures haben dieselbe PRVW-Auflösung **1620 × 1080** (JPEG-Größe variiert je Bildinhalt: ~38–289 kB).
THMB: **alle** Fixtures haben dieselbe THMB-Auflösung **160 × 120** (JPEG ~2.3–8.9 kB).

### 1.4 CMP1 / CRX-Header pro Track

#### CRX_SMALL-Track (alle Fixtures)

| Feld | Wert |
|---|---|
| `imageWidth` × `imageHeight` | 1624 × 1080 |
| `tileWidth` × `tileHeight`  | 1624 × 1080 (1 Tile) |
| `bitsPerSample`             | 14 |
| `numPlanes`                 | 4 |
| `cfaLayout`                 | **1 = GRBG** |
| `encodingType`              | 0 (lossless) |
| `waveletLevels`             | 0 (kein Wavelet) |
| `multipleHTiles`            | false |
| `mdatHeaderSize`            | 112 |
| `hasExtendedHeader`         | false |
| `medianBitDepth`            | 0 |
| Tile-Anzahl im CRX-Header   | 1 |
| Plane-Anzahl pro Tile       | 4 |
| Subband-Anzahl pro Plane    | 1 (Index 0, lossless LL) |
| `quantValue` pro Subband    | **4** |
| `roundedBits` pro Plane     | 0 |
| `supportsPartial` pro Plane | true |
| `supportsPartial` pro Subband | false |

#### CRX_BIG-Track

| Feld | EOS R | G5 X II |
|---|---|---|
| `imageWidth` × `imageHeight` | 6888 × 4546 | 5632 × 3710 |
| `tileWidth` × `tileHeight`   | 3444 × 4546 (= W/2 horizontal split) | 2816 × 3710 (= W/2) |
| `bitsPerSample`              | 14 | 14 |
| `numPlanes`                  | 4 | 4 |
| `cfaLayout`                  | **0 = RGGB** | **0 = RGGB** |
| `encodingType`               | 0 | 0 |
| `waveletLevels`              | 0 | 0 |
| `multipleHTiles`             | false | false |
| `mdatHeaderSize`             | 216 | 216 |
| `hasExtendedHeader`          | false | false |
| `medianBitDepth`             | 0 | 0 |
| Tile-Anzahl im CRX-Header    | 2 | 2 |
| Plane-Anzahl pro Tile        | 4 | 4 |
| Subband-Anzahl pro Plane     | 1 (Index 0) | 1 (Index 0) |
| `quantValue` pro Subband     | 4 | 4 |
| `roundedBits` pro Plane      | 0 | 0 |
| `supportsPartial` pro Plane  | true | true |
| `supportsPartial` pro Subband| false | false |
| **Beide Tiles tragen `tileIndex == 0`** (Header-Bug-fixed via `tileSequence`) | ✓ | ✓ |

> **Kontrast CRX_SMALL ↔ CRX_BIG**: derselbe Sensor liefert in **CRX_SMALL** `cfaLayout = 1 (GRBG)` und in **CRX_BIG** `cfaLayout = 0 (RGGB)`. Das ist ein robust messbarer Unterschied, der bisher in noch keinem Test als Hypothese geprüft worden ist.

### 1.5 IAD1 / aktive Fläche, OB-Bereiche

| Datei | activeArea (L,T,R,B) | leftOB (L,T,R,B) | topOB (L,T,R,B) |
|---|---|---|---|
| EOS R (alle 3) – CRX_BIG | 144, 46, 6887, 4545 | 0, 0, 143, 4545 | 144, 0, 6887, 45 |
| G5 X II (alle 5) – CRX_BIG | 120, 28, 5615, 3695 | 0, 0, 119, 3695 | 120, 0, 5615, 27 |

Alle CRX_SMALL-Tracks haben einen leeren IAD1 (`activeArea` = 0,0,0,0) und nur den Crop-Rahmen — kein OB.

### 1.6 Plane-Payload-Größen (Bytes)

#### EOS R `eosr-iso-low.cr3` – CRX_BIG (repräsentativ; Tile-Größen variieren um ≤ 0.1% zwischen `low/mid/high`)

| Tile | p0 | p1 | p2 | p3 | Σ Tile |
|---|---:|---:|---:|---:|---:|
| 0 | 4 474 168 | 4 614 360 | 4 617 984 | 4 554 688 | 18 261 200 |
| 1 | 4 328 408 | 4 452 176 | 4 452 144 | 4 433 064 | 17 665 792 |

Plane-Größen sind **nahezu symmetrisch** (alle ≈ 4.4 MB, max-min Δ ≈ 4 %). Tile 0 ist konsequent ~3 % größer als Tile 1.

#### G5 X II `sample-iso-high.cr3` – CRX_BIG

| Tile | p0 | p1 | p2 | p3 | Σ Tile |
|---|---:|---:|---:|---:|---:|
| 0 | 938 816 | 540 176 | 538 712 | 3 473 408 | 5 491 112 |
| 1 | 1 018 720 | 532 392 | 532 056 | 3 461 808 | 5 544 976 |

> **Stark asymmetrisch:** Plane 3 ist ~ 6.5× größer als Planes 1 / 2. Diese Asymmetrie tritt **bei jeder G5 X II-Datei** auf, **nicht** bei der EOS R. (Erstmalige strukturierte Beobachtung: das ist kein Tile- oder ISO-Effekt, sondern ein **Kamera-Modell**-Effekt.)

#### G5 X II `sample-wb-tungsten.cr3` – CRX_BIG (gleiche Szene, andere WB)

| Tile | p0 | p1 | p2 | p3 | Σ Tile |
|---|---:|---:|---:|---:|---:|
| 0 | 906 568 | 519 592 | 518 856 | 3 473 208 | 5 418 224 |
| 1 | 980 624 | 500 392 | 500 008 | 3 451 288 | 5 432 312 |

Plane-Größen Δ vs. `sample-iso-high.cr3` (gleiche Szene, ISO 1600, andere WB-Vorgabe): ≤ 4 %. **WB-Vorgabe in der Kamera ändert die CRX-Payload-Größe kaum** — erwartbar, da WB nur als Metadaten gespeichert wird.

#### G5 X II `sample-tele.cr3` vs. `sample-wide.cr3` (vermutete Burst-Frames, ISO 125)

| Datei | Σ CRX_BIG | p3 / mean(p1,p2) |
|---|---:|---:|
| `sample-tele.cr3` | 21 514 960 | 5.4× |
| `sample-wide.cr3` | 21 481 549 (≈ tele) | 5.4× |
| `sample.cr3`      | ≈ 19.4 M    | 5.4× |

Alle drei ISO-125-Burst-Frames haben dieselbe asymmetrische p3-Dominanz und ähnliche Gesamtgröße (Δ ≤ 9 %).

---

## 2. Variabilitätsanalyse

### 2.1 Felder ohne Variabilität im aktuellen Korpus

| Feld | Wert (alle 8 CR3-Dateien) |
|---|---|
| `Cmp1Box.encodingType` | 0 |
| `Cmp1Box.waveletLevels` | 0 (lossless) |
| `Cmp1Box.bitsPerSample` | 14 |
| `Cmp1Box.numPlanes` | 4 |
| `Cmp1Box.multipleHTiles` | false |
| `Cmp1Box.hasExtendedHeader` | false |
| `Cmp1Box.medianBitDepth` | 0 |
| `Cmp1Box.mdatHeaderSize` | 112 (small) / 216 (big) |
| `CrxHeader.Subband.index` | 0 |
| `CrxHeader.Subband.quantValue` | 4 |
| `CrxHeader.Subband.supportsPartial` | false |
| `CrxHeader.Plane.roundedBits` | 0 |
| `CrxHeader.Plane.supportsPartial` | true |
| `Cr3Track.compressionMode` | RAW_LOSSLESS |
| `CncvBox` Codec-Major-Version | `CanonCR3_001` (V1-Marker ff01/ff02/ff03) |

→ Das gesamte Korpus enthält **keine** Variation in: encType, waveletLevels, bps, numPlanes, multipleHTiles, hasExtendedHeader, medianBitDepth, quantValue, roundedBits, supportsPartial, codec-version. Hypothesen, die diese Felder benutzen, sind im aktuellen Korpus **nicht falsifizierbar**.

### 2.2 Felder mit echter Aussagekraft

| Feld | Beobachtete Variation |
|---|---|
| `Cmp1Box.imageWidth/Height`      | 1624×1080 / 5632×3710 / 6888×4546 |
| `Cmp1Box.tileWidth/Height`       | gleich = 1 Tile, halbe Breite = 2 Tiles |
| `Cmp1Box.cfaLayout`              | **0 (RGGB)** für alle CRX_BIG vs. **1 (GRBG)** für alle CRX_SMALL |
| `Cr3Track.dataSize`              | 0.9–35 MB |
| `CrxHeader.Tile.dataSize`        | 0.9 MB (small) … 18.3 MB (eos-r big) |
| `CrxHeader.Plane.dataSize`       | sehr asymmetrisch bei G5 X II, symmetrisch bei EOS R |
| `Iad1Box.activeArea*`            | unterschiedlich pro Sensor |
| `Iad1Box.leftOb*` / `topOb*`     | EOS R: 144 px links + 46 px oben; G5 X II: 120 px + 28 px |
| `Cr3Metadata.cameraModel`        | "Canon EOS R" / "Canon PowerShot G5 X Mark II" |
| `Cr3Metadata.isoSpeed`           | 125 / 1600 (G5 X II); 12800 const (EOS R) |
| `Cr3Metadata.captureDateTime`    | sekundengenau, identifiziert Burst-Frames |
| Plane-Payload-Profile (p0/p1/p2/p3) | siehe §1.6 |

### 2.3 Felder, die noch nie in Tests/Hypothesen verwendet wurden

Aus der Codebasis verifiziert: in `CrxLosslessRawDecoder` und `EosRPipelineDiagnosticTest` werden tatsächlich **gelesen**: `cmp1.isLossless()`, `waveletLevels()`, `numPlanes()`, `tileWidth()`, `tileHeight()`, `mdatHeaderSize()`, `bitsPerSample()`, `subband.dataOffset()`, `subband.dataSize()`.

**Bisher völlig ungenutzt** (extrahiert aber nirgends in Logik konsumiert):

| Quelle | Feld | Warum interessant |
|---|---|---|
| `Cmp1Box` | `cfaLayout` (RGGB ↔ GRBG, real beobachtet zwischen big↔small Track) | Mosaikordnung wird heute hart aus Camera-Modell abgeleitet; CMP1 wäre die Quelle der Wahrheit |
| `Cmp1Box` | `encodingType`, `medianBitDepth`, `hasExtendedHeader` | im Korpus konstant, aber Decoder validiert sie nicht |
| `CrxHeader.Plane` | `roundedBits` | im Korpus immer 0; Decoder berücksichtigt es nirgends — Code-Pfad würde bei realen ≠0-Daten silently falsch dekodieren |
| `CrxHeader.Plane` | `supportsPartial` | true vs. false unterscheidet die Hierarchie-Ebene; bisher nirgends ausgewertet |
| `CrxHeader.Subband` | `quantValue` | konstant 4 im Korpus; in keinem Test dem Decoder als Quantizer übergeben |
| `Iad1Box` | `leftOb*`, `topOb*` Bereiche (143×4545 px Spalte links bei EOS R) | echte OB-Pixel — bisher nutzt nur `ObBlackLevelEstimator.estimate(rawImage, activeAreaLeft)` die Spalten*zahl*, **nicht** die OB-Box des IAD1 |
| `Iad1Box` | `cropLeft/Top/Right/Bottom` | im Decoder nirgends mit `metadata.visibleWidth/Height` konsistent kreuzgeprüft |
| `Cr3Track` | `tileSequence` (in `SubbandExtractor` gem. memory-fact, hier aber nicht im realen Code) | im aktuellen Code-Stand existiert `SubbandExtractor` nicht; tile-collision auf `tileIndex==0` ist nie getestet |
| Cross-file-Vergleich | EOS R liefert 3 Burst-Frames; Plane-Größen sind nicht verglichen worden | erlaubt Stabilitäts-/Rausch-Hypothesen ohne neue Captures |
| Cross-file-Vergleich | G5 X II Burst (`sample` / `sample-tele` / `sample-wide`) gleiche EXIF | siehe oben |

---

## 3. Nutzung vorhandener Informationen

### 3.1 Bereits ausgewertet

| Information | Verwendet von |
|---|---|
| Container-Struktur (FTYP/MOOV/MDAT, Track-Liste) | `Cr3VariantVerificationTest`, `Cr3MetadataReader`, `RealSampleCr3Test`, `EosRCr3ValidationTest` |
| PRVW + THMB Width/Height/Bytes | `Cr3VariantVerificationTest#containerStructure` |
| EXIF: model/iso/f/t/mm/dt | `Cr3VariantVerificationTest#metadataExtraction` |
| `Cmp1Box.imageWidth/Height/tileWidth/tileHeight/bitsPerSample/numPlanes/mdatHeaderSize` | `CrxLosslessRawDecoder.decode(...)` |
| `Cmp1Box.isLossless()` (= waveletLevels==0) | `CrxLosslessRawDecoder` (Guard: only lossless) |
| `CrxHeader.Subband.dataOffset/dataSize` | `CrxLosslessRawDecoder` (per-plane dispatch) |
| `Iad1Box.activeAreaLeft` (nur die Spaltenzahl) | `EosRPipelineDiagnosticTest` → `ObBlackLevelEstimator.estimate(raw, activeAreaLeft)` |
| Pipeline-Stufen (BlackLevel / WB / Demosaic / ColorMatrix / sRGB) end-to-end | `EosRPipelineDiagnosticTest`, `Cr3VariantVerificationTest#rawProcessedDecoding` |
| Camera-Modell → Color-Matrix-Lookup | `CameraColorMatrixRegistry`, `ColorMatrixProcessor` |

### 3.2 Extrahierbar, aber bisher **nicht** systematisch ausgewertet

1. **`cfaLayout`-Diskrepanz CRX_BIG (RGGB) vs. CRX_SMALL (GRBG)** für ein und dieselbe Datei — kein Test prüft das, kein Decoder-Pfad nutzt CMP1 statt Modellname.
2. **OB-Block-Geometrie aus `Iad1Box`** (`leftOb*` / `topOb*`-Boxen). Heute wird nur `activeAreaLeft` als Spaltenzahl benutzt; die vollständigen OB-Rechtecke (z. B. EOS R: `leftOb` 0..143 × 0..4545, `topOb` 144..6887 × 0..45) sind eine bessere Black-Level-Quelle und ungenutzt.
3. **Per-Plane Payload-Größen-Profil**. Beobachtung: G5 X II Plane 3 ist ~6.5× größer als Plane 1/2; EOS R ist ~symmetrisch. Erklärt potenziell, warum heutige Decoder-Sweeps am G5 X II andere Σ|r|-Werte produzieren als an der EOS R — Plane-3-Dominanz heißt: pro-Plane-Bit-Budget muss kameraabhängig kalibriert werden.
4. **EOS R Burst (3 Frames im Abstand von ~6 s, identische Szene/EXIF)**: bisher nirgends paarweise verglichen. Liefert kostenlosen "noise floor" für jede Pixel-für-Pixel-Hypothese (Rauschen sollte das einzige Differential sein).
5. **G5 X II Burst (`sample`/`sample-tele`/`sample-wide`, ISO 125, 6 s Spanne)** — analog, nie verglichen.
6. **`captureDateTime` als Burst-Detektor** — Testinfrastruktur identifiziert diese Frame-Paare nicht.
7. **`quantValue == 4` korpus-konstant**: bisher in keinem Decoder-Test als Argument übergeben. Solange das Korpus keine `quantValue ≠ 4`-Datei enthält, ist jede `K_INIT = quantValue`- vs. `K_INIT = const4`-Hypothese nicht trennbar.
8. **CRX-Header `tileIndex == 0` für beide Tiles** (Header-Bug oder spec). In keinem Test geprüft; kein Code-Pfad warnt. Decoder-Routing per `tileSequence` (Ordinal) statt `tileIndex` ist eine offene Korrektheitsfrage.
9. **PRVW-JPEG bei gleicher Auflösung 1620×1080 für alle Modelle** — als Ground-Truth-Referenz bei Pearson-r-Auswertungen wird die PRVW-Größe nicht überprüft (z. B. ob sie räumlich exakt zur CRX_SMALL-Ebene passt — `1620 ≠ 1624`!).
10. **`PRVW.width = 1620` vs. `CRX_SMALL.imageWidth = 1624`**: 4-Pixel-Diskrepanz, die in keinem Test sauber kalibriert ist.
11. **`Cmp1Box.tileWidth = imageWidth/2`** für CRX_BIG ist die einzige Quelle für die "2-Tiles"-Aufteilung — nirgends als Pre-Decode-Invariante geprüft.

### 3.3 Felder, die im Korpus nicht prüfbar sind

(Im Korpus konstant — kein Test, der mit diesen Werten arbeitet, kann sie heute negativ falsifizieren.)

`encodingType=0`, `waveletLevels=0`, `bps=14`, `numPlanes=4`, `multipleHTiles=false`, `hasExtendedHeader=false`, `medianBitDepth=0`, `roundedBits=0`, `supportsPartial(subband)=false`, `quantValue=4`, CNCV-Major `CanonCR3_001` (V1).

→ Für jede dieser Eigenschaften brauchen wir mindestens **ein** Fixture mit dem abweichenden Wert, um Decoder-Verhalten testen zu können. Siehe §6.

---

## 4. Cross-File-Strukturvergleich

### 4.1 Gleiche Kamera, gleiche Szene, benachbarte Frames

| Gruppe | Kamera | Frames | EXIF identisch | Plane-Größe Δ |
|---|---|---|---|---|
| `eosr-iso-{low,mid,high}.cr3` | EOS R | 3 (`18:30:31`, `:37`, `:43`, ~ 6 s) | model, iso, f, t, mm, sensor | ≤ 0.4 % pro Plane (siehe §1.6) |
| `sample.cr3` / `sample-tele.cr3` / `sample-wide.cr3` | G5 X II | 3 (`21:18:45`, `:51`, `:57`) | model, iso=125, f, t, mm | ≤ 9 % Σ Tile |

**Konsequenz**: pixelweise Differenzbild zweier Burst-Frames würde nahezu nur Rauschen enthalten und ist eine kostenlose Validierungs-Achse für jeden Pixel-Decoder.

### 4.2 Gleiche Kamera, unterschiedliche ISO

Nur G5 X II hat reale ISO-Variation: ISO 125 (`sample*.cr3`) ↔ ISO 1600 (`sample-iso-high.cr3`, `sample-wb-tungsten.cr3`).
EOS R hat keine ISO-Variation im Korpus (alle ISO 12800).

ISO 125 → ISO 1600 ändert die G5 X II CRX_BIG-Größe nicht systematisch (Σ Tile sample-iso-high = 5.49 + 5.54 MB ≈ 11 MB; sample-wide = 21.5 MB — der Faktor 2 erklärt sich nur teilweise durch ISO; die `sample-iso-high.cr3`-Datei ist ~½ so groß, weil sie vermutlich einfacher entropie-kodierbar ist (weniger Detail / mehr Sättigung), nicht weil das ISO direkt die Compression-Rate steuert).

### 4.3 Gleiche Kamera, gleiche Szene, unterschiedliche WB

`sample-iso-high.cr3` (ISO 1600, Auto-WB) vs. `sample-wb-tungsten.cr3` (ISO 1600, Tungsten):

| Plane | iso-high | wb-tungsten | Δ % |
|---|---:|---:|---:|
| Tile0 p0 | 938 816 | 906 568 | −3.4 % |
| Tile0 p1 | 540 176 | 519 592 | −3.8 % |
| Tile0 p2 | 538 712 | 518 856 | −3.7 % |
| Tile0 p3 | 3 473 408 | 3 473 208 | −0.0 % |

→ WB-Voreinstellung beeinflusst die rohe CRX-Payload nur **marginal** (RAW ist by-construction WB-unabhängig); die kleinen Differenzen erklären sich durch leicht unterschiedliche AE/Sensorrauschen zwischen den 14 Sekunden Aufnahme­abstand. **Bestätigt**: `sample-wb-tungsten.cr3` testet keine Decoder-Logik, sondern nur die WB-Multiplier-Pipeline (Stage 2).

### 4.4 Unterschiedliche Kameras

| Aspekt | EOS R | G5 X II |
|---|---|---|
| Sensor | 6888 × 4546 (Vollformat) | 5632 × 3710 (1″) |
| CMP1 für CRX_BIG | identisch (encType, waveletLevels, bps, planes, mdatHdr, cfa=RGGB) bis auf imageWidth/Height/tileWidth | dito |
| Plane-Symmetrie | symmetrisch (~4 % Δ) | stark asymmetrisch (p3 ≈ 6.5× p1/p2) |
| OB-Geometrie | links 144 px, oben 46 px | links 120 px, oben 28 px |
| ISO im Korpus | nur 12800 | 125 und 1600 |
| Anzahl Bursts | 1 (3 Frames) | 1 (3 Frames `sample*`) + 2 Einzelaufnahmen |

### 4.5 RAW vs. C-RAW

**Im Korpus existiert keine C-RAW (lossy) Datei.** Alle 8 Fixtures: `compressionMode = RAW_LOSSLESS`, `cmp1.encodingType = 0`, `cmp1.waveletLevels = 0`. → Der gesamte CRX-Lossy-Codec-Pfad (10 Subbänder, 3-Level-Wavelet, dequant, IDWT) ist **mit dem aktuellen Korpus überhaupt nicht testbar**.

---

## 5. Lücken im Korpus

Konkrete Konfigurationen, die für eine vollständige CRX-Decoder-Validierung **fehlen**:

| Lücke | Warum kritisch | Welches CMP1-Feld würde variieren |
|---|---|---|
| **C-RAW (lossy)** Datei einer beliebigen Kamera | Lossy-Codec-Pfad ist nicht abgedeckt | `encodingType ≠ 0`, `waveletLevels ∈ {1,2,3}`, 10 Subbänder, `quantValue ≠ 4` |
| **CanonCR3_002**+ Datei (z. B. R5, R6, 1DX III) | V2-Marker-Pfad (ff11/ff12/ff13) ist nirgends mit echten Daten getestet | `CncvBox.codecVersion()`, marker-Bytes |
| **EOS R bei ISO 100/400/3200** | Bisher nur ISO 12800 — keine ISO-Variation für Vollformat | `Cr3Metadata.isoSpeed`, OB-Statistik |
| **Datei mit `hasExtendedHeader=true`** (R5/R3 HDR-Modi) | Extended-Header-Parsing-Pfad ist mit echten Daten ungetest | `extendedHeaderSize`, `medianBitDepth` |
| **Datei mit `multipleHTiles=true`** | Multi-H-Tile-Layout ungetest | `multipleHTiles`, abweichende Tile-Anzahl |
| **Datei mit `roundedBits ≠ 0`** | ungetestet | `Plane.roundedBits` |
| **Datei mit Subband-Index > 0** (lossy) | nur Subband 0 im Korpus | `Subband.index` |
| **Identische Szene mit beiden Kameras** | erlaubt direkten kamera-übergreifenden Vergleich | n/a |

---

## 6. Antworten auf die Schlüsselfragen

### 6.1 *Haben wir aus den vorhandenen CR3-Testdateien bereits alle wesentlichen verfügbaren Informationen genutzt?*

**Nein.** Vom Produktionscode werden ~40 % der pro Datei extrahierbaren CMP1/CRX/IAD1-Felder konsumiert. Die übrigen 60 % werden zwar geparst (gut!), aber nirgends als Hypothesen-Eingang oder Korrektheits-Invariante verwendet.

### 6.2 *Was genau noch nicht?*

Konkret nicht ausgewertet (Kurzliste, Details in §3.2):

1. `Cmp1Box.cfaLayout` als Mosaik-Quelle — heute hart aus Modellname.
2. `Cmp1Box.encodingType / medianBitDepth / hasExtendedHeader` als Decoder-Validierung.
3. `CrxHeader.Plane.roundedBits` und `supportsPartial` werden nirgends geprüft.
4. `CrxHeader.Subband.quantValue` wird im Lossless-Pfad ignoriert.
5. `Iad1Box.leftOb* / topOb*`-Boxen — nur `activeAreaLeft` (Spaltenzahl) wird benutzt.
6. **Cross-File-Vergleich** der drei EOS R Burst-Frames (Σ Tile-Differenz < 0.4 %) — kein Test schöpft das aus.
7. **Cross-File-Vergleich** der G5 X II `sample`/`sample-tele`/`sample-wide`-Burst-Triade.
8. **Plane-Größen-Asymmetrie p3 ≫ p1,p2 bei G5 X II** vs. Symmetrie bei EOS R — bisher unbemerkter Modell-spezifischer Header-Unterschied (relevant für jedes pro-Plane-Bit-Budget).
9. **`tileIndex == 0` für beide Tiles**: nicht als Header-Anomalie validiert.
10. **CRX_BIG cfaLayout=RGGB ↔ CRX_SMALL cfaLayout=GRBG für dieselbe Datei**: noch nirgends als Decoder-Hypothese genutzt.
11. **`PRVW.width=1620 ≠ CRX_SMALL.imageWidth=1624`**: unkalibrierte 4-Pixel-Differenz für jeden Pearson-r-Vergleich PRVW↔CRX_SMALL.
12. **EXIF-`isoSpeed` für EOS R ist konstant 12800** — bisher nicht als Korpus-Lücke dokumentiert; Hypothesen, die ISO-Abhängigkeit testen, können das EOS-R-Trio nicht unterscheiden.

### 6.3 *Welche 3 nächsten Schritte bringen mit dem bestehenden Korpus den größten zusätzlichen Erkenntnisgewinn?*

**Schritt 1 — Burst-Frame-Differenz als Validierungsachse einführen**
*(reine Test-/Research-IT, kein Produktionscode)*
Vergleiche `eosr-iso-low/mid/high.cr3` paarweise pixelweise (PRVW JPEG decodiert + CRX_SMALL Plane-Bytes nach Decoder-Hypothese). Bursts sind 6 s auseinander, EXIF identisch → Δ ≤ Sensorrauschen. Jede Decoder-Variante, die zwischen Bursts mehr Δ produziert als das echte Photonen-Rauschen, ist falsifiziert. Analog für `sample/sample-tele/sample-wide`. **Erwarteter Gewinn**: erstes formales Korrektheits-Kriterium, das **unabhängig** von einer Ground-Truth ist.

**Schritt 2 — `cfaLayout` als Hypothesen-Differenzierer ausnutzen**
Für **jede** CR3-Datei liefert CMP1 zwei verschiedene `cfaLayout`-Werte: 0 (RGGB) für CRX_BIG, 1 (GRBG) für CRX_SMALL. Das sind zwei verschiedene Bayer-Reihenfolgen am gleichen Sensor. Test: pro Datei Plane-Permutation und CFA-Layout-Hypothese gemeinsam scannen — Decoder, die die GRBG-Variante (1620×1080-Klasse) korrekt rekonstruieren, müssen automatisch auch den RGGB-Pfad abdecken. **Erwarteter Gewinn**: schließt die im Memory beschriebene tile0/tile1-Plane-K-init-Asymmetrie potenziell, weil sie evtl. nur ein verstecktes CFA-Layout-Mismatch ist.

**Schritt 3 — Plane-Bit-Budget pro Kamera kalibrieren, nicht pro Hypothese**
Die G5 X II hat strukturell `bytes(p3) ≈ 6.5 · bytes(p1)`, die EOS R `bytes(pi) ≈ const`. Jeder K-Init/K-Adaptations-Test, der einen **modell-unabhängigen** k-Wert sucht, ist deshalb verloren. Stattdessen: pro Datei `bits-per-pixel` aus `Plane.dataSize / planePixelCount` ableiten und als **Eingabe** der K-Init-Heuristik testen (z. B. `K_INIT = round(bpp(plane) − bpp(plane0))`). **Erwarteter Gewinn**: trennt zum ersten Mal "Decoder hat falschen K" von "K war von Anfang an plane-/kamera-spezifisch". Verwendet ausschließlich bereits geparste Header-Felder.

---

## 7. Reproduktion

Das in §1 / §1.6 zitierte Inventar lässt sich aus den Fixtures jederzeit mit dem produktiven Parser-Stack ohne neue Module reproduzieren:

```java
// Pseudocode
Cr3Container c = Cr3Parser.parse(in);
String cncv = c.cncvBox().versionString();
for (Cr3Track t : c.tracks()) {
    Cmp1Box cmp1 = t.cmp1();           // CMP1-Felder
    Iad1Box iad1 = t.iad1();           // OB / Active-Area
    if (cmp1 != null && t.dataSize() > 0) {
        byte[] head = readPrefix(file, t.dataOffset(), 4096);
        CrxHeader hdr = CrxHeader.parse(head, CrxHeader.detectVersion(cncv));
        // hdr.tiles() → tile.planes() → plane.subbands(): dataSize, quantValue, ...
    }
}
Cr3Metadata m = new Cr3Decoder().setInput(in).getMetadata();
```

Empfehlung: dieses Inventar nicht als Produktions-Pfad, sondern als reine Research-Testklasse `CrxFixtureInventoryIT` ergänzen, die das obige `tsv` als Test-Output schreibt. Damit ist die Audit-Tabelle in §1 jederzeit automatisch aktuell, sobald neue Fixtures hinzukommen — und kann in CI als Wegweiser für die Korpus-Lücken aus §5 dienen.
