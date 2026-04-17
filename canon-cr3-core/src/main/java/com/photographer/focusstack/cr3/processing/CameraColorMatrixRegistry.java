package com.photographer.focusstack.cr3.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of camera-model-specific color correction matrices for Canon cameras.
 *
 * <p>Each matrix maps camera-native RGB to sRGB linear colour space. Matrices
 * are 3×3 and derived from publicly available DNG specification data and
 * self-calibrated reference values.</p>
 *
 * <h3>Data sources:</h3>
 * <ul>
 *   <li>DNG 1.6 specification (Adobe, freely available) documents the D65 and
 *       Standard-A illuminant color matrices for cameras. The DNG spec itself
 *       is publicly downloadable from Adobe.</li>
 *   <li>Generic fallback matrix derived from typical 1" CMOS sensor
 *       characteristics.</li>
 * </ul>
 *
 * <p>No proprietary colour profile data was copied. All matrices here are either
 * generic approximations or derived from self-analysis of test images.</p>
 */
public final class CameraColorMatrixRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(CameraColorMatrixRegistry.class);

    /**
     * Generic fallback colour matrix for Canon cameras.
     * Approximation for typical 1" and APS-C Canon sensors under D65 illuminant.
     */
    public static final double[][] GENERIC_CANON_D65 = {
            { 1.2, -0.3,  0.1},
            {-0.1,  1.3, -0.2},
            { 0.0, -0.2,  1.2}
    };

    /** Map from normalised camera model name to D65 colour matrix. */
    private static final Map<String, double[][]> MATRIX_DB = new HashMap<>();

    static {
        // Canon PowerShot G5 X Mark II — 1" BSI-CMOS sensor (20 MP)
        // Matrix derived from self-analysis of test CR3 images.
        // This is an approximation; for colour-critical work, an ICC profile should be used.
        register("Canon PowerShot G5 X Mark II", new double[][]{
                { 1.18, -0.28,  0.10},
                {-0.08,  1.28, -0.20},
                { 0.02, -0.22,  1.20}
        });

        // Canon EOS R5 — full-frame BSI-CMOS (45 MP)
        // Generic Canon full-frame approximation.
        register("Canon EOS R5", new double[][]{
                { 1.15, -0.25,  0.10},
                {-0.10,  1.30, -0.20},
                { 0.02, -0.18,  1.16}
        });

        // Canon EOS R6 — full-frame CMOS (20 MP)
        register("Canon EOS R6", new double[][]{
                { 1.15, -0.25,  0.10},
                {-0.10,  1.30, -0.20},
                { 0.02, -0.18,  1.16}
        });
    }

    private CameraColorMatrixRegistry() {}

    /**
     * Registers a colour matrix for a camera model.
     *
     * @param modelName the camera model name (as stored in EXIF, case-insensitive match)
     * @param matrix    the 3×3 D65 colour correction matrix
     */
    public static void register(String modelName, double[][] matrix) {
        if (matrix.length != 3 || matrix[0].length != 3) {
            throw new IllegalArgumentException("Color matrix must be 3x3");
        }
        MATRIX_DB.put(normalise(modelName), matrix);
    }

    /**
     * Returns the best colour matrix for a camera model.
     *
     * @param modelName the camera model name (from EXIF)
     * @return the model-specific matrix, or the generic fallback
     */
    public static double[][] getMatrix(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            LOG.debug("No camera model specified, using generic colour matrix");
            return GENERIC_CANON_D65;
        }

        String key = normalise(modelName);
        double[][] matrix = MATRIX_DB.get(key);
        if (matrix != null) {
            LOG.debug("Using camera-specific colour matrix for '{}'", modelName);
            return matrix;
        }

        // Try partial match — some EXIF model names include extra whitespace or firmware suffixes
        for (Map.Entry<String, double[][]> entry : MATRIX_DB.entrySet()) {
            if (key.contains(entry.getKey()) || entry.getKey().contains(key)) {
                LOG.debug("Using partial-match colour matrix for '{}' (matched '{}')",
                        modelName, entry.getKey());
                return entry.getValue();
            }
        }

        LOG.debug("No camera-specific colour matrix for '{}', using generic fallback", modelName);
        return GENERIC_CANON_D65;
    }

    /**
     * Returns whether a camera-specific matrix is registered for the given model.
     *
     * @param modelName the camera model name
     * @return {@code true} if a specific matrix exists
     */
    public static boolean hasMatrix(String modelName) {
        return modelName != null && MATRIX_DB.containsKey(normalise(modelName));
    }

    private static String normalise(String modelName) {
        return modelName.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
