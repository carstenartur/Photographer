# Java ImageIO Integration

`canon-cr3-imageio` integrates Canon CR3 files with the standard `javax.imageio` API.

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
  <artifactId>canon-cr3-imageio</artifactId>
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

`ImageIO.read` returns the embedded JPEG preview by default. It is not a hidden full-RAW decode.

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

### Linear RAW with explicit options — experimental

Use `read(0, param)` when crop or other `Cr3DecodeOptions` must be honored:

```java
CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
param.setDecodeOptions(Cr3DecodeOptions.rawLinear().cropToActiveArea(true));
BufferedImage linear = reader.read(0, param);
Raster croppedRaster = linear.getRaster();
```

### `readRaster()` current limitation

`CanonCr3ImageReader.readRaster(0, param)` currently ignores the supplied `ImageReadParam` and always decodes with default `Cr3DecodeOptions.rawLinear()` settings. In particular, `cropToActiveArea(true)` passed through `CanonCr3ImageReadParam` does not affect `readRaster()` today:

```java
Raster fullLinearRaster = reader.readRaster(0, null);
```

Treat this as an experimental API limitation, not as configurable raster behavior. A future implementation change must add tests before the parameter can be documented as honored.

RAW-specific modes support only the CR3/CRX variants covered by the experimental decoder and fixed regression corpus. Unsupported variants must be reported as limitations; they must not silently fall back to the embedded preview when the caller explicitly requested RAW.

## Stability table

| Surface | Status |
|---|---|
| `CanonCr3ImageReaderSpi` | `PUBLIC_STABLE` |
| `CanonCr3ImageReader` preview/thumbnail/metadata behavior | `PUBLIC_STABLE` |
| `Cr3ImageMetadata` native metadata contract | `PUBLIC_STABLE` |
| `CanonCr3ImageReadParam` preview selection | Stable subset |
| RAW-specific `CanonCr3ImageReadParam` options through `read()` | `PUBLIC_EXPERIMENTAL` |
| `readRaster()` full-RAW result | Experimental; supplied read parameters currently ignored |

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
