package com.photographer.focusstack.cr3.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;

/**
 * Applies a 3×3 color correction matrix to convert from camera color space
 * to a standard color space (e.g. sRGB linear).
 *
 * <p>The color matrix maps camera-native RGB values to a device-independent
 * color space. For Canon cameras, this matrix is typically provided in the
 * DNG specification or can be derived from the camera's color profile.</p>
 *
 * TODO: Load color matrix from Canon MakerNote or DNG profile
 * TODO: Support different illuminant matrices (D65, StdA)
 * TODO: Interpolate matrices based on color temperature
 */
public final class ColorMatrixProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(ColorMatrixProcessor.class);

    /**
     * Approximate color matrix for Canon PowerShot G5 X Mark II (D65 illuminant).
     * Maps camera RGB to sRGB linear.
     *
     * TODO: Replace with accurate matrix from DNG profile
     */
    public static final double[][] DEFAULT_COLOR_MATRIX = {
            { 1.2, -0.3,  0.1},
            {-0.1,  1.3, -0.2},
            { 0.0, -0.2,  1.2}
    };

    private ColorMatrixProcessor() {
        // utility class
    }

    /**
     * Applies a 3×3 color matrix to an RGB image.
     *
     * @param image  the RGB image (modified in place)
     * @param matrix 3×3 color correction matrix
     */
    public static void process(BufferedImage image, double[][] matrix) {
        if (matrix.length != 3 || matrix[0].length != 3) {
            throw new IllegalArgumentException("Color matrix must be 3x3");
        }

        LOG.debug("Applying 3x3 color matrix to {}x{} image", image.getWidth(), image.getHeight());

        int w = image.getWidth();
        int h = image.getHeight();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                int nr = clamp((int) (matrix[0][0] * r + matrix[0][1] * g + matrix[0][2] * b));
                int ng = clamp((int) (matrix[1][0] * r + matrix[1][1] * g + matrix[1][2] * b));
                int nb = clamp((int) (matrix[2][0] * r + matrix[2][1] * g + matrix[2][2] * b));

                image.setRGB(x, y, (nr << 16) | (ng << 8) | nb);
            }
        }
    }

    /**
     * Applies the default color matrix.
     *
     * @param image the RGB image (modified in place)
     */
    public static void process(BufferedImage image) {
        process(image, DEFAULT_COLOR_MATRIX);
    }

    private static int clamp(int value) {
        return Math.min(255, Math.max(0, value));
    }
}
