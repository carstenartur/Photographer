package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageTypeSpecifier;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExifOrientationCompactTest {
    // The source rectangle is [1, 2, 3] above [4, 5, 6]. Each row below is literal output order.
    private static final int[][] ORDERS = {
            {1, 2, 3, 4, 5, 6}, {3, 2, 1, 6, 5, 4},
            {6, 5, 4, 3, 2, 1}, {4, 5, 6, 1, 2, 3},
            {1, 4, 2, 5, 3, 6}, {4, 1, 5, 2, 6, 3},
            {6, 3, 5, 2, 4, 1}, {3, 6, 2, 5, 1, 4}
    };

    @Test
    void orientsPackedRgbInItsOriginalPixelArray() {
        for (int orientation = 1; orientation <= 8; orientation++) {
            var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
            int[] original = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            System.arraycopy(ORDERS[0], 0, original, 0, 6);

            var rotated = ExifOrientationTransformer.applyToOwnedImageCompact(image, orientation);

            assertSame(original, ((DataBufferInt) rotated.getRaster().getDataBuffer()).getData());
            assertEquals(orientation < 5 ? 3 : 2, rotated.getWidth());
            assertEquals(orientation < 5 ? 2 : 3, rotated.getHeight());
            assertArrayEquals(ORDERS[orientation - 1], original);
        }
    }

    @Test
    void orientsBandedRgb16InItsOriginalChannelArraysWithoutReducingPrecision() {
        for (int orientation = 1; orientation <= 8; orientation++) {
            var image = rgb16(3, 2, ColorSpace.CS_LINEAR_RGB, new int[]{0, 1, 2});
            var original = (DataBufferUShort) image.getRaster().getDataBuffer();
            for (int i = 0; i < 6; i++) {
                image.getRaster().setPixel(i % 3, i / 3, new int[]{i + 1, 32769 + i, 65529 + i});
            }

            var oriented = ExifOrientationTransformer.applyToOwnedImageCompact(image, orientation);

            assertSameBanks(image, oriented);
            assertSame(image.getColorModel(), oriented.getColorModel());
            assertEquals(orientation < 5 ? 3 : 2, oriented.getWidth());
            assertEquals(orientation < 5 ? 2 : 3, oriented.getHeight());
            int[] order = ORDERS[orientation - 1];
            for (int i = 0; i < order.length; i++) {
                int n = order[i];
                assertArrayEquals(new int[]{n, 32768 + n, 65528 + n},
                        oriented.getRaster().getPixel(i % oriented.getWidth(), i / oriented.getWidth(), (int[]) null));
            }
            assertSame(original, oriented.getRaster().getDataBuffer());
        }
    }

    @Test
    void compactsLargeRgb16RowsWithAndWithoutPartialGroups() {
        // Exercise progressively wider groups, including an exact fit, a one-column
        // tail and a tail one column short of a full group at the largest width.
        for (int width : new int[]{351, 463, 799, 1536, 1537, 1599}) {
            for (int orientation = 5; orientation <= 8; orientation++) {
                var image = rgb16(width, 67, ColorSpace.CS_sRGB, new int[]{2, 0, 1});
                fillSamples(image);
                var expected = ExifOrientationTransformer.apply(image, orientation);
                var actual = ExifOrientationTransformer.applyToOwnedImageCompact(image, orientation);
                assertSameSamples(expected, actual);
                assertSameBanks(image, actual);
            }
        }
    }

    @Test
    void matchesCopyingReferenceForThinOddAndPrimeDimensions() {
        int[][] dimensions = {{1, 1}, {1, 7}, {7, 1}, {2, 3}, {3, 2}, {4, 2}, {6, 8},
                {9, 7}, {11, 13}, {71, 9}, {257, 67}};
        for (int[] size : dimensions) {
            for (int type = 0; type < 3; type++) {
                for (int orientation = 2; orientation <= 8; orientation++) {
                    var image = switch (type) {
                        case 0 -> new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
                        case 1 -> rgb16(size[0], size[1], ColorSpace.CS_LINEAR_RGB, new int[]{0, 1, 2});
                        default -> rgb16(size[0], size[1], ColorSpace.CS_sRGB, new int[]{2, 0, 1});
                    };
                    fillSamples(image);
                    var expected = ExifOrientationTransformer.apply(image, orientation);

                    var actual = ExifOrientationTransformer.applyToOwnedImageCompact(image, orientation);

                    assertSameSamples(expected, actual);
                    assertSameBanks(image, actual);
                    if (orientation < 5) {
                        assertSame(image, actual);
                    }
                }
            }
        }
    }

    @Test
    void identityAndInvalidOrientationsLeaveOwnedImagesUnchanged() {
        for (var image : new BufferedImage[]{new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB),
                rgb16(3, 2, ColorSpace.CS_sRGB, new int[]{0, 1, 2})}) {
            fillSamples(image);
            int[] original = samples(image);
            for (int orientation : new int[]{Integer.MIN_VALUE, -1, 0, 1, 9, Integer.MAX_VALUE}) {
                assertSame(image, ExifOrientationTransformer.applyToOwnedImageCompact(image, orientation));
                assertArrayEquals(original, samples(image));
            }
        }
    }

    @Test
    void unsupportedViewsAndInterleavedLayoutsRetainExistingBehavior() {
        for (int orientation = 2; orientation <= 8; orientation++) {
            for (var parent : new BufferedImage[]{new BufferedImage(5, 4, BufferedImage.TYPE_INT_RGB),
                    rgb16(5, 4, ColorSpace.CS_LINEAR_RGB, new int[]{0, 1, 2}),
                    new BufferedImage(5, 4, BufferedImage.TYPE_3BYTE_BGR)}) {
                fillSamples(parent);
                int[] before = samples(parent);
                var image = parent.getSubimage(1, 1, 3, 2);
                var expected = ExifOrientationTransformer.apply(image, orientation);

                var actual = ExifOrientationTransformer.applyToOwnedImageCompact(image, orientation);

                assertSameSamples(expected, actual);
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 5; x++) {
                        if (x == 0 || x == 4 || y == 0 || y == 3) {
                            for (int band = 0; band < 3; band++) {
                                assertEquals(before[(y * 5 + x) * 3 + band], parent.getRaster().getSample(x, y, band));
                            }
                        }
                    }
                }
                if (orientation >= 5 || parent.getType() != BufferedImage.TYPE_INT_RGB) {
                    assertArrayEquals(before, samples(parent));
                    assertNotSame(image.getRaster().getDataBuffer(), actual.getRaster().getDataBuffer());
                }
            }
        }
    }

    @Test
    void aliasedUnsignedShortBanksFallBackWithoutChangingSharedSamples() {
        var type = rgb16(3, 2, ColorSpace.CS_LINEAR_RGB, new int[]{0, 1, 2});
        short[] shared = {1, 2, 3, 4, 5, 6};
        short[] blue = {(short) 65529, (short) 65530, (short) 65531, (short) 65532, (short) 65533, (short) 65534};
        var buffer = new DataBufferUShort(new short[][]{shared, shared, blue}, 6);
        var raster = Raster.createBandedRaster(buffer, 3, 2, 3, new int[]{0, 1, 2}, new int[3], null);
        var image = new BufferedImage(type.getColorModel(), raster, false, null);
        var expected = ExifOrientationTransformer.apply(image, 6);
        int[] original = samples(image);

        var actual = ExifOrientationTransformer.applyToOwnedImageCompact(image, 6);

        assertSameSamples(expected, actual);
        assertArrayEquals(original, samples(image));
        assertNotSame(buffer, actual.getRaster().getDataBuffer());
    }

    private static BufferedImage rgb16(int width, int height, int colorSpace, int[] banks) {
        return ImageTypeSpecifier.createBanded(ColorSpace.getInstance(colorSpace), banks, new int[3],
                DataBuffer.TYPE_USHORT, false, false).createBufferedImage(width, height);
    }

    private static void fillSamples(BufferedImage image) {
        int mask = image.getRaster().getDataBuffer().getDataType() == DataBuffer.TYPE_USHORT ? 65535 : 255;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                for (int band = 0; band < image.getRaster().getNumBands(); band++) {
                    int value = ((y * image.getWidth() + x) * 257 + band * 16411 + 3) & mask;
                    image.getRaster().setSample(x, y, band, value);
                }
            }
        }
    }

    private static int[] samples(BufferedImage image) {
        return image.getRaster().getPixels(0, 0, image.getWidth(), image.getHeight(), (int[]) null);
    }

    private static void assertSameSamples(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        assertEquals(expected.getColorModel(), actual.getColorModel());
        assertArrayEquals(samples(expected), samples(actual));
    }

    private static void assertSameBanks(BufferedImage original, BufferedImage actual) {
        if (original.getRaster().getDataBuffer() instanceof DataBufferInt integers) {
            assertSame(integers.getData(), ((DataBufferInt) actual.getRaster().getDataBuffer()).getData());
        } else {
            var expected = (DataBufferUShort) original.getRaster().getDataBuffer();
            var result = (DataBufferUShort) actual.getRaster().getDataBuffer();
            for (int bank = 0; bank < 3; bank++) {
                assertSame(expected.getData(bank), result.getData(bank));
            }
        }
    }
}
