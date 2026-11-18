# Read CR3 files with Java ImageIO

Use `ImageIO.read(file)` to develop the full-resolution color image from CR3 RAW or C-RAW. Open an explicit reader to select the embedded JPEG, get the original Bayer values, or configure RAW development.

RAW development uses the as-shot white balance when the capture contains supported CTMD ColorData. Its color reproduction is experimental. For the camera's rendering, including any lens correction, noise reduction and picture style applied by the camera, select the embedded JPEG.

| Output | ImageIO call for image index `0` | Result |
|---|---|---|
| RGB from RAW or C-RAW | `ImageIO.read(file)` or `reader.read(0)`. To configure development, pass a param with `rawProcessed()`. | Demosaiced 8-bit sRGB at the active-area resolution with EXIF orientation applied. |
| The camera preview | `reader.read(0, param)` with `param.setPreviewOnly(true)` | Decoded embedded JPEG pixels at the camera preview's dimensions. |
| A thumbnail | `reader.getNumThumbnails(0)`, then `reader.readThumbnail(0, 0)` if present | The separately embedded JPEG thumbnail. A CR3 can have none. |
| A scaled Bayer mosaic | `reader.readRaster(0, null)` or `rawLinear()` | One unsigned 16-bit band, cropped to the active area with black-level adjustment by default. |
| Native sensor codes | `reader.readRaster(0, param)` with `rawSensor()` | One unsigned 16-bit band in the camera's sample units, including optical-black margins. |
| Exact JPEG file for export | [Copy the camera’s embedded JPEG unchanged](import.md#copy-the-cameras-embedded-jpeg-unchanged) | Original compressed bytes, including the camera’s JPEG encoding. |
| Camera and capture information | `reader.getImageMetadata(0)` | Read-only native metadata, when available. |

**Upgrading from 0.1.5.** Starting with 0.1.6, the standard read develops RAW. Pass `setPreviewOnly(true)` to keep the camera-rendered JPEG behavior.

The stream-origin, preview-toggle, Bayer destination validation and metadata schema behavior described below is available in the [current local build](#use-a-local-build).

A missing, unsupported, or unreadable RAW track causes an `IOException` on a RAW read. Applications can explicitly request the embedded JPEG when one is present. Full-sensor values have been verified on the measured 14-bit, four-plane V1 lossless RAW and three-level C-RAW layouts. See the [decoder and supported layouts](decoder.md).

## Maven setup

ImageIO comes with the JDK. A Java 21+ application needs one direct dependency. Maven resolves the decoder and its runtime dependencies from Maven Central.

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>cr3-imageio</artifactId>
  <version>0.1.6</version>
</dependency>
```

### Run the minimal example

The standalone example consists of a [pom.xml](../../examples/cr3-imageio/pom.xml) and [SensorValues.java](../../examples/cr3-imageio/src/main/java/SensorValues.java). It reads the original Bayer samples with `readRaster(0, param)` and `rawSensor()`, then counts how many different values occur in the image.

From `examples/cr3-imageio`, run it with Maven and a JDK 21 or newer. This command uses the [PowerShot G5 X Mark II sample](../../canon-cr3-imageio/src/test/resources/samples/sample.cr3) from the checkout. Replace its path to read your own photo.

```sh
mvn -q compile org.codehaus.mojo:exec-maven-plugin:3.6.4:java \
  -Dexec.mainClass=SensorValues \
  -Dexec.args=../../canon-cr3-imageio/src/test/resources/samples/sample.cr3
```

```text
5632 x 3710 Bayer mosaic, 1584 distinct sensor values, maximum 3512
```

An 8-bit sample can represent 256 distinct values. This exposure uses 1,584 of the possible 16,384 codes of its 14-bit sensor data. Java stores each decoded code in an unsigned 16-bit raster sample.

### Use a local build

To test a local build instead, run the following commands in a Photographer checkout with JDK 25 and Maven 3.9+. Use the printed revision as the dependency version (the `cr3.version` property in the example POM).

```sh
mvn -B -pl canon-cr3-imageio -am install -DskipTests
mvn -q -N help:evaluate -Dexpression=revision -DforceStdout
```

[Verify the released Maven libraries](../cr3-maven-central.md#resolve-a-released-version)

### Optional SIMD color matrix on JDK 25

Build with `-Pcr3-vector` to include the Java Vector API implementation of the RAW-to-RGB color matrix. Add `--add-modules=jdk.incubator.vector` to the JDK 25 application launch to enable it. The same processing JAR uses the scalar path on JDK 21, with the module disabled, or when packed rows or 256-bit vectors are unavailable.

The JMH distribution can include the same implementation.

```sh
mvn -B -Pbenchmarks,cr3-vector -pl canon-cr3-benchmark -am -DskipTests package
java --add-modules=jdk.incubator.vector \
  -Dsamples.dir=canon-cr3-imageio/src/test/resources/samples \
  -jar canon-cr3-benchmark/target/benchmarks.jar Cr3RawProcessedBenchmark
```

## Load the full-resolution color image

Given a `java.io.File file`, read the developed image in a method that can throw `IOException`.

```java
var image = javax.imageio.ImageIO.read(file);
if (image == null) {
    throw new java.io.IOException("No installed ImageIO reader recognized " + file);
}
```

The JAR registers its reader through `META-INF/services/javax.imageio.spi.ImageReaderSpi`. Keep that entry when assembling a shaded JAR. The provider detects CR3 by inspecting the stream and also advertises the format name `cr3`, suffix `cr3`, and MIME type `image/x-canon-cr3`.

## Quick preview

With a `CanonCr3ImageReader` opened as shown below, select the camera JPEG explicitly.

```java
var previewParam = reader.getDefaultReadParam();
previewParam.setPreviewOnly(true);
var preview = reader.read(0, previewParam);
```

## Run a selected read mode

Save this class as `src/main/java/ReadCr3.java`. It opens the file once, selects the CR3 provider even if other ImageIO plug-ins are present, and reads only the requested output.

```java
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReader;
import com.photographer.focusstack.cr3.imageio.Cr3ImageMetadata;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;

public class ReadCr3 {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: ReadCr3 <file.cr3> <mode>");
        }
        try (var input = new FileImageInputStream(new File(args[0]))) {
            var reader = findReader(input);
            try {
                reader.setInput(input);
                var param = reader.getDefaultReadParam();
                switch (args[1]) {
                    case "preview" -> {
                        param.setPreviewOnly(true);
                        describe(reader.read(0, param));
                    }
                    case "thumbnail" -> {
                        if (reader.getNumThumbnails(0) > 0) {
                            describe(reader.readThumbnail(0, 0));
                        } else {
                            System.out.println("No embedded thumbnail");
                        }
                    }
                    case "rgb" -> describe(reader.read(0, param));
                    case "linear", "sensor", "patch" -> {
                        param.setDecodeOptions(args[1].equals("linear")
                                ? Cr3DecodeOptions.rawLinear() : Cr3DecodeOptions.rawSensor());
                        if (args[1].equals("patch")) {
                            param.setSourceRegion(new Rectangle(160, 64, 64, 64));
                        }
                        var samples = reader.readRaster(0, param);
                        System.out.printf("%d x %d, first Bayer value %d%n",
                                samples.getWidth(), samples.getHeight(), samples.getSample(0, 0, 0));
                    }
                    case "metadata" -> {
                        if (reader.getImageMetadata(0) instanceof Cr3ImageMetadata metadata) {
                            var camera = metadata.getCr3Metadata();
                            System.out.printf("%s, sensor %d x %d, %d bits%n",
                                    camera.cameraModel(), camera.sensorWidth(), camera.sensorHeight(),
                                    camera.bitDepth());
                        } else {
                            System.out.println("No CR3 image metadata");
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown mode: " + args[1]);
                }
            } finally {
                reader.dispose();
            }
        }
    }

    static CanonCr3ImageReader findReader(ImageInputStream input) throws IOException {
        var readers = ImageIO.getImageReaders(input);
        while (readers.hasNext()) {
            var candidate = readers.next();
            if (candidate instanceof CanonCr3ImageReader cr3) {
                return cr3;
            }
            candidate.dispose();
        }
        throw new IOException("The CR3 provider is absent or did not recognize this input");
    }

    static void describe(BufferedImage image) {
        System.out.printf("%d x %d, %d bands%n",
                image.getWidth(), image.getHeight(), image.getRaster().getNumBands());
    }
}
```

Build the example in its project directory and run it with the path to a CR3 file.

```shell
mvn compile org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies
java -cp "target/classes:target/dependency/*" ReadCr3 "/path/to/photo.cr3" rgb
```

On Windows, separate the classpath entries with a semicolon, as in `"target/classes;target/dependency/*"`. Replace `rgb` with `preview`, `thumbnail`, `linear`, `sensor`, `patch` or `metadata`. The patch example samples a 64 × 64 rectangle starting at sensor position (160, 64). Use a separate reader and stream for each concurrent import.

## Read parameters

Get a fresh `CanonCr3ImageReadParam` from `reader.getDefaultReadParam()` and pass it to `reader.read(0, param)` or `reader.readRaster(0, param)`. The fluent setters on `Cr3DecodeOptions` modify the same instance.

| Setting | Effect |
|---|---|
| `param.setPreviewOnly(true)` | Selects the embedded JPEG for `read`. Setting it back to `false` restores the latest options supplied through `setDecodeOptions`. |
| `Cr3DecodeOptions.defaults().outputMode(Cr3DecodeOptions.OutputMode.THUMBNAIL)` | Selects the embedded thumbnail through `read(0, param)`. This also allows standard region and subsampling parameters on the thumbnail. |
| `cropToActiveArea(boolean)`, `applyBlackLevel(boolean)` | Both default to `true` in `rawProcessed()` and `rawLinear()`. Disable both in linear mode to scale the full sensor mosaic to 16 bits. Native sensor mode always preserves full-sensor coordinates and unadjusted codes. |
| `applyWhiteBalance(boolean)`, `applyColorMatrix(boolean)`, `applySrgbConversion(boolean)` | These three RGB processing stages are enabled by default in `rawProcessed()`. They can be disabled independently. JPEG and Bayer reads bypass these stages. |
| `bitDepth(...)` | Processed RGB is always 8 bits per channel, including when `BitDepth.SIXTEEN` is requested. Linear and sensor Bayer data use unsigned 16-bit storage. |
| `maxWorkers(int)` | Per-import upper limit for RAW stages. The default `0` uses the processors available to the JVM, `1` requests sequential processing, and a positive value caps parallelism within the shared worker pool. Negative values throw `IllegalArgumentException`. |

`readRaster` always returns Bayer samples. A preview, thumbnail or processed-RGB option passed to `readRaster` selects the **linear** Bayer path, carrying over the crop, black-level and worker settings. Use `read` for JPEG or RGB pixels. Both Bayer modes also work with `read` if a `BufferedImage` is more convenient. In linear mode the adjustment subtracts the decoder's default pedestal, clamps at zero, and scales using the sensor's nominal maximum. Use `rawSensor()` to compare original values across exposures.

## Standard ImageIO regions and destinations

- When supplying an `ImageInputStream`, position it at the first CR3 box before discovering or using the reader. The CR3 probe preserves the stream position and byte order.
- `param.setSourceRegion(rectangle)` uses the selected image's coordinates. With `rawSensor()`, (0, 0) is the sensor origin. For cropped `rawLinear()`, (0, 0) is the active-area origin. Processed RGB uses the EXIF-oriented output's coordinates.
- `param.setSourceSubsampling(2, 2, 0, 0)` keeps every second row and column. On a Bayer mosaic this selects a single color-filter phase. The CRX decoder reads the required tile planes up to the last needed row, including the preceding rows that supply prediction state. Processed RGB develops the selected region with the neighbors needed for demosaicing and collects the optical-black levels used by full-frame processing. JPEG regions are selected from the decoded preview or thumbnail.
- `read` supports destination images, destination types, destination offsets and band selection. A supplied Bayer destination must have unsigned 16-bit storage for the selected band. The reader rejects an incompatible band before writing samples. A compatible `TYPE_USHORT_GRAY` image can receive samples directly at the requested physical offset. A failed decode can leave that destination partially filled. `readRaster` returns a new single-band raster and uses the destination offset as its logical origin. It ignores the destination image and destination type. Use `getMinX()` and `getMinY()` when reading a translated raster.
- For repeated full-frame RGB reads, supply a dedicated `TYPE_INT_RGB` image with `setDestination(image)` and opt in with `setUseDestinationAsWorkspace(true)`. When the destination layout, orientation and read parameters allow it, development uses that image's storage directly. A failed read can leave it partially overwritten.
- `reader.getWidth(0)`, `getHeight(0)` and the first entry from `getImageTypes(0)` describe the default developed RAW image. Inspect the returned preview or Bayer raster for its own dimensions. The metadata tree also exposes sensor and visible dimensions, orientation and capture information. Access it with `metadata.getAsTree(Cr3ImageMetadata.NATIVE_FORMAT_NAME)` and query its schema with `metadata.getMetadataFormat(Cr3ImageMetadata.NATIVE_FORMAT_NAME)`. GPS and crop nodes depend on the file.

## Write a lossless CR3 using a camera profile

Select a built-in `Cr3CameraProfile` and supply a full, single-band unsigned-short Bayer
raster in native 14-bit sensor units (0–16383). The profile provides the sensor geometry,
CFA, CRX tile layout, track descriptions and preview dimensions. The writer can encode a
synthetic raster or one from `Cr3DecodeOptions.rawSensor()` and needs no sample CR3.

| Profile | Full RGGB raster including masked margins | Full JPEG |
|---|---:|---:|
| `EOS_R` | 6888 × 4546 | 6720 × 4480 |
| `POWERSHOT_G5_X_MARK_II` | 5632 × 3710 | 5472 × 3648 |
| `EOS_M50` | 6288 × 4056 | 6000 × 4000 |
| `EOS_RP` | 6384 × 4224 | 6240 × 4160 |

Choose a new destination file. To reuse an existing file, truncate it before wrapping it
in an `ImageOutputStream`.

```java
import com.photographer.focusstack.cr3.encode.Cr3CameraProfile;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageWriter;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Path;

static void writeCr3(Raster mosaic, Cr3CameraProfile profile, Path destination) throws IOException {
    var writer = (CanonCr3ImageWriter) ImageIO.getImageWritersByFormatName("CR3").next();
    try (var output = new FileImageOutputStream(destination.toFile())) {
        writer.setDefaultCameraProfile(profile);
        writer.setOutput(output);
        writer.write(new IIOImage(mosaic, null, null));
    } finally {
        writer.dispose();
    }
}
```

For an EOS R mosaic, pass `Cr3CameraProfile.EOS_R` as the profile. Set
`CanonCr3ImageWriteParam.setCameraProfile(profile)` on the parameters passed to
`writer.write(null, image, parameters)` to override the writer's default for one image.
An unconfigured writer reports an `IIOException`. The writer requires the full sensor grid
for the selected profile.

The writer emits the four-track lossless CRX V1 layout and generates the reduced Bayer
image and JPEGs from the mosaic. It records the selected camera model and
`Software=Photographer`. The output uses nominal black and white levels, unit white
balance gains and orientation 1. These are encoding defaults. ISO, exposure, capture
date, lens, serial number and location are omitted. [Camera profiles and defaults](../../canon-cr3-core/src/main/java/com/photographer/focusstack/cr3/encode/Cr3CameraProfile.java) · [Metadata generation](../../canon-cr3-core/src/main/java/com/photographer/focusstack/cr3/encode/Cr3ProfileMetadata.java)

All four formats have been checked with Photographer and DNGLab 0.8.0. The full Bayer
codes round-trip exactly. Preview rendering and entropy encoding use Photographer's
algorithms. Canon Digital Photo Professional compatibility remains unverified.

## Write and read a Bayer DNG

The same `io.github.carstenartur:cr3-imageio` dependency registers a DNG writer and reader. Keep the ImageReaderSpi and ImageWriterSpi service entries when shading the JAR.

For a full-sensor CR3 import, the returned image carries the metadata needed to export the
Bayer mosaic. The following method uses the `findReader` helper from the complete example above.

```java
static void exportDng(File cr3File, File dngFile) throws IOException {
    try (var input = new FileImageInputStream(cr3File)) {
        var reader = findReader(input);
        try {
            reader.setInput(input);
            var param = reader.getDefaultReadParam();
            param.setDecodeOptions(Cr3DecodeOptions.rawSensor());
            var sensor = reader.read(0, param);
            if (!ImageIO.write(sensor, "DNG", dngFile)) {
                throw new IOException("No DNG writer for this image on the classpath");
            }
        } finally {
            reader.dispose();
        }
    }
}
```

Each sensor code occupies one unsigned 16-bit sample. A 14-bit code of 2857 is stored as 2857.
The file records its Bayer pattern, active area, orientation, black and white levels and camera
calibration. Compression is disabled. This preserves the decoded samples of both RAW and C-RAW.
Automatic CR3 calibration covers the EOS R, EOS RP, EOS M50 and PowerShot G5 X Mark II with
recognized CTMD ColorData (versions 16–19). The black level, white level and as-shot white balance
come from the capture. The two camera color matrices and their illuminants come from the
[RawTherapee 5.12 public-domain and CC0 profiles](https://github.com/RawTherapee/RawTherapee/tree/5.12/rtdata/dcpprofiles).
A missing calibration raises an `IIOException`.

For an application that already has the full native `Raster`, pass the calibration explicitly.
This example assumes the CR3 reader and `param` are configured as above.

```java
var samples = reader.readRaster(0, param);
var writer = ImageIO.getImageWritersByFormatName("DNG").next();
try (var output = ImageIO.createImageOutputStream(dngFile)) {
    writer.setOutput(output);
    var image = new javax.imageio.IIOImage(samples, null, reader.getImageMetadata(0));
    writer.write(null, image, writer.getDefaultWriteParam());
} finally {
    writer.dispose();
}
```

A different sensor source can supply `DngImageMetadata.fromDirectory(rawIfd)` with its own
geometry, Bayer pattern and calibration. A grayscale picture by itself has no CFA or camera
calibration. The writer requires a full-resolution sensor mosaic and rejects incompatible layouts.

Read the exported DNG through the standard API:

```java
var sensor = ImageIO.read(dngFile);
int code = sensor.getRaster().getSample(x, y, 0);
```

The DNG reader returns a single-band Bayer mosaic. Its supported layout is classic TIFF with an
uncompressed, unsigned 16-bit CFA mosaic in the first image directory (IFD0). DNGs with a
JPEG preview in IFD0 and the raw image in a SubIFD, packed 12/14-bit samples, compressed DNGs,
and developed LinearRaw RGB DNGs are outside this reader's scope. Use
`ImageIO.getImageReadersByFormatName("DNG")` to select the provider explicitly. Generic TIFF
providers may also recognize the container. Re-export preserves supported calibration and sensor
samples. The output does not embed the original CR3, JPEG preview, or proprietary MakerNotes.

With an explicit DNG reader, `read(0, param)` supports a source region, subsampling and a
destination offset. If you supply a destination image, select an unsigned 16-bit band.
The reader writes the selected samples to the destination rectangle and preserves samples
outside it. DNG metadata uses the platform TIFF schema, available through
`metadata.getMetadataFormat(DngImageMetadata.NATIVE_FORMAT_NAME)`.

This product includes DNG technology under license by Adobe. [Digital Negative specification and patent license](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/digital-negative.html)

## Verification

Run the ImageIO checks from the Photographer checkout.

```shell
mvn -B -pl canon-cr3-imageio -am clean verify -Djava.awt.headless=true
```

For direct Java file access, command-line examples and imports in Photographer, see [CR3 import](import.md). See the [ImageIO module](../../canon-cr3-imageio/README.md), [API compatibility boundaries](../cr3-api-boundaries.md) and [CR3 manual](index.md).
