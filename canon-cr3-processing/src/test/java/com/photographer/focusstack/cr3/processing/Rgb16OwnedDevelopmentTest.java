package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage.BayerPattern;
import com.photographer.focusstack.cr3.raw.Cr3Workers;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Rgb16OwnedDevelopmentTest {
    private static final double[][] MATRIX = {
            {1.35, -0.22, -0.13}, {-0.19, 1.25, -0.06}, {-0.08, -0.31, 1.39}
    };

    @Test
    void reusesNativeArrayAsTheGreenBankWithoutLosingUnsignedCodes() {
        short[] samples = {(short) 32768, (short) 40000, (short) 50000, (short) 65535};
        var result = owned(samples, 2, 2, BayerPattern.RGGB, null, 0, 0, null,
                65535, null, new Rectangle(2, 2), 1, false, 1);

        assertOwnedGreen(samples, result);
        assertArrayEquals(new int[]{
                32768, 45000, 65535, 32768, 40000, 65535,
                32768, 50000, 65535, 32768, 45000, 65535
        }, result.getRaster().getPixels(0, 0, 2, 2, (int[]) null));
    }

    @Test
    void matchesReadOnlyDevelopmentForSmallFramesAndEveryCalibration() {
        var calibrations = new Calibration[]{
                new Calibration(null, null, 65535, null),
                new Calibration(BlackLevel.global(158), new double[]{1.37, 0.62, 2.11}, 65377, null),
                new Calibration(BlackLevel.perChannel(11, 23, 37, 41),
                        new double[]{1.37, 0.62, 2.11}, 59000, MATRIX),
                new Calibration(perRowBlack(7), null, 65533, MATRIX)
        };
        int[][] dimensions = {{1, 1}, {1, 7}, {7, 1}, {2, 2}, {9, 7}, {8, 6}};
        for (int[] size : dimensions) {
            int width = size[0];
            int height = size[1];
            short[] samples = samples(width, height);
            short[] original = samples.clone();
            var region = new Rectangle(width, height);
            for (var pattern : BayerPattern.values()) {
                for (var calibration : calibrations) {
                    for (boolean srgb : new boolean[]{false, true}) {
                        var expected = BayerDemosaicer.develop16(samples, width, height, pattern,
                                calibration.black(), 3, 5, calibration.gains(), calibration.white(),
                                calibration.matrix(), region, 1, srgb, 1);
                        for (int workers : new int[]{1, 8}) {
                            short[] ownedSamples = samples.clone();
                            var actual = owned(ownedSamples, width, height, pattern, calibration.black(), 3, 5,
                                    calibration.gains(), calibration.white(), calibration.matrix(), region,
                                    1, srgb, workers);
                            assertOwnedGreen(ownedSamples, actual);
                            assertSameSamples(expected, actual);
                        }
                    }
                }
            }
            assertArrayEquals(original, samples, "The borrowed short overload remains read-only");
        }
    }

    @Test
    void matchesReadOnlyDevelopmentAcrossActualOddSizedStripes() {
        int width = 1537;
        int height = 1367;
        // Both the pixel and row thresholds permit all requested stripe counts.
        assertTrue(Pixels.maxStripes(width, height) >= 8);
        short[] samples = samples(width, height);
        short[] original = samples.clone();
        var black = perRowBlack(height);
        double[] gains = {1.21, 0.73, 1.83};
        var region = new Rectangle(width, height);
        for (var pattern : BayerPattern.values()) {
            for (boolean srgb : new boolean[]{false, true}) {
                var expected = BayerDemosaicer.develop16(samples, width, height, pattern, black, 3, 5,
                        gains, 59000, MATRIX, region, 1, srgb, 1);
                for (int workers : new int[]{1, 3, 4, 8}) {
                    short[] ownedSamples = samples.clone();
                    var actual = owned(ownedSamples, width, height, pattern, black, 3, 5,
                            gains, 59000, MATRIX, region, 1, srgb, workers);
                    assertOwnedGreen(ownedSamples, actual);
                    // Whole-plane equality includes both sides of every odd/even stripe boundary.
                    assertSameSamples(expected, actual);
                }
            }
        }
        assertArrayEquals(original, samples);
    }

    @Test
    void preservesInputForRegionsAndNonIdentityOrientations() {
        int width = 9;
        int height = 7;
        short[] samples = samples(width, height);
        short[] original = samples.clone();
        var regions = new Rectangle[]{new Rectangle(width, height), new Rectangle(1, 1, 7, 5),
                new Rectangle(3, 2, 1, 4), new Rectangle(4, 3, 2, 1)};
        var black = BlackLevel.perChannel(11, 23, 37, 41);
        double[] gains = {1.37, 0.62, 2.11};
        for (var pattern : BayerPattern.values()) {
            for (var region : regions) {
                // Out-of-range values keep the established orientation fallback too.
                for (int orientation = 0; orientation <= 9; orientation++) {
                    if (orientation == 1 && region.equals(new Rectangle(width, height))) {
                        continue;
                    }
                    var expected = BayerDemosaicer.develop16(samples, width, height, pattern, black, 3, 5,
                            gains, 59000, MATRIX, region, orientation, true, 1);
                    var actual = owned(samples, width, height, pattern, black, 3, 5,
                            gains, 59000, MATRIX, region, orientation, true, 8);
                    assertSameSamples(expected, actual);
                    assertArrayEquals(original, samples);
                    var buffer = (DataBufferUShort) actual.getRaster().getDataBuffer();
                    for (int band = 0; band < 3; band++) {
                        assertNotSame(samples, buffer.getData(band));
                    }
                }
            }
        }
    }

    @Test
    void keepsOriginalNeighborRowsWhenStripesRunOneAfterAnother() {
        int width = 1537;
        int height = 1367;
        short[] samples = samples(width, height);
        var black = perRowBlack(height);
        double[] gains = {1.21, 0.73, 1.83};
        var region = new Rectangle(width, height);
        var expected = BayerDemosaicer.develop16(samples, width, height, BayerPattern.GBRG, black, 3, 5,
                gains, 59000, MATRIX, region, 1, false, 1);
        BufferedImage[] result = new BufferedImage[1];
        Cr3Workers.run(2, task -> {
            if (task == 0) {
                // Nested work uses the current pool thread: each stripe finishes before the next starts.
                // The next stripe must not read the preceding stripe's already-developed green row.
                result[0] = owned(samples, width, height, BayerPattern.GBRG, black, 3, 5,
                        gains, 59000, MATRIX, region, 1, false, 8);
            }
        });
        assertOwnedGreen(samples, result[0]);
        assertSameSamples(expected, result[0]);
    }

    @Test
    void validatesBeforeChangingOwnedSamples() {
        short[] samples = samples(3, 3);
        short[] original = samples.clone();
        assertThrows(IllegalArgumentException.class, () -> owned(samples, 3, 3, BayerPattern.RGGB,
                null, 0, 0, null, 0, null, new Rectangle(3, 3), 1, false, 1));
        assertThrows(IllegalArgumentException.class, () -> owned(samples, 3, 3, BayerPattern.RGGB,
                null, 0, 0, new double[]{1.0}, 65535, null, new Rectangle(3, 3), 1, false, 1));
        assertThrows(IllegalArgumentException.class, () -> owned(samples, 3, 3, BayerPattern.RGGB,
                null, 0, 0, null, 65535, new double[2][3], new Rectangle(3, 3), 1, false, 1));
        assertThrows(IllegalArgumentException.class, () -> owned(samples, 3, 3, BayerPattern.RGGB,
                null, 0, 0, null, 65535, null, new Rectangle(3, 3), 1, false, -1));
        assertThrows(IllegalArgumentException.class, () -> owned(samples, 3, 3, BayerPattern.RGGB,
                null, 0, 0, null, 65535, null, new Rectangle(1, 1, 3, 3), 1, false, 1));
        assertArrayEquals(original, samples);
    }

    private record Calibration(BlackLevel black, double[] gains, int white, double[][] matrix) {}

    private static short[] samples(int width, int height) {
        short[] samples = new short[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                samples[y * width + x] = (short) ((x * 997 + y * 7919 + ((x + y) % 3 == 0 ? 40000 : 127)) & 65535);
            }
        }
        return samples;
    }

    private static BlackLevel perRowBlack(int height) {
        int[][] levels = new int[4][(height + 6) / 2];
        for (int band = 0; band < levels.length; band++) {
            for (int row = 0; row < levels[band].length; row++) {
                levels[band][row] = 11 + band * 13 + (row * 47) % 401;
            }
        }
        return BlackLevel.perRow(levels);
    }

    private static void assertOwnedGreen(short[] samples, BufferedImage actual) {
        var buffer = (DataBufferUShort) actual.getRaster().getDataBuffer();
        assertSame(samples, buffer.getData(1), "The native Bayer allocation becomes the green output bank");
        assertNotSame(samples, buffer.getData(0));
        assertNotSame(samples, buffer.getData(2));
        assertNotSame(buffer.getData(0), buffer.getData(2));
    }

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

    private static BufferedImage owned(short[] samples, int width, int height, BayerPattern pattern,
                                       BlackLevel black, int sensorX, int sensorY, double[] gains, int white,
                                       double[][] matrix, Rectangle region, int orientation,
                                       boolean srgb, int workers) {
        return BayerDemosaicer.develop16Owned(samples, width, height, pattern, black, sensorX, sensorY,
                gains, white, matrix, region, orientation, srgb, workers);
    }
}
