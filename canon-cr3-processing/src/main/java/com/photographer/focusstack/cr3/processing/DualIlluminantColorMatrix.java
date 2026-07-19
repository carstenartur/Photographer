package com.photographer.focusstack.cr3.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Supports dual-illuminant color correction by interpolating between
 * D65 (daylight, ~6500K) and Standard Illuminant A (tungsten, ~2856K)
 * color matrices based on the estimated or reported color temperature.
 *
 * <h2>Theory (publicly known)</h2>
 * <p>Digital cameras may have different color responses under different
 * lighting conditions. The DNG specification (publicly available from Adobe)
 * documents the concept of dual-illuminant profiles: two 3×3 matrices
 * are specified for two reference illuminants, and the appropriate matrix
 * for a given scene is obtained by linear interpolation between them.</p>
 *
 * <h2>Interpolation approach</h2>
 * <p>The interpolation weight is computed from the correlated color temperature
 * (CCT) of the scene illuminant. At 2856K (Illuminant A), weight = 0.0
 * (use StdA matrix only). At 6500K (D65), weight = 1.0 (use D65 matrix only).
 * Intermediate temperatures are linearly interpolated.</p>
 *
 * <p>No proprietary data was used. The interpolation concept is from the
 * publicly available DNG specification (section on ForwardMatrix/ColorMatrix).
 * Matrix values are self-derived approximations.</p>
 */
public final class DualIlluminantColorMatrix {

    private static final Logger LOG = LoggerFactory.getLogger(DualIlluminantColorMatrix.class);

    /** Standard Illuminant A: correlated color temperature ~2856K (tungsten). */
    public static final int ILLUMINANT_A_CCT = 2856;

    /** D65 illuminant: correlated color temperature ~6500K (daylight). */
    public static final int D65_CCT = 6500;

    /** Map from normalized camera model to dual-illuminant entry. */
    private static final Map<String, DualEntry> DUAL_DB = new HashMap<>();

    /**
     * A pair of 3×3 matrices for two reference illuminants.
     *
     * @param d65Matrix  the matrix calibrated for D65 (daylight) illuminant
     * @param stdAMatrix the matrix calibrated for Standard Illuminant A (tungsten)
     */
    public record DualEntry(double[][] d65Matrix, double[][] stdAMatrix) {
        public DualEntry {
            if (d65Matrix.length != 3 || d65Matrix[0].length != 3
                    || stdAMatrix.length != 3 || stdAMatrix[0].length != 3) {
                throw new IllegalArgumentException("Both matrices must be 3×3");
            }
        }
    }

    static {
        // Canon EOS R5 — self-derived approximations.
        // D65 matrix: from CameraColorMatrixRegistry.
        // StdA matrix: approximation for tungsten lighting — increased red, decreased blue response.
        registerDual("Canon EOS R5",
                new double[][]{
                        { 1.15, -0.25,  0.10},
                        {-0.10,  1.30, -0.20},
                        { 0.02, -0.18,  1.16}
                },
                new double[][]{
                        { 1.05, -0.15,  0.10},
                        {-0.05,  1.20, -0.15},
                        { 0.05, -0.30,  1.25}
                }
        );

        // Canon EOS R6
        registerDual("Canon EOS R6",
                new double[][]{
                        { 1.15, -0.25,  0.10},
                        {-0.10,  1.30, -0.20},
                        { 0.02, -0.18,  1.16}
                },
                new double[][]{
                        { 1.05, -0.15,  0.10},
                        {-0.05,  1.20, -0.15},
                        { 0.05, -0.30,  1.25}
                }
        );

        // Canon PowerShot G5 X Mark II
        registerDual("Canon PowerShot G5 X Mark II",
                new double[][]{
                        { 1.18, -0.28,  0.10},
                        {-0.08,  1.28, -0.20},
                        { 0.02, -0.22,  1.20}
                },
                new double[][]{
                        { 1.08, -0.18,  0.10},
                        {-0.04,  1.18, -0.14},
                        { 0.04, -0.30,  1.26}
                }
        );
    }

    private DualIlluminantColorMatrix() {}

    /**
     * Registers a dual-illuminant matrix pair for a camera model.
     *
     * @param modelName  the camera model name (case-insensitive)
     * @param d65Matrix  D65 (daylight) matrix
     * @param stdAMatrix Standard Illuminant A (tungsten) matrix
     */
    public static void registerDual(String modelName, double[][] d65Matrix, double[][] stdAMatrix) {
        DUAL_DB.put(normalise(modelName), new DualEntry(d65Matrix, stdAMatrix));
    }

    /**
     * Returns whether a dual-illuminant profile exists for a camera model.
     *
     * @param modelName the camera model name
     * @return {@code true} if dual-illuminant matrices are registered
     */
    public static boolean hasDualProfile(String modelName) {
        return modelName != null && DUAL_DB.containsKey(normalise(modelName));
    }

    /**
     * Returns the interpolated color matrix for a given camera model and color temperature.
     *
     * <p>If no dual-illuminant profile exists for the model, falls back to
     * {@link CameraColorMatrixRegistry#getMatrix(String)}.</p>
     *
     * @param modelName       the camera model name (from EXIF)
     * @param colorTempKelvin the estimated scene color temperature in Kelvin
     * @return the interpolated 3×3 color matrix
     */
    public static double[][] getInterpolatedMatrix(String modelName, int colorTempKelvin) {
        if (modelName == null || modelName.isBlank()) {
            return CameraColorMatrixRegistry.getMatrix(modelName);
        }

        DualEntry entry = DUAL_DB.get(normalise(modelName));
        if (entry == null) {
            // No dual profile — use single-illuminant fallback
            LOG.debug("No dual-illuminant profile for '{}', using single matrix", modelName);
            return CameraColorMatrixRegistry.getMatrix(modelName);
        }

        // Compute interpolation weight: 0.0 = StdA (2856K), 1.0 = D65 (6500K)
        double weight = interpolationWeight(colorTempKelvin);
        LOG.debug("Interpolating color matrix for '{}' at {}K (D65 weight: {})",
                modelName, colorTempKelvin, weight);

        return interpolate(entry.d65Matrix(), entry.stdAMatrix(), weight);
    }

    /**
     * Computes the interpolation weight based on color temperature.
     *
     * <p>Linear interpolation between StdA (2856K, weight=0) and D65 (6500K, weight=1),
     * clamped to [0, 1].</p>
     *
     * @param cctKelvin the correlated color temperature in Kelvin
     * @return interpolation weight for D65 matrix (0.0-1.0)
     */
    static double interpolationWeight(int cctKelvin) {
        if (cctKelvin <= ILLUMINANT_A_CCT) return 0.0;
        if (cctKelvin >= D65_CCT) return 1.0;
        return (double) (cctKelvin - ILLUMINANT_A_CCT) / (D65_CCT - ILLUMINANT_A_CCT);
    }

    /**
     * Linearly interpolates between two 3×3 matrices.
     *
     * @param d65Matrix  the D65 matrix (weight 1.0)
     * @param stdAMatrix the StdA matrix (weight 0.0)
     * @param d65Weight  the interpolation weight for D65 (0.0 = all StdA, 1.0 = all D65)
     * @return the interpolated 3×3 matrix
     */
    static double[][] interpolate(double[][] d65Matrix, double[][] stdAMatrix, double d65Weight) {
        double[][] result = new double[3][3];
        double stdAWeight = 1.0 - d65Weight;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                result[i][j] = d65Weight * d65Matrix[i][j] + stdAWeight * stdAMatrix[i][j];
            }
        }
        return result;
    }

    private static String normalise(String modelName) {
        return modelName.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
