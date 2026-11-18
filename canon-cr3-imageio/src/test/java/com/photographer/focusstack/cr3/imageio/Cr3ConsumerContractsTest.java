package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class Cr3ConsumerContractsTest {
    private final CanonCr3ImageReaderSpi spi = new CanonCr3ImageReaderSpi();

    private static byte[] fourSites() throws Exception {
        try (var resource = Cr3ConsumerContractsTest.class.getResourceAsStream("four-sites.cr3")) {
            return resource.readAllBytes();
        }
    }

    private static MemoryCacheImageInputStream embedded(byte[] data) throws Exception {
        byte[] prefixed = new byte[23 + data.length];
        System.arraycopy(data, 0, prefixed, 23, data.length);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(prefixed));
        input.seek(23);
        input.flushBefore(23);
        input.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        return input;
    }

    @Test
    void recognizesStandardAndExtendedBrandsAtTheCurrentPositionWithoutConsumingInput() throws Exception {
        for (boolean extended : new boolean[]{false, true}) {
            for (boolean match : new boolean[]{false, true}) {
                int size = extended ? 28 : 20;
                var box = ByteBuffer.allocate(size).putInt(extended ? 1 : size).putInt(0x66747970);
                if (extended) box.putLong(size);
                box.putInt(0x69736f6d).putInt(0).putInt(match ? 0x63727820 : 0x69736f32);
                try (var input = embedded(box.array())) {
                    assertEquals(match, Cr3Parser.isCr3(input));
                    assertEquals(23L, input.getStreamPosition());
                    assertEquals(ByteOrder.LITTLE_ENDIAN, input.getByteOrder());
                    assertEquals(match, spi.canDecodeInput(input));
                    assertEquals(23L, input.getStreamPosition());
                    assertEquals(ByteOrder.LITTLE_ENDIAN, input.getByteOrder());
                }
            }
        }
    }

    @Test
    void zeroIsNotAnExtendedBoxLength() throws Exception {
        byte[] box = ByteBuffer.allocate(24).putInt(1).putInt(0x66747970)
                .putLong(0).putInt(0x63727820).putInt(0).array();
        try (var input = embedded(box)) {
            assertFalse(spi.canDecodeInput(input));
            assertEquals(23L, input.getStreamPosition());
            assertEquals(ByteOrder.LITTLE_ENDIAN, input.getByteOrder());
        }
    }

    @Test
    void readsEmbeddedRawWithOriginalSensorCodesThroughCoreAndImageIO() throws Exception {
        int[] expected = {8192, 8193, 8191, 8194};
        try (var input = embedded(fourSites())) {
            try (var decoder = new Cr3Decoder()) {
                decoder.setInput(input);
                var raw = decoder.decode(Cr3DecodeOptions.rawSensor()).getRaster();
                assertArrayEquals(expected, raw.getPixels(0, 0, 2, 2, (int[]) null));
            }
            input.seek(23);
            var readers = ImageIO.getImageReaders(input);
            assertTrue(readers.hasNext());
            var reader = (CanonCr3ImageReader) readers.next();
            try {
                reader.setInput(input);
                assertEquals(BufferedImage.TYPE_INT_RGB, reader.read(0).getType());
                var params = reader.getDefaultReadParam();
                params.setDecodeOptions(Cr3DecodeOptions.rawSensor());
                assertArrayEquals(expected, reader.readRaster(0, params).getPixels(0, 0, 2, 2, (int[]) null));
            } finally {
                reader.dispose();
            }
            input.seek(23);
            assertEquals(0, input.read());
        }
    }

    @Test
    void refusesLossyBayerDestinationsBeforeWritingFullOrPartialImages() throws Exception {
        var reader = (CanonCr3ImageReader) spi.createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(fourSites()))) {
            reader.setInput(input);
            var param = reader.getDefaultReadParam();
            var dest = new BufferedImage(2, 2, BufferedImage.TYPE_BYTE_GRAY);
            int[] guard = {51, 52, 53, 54};
            dest.getRaster().setPixels(0, 0, 2, 2, guard);
            param.setDestination(dest);
            for (var mode : new Cr3DecodeOptions[]{Cr3DecodeOptions.rawSensor(), Cr3DecodeOptions.rawLinear()}) {
                param.setDecodeOptions(mode);
                for (Rectangle region : new Rectangle[]{null, new Rectangle(1, 0, 1, 1)}) {
                    param.setSourceRegion(region);
                    assertThrows(IIOException.class, () -> reader.read(0, param));
                    assertArrayEquals(guard, dest.getRaster().getPixels(0, 0, 2, 2, (int[]) null));
                }
            }
        } finally {
            reader.dispose();
        }
    }

    @Test
    void togglingPreviewDoesNotOverwriteTheCallersModeOrReplacementSettings() throws Exception {
        var reader = (CanonCr3ImageReader) spi.createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(fourSites()))) {
            reader.setInput(input);
            var shared = Cr3DecodeOptions.rawSensor().maxWorkers(1);
            var param = reader.getDefaultReadParam();
            param.setDecodeOptions(shared);
            param.setPreviewOnly(true);
            assertEquals(Cr3DecodeOptions.OutputMode.RAW_SENSOR, shared.outputMode());
            assertEquals(Cr3DecodeOptions.OutputMode.PREVIEW, param.getDecodeOptions().outputMode());
            assertEquals(1, param.getDecodeOptions().maxWorkers());
            assertThrows(IIOException.class, () -> reader.read(0, param));
            param.setPreviewOnly(false);
            assertSame(shared, param.getDecodeOptions());
            assertEquals(8192, reader.read(0, param).getRaster().getSample(0, 0, 0));
            param.setPreviewOnly(true);
            var replacement = Cr3DecodeOptions.rawProcessed().applySrgbConversion(false);
            param.setDecodeOptions(replacement);
            param.setPreviewOnly(false);
            assertSame(replacement, param.getDecodeOptions());
            assertEquals(BufferedImage.TYPE_INT_RGB, reader.read(0, param).getType());
        } finally {
            reader.dispose();
        }
    }

    @Test
    void lifecycleQueriesRespectInputAndSeekForwardOnly() throws Exception {
        var reader = spi.createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(fourSites()))) {
            assertThrows(IllegalStateException.class, () -> reader.getNumImages(false));
            assertThrows(IllegalStateException.class, () -> reader.getRawImageType(0));
            reader.setInput(input, true, false);
            assertThrows(IllegalStateException.class, () -> reader.getNumImages(true));
            assertEquals(1, reader.getNumImages(false));
            reader.reset();
            assertThrows(IllegalStateException.class, () -> reader.getNumImages(true));
            assertThrows(IllegalStateException.class, () -> reader.getRawImageType(0));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void advertisedMetadataDescriptorDescribesExistingTreeAndAttributes() throws Exception {
        var reader = spi.createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(fourSites()))) {
            reader.setInput(input);
            var metadata = reader.getImageMetadata(0);
            var name = metadata.getNativeMetadataFormatName();
            var root = metadata.getAsTree(name);
            for (var descriptor : Arrays.asList(spi.getImageMetadataFormat(name), metadata.getMetadataFormat(name))) {
                assertEquals("CanonCR3Metadata", descriptor.getRootName());
                assertEquals(root.getNodeName(), descriptor.getRootName());
                var children = Arrays.asList(descriptor.getChildNames(root.getNodeName()));
                assertTrue(children.containsAll(Arrays.asList("Camera","Image","WhiteBalance","GPS","Crop","Capture")));
                for (var child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                    var attrs = Arrays.asList(descriptor.getAttributeNames(child.getNodeName()));
                    for (int i = 0; i < child.getAttributes().getLength(); i++) {
                        assertTrue(attrs.contains(child.getAttributes().item(i).getNodeName()));
                    }
                }
            }
        } finally {
            reader.dispose();
        }
    }
}
