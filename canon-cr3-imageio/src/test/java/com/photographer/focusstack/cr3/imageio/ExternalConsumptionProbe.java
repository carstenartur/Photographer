package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.jar.JarFile;

final class ExternalConsumptionProbe {

    private ExternalConsumptionProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected <jar-file> <sample-cr3> <thumbnail-reference>");
        }

        Path jarFile = Path.of(args[0]);
        Path sampleFile = Path.of(args[1]);
        Path thumbnailReference = Path.of(args[2]);

        verifyServiceFile(jarFile);
        verifyAutomaticReaderDiscovery();
        BufferedImage defaultPreview = verifyDefaultPreviewRead(sampleFile);
        verifyExplicitReaderUsage(sampleFile, thumbnailReference, defaultPreview);
    }

    private static void verifyServiceFile(Path jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            String servicePath = "META-INF/services/javax.imageio.spi.ImageReaderSpi";
            var entry = jar.getJarEntry(servicePath);
            require(entry != null, "Missing service file " + servicePath + " in " + jarFile);
            try (InputStream input = jar.getInputStream(entry)) {
                String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                require(content.contains(CanonCr3ImageReaderSpi.class.getName()),
                        "Service file does not reference " + CanonCr3ImageReaderSpi.class.getName());
            }
        }
    }

    private static void verifyAutomaticReaderDiscovery() {
        Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix("cr3");
        require(readers.hasNext(), "ImageIO did not discover a CR3 reader by suffix");
        ImageReader reader = readers.next();
        require(reader instanceof CanonCr3ImageReader,
                "Unexpected ImageReader implementation: " + reader.getClass().getName());
        ImageReaderSpi provider = reader.getOriginatingProvider();
        require(provider instanceof CanonCr3ImageReaderSpi,
                "Unexpected ImageReaderSpi implementation: " + provider.getClass().getName());
        reader.dispose();
    }

    private static BufferedImage verifyDefaultPreviewRead(Path sampleFile) throws IOException {
        BufferedImage preview = ImageIO.read(sampleFile.toFile());
        require(preview != null, "ImageIO.read(File) returned null for " + sampleFile);
        require(preview.getWidth() > 0, "ImageIO.read(File) width should be positive");
        require(preview.getHeight() > 0, "ImageIO.read(File) height should be positive");
        return preview;
    }

    private static void verifyExplicitReaderUsage(
            Path sampleFile, Path thumbnailReference, BufferedImage defaultPreview) throws IOException {
        BufferedImage thumbnailImage = ImageIO.read(thumbnailReference.toFile());
        require(thumbnailImage != null, "Thumbnail reference could not be read from " + thumbnailReference);
        Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix("cr3");
        require(readers.hasNext(), "ImageIO did not discover a CR3 reader for explicit usage");
        ImageReader reader = readers.next();

        try (ImageInputStream input = ImageIO.createImageInputStream(sampleFile.toFile())) {
            require(input != null, "Could not create ImageInputStream for " + sampleFile);
            reader.setInput(input);

            CanonCr3ImageReadParam previewParam = new CanonCr3ImageReadParam();
            previewParam.setPreviewOnly(true);
            BufferedImage preview = reader.read(0, previewParam);
            assertSamePreview(preview, defaultPreview, "preview mode");

            CanonCr3ImageReadParam processedParam = new CanonCr3ImageReadParam();
            processedParam.setDecodeOptions(Cr3DecodeOptions.rawProcessed());
            BufferedImage processedRaw = reader.read(0, processedParam);
            require(processedRaw != null, "Processed RAW read returned null");
            require(processedRaw.getWidth() >= preview.getWidth(), "Processed RAW width should be at least preview width");
            require(processedRaw.getHeight() >= preview.getHeight(), "Processed RAW height should be at least preview height");
            require(processedRaw.getWidth() > defaultPreview.getWidth(),
                    "Processed RAW width should be larger than default preview width for the sample fixture");

            CanonCr3ImageReadParam linearParam = new CanonCr3ImageReadParam();
            linearParam.setDecodeOptions(Cr3DecodeOptions.rawLinear().cropToActiveArea(true));
            BufferedImage linearRaw = reader.read(0, linearParam);
            require(linearRaw != null, "Linear RAW image read returned null");
            require(linearRaw.getRaster().getTransferType() == DataBuffer.TYPE_USHORT,
                    "Linear RAW image should expose 16-bit raster data");
            require(linearRaw.getWidth() > defaultPreview.getWidth(),
                    "Linear RAW width should be larger than default preview width for the sample fixture");

            // Clip a negative offset and the right edge of an existing destination.
            // Compare odd Bayer coordinates after the active-area crop and subsampling.
            linearParam.setSourceRegion(new Rectangle(3, 5, 13, 7));
            linearParam.setSourceSubsampling(3, 2, 1, 1);
            linearParam.setDestinationOffset(new Point(-1, 1));
            BufferedImage destination = new BufferedImage(2, 3, BufferedImage.TYPE_USHORT_GRAY);
            linearParam.setDestination(destination);
            require(reader.read(0, linearParam) == destination, "RAW destination identity mismatch");
            for (int y = 1; y < 3; y++) {
                for (int x = 0; x < 2; x++) {
                    int expected = linearRaw.getRaster().getSample(7 + 3 * x, 6 + 2 * (y - 1), 0);
                    require(destination.getRaster().getSample(x, y, 0) == expected, "Clipped RAW sample mismatch");
                }
            }
            require(destination.getRaster().getSample(1, 0, 0) == 0, "RAW write exceeded destination region");

            // The active-area crop and the scaled 16-bit result are the raster defaults.
            // Disable both crop and black-level adjustment and read an unmasked optical-black site.
            linearParam.getDecodeOptions().cropToActiveArea(false).applyBlackLevel(false);
            linearParam.setSourceSubsampling(1, 1, 0, 0);
            linearParam.setSourceRegion(new Rectangle(0, 0, 1, 1));
            linearParam.setDestinationOffset(new Point());
            Raster raster = reader.readRaster(0, linearParam);
            require(raster.getWidth() == 1 && raster.getHeight() == 1, "Raster region was ignored");
            require(raster.getTransferType() == DataBuffer.TYPE_USHORT, "Expected a 16-bit linear raster");
            // LibRaw sample.reference.tiff records native value 2060 in this 14-bit file.
            require(raster.getSample(0, 0, 0) == 2060 * 65535 / 16383, "Uncropped linear value mismatch");

            CanonCr3ImageReadParam sensorParam = new CanonCr3ImageReadParam();
            sensorParam.setDecodeOptions(Cr3DecodeOptions.rawSensor());
            sensorParam.setSourceRegion(new Rectangle(0, 0, 1, 1));
            BufferedImage sensor = reader.read(0, sensorParam);
            require(sensor.getType() == BufferedImage.TYPE_USHORT_GRAY, "Expected 16-bit sensor image");
            require(sensor.getRaster().getSample(0, 0, 0) == 2060, "Native Bayer value mismatch");

            require(reader.getNumThumbnails(0) > 0, "Expected at least one thumbnail");
            BufferedImage thumbnail = reader.readThumbnail(0, 0);
            assertSamePreview(thumbnail, thumbnailImage, "thumbnail read");

            IIOMetadata metadata = reader.getImageMetadata(0);
            require(metadata != null, "Expected ImageIO metadata");
            Node root = metadata.getAsTree(Cr3ImageMetadata.NATIVE_FORMAT_NAME);
            require(root != null, "Metadata tree should not be null");
            require("CanonCR3Metadata".equals(root.getNodeName()),
                    "Unexpected metadata root node: " + root.getNodeName());
        } finally {
            reader.dispose();
        }
    }

    private static void assertSamePreview(BufferedImage actual, BufferedImage expected, String description) {
        require(actual != null, description + " returned null");
        require(actual.getWidth() == expected.getWidth(),
                description + " width mismatch: " + actual.getWidth() + " != " + expected.getWidth());
        require(actual.getHeight() == expected.getHeight(),
                description + " height mismatch: " + actual.getHeight() + " != " + expected.getHeight());

        int[][] samplePoints = {
                {0, 0},
                {actual.getWidth() / 2, actual.getHeight() / 2},
                {actual.getWidth() - 1, actual.getHeight() - 1},
                {actual.getWidth() / 3, actual.getHeight() / 3},
                {actual.getWidth() * 2 / 3, actual.getHeight() * 2 / 3}
        };
        for (int[] point : samplePoints) {
            int x = point[0];
            int y = point[1];
            require(actual.getRGB(x, y) == expected.getRGB(x, y),
                    description + " pixel mismatch at (" + x + "," + y + ")");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
