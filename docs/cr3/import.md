# Import a CR3 file

Use `cr3-core` to open a local CR3 file, inspect camera metadata, or choose exactly which pixels to import. Code that already uses Java ImageIO can use the [ImageIO reader](imageio.md) for the same output choices. The final section covers captured files in Photographer.

## Maven

Use JDK 21 or newer for the consuming application. Add this Maven Central dependency inside the consumer POM's `<dependencies>` element. Maven resolves the container, metadata, codec and processing modules transitively. A consumer that already declares `cr3-imageio` also receives `cr3-core` transitively.

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>cr3-core</artifactId>
  <version>0.1.7</version>
</dependency>
```

For published artifacts, follow the [Maven release guide](../cr3-maven-central.md#resolve-a-released-version). From a source checkout, use JDK 25 and Maven 3.9+ to install the library and print the revision for the consumer POM:

```sh
mvn -B -pl canon-cr3-core -am install
mvn -q -N help:evaluate -Dexpression=revision -DforceStdout
```

## Import from a file

Save this example as `Cr3Import.java`. Pass an input CR3, an output PNG and one of the four modes shown below. For example, `Cr3Import photo.CR3 frame.png sensor` writes the native Bayer codes to a single-band 16-bit PNG. The PNG can look dark when viewed as an ordinary grayscale picture because a 14-bit sensor uses only part of that range.

```java
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public class Cr3Import {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: Cr3Import input.CR3 output.png preview|rgb|linear|sensor");
        }
        var options = switch (args[2]) {
            case "preview" -> Cr3DecodeOptions.defaults();
            case "rgb"     -> Cr3DecodeOptions.rawProcessed();
            case "linear"  -> Cr3DecodeOptions.rawLinear();
            case "sensor"  -> Cr3DecodeOptions.rawSensor();
            default -> throw new IllegalArgumentException("Unknown mode: " + args[2]);
        };
        try (var decoder = new Cr3Decoder()) {
            decoder.setInput(Path.of(args[0]));
            var image = decoder.decode(options);
            System.out.printf("%s: %d x %d%n", decoder.getMetadata().cameraModel(),
                    image.getWidth(), image.getHeight());
            if (!ImageIO.write(image, "png", Path.of(args[1]).toFile())) {
                throw new IOException("PNG writer unavailable");
            }
        }
    }
}
```

Place the source under `src/main/java` in your Maven project. You can use the complete POM from the [ImageIO guide](imageio.md#maven-setup) and change the dependency's artifact ID to `cr3-core`. From the project directory run:

```sh
mvn compile org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies
java -cp "target/classes:target/dependency/*" Cr3Import "photo.CR3" frame.png sensor
```

On Windows, use `"target/classes;target/dependency/*"` for the classpath.

| Mode | What you get |
| --- | --- |
| `preview` | The JPEG rendered by the camera, decoded to pixels and written as PNG in this example. Its resolution depends on the file. |
| `rgb` | An 8-bit RGB image developed from the RAW track. The default processing applies active-area cropping, demosaicing, camera color processing and orientation. |
| `linear` | A single-band Bayer mosaic, cropped to the active area and black-level corrected by default, scaled to 16 bits. |
| `sensor` | The reconstructed sensor codes, including optical-black margins, in the original sensor coordinates. No scaling, cropping or orientation transform. |

The native importer has been verified on 14-bit, four-plane V1 lossless RAW and three-level C-RAW recordings from the EOS R, EOS RP, EOS M50 and PowerShot G5 X Mark II. Selecting an unsupported RAW profile can raise `Cr3Exception`. Requesting the camera preview is a separate choice. For quantitative work, keep the Bayer data or the original CR3. An RGB or preview PNG does not preserve the camera's original file or its complete metadata.

`setInput(Path)` opens the input and the decoder closes it at the end of the block. If you supply an `ImageInputStream` instead, close the stream yourself. Use one decoder per concurrent import. The default `maxWorkers(0)` uses the processors available to the JVM, bounded by the independent work in each stage. A positive `maxWorkers` value caps a particular import. Tile planes can run independently when the output permits it. For stages that require tiles within one Bayer phase to stay ordered, each Bayer phase remains one work item.

To retain only part of the Bayer mosaic, the following statements replace `decoder.decode(options)` inside the open decoder block. This example takes a 64 × 64 patch in full-sensor coordinates. The decoder allocates storage for the selected sites and reconstructs the relevant tile planes up to the last row needed by the patch.

```java
var sensor = Cr3DecodeOptions.rawSensor();
var region = new java.awt.Rectangle(160, 64, 64, 64);
var patch = decoder.decodeRawRegion(sensor, region, 1, 1);
int topLeft = patch.getRaster().getSample(0, 0, 0);
```

## Copy the camera's embedded JPEG unchanged

Use this when you need the original compressed preview bytes. Decoding the preview and then writing it with a JPEG writer would encode it again. The following standalone program needs only `io.github.carstenartur:cr3-container` at the same version, which is also available through either dependency above.

```java
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.preview.Cr3PreviewExtractor;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.stream.FileImageInputStream;

public class CopyCr3Preview {
    public static void main(String[] args) throws Exception {
        try (var input = new FileImageInputStream(Path.of(args[0]).toFile())) {
            var container = Cr3Parser.parse(input);
            if (!Cr3PreviewExtractor.hasPreview(container)) {
                throw new IllegalArgumentException("This CR3 contains no JPEG preview");
            }
            Files.write(Path.of(args[1]), Cr3PreviewExtractor.extractPreviewBytes(input, container));
        }
    }
}
```

Pass `photo.CR3 preview.jpg` to `CopyCr3Preview`. To copy a smaller embedded thumbnail instead, use `hasThumbnail(container)` and `extractThumbnailBytes(input, container)`. Each JPEG is optional.

## Captured files in Photographer

Configure the camera to record RAW or C-RAW and use a capture mode supported by its backend. Open the stacking job in Photographer and choose **Export Frames (ZIP)** to download its stored originals. For API access, use the job ID and a frame ID listed by `GET /api/v1/jobs/{id}`. The following routes serve different representations of the stored capture.

| Request | Result |
| --- | --- |
| `GET /api/v1/jobs/{id}/frames/{frameId}/image` | For a stored CR3 with a preview, returns the embedded JPEG as `image/jpeg`. If extraction fails, returns the original bytes as `application/octet-stream`. |
| `GET /api/v1/jobs/{id}/export` | Downloads a ZIP of the job's frames. A stored CR3 entry contains the original camera file. Extract it and pass it to the Java example above to select Bayer or developed RGB. |

The native focus-bracketing capture path downloads media in chunks to a temporary file. The frame preview endpoint serves the camera rendering. The RAW processing settings above apply in the importing Java program. For shell use with a source checkout, see the [CLI guide](cli.md).
