package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link DualIlluminantColorMatrix}.
 */
class DualIlluminantColorMatrixTest {

    @Test
    @DisplayName("Interpolation weight is 0 at Standard Illuminant A temperature")
    void weightAtStdA() {
        assertThat(DualIlluminantColorMatrix.interpolationWeight(
                DualIlluminantColorMatrix.ILLUMINANT_A_CCT)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Interpolation weight is 1 at D65 temperature")
    void weightAtD65() {
        assertThat(DualIlluminantColorMatrix.interpolationWeight(
                DualIlluminantColorMatrix.D65_CCT)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Interpolation weight is ~0.5 at midpoint temperature")
    void weightAtMidpoint() {
        int midpoint = (DualIlluminantColorMatrix.ILLUMINANT_A_CCT + DualIlluminantColorMatrix.D65_CCT) / 2;
        double weight = DualIlluminantColorMatrix.interpolationWeight(midpoint);
        assertThat(weight).isCloseTo(0.5, within(0.02));
    }

    @Test
    @DisplayName("Weight is clamped to 0 below StdA")
    void weightBelowStdA() {
        assertThat(DualIlluminantColorMatrix.interpolationWeight(2000)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Weight is clamped to 1 above D65")
    void weightAboveD65() {
        assertThat(DualIlluminantColorMatrix.interpolationWeight(10000)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Interpolate returns D65 matrix at weight 1.0")
    void interpolateD65() {
        double[][] d65 = {{1.0, 2.0, 3.0}, {4.0, 5.0, 6.0}, {7.0, 8.0, 9.0}};
        double[][] stdA = {{0.0, 0.0, 0.0}, {0.0, 0.0, 0.0}, {0.0, 0.0, 0.0}};
        double[][] result = DualIlluminantColorMatrix.interpolate(d65, stdA, 1.0);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertThat(result[i][j]).isEqualTo(d65[i][j]);
            }
        }
    }

    @Test
    @DisplayName("Interpolate returns StdA matrix at weight 0.0")
    void interpolateStdA() {
        double[][] d65 = {{0.0, 0.0, 0.0}, {0.0, 0.0, 0.0}, {0.0, 0.0, 0.0}};
        double[][] stdA = {{1.0, 2.0, 3.0}, {4.0, 5.0, 6.0}, {7.0, 8.0, 9.0}};
        double[][] result = DualIlluminantColorMatrix.interpolate(d65, stdA, 0.0);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertThat(result[i][j]).isEqualTo(stdA[i][j]);
            }
        }
    }

    @Test
    @DisplayName("Interpolate at 0.5 returns average of both matrices")
    void interpolateHalf() {
        double[][] d65 = {{2.0, 0.0, 0.0}, {0.0, 2.0, 0.0}, {0.0, 0.0, 2.0}};
        double[][] stdA = {{1.0, 0.0, 0.0}, {0.0, 1.0, 0.0}, {0.0, 0.0, 1.0}};
        double[][] result = DualIlluminantColorMatrix.interpolate(d65, stdA, 0.5);
        assertThat(result[0][0]).isCloseTo(1.5, within(0.001));
        assertThat(result[1][1]).isCloseTo(1.5, within(0.001));
        assertThat(result[2][2]).isCloseTo(1.5, within(0.001));
    }

    @Test
    @DisplayName("Known camera with dual profile returns interpolated matrix")
    void knownCameraDualProfile() {
        assertThat(DualIlluminantColorMatrix.hasDualProfile("Canon EOS R5")).isTrue();

        // At 6500K (D65), should return D65 matrix
        double[][] d65Result = DualIlluminantColorMatrix.getInterpolatedMatrix("Canon EOS R5", 6500);
        double[][] d65Reference = CameraColorMatrixRegistry.getMatrix("Canon EOS R5");
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertThat(d65Result[i][j]).isCloseTo(d65Reference[i][j], within(0.001));
            }
        }
    }

    @Test
    @DisplayName("Unknown camera falls back to single-illuminant registry")
    void unknownCameraFallback() {
        assertThat(DualIlluminantColorMatrix.hasDualProfile("Unknown Camera")).isFalse();
        double[][] result = DualIlluminantColorMatrix.getInterpolatedMatrix("Unknown Camera", 5000);
        assertThat(result).isEqualTo(CameraColorMatrixRegistry.GENERIC_CANON_D65);
    }

    @Test
    @DisplayName("Null model falls back to generic matrix")
    void nullModelFallback() {
        double[][] result = DualIlluminantColorMatrix.getInterpolatedMatrix(null, 5000);
        assertThat(result).isEqualTo(CameraColorMatrixRegistry.GENERIC_CANON_D65);
    }

    @Test
    @DisplayName("G5X II has dual profile")
    void g5xIiDualProfile() {
        assertThat(DualIlluminantColorMatrix.hasDualProfile("Canon PowerShot G5 X Mark II")).isTrue();
    }

    @Test
    @DisplayName("Interpolated matrix at tungsten temp differs from daylight")
    void tungstenDiffersFromDaylight() {
        double[][] tungsten = DualIlluminantColorMatrix.getInterpolatedMatrix("Canon EOS R5", 2856);
        double[][] daylight = DualIlluminantColorMatrix.getInterpolatedMatrix("Canon EOS R5", 6500);
        // They should differ — tungsten shifts red/blue response
        boolean hasDifference = false;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (Math.abs(tungsten[i][j] - daylight[i][j]) > 0.01) {
                    hasDifference = true;
                    break;
                }
            }
        }
        assertThat(hasDifference).isTrue();
    }
}
