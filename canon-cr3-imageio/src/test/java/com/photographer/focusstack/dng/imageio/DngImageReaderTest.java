package com.photographer.focusstack.dng.imageio;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteOrder;
import java.util.Arrays;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.plugins.tiff.TIFFTag;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;

class DngImageReaderTest {
    @Test
    void imageIOPrefersDngToGenericTiffAndKeepsNativeValues() throws Exception {
        byte[] bytes = fixture(true);
        try (var stream = input(bytes)) {
            ImageReader reader = ImageIO.getImageReaders(stream).next();
            try {
                assertEquals("DNG", reader.getFormatName());
                reader.setInput(stream);
                assertEquals(2, reader.getWidth(0));
                assertEquals(2, reader.getHeight(0));
                assertEquals(1, reader.getNumImages(true));
                assertThrows(IndexOutOfBoundsException.class, () -> reader.getWidth(1));
                assertNotNull(reader.getImageMetadata(0));
                assertTrue(reader.canReadRaster());
                var param = reader.getDefaultReadParam();
                param.setSourceRegion(new Rectangle(1, 0, 1, 2));
                param.setDestinationOffset(new Point(-3, 5));
                var crop = reader.readRaster(0, param);
                assertEquals(new Rectangle(-3, 5, 1, 2), crop.getBounds());
                assertEquals(4097, crop.getSample(-3, 5, 0));
                assertEquals(16383, crop.getSample(-3, 6, 0));
                param.setDestinationOffset(new Point());
                assertSame(Image.UndefinedProperty, reader.read(0, param).getProperty(DngImageMetadata.PROPERTY_NAME));
                var fullImage = reader.read(0);
                assertSame(reader.getImageMetadata(0), fullImage.getProperty(DngImageMetadata.PROPERTY_NAME));
                assertArrayEquals(new int[]{0, 4097, 8193, 16383},
                        fullImage.getRaster().getPixels(0, 0, 2, 2, (int[]) null));
            } finally {
                reader.dispose();
            }
        }
    }

    @Test
    void advertisedMetadataDescriptorsDescribeTheNativeTiffTree() throws Exception {
        var reader = ImageIO.getImageReadersByFormatName("DNG").next();
        try (var stream = input(fixture(true))) {
            reader.setInput(stream);
            var metadata = reader.getImageMetadata(0);
            var name = metadata.getNativeMetadataFormatName();
            var root = metadata.getAsTree(name);
            for (var format : Arrays.asList(reader.getOriginatingProvider().getImageMetadataFormat(name),
                    metadata.getMetadataFormat(name))) {
                assertEquals(root.getNodeName(), format.getRootName());
                assertTrue(Arrays.asList(format.getChildNames(format.getRootName())).contains("TIFFIFD"));
            }
        } finally {
            reader.dispose();
        }
    }

