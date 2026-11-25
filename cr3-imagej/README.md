# Canon CR3 import for ImageJ and Fiji

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

In **0.1.8 or later**, choose **File › Import › CR3 Color Image...** to develop the active area of a supported RAW or C-RAW capture into a three-channel, EXIF-oriented sRGB composite. The red, green and blue planes each store unsigned 16-bit values and share an initial display range of 0–65535. The current development version also applies the supported lens and camera tone profiles by default, matching normal ImageIO RGB16 reads. [Profile coverage and independent controls](../docs/cr3/camera-rendering.md). RAW color rendering is experimental.

The [Groovy example](examples/open-cr3-color.groovy) offers the camera JPEG and the 16-bit RAW composite. A headless Java caller can read individual developed values directly:

```java
import com.photographer.focusstack.cr3.imagej.Cr3ColorImporter;
import ij.CompositeImage;
import java.nio.file.Path;

CompositeImage image = Cr3ColorImporter.open(Path.of("/data/capture.cr3"));
int green = image.getStack().getProcessor(2).get(100, 200);
```

ImageJ macros can call `run("CR3 Color Image...", "path=[/data/capture.cr3]");`.

<a id="memory-use-in-018-development-builds"></a>

## Memory use

Both RAW menu commands use the decoder's `AUTO` memory policy. If the normal read exceeds its estimated Java heap budget, the decoder selects compact processing with an adapted worker limit. The sensor importer retains the same dense unsigned-short plane; its fallback reduces worker workspace. The RGB16 importer can also use compact development and in-place orientation. One line beginning `CR3 low-memory:` on standard error reports the limit for the read. The estimate is advisory: if even one worker exceeds the budget, the decoder still attempts that smallest configuration. Sensor codes, RGB16 precision, dimensions and orientation follow the same rules in either mode.

A sensor image keeps one unsigned-short plane (2 bytes per sensor site). For a normal full RGB16 read, the ImageJ stack adopts the decoder's three channel arrays (6 bytes per developed pixel). For example, the 5,496 × 3,668 G5 X Mark II color result retains about 115 MiB of pixel arrays. Codec workspaces, open images and ImageJ's composite display can require additional heap. Calling `open(Path)` lets a headless caller measure the planes without opening a window. See the [adapter ownership contract](../docs/developer/modules.md#imagej-adapter) and [reproducible full-file ImageIO measurements](../docs/cr3/optimization-2026-11-19/README.md).

Fiji runs these commands on Java 21 or later. Building the plugin from source requires JDK 25. The optional Java 25 vector backend accelerates the packed **RGB8** color matrix. The color menu uses the RGB16 pipeline and the sensor menu reads Bayer codes, so both work without a vector module flag.

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
