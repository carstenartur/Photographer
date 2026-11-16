package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;

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
        void transformsOnlySubimagePixelsAcrossColorModels() {
            int[] types = {BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB,
                    BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_3BYTE_BGR};
            for (int type : types) {
                BufferedImage parent = new BufferedImage(8, 4, type);
                checkSubimageTransform(parent);
            }
            // An int-packed image may also use a non-sRGB color space and a padded buffer.
            for (int space : new int[]{ColorSpace.CS_sRGB, ColorSpace.CS_LINEAR_RGB}) {
                var model = new DirectColorModel(ColorSpace.getInstance(space), 24,
                        0xff0000, 0xff00, 0xff, 0, false, DataBuffer.TYPE_INT);
                var buffer = new DataBufferInt(new int[50], 40, 3);
                var raster = Raster.createPackedRaster(buffer, 8, 4, 10,
                        new int[]{0xff0000, 0xff00, 0xff}, null);
                checkSubimageTransform(new BufferedImage(model, raster, false, null));
            }
        }

        private static void checkSubimageTransform(BufferedImage parent) {
            int width = parent.getWidth();
            int height = parent.getHeight();
            int[] expected = new int[width * height];
            double[][] matrix = {{0, 0.5, 0}, {0, 0, 1}, {1.5, 0, 0}};
            var model = parent.getColorModel();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    parent.setRGB(x, y, 0x91542818 + (x << 20) + (y << 8));
                    int rgb = parent.getRGB(x, y);
                    if (x >= 2 && x < 7 && y >= 1 && y < 3) {
                        int r = rgb >>> 16 & 255;
                        int g = rgb >>> 8 & 255;
                        int b = rgb & 255;
                        int transformed = (g / 2 << 16) | (b << 8) | Math.min(255, 3 * r / 2);
                        rgb = model.getRGB(model.getDataElements(transformed, null));
                    }
                    expected[y * width + x] = rgb;
                }
            }
            ColorMatrixProcessor.process(parent.getSubimage(2, 1, 5, 2), matrix);
            assertArrayEquals(expected, parent.getRGB(0, 0, width, height, null, 0, width));
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
