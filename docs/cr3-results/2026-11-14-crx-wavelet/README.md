# RG5 — Full native C-RAW import

The Java decoder reproduced all **104,677,712 sensor samples** across four public Canon C-RAW files. Maximum absolute difference was zero on each file, including the two cameras reserved for verification. These are reconstructed values from a lossy recording mode. The reference is the native Bayer mosaic decoded from the same compressed file. [Per-image results](craw-sensors.tsv) · [Input and reference hashes](../../../scripts/research/public-crx/craw-sensors.tsv)

| raw.pixls.us ID | Camera | Role | Full sensor | Samples compared | Different |
|---|---|---|---:|---:|---:|
| 3506 | PowerShot G5 X Mark II | Discovery | 5632 × 3710 | 20,894,720 | 0 |
| 4543 | EOS R | Discovery | 6888 × 4546 | 31,312,848 | 0 |
| 4656 | EOS M50 | Holdout | 6288 × 4056 | 25,504,128 | 0 |
| 4672 | EOS RP | Holdout | 6384 × 4224 | 26,966,016 | 0 |

The lossless regression run compared all 365,774,016 sites on the 15 RG4 frames again. It also repeated both discovery C-RAW frames. Together these two preregistered panels contain **19 photographs and 470,451,728 distinct sensor sites**, all exact. An additional stream-based check let Java ImageIO discover the registered CR3 reader from the EOS M50 file signature, then passed uncropped RAW_LINEAR options to `reader.read(0, param)`. It matched all 25,504,128 sensor sites. [Lossless regression and C-RAW discovery replay](full-sensor.tsv) · [Original lossless acceptance](../2026-11-14-crx-native/README.md)

## Two additional first-party C-RAW captures

The G5 X Mark II ISO 1600 detail pair exposed a negative first coefficient in the wavelet approximation band. Both public reference sets had passed with the previous opening check. On the first-party 02 capture it rejected two right-tile planes before returning an image. The native forward 5/3 transform recovered opening coefficients −1653 and −1655. Changes to the 21-bit opening literal in private copies, paired with a compensating second residual, distinguished signed decoding from a clipped result. On seven controlled cases, the black-box output recovered the predicted unsaturated 2111 at the next approximation anchor. The Java reader accepts the signed opening for the wavelet approximation and preserves the 14-bit sensor bounds for lossless decoding.

Both unmodified captures were then compared against fresh DNGLab PGMs. The late regression adds **41,789,440 exact sites**. The combined observed scope is **21 photographs and 512,241,168 exact sites**, comprising 15 lossless and six C-RAW files. Each first-party file also passed an independent ImageIO SPI read at every site. [First-party results](first-party-craw.tsv)

## Derivation and scope

On the two discovery inputs, black-box controls varied individual subband payloads to distinguish low-frequency reconstruction, the signed high-frequency coefficients, their quantization and the order of the inverse transform. The reader treats each plane independently, decodes the low-frequency band and nine detail bands, applies the measured inverse quantization factors, and assembles the image with three stages of inverse integer 5/3 lifting. Horizontal edge geometry determines which neighbouring coefficients belong to each tile. The product reads only the CR3 input. [Detail-band reader](../../../canon-cr3-codec/src/main/java/com/photographer/focusstack/cr3/raw/codec/CrxWaveletDetail.java) · [Tile synthesis](../../../canon-cr3-codec/src/main/java/com/photographer/focusstack/cr3/raw/codec/CrxWaveletTile.java)

