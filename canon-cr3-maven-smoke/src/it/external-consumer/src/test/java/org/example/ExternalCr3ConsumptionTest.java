package org.example;

import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReadParam;
import com.photographer.focusstack.cr3.imageio.CanonCr3ImageReader;
import com.photographer.focusstack.cr3.metadata.Cr3Metadata;
import com.photographer.focusstack.cr3.metadata.Cr3MetadataReader;
import com.photographer.focusstack.cr3.raw.codec.BitReader;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalCr3ConsumptionTest {

    @Test
    void packagedSplitModulesWorkOutsideTheReactorClasspath() throws Exception {
        Path sample = Path.of(System.getProperty("cr3.sample", ""))
                .toAbsolutePath()
                .normalize();
        assertTrue(Files.isRegularFile(sample), "Missing CR3 fixture: " + sample);

        verifyContainerArtifact(sample);
        verifyMetadataArtifact(sample);
        verifyCodecArtifact();
        verifyImageIoArtifact(sample);
        verifyTestAndResearchArtifactsDoNotLeak();
    }

    private static void verifyContainerArtifact(Path sample) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(sample.toFile())) {
            assertNotNull(input);
            Cr3Container container = Cr3Parser.parse(input);
            assertNotNull(container);
            assertFalse(container.tracks().isEmpty(), "External parser should expose CR3 tracks");
        }
    }

    private static void verifyMetadataArtifact(Path sample) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(sample.toFile())) {
            assertNotNull(input);
            Cr3Container container = Cr3Parser.parse(input);
            Cr3Metadata metadata = Cr3MetadataReader.read(input, container);
            assertNotNull(metadata);
            assertEquals("Canon", metadata.cameraMake());
            assertTrue(metadata.sensorWidth() > 0, "Expected externally readable sensor width");
            assertTrue(metadata.sensorHeight() > 0, "Expected externally readable sensor height");
        }
    }

    private static void verifyCodecArtifact() throws Exception {
        BitReader reader = new BitReader(new byte[]{(byte) 0b1010_0000});
        assertEquals(0b101, reader.readBits(3));
        assertEquals(0, reader.bytePosition());
        assertEquals(1, reader.length());
    }

    private static void verifyImageIoArtifact(Path sample) throws Exception {
        ImageIO.scanForPlugins();
        Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix("cr3");
        assertTrue(readers.hasNext(), "Packaged ImageIO SPI was not discovered");

        ImageReader reader = readers.next();
        CanonCr3ImageReader cr3Reader = assertInstanceOf(CanonCr3ImageReader.class, reader);
        assertTrue(reader.canReadRaster(), "CR3 reader should advertise raster access");

        BufferedImage preview = ImageIO.read(sample.toFile());
        assertNotNull(preview, "ImageIO.read should return the embedded preview");
        assertTrue(preview.getWidth() > 0);
        assertTrue(preview.getHeight() > 0);

        try (ImageInputStream input = ImageIO.createImageInputStream(sample.toFile())) {
            assertNotNull(input);
            reader.setInput(input);
            assertEquals(preview.getWidth(), reader.getWidth(0));
            assertEquals(preview.getHeight(), reader.getHeight(0));
            assertEquals(preview.getType(), reader.getImageTypes(0).next().getBufferedImageType());
            assertTrue(reader.readerSupportsThumbnails());

            CanonCr3ImageReadParam param = cr3Reader.getDefaultReadParam();
            param.setDecodeOptions(Cr3DecodeOptions.rawSensor());
            param.setSourceRegion(new Rectangle(2100, 1500, 4, 4));
            param.setSourceSubsampling(2, 2, 1, 1);
            param.setDestinationOffset(new Point(-2, 7));
            param.setDestination(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));
            Raster raster = reader.readRaster(0, param);
            assertEquals(DataBuffer.TYPE_USHORT, raster.getTransferType());
            assertEquals(new Rectangle(-2, 7, 2, 2), raster.getBounds());
            // Unscaled Bayer codes from the independent LibRaw sample.reference.tiff.
            assertEquals(2553, raster.getSample(-2, 7, 0));
            assertEquals(2605, raster.getSample(-1, 7, 0));
            assertEquals(2565, raster.getSample(-2, 8, 0));
            assertEquals(2537, raster.getSample(-1, 8, 0));
        } finally {
            reader.dispose();
        }
    }

    private static void verifyTestAndResearchArtifactsDoNotLeak() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.photographer.focusstack.cr3.reference.ReferenceMetrics"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.photographer.focusstack.cr3.research.SubbandHypothesisHarness"));
    }
}
