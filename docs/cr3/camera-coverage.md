# Verified CR3 camera coverage

The table lists camera modes with full, uncropped native Bayer comparisons.
The original and expanded panels cover **61 recordings from 23 camera models**
and **1,571,339,472 exact Bayer values**. The decoder selects a codec from the
compression parameters of the selected RAW track and validates its directory and
geometry. It can also read matching layouts from other cameras.

RAW here means lossless CRX. C-RAW is Canon's lossy capture mode. Its native Bayer
values represent the reconstruction of the compressed data. Comparison with a
reference decoder checks that reconstruction, including optical-black margins.

| Camera | RAW | C-RAW | Notes |
| --- | --- | --- | --- |
| EOS R | Yes | V1 | Full frame |
| EOS RP | Yes | V1 | Full frame and one-tile 1.6× crop C-RAW |
| EOS M50 | Yes | V1 | Full sensor |
| EOS M50 Mark II | Yes | — | Full sensor |
| EOS M6 Mark II | Yes | V1 | Full sensor |
| EOS M200 | Yes | V1 | Full sensor |
| EOS 250D | Yes | V1 | Full sensor |
| EOS 90D | Yes | V1 | Full sensor |
| EOS R5 | — | V2 | Full frame and 1.6× crop C-RAW |
| EOS R5 Mark II | Yes | V2 | Full frame and 1.6× crop |
| EOS R6 | Yes | V2 | Full frame |
| EOS R6 Mark II | Yes | V2 | Full frame and 1.6× crop C-RAW |
| EOS R6 Mark III | Yes | V2 | Full frame and 1.6× crop C-RAW |
| EOS R7 | Yes | V2 | Full sensor |
| EOS R8 | Yes | V2 | Full frame |
| EOS R10 | Yes | — | Full sensor |
| EOS R50 | Yes | V2 | Full sensor, including the tested electronic-shutter C-RAW |
| EOS R50 V | Yes | — | Full sensor |
| EOS R100 | Yes | V1 | Full sensor |
| PowerShot G5 X Mark II | Yes | V1 | Full sensor |
| PowerShot G7 X Mark III | Yes | V1 | Full sensor |
| PowerShot SX70 HS | Yes | V1 | Full sensor |
| PowerShot V1 | — | V2 | Full sensor |

A dash indicates that this table has no verified input for that mode. Camera
firmware, shooting mode, crop, bit depth and compressor settings can change the
layout within a model. Dual Pixel secondary tracks and RAW burst sequences have
separate coverage criteria. Developed color depends on the available
[capture calibration](format.md#metadata-and-sensor-calibration). The
[CR3 writer](format.md#lossless-writer-camera-profiles) and
[automatic DNG calibration](imageio.md#write-and-read-a-bayer-dng) have their
own model lists.

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
