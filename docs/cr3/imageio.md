# Java ImageIO Integration

`cr3-imageio` integrates Canon CR3 files with the standard `javax.imageio` API.

## Supported behavior

The stable external surface covers:

- SPI discovery through `META-INF/services/javax.imageio.spi.ImageReaderSpi`;
- detection by CR3 suffix and supported MIME type;
- `ImageIO.read(File)` using the embedded JPEG preview by default;
- explicit `ImageReader` discovery;
- preview and thumbnail access;
- CR3 metadata through `IIOMetadata` / `Cr3ImageMetadata`;
- safe rejection of unsupported or malformed inputs.

The default path intentionally does not require a complete CRX sensor-RAW decoder.

## Dependency

The release contract currently uses version `0.1.5` for the first public bundle:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>cr3-imageio</artifactId>
  <version>0.1.5</version>
</dependency>
```

Remote availability must still be verified before relying on that coordinate. Repository-side release preparation does not prove publication. See [Maven Central release contract](../cr3-maven-central.md). For a repository checkout, build/install the relevant reactor modules locally.

## Basic preview

```java
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

BufferedImage preview = ImageIO.read(new File("photo.cr3"));
if (preview == null) {
    throw new IllegalArgumentException("No CR3 ImageIO reader accepted the input");
}
```

`ImageReader.getWidth(0)`, `getHeight(0)`, and the first `getImageTypes(0)` entry describe this default preview. The native metadata tree provides the sensor and active-area dimensions.

## Explicit reader lifecycle

```java
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;

Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix("cr3");
if (!readers.hasNext()) {
    throw new IllegalStateException("CR3 ImageIO provider is not installed");
}

ImageReader reader = readers.next();
try (ImageInputStream input = ImageIO.createImageInputStream(new File("photo.cr3"))) {
    reader.setInput(input);
    BufferedImage preview = reader.read(0);
} finally {
    reader.dispose();
}
```

## Thumbnail

```java
ImageReader reader = ImageIO.getImageReadersBySuffix("cr3").next();
try (ImageInputStream input = ImageIO.createImageInputStream(new File("photo.cr3"))) {
    reader.setInput(input);
    int count = reader.getNumThumbnails(0);
    BufferedImage thumbnail = count > 0 ? reader.readThumbnail(0, 0) : null;
} finally {
    reader.dispose();
}
```

Thumbnail availability depends on the source CR3.

## Metadata

```java
import com.photographer.focusstack.cr3.imageio.Cr3ImageMetadata;
import javax.imageio.metadata.IIOMetadata;
import org.w3c.dom.Node;

IIOMetadata metadata = reader.getImageMetadata(0);
Node tree = metadata.getAsTree(Cr3ImageMetadata.NATIVE_FORMAT_NAME);
```

`Cr3ImageMetadata` is the stable ImageIO metadata surface. Low-level TIFF/MakerNote parser helpers are not the external contract.

## Read parameters

`CanonCr3ImageReadParam` combines a stable preview-selection subset with experimental RAW controls.

### Preview-only

```java
CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
param.setPreviewOnly(true);
BufferedImage preview = reader.read(0, param);
```

### Processed RAW — experimental

```java
CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
param.setDecodeOptions(Cr3DecodeOptions.rawProcessed());
BufferedImage rawProcessed = reader.read(0, param);
```

RAW imports automatically size their shared worker pool to the processors available to the JVM. Each stage schedules only as many workers as it can use. Core and ImageIO callers can cap an individual import with `Cr3DecodeOptions.rawProcessed().maxWorkers(2)`. A limit of `1` runs the RAW stages sequentially, and `0` restores automatic sizing. The same option applies to `rawSensor()`, `rawLinear()` and `readRaster`. Concurrent imports use separate readers and share the decode pool. Each image still needs its own output raster.

### Scaled linear Bayer and native sensor samples

| Operation | Result |
|---|---|
| `reader.readRaster(0, null)` | One unsigned 16-bit band. Uses the linear defaults, including the active-area crop, black-level adjustment and scaling to the full 16-bit range. |
| `Cr3DecodeOptions.rawLinear()` with `read` or `readRaster` | Configurable active-area crop and black-level adjustment. Values are scaled to 16 bits. |
| `Cr3DecodeOptions.rawSensor()` with `read` or `readRaster` | Reconstructed Bayer codes in original sensor coordinates and integer range, including optical-black margins. Samples occupy one unsigned 16-bit band, with no processing or scaling. |

Use `rawSensor()` for sensor measurements and stacking. This example reads a 64 × 64 patch in full-sensor coordinates. Imports are `com.photographer.focusstack.cr3.imageio.CanonCr3ImageReadParam` and `com.photographer.focusstack.cr3.decode.Cr3DecodeOptions`.

```java
var param = (CanonCr3ImageReadParam) reader.getDefaultReadParam();
param.setDecodeOptions(Cr3DecodeOptions.rawSensor());
param.setSourceRegion(new java.awt.Rectangle(160, 64, 64, 64));
java.awt.image.Raster patch = reader.readRaster(0, param);
int firstSensorCode = patch.getSample(0, 0, 0);
```

For scaled values across the full frame, use `Cr3DecodeOptions.rawLinear().cropToActiveArea(false).applyBlackLevel(false)`. RAW availability depends on the CRX profile. An explicit RAW read propagates a decoding error for an unsupported profile.

### Standard ImageIO regions and destinations

`read` applies the source region and subsampling to the output selected by the decode options. A processed RGB region uses the oriented RGB image coordinates. A linear Bayer region starts at the active area when cropping is enabled. For example, `param.setSourceSubsampling(2, 2, 0, 0)` keeps every second row and column. On a Bayer mosaic this selects a single CFA phase.

For `read`, a destination image receives the selected source bands at the requested physical offset. `readRaster` always returns a new single-band result and uses the destination offset as its logical origin. It ignores a supplied destination image and destination image type, as specified by Java ImageIO. For scaled linear and native sensor reads, the decoder reconstructs the compressed frame and retains the selected samples in a region-sized output. Processed RGB and preview regions are selected from the fully decoded image.

## Stability table

| Surface | Status |
|---|---|
| `CanonCr3ImageReaderSpi` | `PUBLIC_STABLE` |
| `CanonCr3ImageReader` preview/thumbnail/metadata behavior | `PUBLIC_STABLE` |
| `Cr3ImageMetadata` native metadata contract | `PUBLIC_STABLE` |
| `CanonCr3ImageReadParam` preview selection | Stable subset |
| RAW-specific `CanonCr3ImageReadParam` options through `read()` | `PUBLIC_EXPERIMENTAL` |
| `readRaster()` scaled linear and native sensor results | `PUBLIC_EXPERIMENTAL` |

See [API boundaries](../cr3-api-boundaries.md) for symbol-level ownership.

## Verification

```bash
mvn -B clean verify -pl canon-cr3-imageio -am -Djava.awt.headless=true
```

This includes SPI packaging/discovery and reference-backed integration tests where their fixed references are available.

## Related documentation

- [CR3 overview](index.md)
- [Decoder and processing](decoder.md)
- [Module README](../../canon-cr3-imageio/README.md)
- [Reference pipeline](../reference-pipeline.md)
- [Maven Central release contract](../cr3-maven-central.md)
