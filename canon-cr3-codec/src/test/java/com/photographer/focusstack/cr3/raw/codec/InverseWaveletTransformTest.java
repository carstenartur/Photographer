package com.photographer.focusstack.cr3.raw.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link InverseWaveletTransform}.
 *
 * <p>The Le Gall 5/3 filter is defined in ITU-T T.800 (JPEG 2000 Part 1).
 * Test vectors are self-computed from the public forward transform definition.</p>
 */
class InverseWaveletTransformTest {

    @Test
    @DisplayName("1D inverse of constant signal reconstructs correctly")
    void inverseConstantSignal() {
        // A constant signal [c, c, c, c] after 5/3 forward transform has:
        // lowpass = [c, c] and highpass = [0, 0]
        // Inverse should reconstruct [c, c, c, c]
        int c = 100;
        int[] lowpass = {c, c};
        int[] highpass = {0, 0};
        int[] result = InverseWaveletTransform.inverseSynthesis1D(
                lowpass, highpass, InverseWaveletTransform.FilterType.LEGALL_5_3);
        assertThat(result).hasSize(4);
        // Values should all be close to c
        for (int v : result) {
            assertThat(v).isBetween(c - 1, c + 1);
        }
    }

    @Test
    @DisplayName("1D inverse round-trips a simple ramp")
    void inverseRamp() {
        // Forward Le Gall 5/3 lifting on signal [10, 20, 30, 40]:
        // Step 1 (predict): d[i] = x[2i+1] - floor((x[2i] + x[2i+2]) / 2)
        // For boundary handling (mirror extension):
        //   d[0] = 20 - (10 + 30)/2 = 20 - 20 = 0
        //   d[1] = 40 - (30 + 30)/2 = 40 - 30 = 10  (mirror: x[4] = x[2] = 30)
        // Step 2 (update): s[i] = x[2i] + floor((d[i-1] + d[i] + 2) / 4)
        //   s[0] = 10 + (0 + 0 + 2) / 4 = 10 + 0 = 10  (mirror: d[-1] = d[0] = 0)
        //   s[1] = 30 + (0 + 10 + 2) / 4 = 30 + 3 = 33
        // So lowpass = [10, 33], highpass = [0, 10]
        int[] lowpass = {10, 33};
        int[] highpass = {0, 10};
        int[] result = InverseWaveletTransform.inverseSynthesis1D(
                lowpass, highpass, InverseWaveletTransform.FilterType.LEGALL_5_3);
        assertThat(result).hasSize(4);
        // Due to integer rounding, expect close to original [10, 20, 30, 40]
        assertThat(result[0]).isBetween(9, 11);
        assertThat(result[1]).isBetween(19, 21);
        assertThat(result[2]).isBetween(29, 31);
        assertThat(result[3]).isBetween(39, 41);
    }

    @Test
    @DisplayName("1D inverse with single element")
    void inverseSingleElement() {
        int[] lowpass = {42};
        int[] highpass = {};
        int[] result = InverseWaveletTransform.inverseSynthesis1D(
                lowpass, highpass, InverseWaveletTransform.FilterType.LEGALL_5_3);
        assertThat(result).hasSize(1);
        assertThat(result[0]).isEqualTo(42);
    }

    @Test
    @DisplayName("1D inverse with multiple lowpass but no highpass returns all coefficients")
    void inverseMultipleLowpassNoHighpass() {
        int[] lowpass = {10, 20, 30};
        int[] highpass = {};
        int[] result = InverseWaveletTransform.inverseSynthesis1D(
                lowpass, highpass, InverseWaveletTransform.FilterType.LEGALL_5_3);
        assertThat(result).hasSize(3);
        assertThat(result[0]).isEqualTo(10);
        assertThat(result[1]).isEqualTo(20);
        assertThat(result[2]).isEqualTo(30);
    }

    @Test
    @DisplayName("CDF 9/7 throws UnsupportedOperationException")
    void cdf97NotImplemented() {
        int[] lowpass = {10, 20};
        int[] highpass = {1, 2};
        assertThatThrownBy(() -> InverseWaveletTransform.inverseSynthesis1D(
                lowpass, highpass, InverseWaveletTransform.FilterType.CDF_9_7))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("CDF 9/7");
    }

    @Test
    @DisplayName("2D inverse of uniform subbands produces uniform output")
    void inverse2DUniform() {
        int val = 50;
        int halfW = 2, halfH = 2;
        int outW = 4, outH = 4;
        int[] ll = {val, val, val, val};
        int[] lh = {0, 0, 0, 0};
        int[] hl = {0, 0, 0, 0};
        int[] hh = {0, 0, 0, 0};

        int[] result = InverseWaveletTransform.inverseSynthesis2D(
                ll, lh, hl, hh, halfW, halfH, outW, outH,
                InverseWaveletTransform.FilterType.LEGALL_5_3);

        assertThat(result).hasSize(outW * outH);
        // All values should be close to val
        for (int v : result) {
            assertThat(v).isBetween(val - 1, val + 1);
        }
    }

    @Test
    @DisplayName("2D inverse output has correct dimensions")
    void inverse2DDimensions() {
        int halfW = 3, halfH = 4;
        int outW = 6, outH = 8;
        int[] ll = new int[halfW * halfH];
        int[] lh = new int[halfW * halfH];
        int[] hl = new int[halfW * halfH];
        int[] hh = new int[halfW * halfH];

        int[] result = InverseWaveletTransform.inverseSynthesis2D(
                ll, lh, hl, hh, halfW, halfH, outW, outH,
                InverseWaveletTransform.FilterType.LEGALL_5_3);

        assertThat(result).hasSize(outW * outH);
    }
}
