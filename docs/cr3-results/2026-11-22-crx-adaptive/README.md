# Adaptive CRX V2 C-RAW

The sensor reader supports a measured 14-bit C-RAW profile with a single tile, four Bayer planes, three integer 5/3 wavelet levels and adaptive quantization. The primary track's `CMP1` version is `0x0200`. The reader validates the tile, plane and band records and the quantization range before reconstructing Bayer rows. Each worker keeps the existing bounded subband windows and lifting rows. The tile's quantization grid occupies one byte per cell and is shared by all four plane readers. A region read retains the same predictor and wavelet dependencies as a full read.

## Measurements

The preregistered [experiment #442](https://github.com/carstenartur/Photographer/issues/442) used EOS R8 for discovery and EOS R5 Mark II full-frame and APS-C crop recordings as holdouts. The additional EOS R5, R6, R6 Mark II, R6 Mark III, R7, R50 and PowerShot V1 samples check the same rules. The EOS R50 electronic-shutter specimen extends the measured quantization range. The registry binds every CC0 original to its DNGLab 0.8.0 native PGM. Separately, LibRaw 0.22.3 exposed identical native values through the public C API for every sample in the panel. [Pinned originals and references](../../../scripts/research/public-crx/adaptive-craw.tsv) · [LibRaw comparison client](../../../scripts/benchmarks/libraw_bench.c)

| Archive ID | Camera and mode | Full sensor | Quantization grid | Native values compared | Differences |
| --- | --- | ---: | ---: | ---: | ---: |
| 6587 | EOS R8 full-frame C-RAW | 6188 × 4120 | 387 × 1030 | 25,494,560 | 0 |
| 7882 | EOS R5 Mark II full-frame C-RAW | 8480 × 5650 | 530 × 1413 | 47,912,000 | 0 |
| 7884 | EOS R5 Mark II APS-C C-RAW | 5376 × 3574 | 336 × 894 | 19,213,824 | 0 |
| 4660 | EOS R6 C-RAW | 5568 × 3708 | 348 × 927 | 20,646,144 | 0 |
| 5634 | EOS R7 C-RAW | 7128 × 4732 | 446 × 1183 | 33,729,696 | 0 |
| 6646 | EOS R50 C-RAW | 6192 × 4060 | 387 × 1015 | 25,139,520 | 0 |
| 4696 | EOS R5 APS-C C-RAW | 5248 × 3510 | 328 × 878 | 18,420,480 | 0 |
| 4698 | EOS R5 full-frame C-RAW | 8352 × 5586 | 522 × 1397 | 46,654,272 | 0 |
| 6408 | EOS R6 Mark II APS-C C-RAW | 3936 × 2612 | 246 × 653 | 10,280,832 | 0 |
| 6409 | EOS R6 Mark II full-frame C-RAW | 6188 × 4120 | 387 × 1030 | 25,494,560 | 0 |
| 6645 | EOS R50 C-RAW, electronic shutter | 6192 × 4060 | 387 × 1015 | 25,139,520 | 0 |
| 8500 | PowerShot V1 C-RAW | 5928 × 3944 | 371 × 986 | 23,380,032 | 0 |
| 8962 | EOS R6 Mark III full-frame C-RAW | 7144 × 4760 | 447 × 1190 | 34,005,440 | 0 |
| 8964 | EOS R6 Mark III APS-C C-RAW | 4512 × 2994 | 282 × 749 | 13,508,928 | 0 |
| | **Total** | | | **369,019,808** | **0** |

The [machine-readable results](full-sensor.tsv) contain the matching sensor counts. These comparisons cover reconstructed Bayer values, including optical black, outer boundaries and clipping on the EOS R5 Mark II captures. Color development and DNG export have their own calibration and camera profile requirements. [Sensor and rendering options](../../cr3/decoder.md#output-modes)

## Observed directory and reconstruction

The following offsets are relative to the marker, including the marker and length words. The directory observations were made on the pinned camera bytes. The extended records determine the entropy extents without reading the adjacent payloads.

| Record | Body bytes | Observed additional fields |
| --- | ---: | --- |
| `FF11` tile | 16 | Unsigned 32-bit map entropy bytes at +12, unsigned 16-bit map padding at +16 and zero at +18. Tile length includes the map, its padding and all plane data. |
| `FF12` plane | 8 | Unsigned 32-bit plane byte count at +4 and the Bayer position in the high nibble at +8. |
| `FF13` band | 16 | Unsigned 16-bit gain at +10, 32-bit base at +12, unsigned 16-bit entropy padding at +16 and zero at +18. Band length includes its padding. |

The quantization map starts at the `CMP1` directory boundary. The first band follows the map and map padding. The map width is the sensor tile width rounded up to groups of sixteen, and the map height is the tile height rounded up to groups of four. The first signed residual is folded into an unsigned 32-bit literal. Subsequent symbols use a Rice quotient with a 23-zero escape and eight-bit escape value. The tested Rice state starts at two, uses the existing adaptive thresholds with a maximum width of four, and carries across rows. The map uses left prediction on the first row and median edge prediction on the later rows. The interior update uses half the unsigned residual rounded down plus the absolute difference between the two upper neighbors. Row ends update with the full residual. The accepted quantization values are 150–168.

The first four subbands have unit expansion. The middle scale uses two neighboring map rows averaged with integer rounding down, and each map column covers two coefficients. An odd final map row supplies its own value. The finest scale uses one map row at a time and each map column covers four coefficients. For the observed gain and zero-base detail bands, the expansion multiplies the pre-existing quantization step at map value minus 124 by the gain and divides by eight. The observed steps for map values 165–168 double the values six positions earlier, giving 72, 80, 90 and 102. The entropy and wavelet paths reuse the existing coefficient readers and integer lifting. The reference reconstruction is clamped to the 14-bit sensor range at output. [V1 coefficient and wavelet reference](../../cr3/format.md#v1-three-level-c-raw-reconstruction)

The map framing and predictor were checked against complete entropy extents. The dequantization rule was tested by forward-transforming the reference sensor planes with the 5/3 transform and comparing the resulting coefficients with the independently read band streams. Full reconstruction then compared the clamped output across the entire sensor, including locations where saturation prevents an inverse comparison in the coefficient domain. The public format observations and the decoded reference outputs were the external comparison inputs. [LightCraft published observations](https://github.com/storytold/lightcraft/blob/f3bb7a9f3068e6b3e6136a1e864f7a3ae5dfc796/docs/cr3.md#verified-sensor-decoding) · [Clean-room evidence rules](../../cleanroom/README.md)

## Reproduce the ImageIO comparison

From the project root, create the hash-checked cache from the public originals and the DNGLab release binary for Linux x86-64. Then run the ImageIO integration test. The native sensor test also compares source regions at opposite corners to the corresponding samples in the full image.

```sh
cache=/absolute/path/to/cr3-reference-cache
bash scripts/research/public-crx/prepare-sensor-fixtures.sh "$cache" \
  scripts/research/public-crx/adaptive-craw.tsv
mvn -B -pl canon-cr3-imageio -am verify \
  -Dit.test=Cr3CameraCoverageIT -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dcr3.adaptive-craw.samples.dir="$cache" \
  -Dfailsafe.maxHeap=512m
```
