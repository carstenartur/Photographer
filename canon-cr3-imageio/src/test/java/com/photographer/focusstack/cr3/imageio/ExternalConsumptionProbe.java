package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
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

            Raster raster = reader.readRaster(0, linearParam);
            require(raster != null, "readRaster() returned null");
            require(raster.getWidth() == linearRaw.getWidth(), "Raster width should match linear RAW image width");
            require(raster.getHeight() == linearRaw.getHeight(), "Raster height should match linear RAW image height");
            require(raster.getTransferType() == DataBuffer.TYPE_USHORT,
                    "Linear RAW raster should expose 16-bit data");

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
