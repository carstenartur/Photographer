# Comparing the RGB16 import with Canon’s development

Each CR3 can provide two views of the same exposure. `ImageIO.read(File)` develops the RAW sensor data into three unsigned 16-bit sRGB channels. The embedded camera JPEG records Canon’s choices for that exposure. This pairing lets us check whether our import reproduces the camera’s brightness, color and framing on existing photographs. [Measurements for each original, including SHA-256](camera-jpeg-comparison.tsv)

## Results

These historical results describe the neutral rendering before supported lens and camera tone profiles became the default. Use `--render neutral` to reproduce this baseline, and `--render default` to measure the current ImageIO behavior. The [profiled rendering study](camera-rendering.md) records the improvements and remaining differences.

All 13 repository originals and six public originals were examined. Sixteen produced measurable pairs using the large JPEG track. The table reports the median and 90th percentile of 48 regional color differences (CIEDE2000) for selected exposures. The last column measures lightness in CIELAB units. A negative value means that Photographer rendered the typical region darker than the camera did.

| Camera | Original | Median ΔE00 | 90th percentile ΔE00 | Median ΔL* (Photographer − Canon) |
| --- | --- | ---: | ---: | ---: |
| G5 X Mark II | `sample.cr3` | 10.04 | 11.77 | −12.24 |
| G5 X Mark II | `sample-wide.cr3` (textured wall) | 21.16 | 22.45 | −21.95 |
| EOS R | `eosr-iso-low.cr3` | 2.41 | 8.87 | +0.76 |
| G7 X Mark III | raw.pixls.us 6053 (RAW) | 23.41 | 26.60 | −24.14 |
| G7 X Mark III | raw.pixls.us 6054 (C-RAW) | 23.30 | 26.51 | −23.93 |
| EOS R6 | raw.pixls.us 4660 (C-RAW) | 19.27 | 26.01 | −19.57 |

For `sample.cr3`, the median shifts along the red–green and yellow–blue axes were small (Δa* −0.66, Δb* −0.41). Lightness accounted for much of the visible difference. On the textured wall, color also shifted (Δa* −6.42, Δb* −5.23). The two G7 X Mark III originals show similar landscapes with the same Landscape picture style. Both RAW and C-RAW developed substantially darker than their respective JPEGs. None of these scene comparisons isolates the effect of compression or an individual processing step.

The three EOS R filenames suggest an ISO series, but their capture metadata all say ISO 12800, 1/250 s and f/4. They show similar dark scenes. The G5 X Mark II dark frame and white flat frame are useful controls. Several other G5 X Mark II camera JPEGs are almost white, including `sample-wb-tungsten.cr3`. Its white-balance preset identifies a camera setting. The illuminant was unmeasured. Pooling these files into one camera accuracy score would give repeated scenes and exposure extremes undue weight.

Two pairs failed the geometry check. For the EOS R50 (6648), Photographer returned 5844 × 3960 pixels and Canon 6000 × 4000. For the EOS R5 Mark II (7884), they returned 4956 × 3232 and 5088 × 3392. The EOS R8 (6587) had no JPEG track and its preview used a different encoding. All three appear in the TSV with an explicit status. The sample registries provide URLs and checksums for the public originals. [G7 X Mark III and EOS R50](../../scripts/research/public-crx/v1-cameras.tsv) · [EOS R6, R5 Mark II and R8](../../scripts/research/public-crx/adaptive-craw.tsv)

As a cross-check, decoding the wall JPEG independently with Pillow and evaluating the two images with NumPy and LittleCMS reproduced all six reported measurements to four decimal places. Matching textured patches after resizing both images to 512 × 340 pixels also showed why positional precision matters. The typical displacement among matched patches was about two pixels for the G5 X Mark II wall and 22 pixels for the excluded R5 Mark II frame. These displacements were measured separately and were never used to align or adjust the color results.

## What the probe measures

1. Parse the container and select the full-resolution `JPEG_BIG` track when present. If only the smaller `PRVW` is available, the probe identifies it separately. The G5 X Mark II originals have 5472 × 3648 camera JPEGs and 5496 × 3668 developed RGB16 images. The EOS R originals have 6720 × 4480 and 6744 × 4500 pixels respectively. The repository’s older `Cr3VisualQualityTest` uses an 8-bit RAW development and the smaller preview.
2. Check the JPEG’s color declaration in its EXIF data and in the CR3, and account for display orientation. All 16 measured pairs declare sRGB in the container and use orientation 1. The probe skips unknown or conflicting declarations and embedded ICC profiles. It reads the ordinary RGB16 result through the registered Photographer ImageIO provider and verifies its precision and color space.
3. Take the middle 75% of each oriented image in both dimensions and split it into eight columns and six rows. Average all samples within each region after converting sRGB to linear light, then convert the means to CIELAB relative to D65. No exposure, white-balance or tone adjustment is fitted between the two images.
4. Compare corresponding regional means. The TSV includes the median and nearest-rank 90th percentile of ΔE00 and the signed median differences in L*, a* and b* (Photographer minus Canon). `jpeg_median_L` gives the median lightness of the reference regions. A pair whose aspect ratios differ by more than 1% receives a geometry status and no score.

These regional averages describe broad differences in appearance. Small changes in framing or lens distortion can move a feature across a region boundary. Pointwise color accuracy, sharpness and sensor decoding need different checks. The [physical chart procedure](color-chart-validation.md) measures reproduction against independent patch values under a specified light.

## Reproduce a comparison

From the repository root, build the [standalone benchmark JAR](../../scripts/benchmarks/README.md#java-probe) with the project build JDK. Compile the three measurement sources and run them with Java 21 or newer. Supply one or more original CR3s on the command line.

```bash
jar=canon-cr3-benchmark/target/benchmarks.jar
src=canon-cr3-imageio/src/test/java/com/photographer/focusstack/cr3/imageio
classes=$(mktemp -d)
javac --release 21 -cp "$jar" -d "$classes" \
    "$src/ImageQualityHelper.java" "$src/Cr3CameraJpegTags.java" "$src/Cr3CameraJpegProbe.java"
samples=canon-cr3-imageio/src/test/resources/samples
java -cp "$classes:$jar" com.photographer.focusstack.cr3.imageio.Cr3CameraJpegProbe \
    --render neutral "$samples/sample.cr3" "$samples/sample-wide.cr3" > comparison.tsv
```

Replace the two sample paths with `"$samples"/*.cr3 "$samples"/research/*.cr3` to measure all 13 repository originals. Inspect the `status` column before interpreting a row. Missing camera JPEGs, unsupported color profiles and incompatible framing carry no numerical score.

To evaluate the separately selectable lens and tone corrections, use the [four-mode comparison](camera-rendering.md#reproduce-the-comparison).
