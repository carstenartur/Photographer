# Read CR3 files with Java ImageIO

Use `ImageIO.read(file)` for the photograph as rendered by the camera. It decodes the embedded JPEG preview. For RAW development or numerical measurements, open an explicit reader and select the required output.

| Output | ImageIO call for image index `0` | Result |
|---|---|---|
| The camera preview | `ImageIO.read(file)` or `reader.read(0)` | Decoded embedded JPEG pixels. The preview has its own dimensions. |
| A thumbnail | `reader.getNumThumbnails(0)`, then `reader.readThumbnail(0, 0)` if present | The separately embedded JPEG thumbnail. A CR3 can have none. |
| RGB from RAW or C-RAW | `reader.read(0, param)` with `rawProcessed()` | Demosaiced RGB, 8 bits per channel, with EXIF orientation applied. |
| A scaled Bayer mosaic | `reader.readRaster(0, null)` or `rawLinear()` | One unsigned 16-bit band, cropped to the active area with black-level adjustment by default. |
| Native sensor codes | `reader.readRaster(0, param)` with `rawSensor()` | One unsigned 16-bit band in the camera's sample units, including optical-black margins. |
| Exact JPEG file for export | [Copy the camera’s embedded JPEG unchanged](import.md#copy-the-cameras-embedded-jpeg-unchanged) | Original compressed bytes, including the camera’s JPEG encoding. |
| Camera and capture information | `reader.getImageMetadata(0)` | Read-only native metadata, when available. |

Preview, thumbnail and metadata access are stable APIs. The RAW modes are experimental and depend on the file's CRX profile. An explicit RAW read reports an error if decoding fails. An application that can use a camera preview instead can handle the exception and request the preview explicitly. See the [decoder and supported layouts](decoder.md).

## Maven setup

Use JDK 25 and Maven 3.9.x. This example `pom.xml` declares one dependency. Maven resolves the other five CR3 libraries and the SLF4J API transitively. Once the selected version is published, Maven Central is available through Maven's default configuration. Downloading a release requires no publisher credentials or signing profile.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>read-cr3</artifactId>
  <version>1.0</version>
  <properties>
    <maven.compiler.release>25</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <cr3.version>0.1.5</cr3.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>cr3-imageio</artifactId>
      <version>${cr3.version}</version>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.14.1</version>
      </plugin>
    </plugins>
  </build>
</project>
```

The coordinates above target the 0.1.5 release. To use a development checkout before its version reaches Central, run `mvn -pl canon-cr3-imageio -am install` from the Photographer repository root. Set `cr3.version` in the consumer POM to the installed revision, for example `0.1.5-SNAPSHOT`. Publication and independent download verification are covered in the [Maven Central guide](../cr3-maven-central.md).

## Quick preview

Given a `java.io.File file`, read the preview in a method that can throw `IOException`.

```java
var preview = javax.imageio.ImageIO.read(file);
if (preview == null) {
    throw new java.io.IOException("No installed ImageIO reader recognized " + file);
}
```

The JAR registers its reader through `META-INF/services/javax.imageio.spi.ImageReaderSpi`. Keep that entry when assembling a shaded JAR. The provider detects CR3 by inspecting the stream and also advertises the format name `cr3`, suffix `cr3`, and MIME type `image/x-canon-cr3`. A recognized file with a missing or unreadable preview can raise an `IOException`.

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
                    case "preview" -> describe(reader.read(0));
                    case "thumbnail" -> {
                        if (reader.getNumThumbnails(0) > 0) {
                            describe(reader.readThumbnail(0, 0));
                        } else {
                            System.out.println("No embedded thumbnail");
                        }
                    }
                    case "rgb" -> {
                        param.setDecodeOptions(Cr3DecodeOptions.rawProcessed().maxWorkers(2));
                        describe(reader.read(0, param));
                    }
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
| `param.setPreviewOnly(true)` | Forces the embedded JPEG for `read`. For another mode, use a fresh parameter and call `setDecodeOptions`. |
| `Cr3DecodeOptions.defaults().outputMode(Cr3DecodeOptions.OutputMode.THUMBNAIL)` | Selects the embedded thumbnail through `read(0, param)`. This also allows standard region and subsampling parameters on the thumbnail. |
| `cropToActiveArea(boolean)`, `applyBlackLevel(boolean)` | Both default to `true` in `rawProcessed()` and `rawLinear()`. Disable both in linear mode to scale the full sensor mosaic to 16 bits. Native sensor mode always preserves full-sensor coordinates and unadjusted codes. |
| `applyWhiteBalance(boolean)`, `applyColorMatrix(boolean)`, `applySrgbConversion(boolean)` | These three RGB processing stages are enabled by default in `rawProcessed()`. They can be disabled independently. JPEG and Bayer reads bypass these stages. |
| `bitDepth(...)` | In 0.1.5, processed RGB is always 8 bits per channel, including when `BitDepth.SIXTEEN` is requested. Linear and sensor Bayer data always use unsigned 16-bit storage. |
| `maxWorkers(int)` | Per-import upper limit for RAW stages. The default `0` uses the processors available to the JVM, `1` requests sequential processing, and a positive value caps parallelism within the shared worker pool. Negative values throw `IllegalArgumentException`. |

`readRaster` always returns Bayer samples. A preview, thumbnail or processed-RGB option passed to `readRaster` selects the **linear** Bayer path, carrying over the crop, black-level and worker settings. Use `read` for JPEG or RGB pixels. Both Bayer modes also work with `read` if a `BufferedImage` is more convenient. In linear mode the adjustment subtracts the decoder's default pedestal, clamps at zero, and scales using the sensor's nominal maximum. Use `rawSensor()` to compare original values across exposures.

## Standard ImageIO regions and destinations

- `param.setSourceRegion(rectangle)` uses the selected image's coordinates. With `rawSensor()`, (0, 0) is the sensor origin. For cropped `rawLinear()`, (0, 0) is the active-area origin. Processed RGB uses the EXIF-oriented output's coordinates.
- `param.setSourceSubsampling(2, 2, 0, 0)` keeps every second row and column. On a Bayer mosaic this selects a single color-filter phase. A Bayer region limits the size of the output buffer. The decoder still traverses the compressed frame. JPEG and processed RGB regions are selected from the fully decoded image.
- `read` supports destination images, destination types, destination offsets and band selection. Choose a destination compatible with the selected output. A supplied destination receives pixels at the requested physical offset. `readRaster` returns a new single-band raster and uses the destination offset as its logical origin. It ignores the destination image and destination type. Use `getMinX()` and `getMinY()` when reading a translated raster.
- `reader.getWidth(0)`, `getHeight(0)` and the first entry from `getImageTypes(0)` describe the preview. For RAW output geometry, inspect the returned image or raster. The metadata tree also exposes sensor and visible dimensions, orientation and capture information. Access it with `metadata.getAsTree(Cr3ImageMetadata.NATIVE_FORMAT_NAME)`. GPS and crop nodes depend on the file.

## Verification

Run the ImageIO checks from the Photographer checkout.

```shell
mvn -B -pl canon-cr3-imageio -am clean verify -Djava.awt.headless=true
```

For direct Java file access, command-line examples and imports in Photographer, see [CR3 import](import.md). See the [ImageIO module](../../canon-cr3-imageio/README.md), [API compatibility boundaries](../cr3-api-boundaries.md) and [CR3 manual](index.md).
