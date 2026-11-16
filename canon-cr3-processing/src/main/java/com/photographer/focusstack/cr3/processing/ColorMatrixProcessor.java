package com.photographer.focusstack.cr3.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;

/**
 * Applies a 3×3 color correction matrix to convert from camera color space
 * to a standard color space (e.g. sRGB linear).
 *
 * <p>The color matrix maps camera-native RGB values to a device-independent
 * color space. Camera-specific matrices are looked up via
 * {@link CameraColorMatrixRegistry} based on the EXIF camera model.</p>
 */
public final class ColorMatrixProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(ColorMatrixProcessor.class);

    /**
     * Default color matrix — generic Canon approximation (D65 illuminant).
     * Use {@link CameraColorMatrixRegistry} for camera-specific matrices.
     */
    public static final double[][] DEFAULT_COLOR_MATRIX = CameraColorMatrixRegistry.GENERIC_CANON_D65;

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
        process(image, matrix, 1);
    }

    /**
     * Applies the matrix in place, processing disjoint packed RGB rows in parallel.
     *
     * @param image the RGB image to modify
     * @param matrix 3×3 color correction matrix
     * @param maxWorkers maximum number of workers, or zero for automatic selection
     */
    public static void process(BufferedImage image, double[][] matrix, int maxWorkers) {
        if (matrix.length != 3 || matrix[0].length != 3) {
            throw new IllegalArgumentException("Color matrix must be 3x3");
        }

        LOG.debug("Applying 3x3 color matrix to {}x{} image", image.getWidth(), image.getHeight());

        if (!processWithVectors(image, matrix, maxWorkers)) {
            Pixels.transform(image, maxWorkers, rgb -> applyMatrix(rgb, matrix));
        }
    }

    static boolean processWithVectors(BufferedImage image, double[][] matrix, int maxWorkers) {
        VectorKernel kernel = VectorSupport.KERNEL;
        return kernel != null && kernel.process(image, matrix, maxWorkers);
    }

    interface VectorKernel {
        boolean process(BufferedImage image, double[][] matrix, int maxWorkers);
    }

    private static final class VectorSupport {
        private static final VectorKernel KERNEL = load();

        private static VectorKernel load() {
            // Only the optional Java 25 build contains a Vector API implementation.
            if (Runtime.version().feature() != 25
                    || ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
                return null;
            }
            try {
                Class<?> implementation = Class.forName("com.photographer.focusstack.cr3.processing.ColorMatrixVectors");
                return (VectorKernel) implementation.getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    static int applyMatrix(int rgb, double[][] matrix) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        int nr = clamp((int) (matrix[0][0] * r + matrix[0][1] * g + matrix[0][2] * b));
        int ng = clamp((int) (matrix[1][0] * r + matrix[1][1] * g + matrix[1][2] * b));
        int nb = clamp((int) (matrix[2][0] * r + matrix[2][1] * g + matrix[2][2] * b));
        return (nr << 16) | (ng << 8) | nb;
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
