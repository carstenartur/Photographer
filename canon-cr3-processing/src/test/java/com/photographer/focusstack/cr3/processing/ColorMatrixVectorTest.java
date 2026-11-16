package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ColorMatrixVectorTest {
    private static final double[][] IDENTITY = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};

    @Test
    @EnabledIfSystemProperty(named = "cr3.vector.tests", matches = "true")
    void usesVectorKernelWhenHardwareSupports256BitVectors() throws ReflectiveOperationException {
        Class<?> vector = Class.forName("jdk.incubator.vector.DoubleVector");
        Class<?> species = Class.forName("jdk.incubator.vector.VectorSpecies");
        Object preferred = vector.getField("SPECIES_PREFERRED").get(null);
        int bits = (int) species.getMethod("vectorBitSize").invoke(preferred);
        var image = new BufferedImage(7, 2, BufferedImage.TYPE_INT_RGB);

        assertEquals(bits >= 256, ColorMatrixProcessor.processWithVectors(image, IDENTITY, 0));
    }

    @Test
    void preservesPackedArgbCropAndTailWithAutomaticWorkers() {
        var image = new BufferedImage(1037, 521, BufferedImage.TYPE_INT_ARGB);
        var crop = image.getSubimage(3, 2, 1031, 517);
        double[][] matrix = {
                {1.1250000000000002, -0.12499999999999999, 0.0001},
                {Double.MIN_VALUE, Math.nextDown(1.0), -0.0125},
                {-0.75, 1.5, 0.8}
        };
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        var random = new Random(2873);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = random.nextInt();
        }
        int[] expected = pixels.clone();
        for (int y = 2; y < 2 + crop.getHeight(); y++) {
            for (int x = 3; x < 3 + crop.getWidth(); x++) {
                int i = y * image.getWidth() + x;
                expected[i] = scalarRgb(expected[i], matrix);
            }
        }

        ColorMatrixProcessor.process(crop, matrix, 0);

        assertArrayEquals(expected, pixels);
    }

    @Test
    void preservesJavaCastsWithNonfiniteAndOverflowingCoefficients() {
        var image = new BufferedImage(7, 1, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int[] before = {0x000000, 0x010000, 0x020000, 0x020200, 0x000200, 0x000002, 0x010200};
        System.arraycopy(before, 0, pixels, 0, before.length);
        double[][] matrix = {
                {Double.NaN, 0, 0},
                {Double.POSITIVE_INFINITY, 0, 0},
                {Double.MAX_VALUE, -Double.MAX_VALUE, 0}
        };

        ColorMatrixProcessor.process(image, matrix, 0);

        assertArrayEquals(new int[]{0x000000, 0x00ffff, 0x00ffff, 0x00ff00, 0x000000, 0x000000, 0x00ff00}, pixels);
    }

    @Test
    void preservesScalarBehaviorForAnExtraCoefficientInTheSecondRow() {
        var image = new BufferedImage(7, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(2, 0, 0xff19364b);
        double[][] matrix = {{1, 0, 0}, {0, 0, 1, 42}, {0, 1, 0}};

        ColorMatrixProcessor.process(image, matrix, 0);

        assertEquals(0xff194b36, image.getRGB(2, 0));
    }

    @Test
    void rejectsNegativeWorkerLimit() {
        var image = new BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB);
        assertThrows(IllegalArgumentException.class, () -> ColorMatrixProcessor.process(image, IDENTITY, -1));
    }

    private static int scalarRgb(int rgb, double[][] matrix) {
        int red = rgb >>> 16 & 255;
        int green = rgb >>> 8 & 255;
        int blue = rgb & 255;
        int result = 0;
        for (double[] row : matrix) {
            double channel = row[0] * red + row[1] * green + row[2] * blue;
            result = (result << 8) | Math.max(0, Math.min(255, (int) channel));
        }
        return result;
    }
}
