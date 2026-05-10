package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class SrgbConverterTest {

    @Nested
    class GammaLut {
        @Test
        void blackRemainsBlack() {
            BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            // All pixels black
            SrgbConverter.process(img);
            int rgb = img.getRGB(0, 0);
            assertEquals(0, (rgb >> 16) & 0xFF);
            assertEquals(0, (rgb >> 8) & 0xFF);
            assertEquals(0, rgb & 0xFF);
        }

        @Test
        void whiteRemainsWhite() {
            BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 2; y++)
                for (int x = 0; x < 2; x++)
                    img.setRGB(x, y, 0xFFFFFF);
            SrgbConverter.process(img);
            int rgb = img.getRGB(0, 0);
            assertEquals(255, (rgb >> 16) & 0xFF);
            assertEquals(255, (rgb >> 8) & 0xFF);
            assertEquals(255, rgb & 0xFF);
        }

        @Test
        void gammaIncreasesLowValues() {
            // Linear mid-grey (128) should become brighter after sRGB gamma
            BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, (128 << 16) | (128 << 8) | 128);
            SrgbConverter.process(img);
            int rgb = img.getRGB(0, 0);
            int r = (rgb >> 16) & 0xFF;
            // sRGB gamma makes mid-tones brighter
            assertTrue(r > 128, "sRGB gamma should brighten linear mid-grey, got " + r);
        }

        @Test
        void monotonicallyIncreasing() {
            BufferedImage img = new BufferedImage(256, 1, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 256; x++) {
                img.setRGB(x, 0, (x << 16) | (x << 8) | x);
            }
            SrgbConverter.process(img);
            int prev = -1;
            for (int x = 0; x < 256; x++) {
                int val = (img.getRGB(x, 0) >> 16) & 0xFF;
                assertTrue(val >= prev, "sRGB LUT should be monotonic at x=" + x);
                prev = val;
            }
        }

        @Test
        void processMatchesExpectedPixelSnapshot() {
            BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, (0 << 16) | (64 << 8) | 128);
            img.setRGB(1, 0, (255 << 16) | (128 << 8) | 64);
            img.setRGB(0, 1, (64 << 16) | (0 << 8) | 255);
            img.setRGB(1, 1, (128 << 16) | (255 << 8) | 0);

            SrgbConverter.process(img);

            int[] actual = {
                    img.getRGB(0, 0) & 0xFFFFFF,
                    img.getRGB(1, 0) & 0xFFFFFF,
                    img.getRGB(0, 1) & 0xFFFFFF,
                    img.getRGB(1, 1) & 0xFFFFFF
            };
            int[] expected = {0x0089BC, 0xFFBC89, 0x8900FF, 0xBCFF00};
            assertArrayEquals(expected, actual);
        }
    }

    @Nested
    class LinearToSrgb {
        @Test
        void zeroMapsToZero() {
            assertEquals(0.0, SrgbConverter.linearToSrgb(0.0), 1e-6);
        }

        @Test
        void oneMapsToOne() {
            assertEquals(1.0, SrgbConverter.linearToSrgb(1.0), 1e-3);
        }

        @Test
        void midpointIsAboveHalf() {
            double result = SrgbConverter.linearToSrgb(0.5);
            assertTrue(result > 0.5, "sRGB of 0.5 linear should be > 0.5, got " + result);
        }

        @Test
        void lowLinearUsesLinearTransfer() {
            // For values <= 0.0031308, sRGB = 12.92 * linear
            double result = SrgbConverter.linearToSrgb(0.001);
            assertEquals(0.01292, result, 1e-4);
        }
    }
}
