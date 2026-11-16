package com.photographer.focusstack.cr3.processing;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorSpecies;

import java.awt.image.BufferedImage;

import static jdk.incubator.vector.VectorOperators.ASHR;
import static jdk.incubator.vector.VectorOperators.D2I;
import static jdk.incubator.vector.VectorOperators.I2D;
import static jdk.incubator.vector.VectorOperators.LSHL;

final class ColorMatrixVectors implements ColorMatrixProcessor.VectorKernel {
    private static final VectorSpecies<Double> CHANNELS = DoubleVector.SPECIES_256;
    private static final VectorSpecies<Integer> RGB = IntVector.SPECIES_128;
    private static final boolean SUPPORTED =
            DoubleVector.SPECIES_PREFERRED.vectorBitSize() >= CHANNELS.vectorBitSize();

    @Override
    public boolean process(BufferedImage image, double[][] matrix, int maxWorkers) {
        int width = image.getWidth();
        if (!SUPPORTED || width < RGB.length()
                || matrix[1] == null || matrix[1].length != 3
                || matrix[2] == null || matrix[2].length != 3) {
            return false;
        }
        Pixels.Packed packed = Pixels.packed(image);
        if (packed == null) {
            return false;
        }

        int bound = RGB.loopBound(width);
        Pixels.transformPacked(packed, width, image.getHeight(), maxWorkers,
                (pixels, offset) -> transformRow(pixels, offset, width, bound, matrix));
        return true;
    }

    private static void transformRow(int[] pixels, int offset, int width, int bound, double[][] matrix) {
        int x = 0;
        for (; x < bound; x += RGB.length()) {
            IntVector pixel = IntVector.fromArray(RGB, pixels, offset + x);
            DoubleVector r = channel(pixel.lanewise(ASHR, 16));
            DoubleVector g = channel(pixel.lanewise(ASHR, 8));
            DoubleVector b = channel(pixel);
            IntVector nr = output(r, g, b, matrix[0]);
            IntVector ng = output(r, g, b, matrix[1]);
            IntVector nb = output(r, g, b, matrix[2]);
            nr.lanewise(LSHL, 16).or(ng.lanewise(LSHL, 8)).or(nb).intoArray(pixels, offset + x);
        }
        for (; x < width; x++) {
            pixels[offset + x] = ColorMatrixProcessor.applyMatrix(pixels[offset + x], matrix);
        }
    }

    private static DoubleVector channel(IntVector pixels) {
        return (DoubleVector) pixels.and(0xFF).convertShape(I2D, CHANNELS, 0);
    }

    private static IntVector output(DoubleVector r, DoubleVector g, DoubleVector b, double[] coefficients) {
        // Keep the scalar addition order and narrow to int before clamping.
        DoubleVector value = r.mul(coefficients[0]).add(g.mul(coefficients[1])).add(b.mul(coefficients[2]));
        return ((IntVector) value.convertShape(D2I, RGB, 0)).max(0).min(255);
    }
}
