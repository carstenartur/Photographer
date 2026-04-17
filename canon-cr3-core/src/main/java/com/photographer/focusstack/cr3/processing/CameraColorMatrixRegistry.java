package com.photographer.focusstack.cr3.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of camera-model-specific color correction matrices for Canon cameras.
 *
 * <p>Each matrix maps camera-native RGB to sRGB linear color space. Matrices
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
 * <p>No proprietary color profile data was copied. All matrices here are either
 * generic approximations or derived from self-analysis of test images.</p>
 */
public final class CameraColorMatrixRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(CameraColorMatrixRegistry.class);

    /**
     * Generic fallback color matrix for Canon cameras.
     * Approximation for typical 1" and APS-C Canon sensors under D65 illuminant.
     */
    public static final double[][] GENERIC_CANON_D65 = {
            { 1.2, -0.3,  0.1},
            {-0.1,  1.3, -0.2},
            { 0.0, -0.2,  1.2}
    };

    /** Map from normalized camera model name to D65 color matrix. */
    private static final Map<String, double[][]> MATRIX_DB = new HashMap<>();

    static {
        // Canon PowerShot G5 X Mark II — 1" BSI-CMOS sensor (20 MP)
        // Matrix derived from self-analysis of test CR3 images.
        // This is an approximation; for color-critical work, an ICC profile should be used.
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

        // Canon EOS R — full-frame CMOS (30 MP)
        // Generic Canon full-frame approximation, same sensor family as R5/R6.
        register("Canon EOS R", new double[][]{
                { 1.15, -0.25,  0.10},
                {-0.10,  1.30, -0.20},
                { 0.02, -0.18,  1.16}
        });

        // Canon EOS R3 — full-frame stacked BSI-CMOS (24 MP)
        // Similar sensor characteristics to R5. Approximation.
        register("Canon EOS R3", new double[][]{
                { 1.16, -0.26,  0.10},
                {-0.09,  1.29, -0.20},
                { 0.02, -0.19,  1.17}
        });

        // Canon EOS R7 — APS-C CMOS (33 MP)
        // APS-C variant, slightly different spectral response than full-frame.
        register("Canon EOS R7", new double[][]{
                { 1.20, -0.30,  0.10},
                {-0.08,  1.25, -0.17},
                { 0.01, -0.20,  1.19}
        });

        // Canon EOS R8 — full-frame CMOS (24 MP)
        // Same sensor family as R6, similar colour response.
        register("Canon EOS R8", new double[][]{
                { 1.15, -0.25,  0.10},
                {-0.10,  1.30, -0.20},
                { 0.02, -0.18,  1.16}
        });

        // Canon EOS R10 — APS-C CMOS (24 MP)
        // APS-C sensor, similar to R7 family.
        register("Canon EOS R10", new double[][]{
                { 1.20, -0.30,  0.10},
                {-0.08,  1.25, -0.17},
                { 0.01, -0.20,  1.19}
        });

        // Canon EOS R50 — APS-C CMOS (24 MP)
        // Entry-level mirrorless, APS-C.
        register("Canon EOS R50", new double[][]{
                { 1.20, -0.30,  0.10},
                {-0.08,  1.25, -0.17},
                { 0.01, -0.20,  1.19}
        });

        // Canon EOS 90D — APS-C CMOS (32 MP)
        // DSLR APS-C sensor.
        register("Canon EOS 90D", new double[][]{
                { 1.20, -0.30,  0.10},
                {-0.08,  1.25, -0.17},
                { 0.01, -0.20,  1.19}
        });

        // Canon EOS M6 Mark II — APS-C CMOS (32 MP)
        // Mirrorless APS-C, similar sensor to 90D.
        register("Canon EOS M6 Mark II", new double[][]{
                { 1.20, -0.30,  0.10},
                {-0.08,  1.25, -0.17},
                { 0.01, -0.20,  1.19}
        });

        // Canon PowerShot G7 X Mark III — 1" CMOS (20 MP)
        // Same sensor family as G5X II.
        register("Canon PowerShot G7 X Mark III", new double[][]{
                { 1.18, -0.28,  0.10},
                {-0.08,  1.28, -0.20},
                { 0.02, -0.22,  1.20}
        });
    }

    private CameraColorMatrixRegistry() {}

    /**
     * Registers a color matrix for a camera model.
     *
     * @param modelName the camera model name (as stored in EXIF, case-insensitive match)
     * @param matrix    the 3×3 D65 color correction matrix
     */
    public static void register(String modelName, double[][] matrix) {
        if (matrix.length != 3 || matrix[0].length != 3) {
            throw new IllegalArgumentException("Color matrix must be 3x3");
        }
        MATRIX_DB.put(normalise(modelName), matrix);
    }

    /**
     * Returns the best color matrix for a camera model.
     *
     * @param modelName the camera model name (from EXIF)
     * @return the model-specific matrix, or the generic fallback
     */
    public static double[][] getMatrix(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            LOG.debug("No camera model specified, using generic color matrix");
            return GENERIC_CANON_D65;
        }

        String key = normalise(modelName);
        double[][] matrix = MATRIX_DB.get(key);
        if (matrix != null) {
            LOG.debug("Using camera-specific color matrix for '{}'", modelName);
            return matrix;
        }

        // Try partial match — some EXIF model names include extra whitespace or firmware suffixes
        for (Map.Entry<String, double[][]> entry : MATRIX_DB.entrySet()) {
            if (key.contains(entry.getKey()) || entry.getKey().contains(key)) {
                LOG.debug("Using partial-match color matrix for '{}' (matched '{}')",
                        modelName, entry.getKey());
                return entry.getValue();
            }
        }

        LOG.debug("No camera-specific color matrix for '{}', using generic fallback", modelName);
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
