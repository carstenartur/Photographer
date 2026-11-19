package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.Cr3RawImage.BayerPattern;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Rgb16UnsignedBayerTest {
    private static final double[][] MATRIX = {
            {1.35, -0.22, -0.13}, {-0.19, 1.25, -0.06}, {-0.08, -0.31, 1.39}
    };

    @Test
    void widensNativeShortSamplesBeforeNormalization() {
        short[] samples = {(short) 32768, (short) 40000, (short) 50000, (short) 65535};
        short[] original = samples.clone();
        var image = compact(samples, 2, 2, BayerPattern.RGGB, null, 0, 0, null,
                65535, null, new Rectangle(2, 2), 1, false, 1);

        assertArrayEquals(new int[]{
                32768, 45000, 65535, 32768, 40000, 65535,
                32768, 50000, 65535, 32768, 45000, 65535
        }, image.getRaster().getPixels(0, 0, 2, 2, (int[]) null));
        assertArrayEquals(original, samples);
    }

    @Test
    void matchesIntDevelopmentForCalibratedCropsInEveryOrientation() {
        int width = 9;
        int height = 7;
        int[] samples = new int[width * height];
        short[] packed = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (i * 997 + (i % 3 == 0 ? 40000 : 127)) & 65535;
            packed[i] = (short) samples[i];
        }
        int[] original = samples.clone();
        short[] packedOriginal = packed.clone();
        var perRow = BlackLevel.perRow(new int[][]{
                {3, 7, 13, 17, 29, 31, 43, 47}, {11, 19, 23, 31, 41, 53, 59, 67},
                {5, 17, 29, 43, 61, 73, 89, 101}, {2, 13, 31, 47, 67, 83, 103, 127}
        });
        var calibrations = new Calibration[]{
                new Calibration(null, null, 65535, null),
                new Calibration(BlackLevel.global(158), new double[]{1.37, 0.62, 2.11}, 65377, null),
                new Calibration(BlackLevel.perChannel(11, 23, 37, 41),
                        new double[]{1.37, 0.62, 2.11}, 65524, MATRIX),
                new Calibration(perRow, null, 65533, MATRIX)
        };
        var regions = new Rectangle[]{new Rectangle(width, height), new Rectangle(1, 1, 7, 5),
                new Rectangle(3, 2, 1, 4), new Rectangle(4, 3, 2, 1)};

        for (var pattern : BayerPattern.values()) {
            var bayer = new Cr3RawImage(width, height, samples, 16, pattern);
            for (var calibration : calibrations) {
                for (var region : regions) {
                    for (int orientation = 1; orientation <= 8; orientation++) {
                        for (boolean srgb : new boolean[]{false, true}) {
                            var expected = BayerDemosaicer.develop16(bayer, calibration.black(), 3, 5,
                                    calibration.gains(), calibration.white(), calibration.matrix(),
                                    region, orientation, srgb, 1);
                            var actual = compact(packed, width, height, pattern, calibration.black(), 3, 5,
                                    calibration.gains(), calibration.white(), calibration.matrix(),
                                    region, orientation, srgb, 1);
                            assertSameSamples(expected, actual);
                        }
                    }
                }
            }
        }
        assertArrayEquals(original, samples);
        assertArrayEquals(packedOriginal, packed);
    }

    @Test
    void matchesIntDevelopmentAcrossParallelStripes() {
        int width = 1025;
        int height = 769;
        int[] samples = new int[width * height];
        short[] packed = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (i * 31) & 65535;
            packed[i] = (short) samples[i];
        }
        var bayer = new Cr3RawImage(width, height, samples, 16, BayerPattern.GBRG);
        var black = BlackLevel.perChannel(313, 321, 299, 303);
        double[] gains = {1.21, 1.05, 0.83};
        var region = new Rectangle(3, 2, width - 5, height - 5);
        var expected = BayerDemosaicer.develop16(bayer, black, 13, 9, gains, 65236, MATRIX,
                region, 7, true, 1);
        for (int workers : new int[]{1, 3, 4, 8}) {
            var actual = compact(packed, width, height, BayerPattern.GBRG, black, 13, 9,
                    gains, 65236, MATRIX, region, 7, true, workers);
            assertSameSamples(expected, actual);
        }
    }

    @Test
    void rejectsCompactGeometryThatDoesNotCoverItsSamples() {
        assertThrows(IllegalArgumentException.class, () -> compact(new short[4], 2, 3,
                BayerPattern.RGGB, null, 0, 0, null, 65535, null, new Rectangle(2, 3), 1, false, 1));
        assertThrows(IllegalArgumentException.class, () -> compact(new short[4], -2, -2,
                BayerPattern.RGGB, null, 0, 0, null, 65535, null, new Rectangle(2, 2), 1, false, 1));
    }

    private record Calibration(BlackLevel black, double[] gains, int white, double[][] matrix) {}

    private static void assertSameSamples(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        assertEquals(expected.getColorModel(), actual.getColorModel());
        var a = (DataBufferUShort) expected.getRaster().getDataBuffer();
        var b = (DataBufferUShort) actual.getRaster().getDataBuffer();
        for (int band = 0; band < 3; band++) {
            assertArrayEquals(a.getData(band), b.getData(band), "RGB band " + band);
        }
    }

    private static BufferedImage compact(short[] samples, int width, int height, BayerPattern pattern,
                                         BlackLevel black, int sensorX, int sensorY, double[] gains, int white,
                                         double[][] matrix, Rectangle region, int orientation,
                                         boolean srgb, int workers) {
        return BayerDemosaicer.develop16(samples, width, height, pattern, black, sensorX, sensorY,
                gains, white, matrix, region, orientation, srgb, workers);
    }
}
