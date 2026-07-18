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
        assertInstanceOf(CanonCr3ImageReader.class, reader);
        assertTrue(reader.canReadRaster(), "CR3 reader should advertise raster access");

        BufferedImage preview = ImageIO.read(sample.toFile());
        assertNotNull(preview, "ImageIO.read should return the embedded preview");
        assertTrue(preview.getWidth() > 0);
        assertTrue(preview.getHeight() > 0);

        try (ImageInputStream input = ImageIO.createImageInputStream(sample.toFile())) {
            assertNotNull(input);
            reader.setInput(input);
            CanonCr3ImageReadParam param = new CanonCr3ImageReadParam();
            param.setDecodeOptions(Cr3DecodeOptions.rawLinear().cropToActiveArea(true));
            Raster raster = reader.readRaster(0, param);
            assertNotNull(raster);
            assertEquals(DataBuffer.TYPE_USHORT, raster.getTransferType());
            assertTrue(raster.getWidth() > preview.getWidth());
            assertTrue(raster.getHeight() > preview.getHeight());
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
