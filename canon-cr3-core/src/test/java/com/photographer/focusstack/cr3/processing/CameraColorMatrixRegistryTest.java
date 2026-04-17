package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CameraColorMatrixRegistry}.
 */
class CameraColorMatrixRegistryTest {

    @Test
    @DisplayName("Known camera model returns specific matrix")
    void knownModel() {
        double[][] matrix = CameraColorMatrixRegistry.getMatrix("Canon PowerShot G5 X Mark II");
        assertThat(matrix).isNotNull();
        assertThat(matrix.length).isEqualTo(3);
        assertThat(matrix[0].length).isEqualTo(3);
        // Should be G5X-specific, not the generic fallback
        assertThat(matrix[0][0]).isNotEqualTo(CameraColorMatrixRegistry.GENERIC_CANON_D65[0][0]);
    }

    @Test
    @DisplayName("Unknown camera model falls back to generic")
    void unknownModel() {
        double[][] matrix = CameraColorMatrixRegistry.getMatrix("Unknown Camera XYZ");
        assertThat(matrix).isEqualTo(CameraColorMatrixRegistry.GENERIC_CANON_D65);
    }

    @Test
    @DisplayName("Null model name falls back to generic")
    void nullModel() {
        double[][] matrix = CameraColorMatrixRegistry.getMatrix(null);
        assertThat(matrix).isEqualTo(CameraColorMatrixRegistry.GENERIC_CANON_D65);
    }

    @Test
    @DisplayName("Empty model name falls back to generic")
    void emptyModel() {
        double[][] matrix = CameraColorMatrixRegistry.getMatrix("");
        assertThat(matrix).isEqualTo(CameraColorMatrixRegistry.GENERIC_CANON_D65);
    }

    @Test
    @DisplayName("hasMatrix returns true for known models")
    void hasMatrixKnown() {
        assertThat(CameraColorMatrixRegistry.hasMatrix("Canon PowerShot G5 X Mark II")).isTrue();
        assertThat(CameraColorMatrixRegistry.hasMatrix("Canon EOS R5")).isTrue();
    }

    @Test
    @DisplayName("hasMatrix returns false for unknown models")
    void hasMatrixUnknown() {
        assertThat(CameraColorMatrixRegistry.hasMatrix("Unknown")).isFalse();
        assertThat(CameraColorMatrixRegistry.hasMatrix(null)).isFalse();
    }

    @Test
    @DisplayName("Case-insensitive model lookup")
    void caseInsensitive() {
        double[][] lower = CameraColorMatrixRegistry.getMatrix("canon powershot g5 x mark ii");
        double[][] upper = CameraColorMatrixRegistry.getMatrix("CANON POWERSHOT G5 X MARK II");
        assertThat(lower).isEqualTo(upper);
    }

    @Test
    @DisplayName("Canon EOS R has a specific matrix")
    void eosR() {
        assertThat(CameraColorMatrixRegistry.hasMatrix("Canon EOS R")).isTrue();
        double[][] matrix = CameraColorMatrixRegistry.getMatrix("Canon EOS R");
        assertThat(matrix).isNotNull();
        assertThat(matrix.length).isEqualTo(3);
    }

    @Test
    @DisplayName("Canon EOS R3 has a specific matrix")
    void eosR3() {
        assertThat(CameraColorMatrixRegistry.hasMatrix("Canon EOS R3")).isTrue();
    }

    @Test
    @DisplayName("Canon EOS R7 has an APS-C specific matrix")
    void eosR7() {
        assertThat(CameraColorMatrixRegistry.hasMatrix("Canon EOS R7")).isTrue();
        double[][] matrix = CameraColorMatrixRegistry.getMatrix("Canon EOS R7");
        // APS-C matrix has different values than full-frame
        assertThat(matrix[0][0]).isNotEqualTo(CameraColorMatrixRegistry.getMatrix("Canon EOS R5")[0][0]);
    }

    @Test
    @DisplayName("Canon EOS R10 has a specific matrix")
    void eosR10() {
        assertThat(CameraColorMatrixRegistry.hasMatrix("Canon EOS R10")).isTrue();
    }

    @Test
    @DisplayName("Canon EOS 90D has a specific matrix")
    void eos90D() {
        assertThat(CameraColorMatrixRegistry.hasMatrix("Canon EOS 90D")).isTrue();
    }

    @Test
    @DisplayName("Canon PowerShot G7 X Mark III has a specific matrix")
    void g7xIii() {
        assertThat(CameraColorMatrixRegistry.hasMatrix("Canon PowerShot G7 X Mark III")).isTrue();
    }
}
