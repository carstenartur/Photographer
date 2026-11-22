package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions.BitDepth;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class Cr3RgbSubsamplingTest {
    @ParameterizedTest
    @CsvSource({"EIGHT, true", "SIXTEEN, true", "SIXTEEN, false"})
    void samplesMatchTheFullDevelopedImageAtEveryOrientation(BitDepth depth, boolean srgb) throws Exception {
        var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
        try (var input = fixture()) {
            reader.setInput(input);
            var metadata = ((Cr3ImageMetadata) reader.getImageMetadata(0)).getCr3Metadata();
            metadata.setWhiteBalanceMultipliers(new double[]{1.25, 1.1, 0.7501});
            for (int orientation = 1; orientation <= 8; orientation++) {
                metadata.setOrientation(orientation);
                var options = Cr3DecodeOptions.rawProcessed().bitDepth(depth).applySrgbConversion(srgb);
                var param = reader.getDefaultReadParam();
                param.setDecodeOptions(options);
                BufferedImage full = reader.read(0, param);
                Rectangle bounds = full.getRaster().getBounds();

                // Both sampled endpoints may coincide with the full bounds on this odd-sized crop.
                assertSampledRead(reader, param, full, bounds, 2, 2, 0, 0);
                var inset = new Rectangle(1, 2, bounds.width - 1, bounds.height - 3);
                assertSampledRead(reader, param, full, inset, 2, 3, 1, 2);
                assertSampledRead(reader, param, full, inset, 1, 3, 0, 1);
                assertSampledRead(reader, param, full, inset, 3, 1, 2, 0);
            }
        } finally {
            reader.dispose();
        }
    }

    @ParameterizedTest
    @EnumSource(BitDepth.class)
    void retainsClippedDestinationOffsetsAndBandMappings(BitDepth depth) throws Exception {
        var reader = new CanonCr3ImageReader(new CanonCr3ImageReaderSpi());
        try (var input = fixture()) {
            reader.setInput(input);
            var metadata = ((Cr3ImageMetadata) reader.getImageMetadata(0)).getCr3Metadata();
            metadata.setOrientation(6);
            var param = reader.getDefaultReadParam();
            param.setDecodeOptions(Cr3DecodeOptions.rawProcessed().bitDepth(depth));
            BufferedImage full = reader.read(0, param);
            var destinationType = depth == BitDepth.SIXTEEN
                    ? ImageTypeSpecifier.createInterleaved(ColorSpace.getInstance(ColorSpace.CS_sRGB),
                            new int[]{0, 1, 2}, DataBuffer.TYPE_USHORT, false, false)
                    : ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB);
            var destination = destinationType.createBufferedImage(2, 3);
            for (int y = 0; y < destination.getHeight(); y++) {
                for (int x = 0; x < destination.getWidth(); x++) {
                    destination.getRaster().setPixel(x, y, new int[]{11, 22, 33});
                }
            }
            param.setSourceRegion(new Rectangle(1, 1, 8, 9));
            param.setSourceSubsampling(2, 3, 1, 1);
            param.setSourceBands(new int[]{2, 0});
            param.setDestinationBands(new int[]{0, 2});
            param.setDestinationOffset(new Point(-1, 1));
            param.setDestination(destination);

            assertSame(destination, reader.read(0, param));
            for (int x = 0; x < destination.getWidth(); x++) {
                assertArrayEquals(new int[]{11, 22, 33}, destination.getRaster().getPixel(x, 0, (int[]) null));
                for (int y = 1; y < destination.getHeight(); y++) {
                    int[] expected = full.getRaster().getPixel(4 + 2 * x, 2 + 3 * (y - 1), (int[]) null);
                    assertArrayEquals(new int[]{expected[2], 22, expected[0]},
                            destination.getRaster().getPixel(x, y, (int[]) null));
                }
            }
        } finally {
            reader.dispose();
        }
    }

    private static void assertSampledRead(CanonCr3ImageReader reader, CanonCr3ImageReadParam param,
                                          BufferedImage full, Rectangle source, int xStep, int yStep,
                                          int xOffset, int yOffset) throws Exception {
        param.setSourceRegion(source);
        param.setSourceSubsampling(xStep, yStep, xOffset, yOffset);
        BufferedImage sampled = reader.read(0, param);
        assertEquals(Math.ceilDiv(source.width - xOffset, xStep), sampled.getWidth());
        assertEquals(Math.ceilDiv(source.height - yOffset, yStep), sampled.getHeight());
        assertEquals(full.getColorModel(), sampled.getColorModel());
        for (int y = 0; y < sampled.getHeight(); y++) {
            for (int x = 0; x < sampled.getWidth(); x++) {
                assertArrayEquals(full.getRaster().getPixel(source.x + xOffset + x * xStep,
                                source.y + yOffset + y * yStep, (int[]) null),
                        sampled.getRaster().getPixel(x, y, (int[]) null));
            }
        }
    }

    private static MemoryCacheImageInputStream fixture() throws Exception {
        var sensor = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, 18, 12, 1, null);
        for (int y = 0; y < sensor.getHeight(); y++) {
            for (int x = 0; x < sensor.getWidth(); x++) {
                int value = 463 + 13 * y + 5 * x;
                if (x >= 4) {
                    value += (1673 * x + 1171 * y + 19 * x * y) % 13000;
                }
                sensor.setSample(x, y, 0, value);
            }
        }
        byte[] bytes = Cr3InclusiveActiveAreaTest.sensorFile(sensor, 4, 1, 14, 9, 48);
        return new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes));
    }
}
