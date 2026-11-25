package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageTypeSpecifier;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpticalCorrectionTest {
    @Test
    void illuminationUsesSensorDiagonalAndLinearLightAtBothPrecisions() {
        OpticalCorrection correction = new OpticalCorrection(0, 0, 0, 1, 1, -0.5, 0, 0);
        for (boolean srgb : new boolean[]{true, false}) {
            for (boolean high : new boolean[]{true, false}) {
                BufferedImage frame = high ? rgb16(41, 31, srgb)
                        : new BufferedImage(41, 31, BufferedImage.TYPE_INT_RGB);
                int max = high ? 65535 : 255;
                int input = (int) Math.round(0.3 * max);
                fillBand(frame, 0, input);
                assertSame(frame, correction.applyToOwned(frame, srgb, 1));
                assertEquals(input, frame.getRaster().getSample(20, 15, 0));
                double light = (double) input / max;
                if (srgb) light = Math.pow((light + 0.055) / 1.055, 2.4);
                // Half-diagonal = 25 px. The corner has 50% attenuation and needs 2× gain.
                int expected = (int) Math.round(max * (srgb ? SrgbConverter.linearToSrgb(2 * light) : 2 * light));
                assertEquals(expected, frame.getRaster().getSample(40, 30, 0));
                assertEquals(0, frame.getRaster().getSample(40, 30, 1));
            }
        }
    }

    @Test
    void correctsAColorFringeWithOneBilinearInterpolation() {
        BufferedImage frame = rgb16(101, 61, false);
        for (int y = 0; y < 61; y++) {
            for (int x = 0; x < 101; x++) {
                frame.getRaster().setPixel(x, y, new int[]{1000 + 10 * x, 1000 + 10 * x, 1000 + 10 * x});
            }
        }
        new OpticalCorrection(0, 0, 0, 0.9, 0.95, 0, 0, 0).applyToOwned(frame, false, 1);
        // At x=70 the shifts back toward the center are 2 and 1 source pixels.
        assertArrayEquals(new int[]{1680, 1700, 1690}, frame.getRaster().getPixel(70, 30, (int[]) null));
        assertArrayEquals(new int[]{1500, 1500, 1500}, frame.getRaster().getPixel(50, 30, (int[]) null));
    }

    @Test
    void mapsPtlensUsingHalfTheShortSideAndPreservesPortraitGeometry() {
        BufferedImage landscape = rgb16(101, 61, false);
        BufferedImage portrait = rgb16(61, 101, false);
        for (int y = 0; y < 61; y++) {
            for (int x = 0; x < 101; x++) {
                int code = x * 128 + y;
                landscape.getRaster().setSample(x, y, 0, code);
                portrait.getRaster().setSample(y, x, 0, code);
            }
        }
        // Each input pixel keeps its on-axis sampling density: crop = 1 / d with d=1.05.
        // The mapped half-radius further contracts according to the quadratic coefficient.
        OpticalCorrection correction = new OpticalCorrection(0, -0.05, 0, 1, 1, 0, 0, 0);
        correction.applyToOwned(landscape, false, 1);
        correction.applyToOwned(portrait, false, 2);
        double radius = 0.5 / 1.05;
        double sourceOffset = 30 * radius * (1.05 - 0.05 * radius * radius);
        assertEquals(Math.round((50 + sourceOffset) * 128 + 30),
                landscape.getRaster().getSample(65, 30, 0));
        assertEquals(51 * 128 + 30, landscape.getRaster().getSample(51, 30, 0));
        for (int y = 0; y < 61; y++) {
            for (int x = 0; x < 101; x++) {
                assertEquals(landscape.getRaster().getSample(x, y, 0), portrait.getRaster().getSample(y, x, 0), 1);
            }
        }
    }

    @Test
    void cropsTheFieldBeforeAnIlluminationZeroAndKeepsUnsignedCodes() {
        BufferedImage frame = rgb16(101, 61, false);
        fillBand(frame, 0, 40000);
        new OpticalCorrection(0, 0, 0, 1, 1, -2, 0, 0).applyToOwned(frame, false, 1);
        assertEquals(40000, frame.getRaster().getSample(50, 30, 0));
        for (int y = 0; y < 61; y++) {
            for (int x = 0; x < 101; x++) {
                assertTrue(frame.getRaster().getSample(x, y, 0) >= 40000);
            }
        }
        assertEquals(65535, frame.getRaster().getSample(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new OpticalCorrection(
                Double.NaN, 0, 0, 1, 1, 0, 0, 0));
    }

    @Test
    void scalesOnlyTheShadingGainAndPreservesTheSampledField() {
        var correction = new OpticalCorrection(0.03, -0.12, 0.02, 1.001, 1, -0.6, 0, 0);
        var zero = rgb16(101, 61, false);
        var half = rgb16(101, 61, false);
        var full = rgb16(101, 61, false);
        for (int y = 0; y < 61; y++) {
            for (int x = 0; x < 101; x++) {
                int code = 100 * x + y + 1000;
                zero.getRaster().setSample(x, y, 0, code);
                half.getRaster().setSample(x, y, 0, code);
                full.getRaster().setSample(x, y, 0, code);
            }
        }
        correction.applyToOwned(zero, false, 1, 0);
        correction.applyToOwned(half, false, 1, 0.5);
        correction.applyToOwned(full, false, 1);
        // Distinct gradients make this sensitive to an unintended shift of the sampling positions.
        for (int y = 0; y < 61; y++) {
            for (int x = 0; x < 101; x++) {
                int base = zero.getRaster().getSample(x, y, 0);
                int corrected = full.getRaster().getSample(x, y, 0);
                assertEquals((base + corrected) * 0.5, half.getRaster().getSample(x, y, 0), 1);
            }
        }
        assertTrue(full.getRaster().getSample(99, 59, 0) > zero.getRaster().getSample(99, 59, 0));
        assertThrows(IllegalArgumentException.class, () -> correction.applyToOwned(zero, false, 1, Double.NaN));
    }

    private static BufferedImage rgb16(int w, int h, boolean srgb) {
        var cs = ColorSpace.getInstance(srgb ? ColorSpace.CS_sRGB : ColorSpace.CS_LINEAR_RGB);
        return ImageTypeSpecifier.createBanded(cs, new int[]{0, 1, 2}, new int[3],
                DataBuffer.TYPE_USHORT, false, false).createBufferedImage(w, h);
    }

    private static void fillBand(BufferedImage image, int band, int code) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.getRaster().setSample(x, y, band, code);
            }
        }
    }
}
