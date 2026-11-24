# Measuring CR3 RGB16 color with a physical chart

This procedure measures the colors returned by Photographer's default `ImageIO.read(File)` against independent patch references. It covers both RAW and C-RAW supported by the reader. The output is a comparison in CIELAB for the chosen camera, chart, lighting and exposure. The repository's G5 X Mark II fixtures have decoder reference renders and camera previews. A measured G5 X Mark II color accuracy result requires a chart recording and reference data.

## Capture a daylight and artificial-light pair

1. Photograph the same physical chart separately in diffuse daylight and under the artificial light of interest, for example tungsten. Use one light source per capture. Keep illumination uniform across the chart, avoid reflections and shadows, use low ISO, and fill enough of the frame to sample clean patch interiors.
2. Record the exact chart make, edition or serial number, reference source and measurement conditions. Record the light source, camera model, firmware, RAW/C-RAW mode, exposure and white balance. For an as-shot assessment, set white balance as intended for the light and retain the original CR3. Record whether this was automatic, a preset, or a custom balance from an independent gray card.
3. Set the exposure using the same predeclared metering procedure under each light. A small exposure bracket can reveal clipped highlights and dark-patch noise. Keep and report each exposure separately. Exposure influences lightness and the reported ΔE. This probe compares the RGB values as delivered, at the recorded exposure.
4. Supply CIELAB reference values for a 2° observer relative to D50 or D65. Prefer a measurement of the actual chart. A manufacturer's nominal reference must identify the matching chart generation and should be labelled **nominal** in results. For a light-specific accuracy claim, derive the patch references from the measured reflectances and illuminant spectrum, or measure them under that illuminant and adapt to the chosen reference white. A manufacturer's D50 table can serve as a common reproduction target under both lights. It cannot establish the actual patch colors under an unmeasured LED spectrum. [X-Rite chart generations and reference data](https://www.xrite.com/en/service-support/new_color_specifications_for_colorchecker_sg_and_classic_charts)
5. Select a small interior rectangle for each patch in the developed, EXIF-oriented output image. Coordinates are zero-based from the top left. Supply a separate region file for each framing. A perspective-skewed chart is fine if the rectangles stay inside the patches.

## Prepare the two tables

Use UTF-8 tab-separated files with the literal headers below. Every patch ID must appear exactly once in each file. IDs must agree across the two files.

| File | Header in column order | Values |
| --- | --- | --- |
| `reference.tsv` | `patch`, `L`, `a`, `b` | Physical patch ID and reference L*, a*, b*. Use a dot for the decimal separator. |
| `regions.tsv` | `patch`, `x`, `y`, `width`, `height` | Same ID and a rectangle in the decoded image. Only pixels inside this rectangle contribute. |

The light name and the reference white describe different things. Pass `D50` when the **Lab table** is relative to D50, even if the photo was taken under tungsten. Keep the original reference, ROI files, recording notes, input checksums and Photographer revision with each result.

## Run on the actual CR3 reader

From this checkout, build the benchmark JAR using the [public probe build instructions](../../scripts/benchmarks/README.md#java-probe). It contains the default CR3 reader and its runtime dependencies. Compile the two test-side source files with a JDK supporting Java 21. The probe verifies that Photographer is the first reader for the file, then calls `ImageIO.read(File)` and checks for three unsigned 16-bit sRGB channels.

```bash
jar=canon-cr3-benchmark/target/benchmarks.jar
src=canon-cr3-imageio/src/test/java/com/photographer/focusstack/cr3/imageio
classes=$(mktemp -d)
javac --release 21 -cp "$jar" -d "$classes" "$src/ImageQualityHelper.java" "$src/Cr3ColorChartProbe.java"
probe=com.photographer.focusstack.cr3.imageio.Cr3ColorChartProbe
java -cp "$classes:$jar" "$probe" daylight.cr3 D50 reference.tsv daylight-regions.tsv > daylight-results.tsv
java -cp "$classes:$jar" "$probe" tungsten.cr3 D50 reference.tsv tungsten-regions.tsv > tungsten-results.tsv
```

The example uses a common D50 rendering target. Use separate reference files if the individual lighting measurements produced different targets.

## Interpret the report

The probe reads the full unsigned 16-bit channel values and inverts the sRGB transfer function **before** averaging the pixels of each patch. It converts the resulting linear sRGB mean to CIE XYZ D65. With a D50 reference it applies Bradford chromatic adaptation before conversion to CIELAB. Each patch reports reference and measured L*, a*, b*, the signed differences (measured minus reference), and CIEDE2000 (ΔE00, unit weights). [sRGB and Lab conversion](https://www.w3.org/TR/css-color-4/#color-conversion-code) · [CIEDE2000 and reference tests](https://hajim.rochester.edu/ece/sites/gsharma/ciede2000/)

Read the signed a* and b* errors of neutral patches to see whether a cast changes with illumination. The signed L* error shows lightness changes. The two boundary counts record pixels whose **developed output** reaches zero or 65535 in any channel. Check suspicious patches for exposure, reflections or an incorrectly placed region. The summary gives mean, median and worst ΔE00 with equal weight per patch. Compare identical patch sets and retain the per-patch rows so a large hue error remains visible.
