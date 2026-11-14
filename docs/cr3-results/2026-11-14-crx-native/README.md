# RG4 — Full native lossless CR3 import

Photographer’s public Java decoder returned all **365,774,016 native Bayer values exactly** across 15 lossless CR3 files. The panel covers Canon EOS R, PowerShot G5 X Mark II, EOS M50 and EOS RP. The supported layout in this experiment is V1 with 14-bit samples, four positional planes, encoding type 0, a single subband per plane, and horizontal full-height tiles. The two three-level C-RAW files returned an unsupported-codec exception at the RG4 snapshot. [Full-image results](full-sensor.tsv)

| Panel | Inputs | Compared native values | Different | Maximum absolute error |
|---|---:|---:|---:|---:|
| Discovery, hash-registered lossless originals and LibRaw TIFFs | 11 | 261,096,304 | 0 | 0 |
| Public lossless inputs and DNGLab PGMs | 4 | 104,677,712 | 0 | 0 |
| Public three-level C-RAW | 2 | 0 | — | — |

Before this change, the same Java entry returned a 16-bit grayscale image for both lossless discovery examples, with the following native results. Each first difference occurred at sensor coordinate (0, 0).

| Baseline fixture | Compared | Different | Expected at (0, 0) | Returned at (0, 0) | Maximum absolute error |
|---|---:|---:|---:|---:|---:|
| G5 X Mark II, sample-wide.cr3 | 20,894,720 | 20,894,058 | 2,052 | 0 | 13,149 |
| EOS R, eosr-iso-low.cr3 | 31,312,848 | 31,312,785 | 2,048 | 0 | 3,927 |

## Derivation and acceptance

The first-row experiment extended RG3's opening to the right-hand boundary of each positional plane. It used the previous reconstructed sample, parity-folded residuals, the registered opening, and a bounded Rice code with a literal escape. All 131,440 sites on 88 discovery rows and 50,384 on 32 public holdout rows matched. Each measurement records the final bit position, final Rice parameter and both row hashes. [Discovery rows](first-row-discovery.tsv) · [Holdout rows](first-row-holdout.tsv)

For subsequent rows, the team compared predicted symbols to the original compressed bitstream on the discovery panel. The resulting reader uses the median edge predictor, an adaptive Rice parameter and run coding in qualifying flat regions. Predictor neighbours and run state belong to one tile and plane. The CMP1 directory locates the first byte of each physical subband. The implementation restores each sample directly into its CFA position and detects exhausted input. The model, thresholds and run boundaries were tested against compressed bits and native samples. The published Canon and JPEG-LS materials supplied candidate principles. [Registered sources](../../cleanroom/source-register.json) · [Native plane reader](../../../canon-cr3-codec/src/main/java/com/photographer/focusstack/cr3/raw/codec/CrxNativeLosslessPlane.java)

The baseline was `623487053156b0c2326bc9d8ea05448ed3814b2c`. Issue 336 records the separate first-row and full-plane freezes. The frozen full-plane comparator has SHA-256 `47fcf8c6b6e644dc76809821c9448bf3913c79ec620a744547185e3267fab168`. The discovery algorithm was fixed before checking its predictions on the rest of the four public mosaics. The acceptance gate required zero differing sites on every discovery and held-out frame. Java then repeated that comparison through the public product API. [Issue 336](https://github.com/carstenartur/Photographer/issues/336) · [Experiment ledger](../../cleanroom/experiment-ledger.json)

The Java test verifies SHA-256 of each input and expected output, disables cropping and black-level subtraction, and checks every native sample in raster order. It inverts the documented 16-bit output scaling exactly and rejects values inconsistent with 14-bit encoding. It records geometry, samples compared, mismatch count, first difference and maximum error before applying its acceptance assertions. Ordinary unit tests exercise a synthetic 2 × 2 file through the stream input path. Real files use the file input path. Reference decoders and camera-specific fixture values are confined to preparation and comparison outside the product. [Product-path test](../../../canon-cr3-research/src/test/java/com/photographer/focusstack/cr3/research/FullSensorDecodeIT.java) · [Public RAW fixtures](../../../scripts/research/public-crx/lossless-holdout.tsv)

## Reproduction

Use JDK 25, Maven, the repository root and absolute paths for the two caches. The preparation scripts pin external Linux x86-64 release binaries, the public CR3 inputs and reference outputs. The Java replay reads those existing files without invoking an external decoder.

```sh
holdout=$(mktemp -d)
craw=$(mktemp -d)
bash scripts/research/public-crx/prepare-lossless-holdout.sh "$holdout"
bash scripts/research/public-crx/verify-public-crx.sh "$craw" > "$craw/reference-comparison.tsv"
mvn -B --no-transfer-progress verify -Pcr3-research-it -pl canon-cr3-research -am \
  -Dit.test=LosslessFirstRowIT,FullSensorDecodeIT -Djava.awt.headless=true \
  -Dcr3.first-row.discovery.enabled=true -Dcr3.first-row.holdout.enabled=true \
  -Dcr3.full-sensor.mode=strict -Dcr3.opening.holdout.dir="$holdout" \
  -Dcr3.public.craw.dir="$craw"
python3 scripts/manage_cr3_generated_evidence.py --root . compare RG4-first-row \
  --source-root canon-cr3-research/target/lossless-first-row
python3 scripts/manage_cr3_generated_evidence.py --root . compare RG4-full-sensor \
  --source-root canon-cr3-research/target/full-sensor-decode
```

Strict mode requires exact lossless mosaics. With a public C-RAW cache supplied, the snapshot additionally checks that a C-RAW file either decodes exactly or returns its specific unsupported-codec status. Use `-Dcr3.full-sensor.require-craw=true` to require both C-RAW mosaics to decode exactly as well. That gate fails on the RG4 snapshot. The `report` mode retains measured mismatches for investigating a different implementation.