The fixed holdout predictions, including the exact-pixel acceptance rule, are recorded in issue 337. The frozen discovery readers were `firstlast.c` (SHA-256 `4ebaa6aac58ea6cc5210f42b7ba736674a002189b937f3158da6710e1cb4c810`) and `indep.py` (`e2918a4ad0da5329177cec865153d36600263736199a2dad3c8c417c10d670ea`). File and oracle hashes were verified before the held-out predictions were compared. The production baseline was `07bd797382108f4469d96855f2587cc20cbacf78`, which returned an explicit unsupported result on all four inputs. [Issue 337](https://github.com/carstenartur/Photographer/issues/337) · [Clean-room sources](../../cleanroom/source-register.json)

This evidence covers V1, encoding type 0, four unrounded 14-bit positional planes, three wavelet levels and two equal-width horizontal tiles. Each observed plane contains ten subbands. Decoder guards enforce supported quantizers and the selected tile/plane geometry. The holdout extends the measured camera models to EOS M50 and EOS RP. [Native C-RAW profile and validation](../../../canon-cr3-codec/src/main/java/com/photographer/focusstack/cr3/raw/codec/CrxLossyDecoder.java)

## Reproduction

Run from the repository root with JDK 25 and Maven. The Linux x86-64 preparation script verifies the CC0 downloads and the DNGLab 0.8.0 binary, generates the native PGM references and checks their SHA-256 hashes. The test reads the prepared references, checks each pair again and compares every Bayer site through the normal Java product path. Strict mode requires a complete image and zero errors from all four files.

```sh
craw=$(mktemp -d)
bash scripts/research/public-crx/prepare-sensor-fixtures.sh "$craw" \
  scripts/research/public-crx/craw-sensors.tsv
mvn -B --no-transfer-progress verify -Pcr3-research-it -pl canon-cr3-research -am \
  -Dit.test=CrawSensorIT -Dcr3.craw-sensor.mode=strict \
  -Dcr3.craw-sensor.dir="$craw" -Djava.awt.headless=true
python3 scripts/manage_cr3_generated_evidence.py --root . compare RG5-craw \
  --source-root canon-cr3-research/target/craw-sensor
```

To replay the RG4 panel on this revision, prepare a second cache for its public lossless files. The existing full-sensor test uses the discovery C-RAW filenames from RG2. The hard links below reuse the two verified originals and PGMs.

```sh
holdout=$(mktemp -d)
bash scripts/research/public-crx/prepare-lossless-holdout.sh "$holdout"
ln -f "$craw/3506.cr3" "$craw/g5xm2.cr3"
ln -f "$craw/3506.pgm" "$craw/g5xm2.sample"
ln -f "$craw/4543.cr3" "$craw/eos-r.cr3"
ln -f "$craw/4543.pgm" "$craw/eos-r.sample"
mvn -B --no-transfer-progress verify -Pcr3-research-it -pl canon-cr3-research -am \
  -Dit.test=FullSensorDecodeIT -Dcr3.full-sensor.mode=strict -Dcr3.full-sensor.require-craw=true \
  -Dcr3.opening.holdout.dir="$holdout" -Dcr3.public.craw.dir="$craw" -Djava.awt.headless=true
python3 scripts/manage_cr3_generated_evidence.py --root . compare RG5-regression \
  --source-root canon-cr3-research/target/full-sensor-decode
```

DNGLab and LibRaw supplied reference outputs as black boxes. The validated production path uses Java for CRX entropy decoding, inverse quantization, wavelet reconstruction and Bayer placement. [Full product acceptance test](../../../canon-cr3-research/src/test/java/com/photographer/focusstack/cr3/research/CrawSensorIT.java)


To repeat the private-pair check, use the same verified DNGLab binary as the four-camera preparation. The Java test pins the original and PGM hashes of both local captures.

```sh
localrefs=$(mktemp -d)
for f in canon-cr3-imageio/src/test/resources/samples/research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-{02,03}.cr3; do
  name=$(basename "$f" .cr3)
  RAYON_NUM_THREADS=1 "$craw/dnglab" analyze --raw-pixel "$f" > "$localrefs/$name.pgm"
done
mvn -B --no-transfer-progress verify -Pcr3-research-it -pl canon-cr3-research -am \
  -Dit.test=CrawSensorIT -Dcr3.craw-sensor.local-dir="$localrefs" -Djava.awt.headless=true
python3 scripts/manage_cr3_generated_evidence.py --root . compare RG5-local \
  --source-root canon-cr3-research/target/craw-sensor-local
```