    @Test
    void refusesEightBitDestinationsWithoutChangingTheirSamples() throws Exception {
        var reader = ImageIO.getImageReadersByFormatName("DNG").next();
        try (var stream = input(fixture(true))) {
            reader.setInput(stream);
            var param = reader.getDefaultReadParam();
            var target = new BufferedImage(2, 2, BufferedImage.TYPE_BYTE_GRAY);
            int[] guard = {71, 72, 73, 74};
            target.getRaster().setPixels(0, 0, 2, 2, guard);
            param.setDestination(target);
            assertThrows(javax.imageio.IIOException.class, () -> reader.read(0, param));
            assertArrayEquals(guard, target.getRaster().getPixels(0, 0, 2, 2, (int[]) null));
            param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_BYTE_GRAY));
            assertThrows(javax.imageio.IIOException.class, () -> reader.read(0, param));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void writesSelectedSixteenBitCodesIntoAnOffsetSubimage() throws Exception {
        var reader = ImageIO.getImageReadersByFormatName("DNG").next();
        try (var stream = input(fixture(true))) {
            reader.setInput(stream);
            var parent = new BufferedImage(5, 4, BufferedImage.TYPE_USHORT_GRAY);
            var target = parent.getSubimage(1, 1, 3, 2);
            var params = reader.getDefaultReadParam();
            params.setSourceRegion(new Rectangle(1, 0, 1, 2));
            params.setDestinationOffset(new Point(1, 0));
            params.setDestination(target);
            assertSame(target, reader.read(0, params));
            int[] expected = new int[20];
            expected[7] = 4097;
            expected[12] = 16383;
            assertArrayEquals(expected, parent.getRaster().getPixels(0, 0, 5, 4, (int[]) null));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void clipsSubsampledSensorSitesIntoOneBandOfATranslatedDestination() throws Exception {
        var sensor = new BufferedImage(8, 6, BufferedImage.TYPE_USHORT_GRAY);
        for (int y = 0; y < sensor.getHeight(); y++) {
            for (int x = 0; x < sensor.getWidth(); x++) {
                sensor.getRaster().setSample(x, y, 0, 8192 + 100 * y + x);
            }
        }
        var rgb16 = ImageTypeSpecifier.createInterleaved(ColorSpace.getInstance(ColorSpace.CS_sRGB),
                new int[]{0, 1, 2}, DataBuffer.TYPE_USHORT, false, false);
        var parent = rgb16.createBufferedImage(8, 7);
        int[] expected = new int[8 * 7 * 3];
        Arrays.setAll(expected, i -> 0xc000 + i);
        parent.getRaster().setPixels(0, 0, 8, 7, expected);
        var target = parent.getSubimage(2, 2, 4, 4);

        var reader = ImageIO.getImageReadersByFormatName("DNG").next();
        try (var stream = input(fixture(true, 1, sensor))) {
            reader.setInput(stream);
            var params = reader.getDefaultReadParam();
            params.setSourceRegion(new Rectangle(1, 0, 6, 6));
            params.setSourceSubsampling(2, 2, 1, 1);
            params.setDestinationOffset(new Point(-1, 1));
            params.setDestinationBands(new int[]{1});
            params.setDestination(target);
            assertSame(target, reader.read(0, params));

            // The clipped grid retains source columns 4 and 6 and rows 1, 3 and 5.
            int[][] retained = {{8296, 8298}, {8496, 8498}, {8696, 8698}};
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 2; x++) {
                    int parentPixel = 8 * (3 + y) + 2 + x;
                    expected[3 * parentPixel + 1] = retained[y][x];
                }
            }
            assertArrayEquals(expected, parent.getRaster().getPixels(0, 0, 8, 7, (int[]) null));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void discoveryDeclinesNonRectangularCfaLayout() throws Exception {
        try (var stream = input(fixture(true, 2))) {
            assertFalse(new DngImageReaderSpi().canDecodeInput(stream));
        }
    }

    @Test
    void ordinaryTiffAndTruncatedInputAreRejectedWithoutChangingStreamState() throws Exception {
        var reader = ImageIO.getImageReadersByFormatName("DNG").next();
        try {
            var provider = reader.getOriginatingProvider();
            for (byte[] data : new byte[][]{fixture(false), {0x49, 0x49, 42, 0, 8, 0, 0, 0}}) {
                try (var stream = input(data)) {
                    stream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
                    assertFalse(provider.canDecodeInput(stream));
                    assertEquals(0, stream.getStreamPosition());
                    assertEquals(ByteOrder.LITTLE_ENDIAN, stream.getByteOrder());
                }
            }
        } finally {
            reader.dispose();
        }
    }

    @Test
    void readsAtNonzeroOffsetAndReleasesOldMetadataWhenInputChanges() throws Exception {
        byte[] bytes = fixture(true);
        byte[] prefixed = new byte[15 + bytes.length];
        System.arraycopy(bytes, 0, prefixed, 15, bytes.length);
        var reader = ImageIO.getImageReadersByFormatName("dng").next();
        try (var first = input(prefixed); var second = input(fixture(false))) {
            first.seek(15);
            reader.setInput(first);
            assertEquals(8193, reader.read(0).getRaster().getSample(0, 1, 0));
            reader.setInput(second);
            assertThrows(javax.imageio.IIOException.class, () -> reader.getWidth(0));
            reader.reset();
            assertThrows(IllegalStateException.class, () -> reader.read(0));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void metadataAndPixelsDoNotFetchTheEmbeddedOriginalRawFile() throws Exception {
        byte[] bytes = fixture(true);
        int embedded = java.util.HexFormat.of().formatHex(bytes).indexOf("a5".repeat(64)) / 2;
        assertTrue(embedded > 0);
        var reader = ImageIO.getImageReadersByFormatName("DNG").next();
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            public void seek(long position) throws java.io.IOException {
                assertFalse(position >= embedded && position < embedded + 4096,
                        "Unexpected read from embedded original CR3");
                super.seek(position);
            }
        }) {
            reader.setInput(stream);
            assertNotNull(reader.getImageMetadata(0));
            assertEquals(16383, reader.read(0).getRaster().getSample(1, 1, 0));
        } finally {
            reader.dispose();
        }
    }

    // Independent fixture: only use the JDK TIFF writer to store the known pixel and tag values.
    private static byte[] fixture(boolean dng) throws Exception {
        return fixture(dng, 1);
    }

    private static byte[] fixture(boolean dng, int cfaLayout) throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_USHORT_GRAY);
        image.getRaster().setPixels(0, 0, 2, 2, new int[]{0, 4097, 8193, 16383});
        return fixture(dng, cfaLayout, image);
    }

