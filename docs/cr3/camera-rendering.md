# Profiled RGB16 development for the PowerShot G5 X Mark II and EOS R

Use this rendering for a display image developed from CR3 sensor samples, with lens correction and photographic contrast. The output has three unsigned 16-bit sRGB channels. The camera JPEG serves as a reference when measuring the rendering. Importing a photo with the profile uses the RAW or C-RAW pixels and the capture metadata.

## ImageIO

The normal ImageIO read includes the supported optical profiles and photographic tone curve:

```java
BufferedImage developed = ImageIO.read(file);
```

The [complete ImageIO example](imageio.md#run-a-selected-read-mode) opens and closes the reader and stream. Its `rgb` and `camera` modes now select the same full RGB16 development. The core `rawProcessed()` factories and ImageJ color importer also include the supported profiles. To disable either stage, modify the default parameters; disabling both restores the earlier neutral rendering:

```java
var param = new CanonCr3ImageReadParam();
param.getDecodeOptions().applyLensCorrection(false).applyCameraTone(false);
BufferedImage neutral = reader.read(0, param);
```

Keep one flag enabled to use that stage independently. `Cr3DecodeOptions.cameraRendered()` remains an explicit factory for the same RGB16 defaults. Native sensor reads, Bayer DNG export and the explicit JPEG preview each retain their original pixel source.

## Which profiles apply?

| Body | Supported optics | Recorded Canon Picture Styles evaluated for tone |
| --- | --- | --- |
| PowerShot G5 X Mark II | Built-in 8.8–44 mm zoom | Faithful and Neutral |
| EOS R | Canon RF 24–105mm F4 L IS USM and RF 85mm F2 Macro IS STM | Auto, Landscape and Standard |

The two stages select their profiles independently. On the EOS R the metadata must identify the lens. A different lens on the same camera, an unidentified adapted lens, or a camera crop recording bypasses the optical stage. Lens correction uses the full, supported active field and retains its output dimensions. An ImageIO source region or subsampling selects pixels on that corrected image. Even a small region can therefore require a full-field optical render.

The lens profiles describe distortion, red/blue displacement relative to green, and light falloff. The Java renderer combines the geometric corrections in one resampling operation per channel and averages neighboring samples in linear light. It chooses a centered rectangle with valid source samples and limits the scale to preserve spatial detail at the optical center. Its framing can differ from Canon’s JPEG. The underlying calibration data and attribution ship in the processing JAR. [Data sources, modifications and license](../../canon-cr3-processing/src/main/resources/META-INF/lensfun/NOTICE.txt)

When a capture falls between calibration points, the renderer interpolates in focal length, aperture stops and reciprocal focus distance. It uses the nearest measured endpoint outside the calibrated interval. In particular, the G5 wall image reports a focus distance of 0.41 m, while the database provides identical shading coefficients at 10 and 1,000 m. The wall test assesses the visible result of using those coefficients at close focus. A controlled flat-field measurement at that distance would establish optical accuracy.

Canon documents that the EOS R reduces peripheral illumination correction at higher ISO. With this preset you can choose how much of the calibrated gain to apply, keeping the same output framing. For instance, `Cr3DecodeOptions.cameraRendered().lensShadingStrength(0.5)` applies half the difference between unity gain and the full-profile gain. At `0` the geometry and lateral color corrections still apply. The default strength in the preset is `1`. [Canon EOS R Advanced User Guide, p. 111](https://gdlp01.c-wss.com/gds/3/0300032273/09/EOS_R_Advanced_User_Guide_EN.pdf)

The shared tone curve was fitted from developed RGB16 and the camera JPEGs of selected exposures. Each pixel receives a common gain across the three linear RGB channels. This preserves their ratios and keeps black and white fixed. The preset applies the curve only for the combinations in the table. It does not recreate all Canon Picture Style settings. A linear RGB request skips the display tone curve. [Calibration and validation procedure](../../scripts/research/camera-tone/README.md)

## Comparison with the embedded camera JPEG

The table reports the median and 90th percentile of the color difference across 48 regions in the center 75% of the frame. A smaller ΔE00 means a closer match to the JPEG from the same exposure. The wide-angle wall and the RF 85 mm plant contributed to the tone fit. The other examples below are independent scenes. Each raw.pixls.us series can include several exposures of the same subject. [Results by file, SHA-256 and rendering mode](camera-rendering-measurements.tsv) · [Source for the two Standard exposures](https://photorec.tv/content/2018/09/canon-eos-r-sample-gallery)

| Camera and scene | Recorded style | Median ΔE00, neutral → profiled | 90th percentile, neutral → profiled |
| --- | --- | ---: | ---: |
| G5 X II, wide-angle wall | Faithful | 21.16 → 7.97 | 22.45 → 9.26 |
| G5 X II, dim interior | Faithful | 10.04 → 4.96 | 11.77 → 6.33 |
| G5 X II, lake (raw.pixls.us 3510) | Neutral | 19.08 → 15.03 | 24.17 → 16.71 |
| EOS R, RF 85 mm, plant (raw.pixls.us 4515) | Landscape | 21.26 → 6.44 | 25.98 → 12.40 |
| EOS R, RF 24–105 mm at 24 mm, ISO 12800 | Auto | 2.41 → 2.86 | 8.87 → 4.51 |
| EOS R, RF 50 mm, beach portrait | Standard, tone only | 15.42 → 6.69 | 21.75 → 10.24 |
| EOS R, EF 24–70 mm, still life | Standard, tone only | 8.90 → 7.99 | 14.15 → 12.77 |

The near-dark EOS R frame shows why shading has a separate strength. With geometry and tone active, the median ΔE00 was 1.99 at strength 0, 2.28 at 0.5 and 2.86 at 1. A single ISO 12800 scene gives no general rule for selecting a gain. The two Standard exposures use lenses outside the three bundled profiles. They exercise the tone stage alone.

Geometric alignment was checked separately on 512-pixel-wide images using the same landmarks before and after correction. On the G5 wall, the median displacement from the JPEG fell from 2.24 to 1.41 pixels (27 shared landmarks). It fell from 2.24 to 1.00 on the lake (63), and from 1.41 to 0.00 on the EOS R at 24 mm (25). It stayed at 1.00 on the RF 85 mm plant (75). For the G5 wall, the four outer patches were a median 1.97 stops darker than the camera JPEG before lens correction and 1.24 stops darker after it, with the tone curve disabled in both reads.

## Import cost

### Optimization measured on 25 November 2026

The current implementation reuses shaded source values within bounded tiles. The
comparison below runs the **same profiled RGB16 development** before and after
that optimization; the change of default does not account for the improvement.
The G5 image is the dim interior, `sample.cr3`, and the EOS image is
`eosr-iso-low.cr3`. These are warm complete-file imports on Java 21.0.13.1 with
four workers, four processors exposed to the JVM, Serial GC and a 768 MiB heap.
Each arm ran in two fresh JVMs, with three warmups and five measured imports per
fork. Each value is the median of the two fork medians.

| Capture | Original profiled import | Optimized import | Elapsed-time reduction | CPU time, original → optimized | Allocated per import, original → optimized |
| --- | ---: | ---: | ---: | ---: | ---: |
| G5 X II interior, 5496 × 3668 | 744.1 ms | 671.4 ms | 9.8% | 2670 → 2315 ms | 155.53 → 155.75 MiB |
| EOS R, 6744 × 4500 | 1207.6 ms | 1023.1 ms | 15.3% | 4220 → 3525 ms | 233.56 → 233.78 MiB |

Complete image fingerprints are unchanged on both Java 21 and Java 25. The
additional allocation is about 0.22 MiB per four-worker import. The tile holds
at most 1,024 mapped output sites and 4,096 shaded source values; its array
payload is 56 KiB per worker, with 64 KiB reserved in heap planning. Larger
source footprints use the original scalar sampler. One temporary output
channel is still reused across all three bands. The planner also accounts for
lookup tables and their first initialization.

On Java 25 the same complete-import comparison reduced elapsed time by 19.1%
and 16.6%, respectively. Isolated Java 25 optical-stage measurements fell from
554.8 to 345.8 ms on the G5 and from 776.2 to 594.2 ms on the EOS R. Those stage
figures exclude RAW decoding and tone processing. The shared host showed
variation between forks: for example, G5 Java 21 baseline medians were 781.5
and 706.8 ms, versus 661.6 and 681.1 ms after optimization. These measurements
describe these inputs and settings, not a universal speedup.

[Raw measurements, source hashes, fork results and reproduction](camera-rendering-performance-2026-11-25/README.md)

### Historical cost of enabling the profiles

The following baseline was recorded before the default change and performance work, at the original PR #457 implementation. Its former `default` mode is now the explicit neutral rendering. These are full-resolution file imports on four logical CPUs with OpenJDK 21.0.13, Serial GC and a 768 MiB heap. For elapsed time, two fresh JVMs per configuration each ran three warmups and four measured imports. Values are medians of the two fork medians on a shared host. Peak resident memory was measured separately on a single cold import. The neutral import and the camera preset both return three 16-bit channels.

This earlier run uses a different JVM/host session and G5 capture; do not derive
an optimization speedup by comparing its absolute times with the table above.

| Original | Elapsed time, neutral → profiled | CPU time, neutral → profiled | Allocated per import, neutral → profiled | Cold peak RSS, neutral → profiled |
| --- | ---: | ---: | ---: | ---: |
| G5 X II, 5496 × 3668 | 0.61 → 2.07 s | 0.84 → 2.99 s | 116.93 → 155.70 MiB | 180 → 221 MiB |
| EOS R, 6744 × 4500 | 0.82 → 2.64 s | 1.20 → 4.79 s | 175.34 → 233.56 MiB | 237 → 289 MiB |

The optical remap reuses one temporary 16-bit channel across red, green and blue. The larger elapsed times include this remap and the tone stage. Neutral RGB16 output hashes matched the previous implementation on ten imports, and native Bayer hashes matched for RAW and C-RAW from both bodies. To repeat the timing, use the [public import probe](../../scripts/benchmarks/README.md#java-probe) with `--mode rgb16neutral` and `--mode default` on the same originals. Compare original and optimized implementations with `--mode rgb16camera` on both; otherwise a changed default would mix different workloads.

## Reproduce the comparison

Build the benchmark JAR and compile the comparison tool as described in the [same-exposure camera JPEG study](camera-jpeg-comparison.md#reproduce-a-comparison). Run the four settings against the same originals:

```bash
samples=canon-cr3-imageio/src/test/resources/samples
for mode in neutral lens tone camera; do
    java -cp "$classes:$jar" com.photographer.focusstack.cr3.imageio.Cr3CameraJpegProbe \
        --render "$mode" "$samples/sample-wide.cr3" "$samples/eosr-iso-low.cr3" > "$mode.tsv"
done
```

The committed 44-row comparison predates the default change: rows labeled `default` there describe neutral rendering, reproduced with `--render neutral` today. `--render default` now measures the profiled ImageIO default. Each row reports signed lightness and color shifts relative to the camera JPEG as well as median and 90th-percentile CIEDE2000. The 48 regional means describe broad image appearance. A change in framing can move content between the regions.
