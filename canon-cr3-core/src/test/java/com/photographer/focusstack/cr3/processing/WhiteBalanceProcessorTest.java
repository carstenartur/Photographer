package com.photographer.focusstack.cr3.processing;

import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WhiteBalanceProcessorTest {

    private static Cr3RawImage createUniformImage(int width, int height, int value, int bitDepth,
                                                    Cr3RawImage.BayerPattern pattern) {
        int[] data = new int[width * height];
        java.util.Arrays.fill(data, value);
        return new Cr3RawImage(width, height, data, bitDepth, pattern);
    }

    @Nested
    class MultiplierApplication {
        @Test
        void appliesMultipliersToRGGBPattern() {
            // 4x4 image with all pixels = 1000, 14-bit
            Cr3RawImage img = createUniformImage(4, 4, 1000, 14, Cr3RawImage.BayerPattern.RGGB);
            double[] mults = {2.0, 1.0, 1.5};
            WhiteBalanceProcessor.process(img, mults);

            // (0,0) = R → 1000 * 2.0 = 2000
            assertEquals(2000, img.getPixel(0, 0));
            // (1,0) = Gr → 1000 * 1.0 = 1000
            assertEquals(1000, img.getPixel(1, 0));
            // (0,1) = Gb → 1000 * 1.0 = 1000
            assertEquals(1000, img.getPixel(0, 1));
            // (1,1) = B → 1000 * 1.5 = 1500
            assertEquals(1500, img.getPixel(1, 1));
        }

        @Test
        void clampsToMaxValue() {
            Cr3RawImage img = createUniformImage(2, 2, 15000, 14, Cr3RawImage.BayerPattern.RGGB);
            double[] mults = {2.0, 1.0, 1.5};
            WhiteBalanceProcessor.process(img, mults);
            // 15000 * 2.0 = 30000, clamped to 16383
            assertEquals(16383, img.getPixel(0, 0));
        }

        @Test
        void clampsToZero() {
            Cr3RawImage img = createUniformImage(2, 2, 0, 14, Cr3RawImage.BayerPattern.RGGB);
            double[] mults = {2.0, 1.0, 1.5};
            WhiteBalanceProcessor.process(img, mults);
            assertEquals(0, img.getPixel(0, 0));
        }
    }

    @Nested
    class Validation {
        @Test
        void rejectsWrongMultiplierCount() {
            Cr3RawImage img = createUniformImage(2, 2, 1000, 14, Cr3RawImage.BayerPattern.RGGB);
            assertThrows(IllegalArgumentException.class,
                    () -> WhiteBalanceProcessor.process(img, new double[]{1.0, 1.0}));
        }
    }

    @Nested
    class BayerPatterns {
        @Test
        void supportsBGGRPattern() {
            Cr3RawImage img = createUniformImage(2, 2, 1000, 14, Cr3RawImage.BayerPattern.BGGR);
            double[] mults = {2.0, 1.0, 1.5};
            WhiteBalanceProcessor.process(img, mults);
            // (0,0) = B → 1500, (1,1) = R → 2000
            assertEquals(1500, img.getPixel(0, 0));
            assertEquals(2000, img.getPixel(1, 1));
        }

        @Test
        void supportsGRBGPattern() {
            Cr3RawImage img = createUniformImage(2, 2, 1000, 14, Cr3RawImage.BayerPattern.GRBG);
            double[] mults = {2.0, 1.0, 1.5};
            WhiteBalanceProcessor.process(img, mults);
            // (0,0) = G → 1000, (1,0) = R → 2000
            assertEquals(1000, img.getPixel(0, 0));
            assertEquals(2000, img.getPixel(1, 0));
        }

        @Test
        void supportsGBRGPattern() {
            Cr3RawImage img = createUniformImage(2, 2, 1000, 14, Cr3RawImage.BayerPattern.GBRG);
            double[] mults = {2.0, 1.0, 1.5};
            WhiteBalanceProcessor.process(img, mults);
            // (0,0) = G → 1000, (1,0) = B → 1500
            assertEquals(1000, img.getPixel(0, 0));
            assertEquals(1500, img.getPixel(1, 0));
        }
    }

    @Nested
    class DefaultMultipliers {
        @Test
        void usesDefaultDaylightMultipliers() {
            Cr3RawImage img = createUniformImage(2, 2, 1000, 14, Cr3RawImage.BayerPattern.RGGB);
            WhiteBalanceProcessor.process(img);
            // R: 1000 * 1.9 = 1900, G: 1000 * 1.0 = 1000, B: 1000 * 1.5 = 1500
            assertEquals(1900, img.getPixel(0, 0));
            assertEquals(1000, img.getPixel(1, 0));
            assertEquals(1500, img.getPixel(1, 1));
        }
    }
}
