package com.photographer.focusstack.cr3.raw.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrxWaveletDetailTest {
    @Test
    void readsRunsTheirInterruptionsAndSignedDetailCoefficients() throws IOException {
        byte[] data = HexFormat.of().parseHex("ab841ccc52bcfe0671a0cd");
        assertThat(decode(data, 1, 9, 8, 4, 4)).containsExactly(
                0, 8, -24, 0, 0, 0, 8, 12,
                0, 0, 0, 0, 0, 0, 0, 4,
                0, 0, 0, 0, 0, 0, 0, 0,
                12, 0, -4, 0, 0, 4, 0, -4);
        assertThat(decode(data, 1, 9, 8, 4, 0)).containsOnly(0);
    }

    @Test
    void usesTheStoredRiceWidthAboveTheNextCoefficient() throws IOException {
        byte[] data = HexFormat.of().parseHex("2040002034fc802012202041094a94d5111180");
        assertThat(decode(data, 0, data.length, 8, 3, 1)).containsExactly(
                1, 3, 32, 45, -32, 32, 64, 100,
                0, 0, 0, 0, 1, 1, 2, 2,
                0, 0, -2, 0, 0, 0, 0, 1);
    }

    @Test
    void checksByteConsumptionBoundsAndQuantizationOverflow() {
        byte[] data = HexFormat.of().parseHex("00000000007d0900");
        assertThatThrownBy(() -> decode(data, 0, 7, 1, 1, 1)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> decode(data, 0, data.length, 1, 1, 3000))
                .isInstanceOf(IOException.class).hasMessageContaining("coefficient");
        assertThatThrownBy(() -> decode(new byte[]{-1, 0}, 0, 2, 1, 1, 1))
                .isInstanceOf(IOException.class).hasMessageContaining("Unconsumed");
        assertThatThrownBy(() -> decode(data, 1, Integer.MAX_VALUE, 1, 1, 1))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> decode(data, 0, 1, Integer.MAX_VALUE, 10, 1))
                .isInstanceOf(IOException.class);
    }
    private static int[] decode(byte[] data, int offset, int length, int width, int height, int factor)
            throws IOException {
        return WaveletTestRows.collect(CrxWaveletDetail.band(data, offset, length, width, height, factor));
    }

}
