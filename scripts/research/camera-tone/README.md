# Fitting the camera-like tone curve

The optional camera tone controls display contrast while retaining RAW-derived color. It applies one shared curve to recorded Faithful or Neutral exposures from the PowerShot G5 X Mark II and Standard, Auto or Landscape exposures from the EOS R. These five combinations have camera-JPEG comparisons. The curve approximates broad brightness and contrast. Canon can vary its rendering with lighting, scene content and additional camera settings.

## Curve and measurements

For each developed sRGB pixel, invert the sRGB transfer and find the largest linear channel `m`. For `0 < m < 1`, map that peak to `0.1 m + 0.9 sigmoid(a × logit(m) + b)`. Multiply all three linear channels by the resulting peak-to-input ratio and convert to sRGB. This preserves the channel ratios, keeps greys neutral, and gives the tone curve a positive slope into black and white. A pixel with an already saturated channel keeps its original values. The calibration yields `a = 1.523` and `b = 2.000`. The curve is fixed at import time, including for partial reads. JPEG decoding is part of the calibration and QA.

The camera reference is the large sRGB JPEG from the same CR3. From the 8 × 6 [camera comparison grid](../../../docs/cr3/camera-jpeg-comparison.md), take the central four columns and four rows to reduce the influence of lens shading and small framing differences. For each region, measure the JPEG’s mean linear luminance and sample the RGB16 image every fourth pixel along each axis. Sum the RGB16 luminances in 128 bins of maximum sRGB channel code. The script predicts the corrected mean linear luminance from those bin weights and the mean linear peak per bin.

The fit minimizes robust residuals in log luminance for the 48 training regions, with a floor of 0.002 added to both measurements to limit the leverage of deep shadows. The optimization permits slopes from 1 to 2 and offsets from 0 to 2 in natural-log odds. The offset reached its upper bound. Each training original has equal weight. These limits constrain a fit made from a small number of scenes.

| Role | Camera / recorded style | Original | Median absolute regional ΔL* before → after |
| --- | --- | --- | ---: |
| Fit | G5 X II Faithful | `research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3` | 2.11 → 0.78 |
| Fit | G5 X II Faithful | `sample-wide.cr3` | 21.84 → 2.80 |
| Fit | EOS R Landscape | raw.pixls.us 4515 | 26.95 → 6.88 |
| Independent scene | G5 X II Faithful | `sample.cr3` | 11.45 → 3.27 |
| Independent scene | G5 X II Neutral | raw.pixls.us 3510 | 22.90 → 13.91 |
| Independent scene | EOS R Auto | `eosr-iso-low.cr3` | 2.16 → 1.50 |

These values describe the tone-only fit against Canon’s rendered luminance. The high residual on the G5 X II Neutral lake is one example of the limitations of a shared curve. The EOS R Landscape plant and the owned EOS R Auto scene are different subjects. Other EOS R members of either series repeat those subjects. The [sample registry](../public-crx/lossless-holdout.tsv) identifies the public originals by URL, CC0 license and SHA-256. The [repository capture table](../../../docs/cr3/camera-jpeg-comparison.tsv) gives the input hashes of the other originals.

## Independent EOS R Standard check

After freezing both curve parameters, we compared two EOS R Standard captures from the [PhotoRec EOS R sample gallery](https://photorec.tv/content/2018/09/canon-eos-r-sample-gallery) with their embedded camera JPEGs. Each measurement used the 48-region CIEDE2000 protocol for the RGB16 import. The table above measures lightness alone in 16 central regions. The optical correction was disabled for this check.

| Standard exposure | Scene | Median ΔE00 before → after | 90th percentile ΔE00 before → after |
| --- | --- | ---: | ---: |
| `2Y1A0641.CR3` | Bright outdoor portrait | 15.42 → 6.69 | 21.75 → 10.24 |
| `2Y1A1203.CR3` | Darker still life | 8.90 → 7.99 | 14.15 → 12.77 |

These captures support enabling the frozen curve for EOS R Standard. The smaller improvement on the still life illustrates the range of the measured effect. Neither original contributed to fitting the curve.

## Reproduce

Build the [benchmark JAR](../../benchmarks/README.md#java-probe). Download the two registry originals as `4515.cr3` and `3510.cr3` and verify their checksums. Set `public_dir` to their directory. Run from the repository root with a Java 21-compatible compiler, Python, NumPy and SciPy.

```bash
jar=canon-cr3-benchmark/target/benchmarks.jar
src=canon-cr3-imageio/src/test/java/com/photographer/focusstack/cr3/imageio
tools=scripts/research/camera-tone
classes=$(mktemp -d)
javac --release 21 -cp "$jar" -d "$classes" "$src/ImageQualityHelper.java" \
    "$src/Cr3CameraJpegTags.java" "$src/Cr3CameraJpegProbe.java" "$tools/CameraToneSamples.java"
samples=canon-cr3-imageio/src/test/resources/samples
java -Xmx768m -cp "$classes:$jar" com.photographer.focusstack.cr3.imageio.CameraToneSamples \
    "$samples/research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3" "$samples/sample-wide.cr3" \
    "$public_dir/4515.cr3" "$samples/sample.cr3" "$public_dir/3510.cr3" \
    "$samples/eosr-iso-low.cr3" > camera-tone-samples.tsv
python3 "$tools/fit.py" camera-tone-samples.tsv
```

Include additional captures on the Java command line to evaluate the fitted curve on new exposures. Only the three named training originals enter the fit. Keep the recorded style and the scene grouping with any new results.
