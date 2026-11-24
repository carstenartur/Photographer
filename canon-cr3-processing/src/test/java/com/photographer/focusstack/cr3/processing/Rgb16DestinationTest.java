package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage.BayerPattern;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageTypeSpecifier;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Rgb16DestinationTest {
    private static final double[][] MATRIX = {{1.31, -0.13, -0.06}, {-0.12, 1.25, -0.11}, {0.02, -0.34, 1.25}};
    private static final double[] GAINS = {1.37, 0.73, 1.81};
    private static final BlackLevel BLACK = BlackLevel.perChannel(11, 23, 37, 41);

    @Test
    void developsCropsIntoPermutedBanksAcrossAllOrientationsAndEncodings() {
        int width = 39;
        int height = 27;
        var region = new Rectangle(3, 1, 35, 25);
        short[] samples = samples(width, height);
        short[] original = samples.clone();
        for (var pattern : BayerPattern.values()) {
            for (boolean srgb : new boolean[]{true, false}) {
                for (int orientation = 1; orientation <= 8; orientation++) {
                    var expected = BayerDemosaicer.develop16(samples, width, height, pattern, BLACK, 1, 3,
                            GAINS, 41123, MATRIX, region, orientation, srgb, 1);
                    var target = banded(expected.getWidth(), expected.getHeight(), srgb);
                    var actual = BayerDemosaicer.develop16Into(samples, width, height, pattern, BLACK, 1, 3,
                            GAINS, 41123, MATRIX, region, orientation, srgb, 4, target);
                    assertSame(target, actual);
                    assertEquals(expected.getColorModel(), target.getColorModel());
                    assertArrayEquals(pixels(expected), pixels(target));
                }
            }
        }
        assertArrayEquals(original, samples);
    }

    @Test
    void consumesTheLogicalGreenBankAcrossStripeBoundaries() {
        int width = 1051;
        int height = 777;
        var region = new Rectangle(width, height);
        var target = banded(width, height, true);
        var storage = (DataBufferUShort) target.getRaster().getDataBuffer();
        var bands = BayerDemosaicer.rgb16DestinationBanks(target, width, height, true);
        assertSame(storage.getData(2), bands[0]);
        assertSame(storage.getData(0), bands[1]);
        assertSame(storage.getData(1), bands[2]);
        short[] original = samples(width, height);
        System.arraycopy(original, 0, bands[1], 0, original.length);
        var expected = BayerDemosaicer.develop16(original, width, height, BayerPattern.GBRG, BLACK, 1, 3,
                GAINS, 41123, MATRIX, region, 1, true, 1);

        var result = BayerDemosaicer.develop16Into(bands[1], width, height, BayerPattern.GBRG, BLACK, 1, 3,
                GAINS, 41123, MATRIX, region, 1, true, 3, target);

        assertSame(target, result);
        assertArrayEquals(pixels(expected), pixels(result));
    }

    @Test
    void rejectsUnsafeAliasingBeforeOverwritingAnyBand() {
        int width = 17;
        int height = 17;
        var target = banded(width, height, false);
        var bands = BayerDemosaicer.rgb16DestinationBanks(target, width, height, false);
        for (int channel = 0; channel < 3; channel++) {
            Arrays.fill(bands[channel], (short) (channel + 100));
        }
        int[] before = pixels(target);
        for (int channel = 0; channel < 3; channel++) {
            short[] shared = bands[channel];
            int orientation = channel == 1 ? 6 : 1;
            assertThrows(IllegalArgumentException.class,
                    () -> BayerDemosaicer.develop16Into(shared, width, height, BayerPattern.RGGB, BLACK, 0, 0,
                            GAINS, 41123, MATRIX, new Rectangle(width, height), orientation, false, 2, target));
            assertArrayEquals(before, pixels(target));
        }
    }

    @Test
    void excludesViewsAliasedBanksAndOtherColorEncodings() {
        var dense = banded(11, 9, true);
        assertNull(BayerDemosaicer.rgb16DestinationBanks(dense, 9, 11, true));
        assertNull(BayerDemosaicer.rgb16DestinationBanks(dense, 11, 9, false));
        assertNull(BayerDemosaicer.rgb16DestinationBanks(dense.getSubimage(1, 1, 6, 5), 6, 5, true));
        assertNull(BayerDemosaicer.rgb16DestinationBanks(dense.getSubimage(0, 0, 11, 9), 11, 9, true));
        int size = dense.getWidth() * dense.getHeight();
        short[] shared = new short[size];
        var data = new DataBufferUShort(new short[][]{shared, shared, new short[size]}, size);
        var raster = Raster.createBandedRaster(data, 11, 9, 11, new int[]{0, 1, 2}, new int[3], null);
        var aliased = new BufferedImage(dense.getColorModel(), raster, false, null);
        assertNull(BayerDemosaicer.rgb16DestinationBanks(aliased, 11, 9, true));
    }

    private static BufferedImage banded(int width, int height, boolean srgb) {
        var color = ColorSpace.getInstance(srgb ? ColorSpace.CS_sRGB : ColorSpace.CS_LINEAR_RGB);
        return ImageTypeSpecifier.createBanded(color, new int[]{2, 0, 1}, new int[3], DataBuffer.TYPE_USHORT,
                false, false).createBufferedImage(width, height);
    }

    private static short[] samples(int width, int height) {
        var random = new Random(3315);
        var samples = new short[width * height];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) random.nextInt(65536);
        }
        return samples;
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRaster().getPixels(0, 0, image.getWidth(), image.getHeight(), (int[]) null);
    }
}
