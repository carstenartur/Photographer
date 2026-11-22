# RG6 — V1 single-tile C-RAW and camera verification

[Experiment #443](https://github.com/carstenartur/Photographer/issues/443)
asks whether the existing C-RAW outer-edge rule reconstructs a full-height
tile when it has no horizontal neighbours. The candidate changes tile
validation and edge selection for that geometry. Separately, a track
selection check uses the selected track's CMP1 version when it is known.
The container-level CNCV string can name a different generation.

The input panel consists of 26 CC0 CR3 files from raw.pixls.us, with one
uncropped native 16-bit PGM per file from DNGLab 0.8.0. The
[registry](../../../scripts/research/public-crx/v1-cameras.tsv) binds each
original and reference to its SHA-256, and the
[fixture preparer](../../../scripts/research/public-crx/prepare-sensor-fixtures.sh)
pins the executable. Public format observations selected the inputs. The
codec derivation uses the existing Photographer transform and observations
of the input bytes. DNGLab runs as a black-box reference.

## Result

The candidate returned **690,078,496 exact Bayer values across all 26 files
and 18 models**. The baseline commit
`271db071539de2a0a2e4f5cdbeaeb432af65fba1` returned exact results for 23 of
these files and rejected the other three.

| Input | Baseline | Candidate |
| --- | --- | --- |
| 4671, EOS RP 1.6× crop C-RAW | Rejected the one-tile geometry | 10,708,992 exact sites |
| 6585, EOS R8 RAW | Selected V2 from CNCV for a V1 track | 25,494,560 exact sites |
| 7881, EOS R5 Mark II RAW | Selected V2 from CNCV for a V1 track | 47,912,000 exact sites |

The [comparison matrix](v1-match-matrix.tsv) records dimensions, tile geometry,
both version identifiers and the native-sensor comparison for each file.
The V1 one-tile hypothesis is **SUPPORTED** on the registered RP capture.
The two conflicting version identifiers are direct observations of original
files and establish the need for track-specific dispatch.
LibRaw 0.22.3 independently returned identical native raster bytes for the
three files listed above.

## Replay

```sh
cache="$(mktemp -d)"
bash scripts/research/public-crx/prepare-sensor-fixtures.sh \
  "$cache" scripts/research/public-crx/v1-cameras.tsv
mvn -B verify -pl canon-cr3-imageio -am \
  -Dit.test=Cr3CameraCoverageIT -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dcr3.camera.samples.dir="$cache" -Djava.awt.headless=true \
  -Dfailsafe.maxHeap=512m
```

The integration test checks all input/reference hashes, complete native
Bayer values through ImageIO, and opposite corner regions. This verifies
the measured capture layouts. Camera-specific display calibration,
CR3 writing and DNG export have separate coverage.