    private static byte[] fixture(boolean dng, int cfaLayout, BufferedImage image) throws Exception {
        var writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        var bytes = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(stream);
            var tiff = TIFFDirectory.createFromMetadata(writer.getDefaultImageMetadata(
                    ImageTypeSpecifier.createFromRenderedImage(image), writer.getDefaultWriteParam()));
            if (dng) {
                byte[] original = new byte[4096];
                Arrays.fill(original, (byte) 0xa5);
                put(tiff, 50828, TIFFTag.TIFF_UNDEFINED, original.length, original);
                put(tiff, 262, TIFFTag.TIFF_SHORT, 1, new char[]{32803});
                put(tiff, 33421, TIFFTag.TIFF_SHORT, 2, new char[]{2, 2});
                put(tiff, 33422, TIFFTag.TIFF_BYTE, 4, new byte[]{0, 1, 1, 2});
                put(tiff, 50706, TIFFTag.TIFF_BYTE, 4, new byte[]{1, 1, 0, 0});
                put(tiff, 50707, TIFFTag.TIFF_BYTE, 4, new byte[]{1, 1, 0, 0});
                put(tiff, 50708, TIFFTag.TIFF_ASCII, 1, new String[]{"Test camera"});
                put(tiff, 50711, TIFFTag.TIFF_SHORT, 1, new char[]{(char) cfaLayout});
                put(tiff, 50717, TIFFTag.TIFF_LONG, 1, new long[]{16383});
                put(tiff, 50721, TIFFTag.TIFF_SRATIONAL, 9,
                        new int[][]{{1,1},{0,1},{0,1},{0,1},{1,1},{0,1},{0,1},{0,1},{1,1}});
                put(tiff, 50728, TIFFTag.TIFF_RATIONAL, 3, new long[][]{{1,1},{1,1},{1,1}});
                put(tiff, 50778, TIFFTag.TIFF_SHORT, 1, new char[]{21});
            }
            writer.write(null, new IIOImage(image, null, tiff.getAsMetadata()), null);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static void put(TIFFDirectory tiff, int id, int type, int count, Object values) {
        tiff.addTIFFField(new TIFFField(new TIFFTag("Tag" + id, id, 1 << type), type, count, values));
    }

    private static ImageInputStream input(byte[] bytes) throws Exception {
        return ImageIO.createImageInputStream(new ByteArrayInputStream(bytes));
    }
}
