package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParallelColorTransformTest {

    @Test
    void matrixTransformsExactlyTheSubimageAndPreservesOverlappingScanlineOrder() {
        double[][] matrix = {{0, 0.5, 0}, {0, 0, 1}, {1.5, 0, 0}};
        checkStrides(image -> ColorMatrixProcessor.process(image, matrix, 3), rgb -> {
            int red = rgb >>> 16 & 255;
            int green = rgb >>> 8 & 255;
            int blue = rgb & 255;
            return (green / 2 << 16) | (blue << 8) | Math.min(255, 3 * red / 2);
        });
    }

    @Test
    void gammaTransformsExactlyTheSubimageAndPreservesOverlappingScanlineOrder() {
        int[] gamma = new int[256];
        for (int i = 0; i < gamma.length; i++) {
            gamma[i] = (int) Math.round(255 * SrgbConverter.linearToSrgb(i / 255.0));
        }
        checkStrides(image -> SrgbConverter.process(image, 3), rgb ->
                (gamma[rgb >>> 16 & 255] << 16) | (gamma[rgb >>> 8 & 255] << 8) | gamma[rgb & 255]);
    }

    private static void checkStrides(Consumer<BufferedImage> transform, IntUnaryOperator scalar) {
        int width = 1031;
        int height = 779;
        for (int stride : new int[]{width + 9, width - 13}) {
            int size = (height - 1) * stride + width;
            int[] reference = new Random(414).ints(size + 7).toArray();
            int[] actual = reference.clone();
            BufferedImage referenceView = subimage(reference, size, width, height, stride);
            BufferedImage actualView = subimage(actual, size, width, height, stride);
            for (int y = 0; y < referenceView.getHeight(); y++) {
                for (int x = 0; x < referenceView.getWidth(); x++) {
                    referenceView.setRGB(x, y, scalar.applyAsInt(referenceView.getRGB(x, y)));
                }
            }
            transform.accept(actualView);
            assertArrayEquals(reference, actual);
        }
    }

    private static BufferedImage subimage(int[] data, int size, int width, int height, int stride) {
        int[] masks = {0xff0000, 0xff00, 0xff};
        var buffer = new DataBufferInt(data, size, 4);
        var raster = Raster.createPackedRaster(buffer, width, height, stride, masks, null);
        var model = new DirectColorModel(24, masks[0], masks[1], masks[2]);
        return new BufferedImage(model, raster, false, null).getSubimage(2, 3, width - 4, height - 6);
    }

    @Test
    void genericRasterAlsoRejectsNegativeWorkerLimit() {
        var image = new BufferedImage(7, 5, BufferedImage.TYPE_3BYTE_BGR);
        assertThrows(IllegalArgumentException.class, () -> SrgbConverter.process(image, -1));
    }
}
