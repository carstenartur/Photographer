package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class ColorMatrixProcessorTest {

    private static BufferedImage createUniformImage(int width, int height, int r, int g, int b) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int rgb = (r << 16) | (g << 8) | b;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    @Nested
    class MatrixApplication {
        @Test
        void identityMatrixLeavesPixelsUnchanged() {
            BufferedImage img = createUniformImage(4, 4, 100, 150, 200);
            double[][] identity = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
            ColorMatrixProcessor.process(img, identity);

            int rgb = img.getRGB(2, 2);
            assertEquals(100, (rgb >> 16) & 0xFF);
            assertEquals(150, (rgb >> 8) & 0xFF);
            assertEquals(200, rgb & 0xFF);
        }

        @Test
        void matrixScalesChannels() {
            BufferedImage img = createUniformImage(2, 2, 100, 100, 100);
            double[][] matrix = {{2, 0, 0}, {0, 0.5, 0}, {0, 0, 1.5}};
            ColorMatrixProcessor.process(img, matrix);

            int rgb = img.getRGB(0, 0);
            assertEquals(200, (rgb >> 16) & 0xFF);  // 100 * 2
            assertEquals(50, (rgb >> 8) & 0xFF);     // 100 * 0.5
            assertEquals(150, rgb & 0xFF);            // 100 * 1.5
        }

        @Test
        void clampsNegativeToZero() {
            BufferedImage img = createUniformImage(2, 2, 50, 100, 50);
            double[][] matrix = {{1, -2, 0}, {0, 1, 0}, {0, 0, 1}};
            ColorMatrixProcessor.process(img, matrix);

            int rgb = img.getRGB(0, 0);
            // r = 50*1 + 100*(-2) + 50*0 = -150 → clamped to 0
            assertEquals(0, (rgb >> 16) & 0xFF);
        }

        @Test
        void clampsOverflowTo255() {
            BufferedImage img = createUniformImage(2, 2, 200, 200, 200);
            double[][] matrix = {{2, 0, 0}, {0, 2, 0}, {0, 0, 2}};
            ColorMatrixProcessor.process(img, matrix);

            int rgb = img.getRGB(0, 0);
            assertEquals(255, (rgb >> 16) & 0xFF);
            assertEquals(255, (rgb >> 8) & 0xFF);
            assertEquals(255, rgb & 0xFF);
        }
    }

    @Nested
    class Validation {
        @Test
        void rejectsNon3x3Matrix() {
            BufferedImage img = createUniformImage(2, 2, 100, 100, 100);
            double[][] bad = {{1, 0}, {0, 1}};
            assertThrows(IllegalArgumentException.class,
                    () -> ColorMatrixProcessor.process(img, bad));
        }
    }

    @Nested
    class DefaultMatrix {
        @Test
        void defaultMatrixProcessesWithoutError() {
            BufferedImage img = createUniformImage(4, 4, 128, 128, 128);
            ColorMatrixProcessor.process(img);
            // Just verify it doesn't throw and pixels are within range
            int rgb = img.getRGB(2, 2);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            assertTrue(r >= 0 && r <= 255);
            assertTrue(g >= 0 && g <= 255);
            assertTrue(b >= 0 && b <= 255);
        }
    }
}
