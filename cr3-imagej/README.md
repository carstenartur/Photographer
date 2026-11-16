# Canon CR3 sensor import for ImageJ and Fiji

Install the plugin and its decoder using the [Fiji installation and update-site instructions](../docs/cr3/fiji-update-site.md). Start Fiji with Java 21 or later. Choose **File › Import › CR3 Sensor Data...** and select a Canon CR3 file.

The command opens the original Bayer mosaic as one unsigned 16-bit plane. Fiji displays that plane in grayscale. Each pixel holds one camera sample at a red, green or blue sensor site. The import includes the margins and preserves the camera's numeric range and sensor coordinates. ImageJ's brightness and contrast controls adjust the display range independently of these values. Supported RAW and C-RAW configurations are listed in the [decoder guide](../docs/cr3/decoder.md).

**Image › Show Info...** displays the camera model and available sensor properties. `CR3.CFA` names the Bayer pattern at the sensor origin. `CR3.BitDepth` gives the recorded bit depth. `CR3.ActiveAreaLTRBInclusive` gives zero-based left, top, right and bottom coordinates, including both end pixels. `CR3.BlackLevelRGGB` and `CR3.WhiteBalanceGainRGGB` use camera channel order (red, green on a red row, green on a blue row, blue). `CR3.WhiteLevel` records the white level for the exposure. These values describe the uncorrected mosaic. No crop, rotation, interpolation, black-level adjustment or gain is applied.

An ImageJ macro can pass the path directly:

```ijm
run("CR3 Sensor Data...", "path=[/data/capture.cr3]");
```

For headless Java, `Cr3SensorImporter.open(Path)` returns an `ImagePlus` and propagates file and decoder errors.

```java
import com.photographer.focusstack.cr3.imagej.Cr3SensorImporter;
import ij.ImagePlus;
import java.nio.file.Path;

ImagePlus image = Cr3SensorImporter.open(Path.of("/data/capture.cr3"));
int sensorCode = image.getProcessor().get(100, 200);
```

## Open a color image

Use the [Fiji script for color output](../docs/cr3/fiji-update-site.md#open-a-color-image) to open the camera’s embedded JPEG or develop a supported RAW file into RGB. Use **CR3 Sensor Data...** for measurements in native Bayer coordinates.

## Verify an import against a reference

On the Fiji distribution of 12 November 2026 (ImageJ 2.18.0/1.54p and Java 21), the PowerShot G5 X Mark II sample matched all 20,894,720 LibRaw reference values and the EOS R ISO-low sample matched all 31,312,848. To repeat the comparison, choose the CR3 and its corresponding full-sensor TIFF.

```ijm
sample = File.openDialog("Choose the CR3 capture");
reference = File.openDialog("Choose its sensor reference TIFF");
run("CR3 Sensor Data...", "path=[" + sample + "]");
sensor = getImageID();
open(reference);
imageCalculator("Difference create 32-bit", sensor, getImageID());
getRawStatistics(count, mean, minimum, maximum);
print("Sensor sites: " + d2s(count, 0) + ", maximum absolute difference: " + maximum);
```
