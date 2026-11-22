# CR3 camera compatibility

Photographer's reference panels cover **61 recordings from 23 camera models**
and **1,571,339,472 exact Bayer values**. The decoder selects a codec from the
compression parameters of the RAW track. Camera firmware, crop and shooting
mode can change the encoding within the same model.

## Canon cameras that record CR3

**V1** or **V2** means that Photographer matched every native Bayer value in the
tested CRX profile, including the optical-black margins. **Untested** means
there is no verified native Bayer result for that camera and mode in the
project's evidence. RAW uses lossless compression. C-RAW uses lossy compression
and its reference is the sensor mosaic reconstructed from that recording.

| Camera | RAW | C-RAW | Tested recording or scope |
| --- | --- | --- | --- |
| EOS R1 | Untested | Untested | |
| EOS R3 | Untested | Untested | |
| EOS R5 | Untested | V2 | Full-frame and 1.6× crop C-RAW |
| EOS R5 C | Untested | Untested | Still-photo CR3 |
| EOS R5 Mark II | V1 | V2 | Full frame and 1.6× crop |
| EOS R6 | V1 | V2 | Full frame |
| EOS R6 Mark II | V1 | V2 | Full frame and 1.6× crop C-RAW. Adaptive QP limits apply. |
| EOS R6 Mark III | V1 | V2 | Full frame and 1.6× crop C-RAW |
| EOS R6 V | Untested | Untested | |
| EOS R7 | V1 | V2 | Full sensor |
| EOS R8 | V1 | V2 | Full frame |
| EOS R8 Mark II | Untested | Untested | |
| EOS R10 | V1 | Untested | Full-sensor RAW |
| EOS R50 | V1 | V2 | Full sensor, including the tested electronic-shutter C-RAW |
| EOS R50 V | V1 | Untested | Full-sensor RAW |
| EOS R100 | V1 | V1 | Full sensor |
| EOS R | V1 | V1 | Full frame |
| EOS Ra | Untested | Untested | |
| EOS RP | V1 | V1 | Full frame and one-tile 1.6× crop C-RAW |
| EOS M50 | V1 | V1 | Full sensor |
| EOS M50 Mark II | V1 | Untested | Full-sensor RAW |
| EOS M6 Mark II | V1 | V1 | Full sensor |
| EOS M200 | V1 | V1 | Full sensor |
| EOS-1D X Mark III | Untested | Untested | |
| EOS 90D | V1 | V1 | Full sensor |
| EOS 850D / Rebel T8i / Kiss X10i | Untested | Untested | |
| EOS 250D | V1 | V1 | Full sensor |
| PowerShot G5 X Mark II | V1 | V1 | Full sensor |
| PowerShot G7 X Mark III | V1 | V1 | Full sensor |
| PowerShot SX70 HS | V1 | V1 | Full sensor |
| PowerShot V1 | Untested | V2 | Full-sensor C-RAW |
| Cinema EOS C50 | Untested | Untested | PHOTO mode |

Model file formats are listed in the [Adobe camera format table](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/camera-raw-plug-supported-cameras.html) and the [Canon C50 PHOTO specifications](https://canon.jp/biz/product/camera/pro-imaging/cinema-eos/lineup/ces-camera/c50/spec/photo). Photographer's verification uses the reference comparisons below.

## Known limits

| Recording or feature | CR3 reader support |
| --- | --- |
| Adaptive V2 C-RAW with QP outside 150–168 | Unsupported. Public observations describe EOS R6 Mark II recordings containing QP 172 and 175. The verified R6 Mark II recordings use the supported range. |
| CRX track declaring a sample depth other than 14 bits | Unsupported for native Bayer output. The track's declared depth determines this check. |
| Secondary sensor data in Dual Pixel RAW | The native import selects the main RAW track. Secondary sensor data is unsupported. |
| RAW burst frames after the first sample | Unsupported. The current reader exposes a single image. |

See the [adaptive C-RAW profile](../cr3-results/2026-11-22-crx-adaptive/README.md), [published R6 Mark II observations](https://github.com/storytold/lightcraft/blob/f3bb7a9f3068e6b3e6136a1e864f7a3ae5dfc796/docs/cr3.md#verified-sensor-decoding) and [camera mode notes](open-hypotheses.md#camera-modes-to-check-against-the-format-description).

## Common Canon cameras that record CR2

CR2 sensor data is outside the CR3 reader's scope.

| Family | Examples |
| --- | --- |
| EOS DSLR | EOS-1D X Mark II, EOS 5D Mark IV, EOS 6D Mark II, EOS 7D Mark II, EOS 80D, EOS 200D |
| EOS M | EOS M5, EOS M6 |
| PowerShot | G5 X, G7 X, G7 X Mark II, SX60 HS |

[Camera file formats](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/camera-raw-plug-supported-cameras.html)

Developed color depends on the available capture calibration. The file writer
and automatic DNG calibration have their own model lists. [Capture calibration](format.md#metadata-and-sensor-calibration) · [CR3 writer profiles](format.md#lossless-writer-camera-profiles) · [Automatic DNG calibration](imageio.md#write-and-read-a-bayer-dng)

## Evidence

- [Original lossless references](../cr3-results/2026-11-14-crx-native/README.md)
- [Original V1 C-RAW references](../cr3-results/2026-11-14-crx-wavelet/README.md)
- [EOS RP one-tile C-RAW and V1 camera verification](../cr3-results/2026-11-22-crx-camera-coverage/README.md)
- [Adaptive C-RAW results](../cr3-results/2026-11-22-crx-adaptive/README.md)

## ImageIO reads with a limited heap

These are observed Java 21.0.13 G1 results with one worker. The native
samples match the full-sensor references. Both RGB16 results match
the full output fingerprints from FAST with a 512 MiB heap.

| Recording and output mode | Maximum Java heap | Observed peak process RSS | Output |
| --- | ---: | ---: | --- |
| EOS RP 1.6× crop C-RAW, native Bayer | 40 MiB | 68 MiB | 4032 × 2656, one band |
| EOS R5 Mark II full-frame C-RAW, native Bayer | 128 MiB | 142 MiB | 8480 × 5650, one band |
| EOS R8 C-RAW, RGB16 AUTO, rotated by EXIF orientation 6 | 192 MiB | 190 MiB | 3904 × 5844, three bands |
| EOS R5 Mark II C-RAW, RGB16 LOW_MEMORY, rotated by EXIF orientation 6 | 320 MiB | 306 MiB | 5300 × 7932, three bands |

Both RGB16 compact runs emitted one low-memory diagnostic. Heap
limits are per JVM, and the observed RSS includes runtime and native
memory. Use the registered fixture IDs and [import probe](../../scripts/benchmarks/README.md#java-probe)
to replay these cases.
