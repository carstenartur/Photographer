package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CrxLossyDecoder}.
 */
class CrxLossyDecoderTest {

    @Test
    @DisplayName("isLossy returns true for CMP1 with wavelet levels > 0")
    void isLossyTrue() {
        Cmp1Box cmp1 = new Cmp1Box((short) 1, 5496, 3672,
                5496, 3672, 14, 4, 0, 0, 3, false, 0, false);
        assertThat(CrxLossyDecoder.isLossy(cmp1)).isTrue();
    }

    @Test
    @DisplayName("isLossy returns false for lossless CMP1")
    void isLossyFalse() {
        Cmp1Box cmp1 = new Cmp1Box((short) 1, 5496, 3672,
                5496, 3672, 14, 4, 0, 0, 0, false, 0, false);
        assertThat(CrxLossyDecoder.isLossy(cmp1)).isFalse();
    }

    @Test
    @DisplayName("isLossy returns false for null CMP1")
    void isLossyNull() {
        assertThat(CrxLossyDecoder.isLossy(null)).isFalse();
    }

    @Test
    @DisplayName("Rejects C-RAW with an empty tile directory")
    void rejectsMissingTileDirectory() {
        Cmp1Box cmp1 = new Cmp1Box((short) 1, 100, 100,
                50, 100, 14, 4, 0, 0, 3, true, 12, false);
        byte[] data = new byte[1000];

        assertThatThrownBy(() -> CrxLossyDecoder.decode(
                data, 100, 100, 14, CrxHeader.CodecVersion.V1, cmp1))
                .isInstanceOf(Cr3Exception.class)
                .hasMessageContaining("tile directory");
    }

    @Test
    void unsupportedFileReportsItsWaveletLevelsAndPlanes() {
        Cmp1Box cmp1 = new Cmp1Box((short) 1, 100, 100,
                100, 100, 14, 4, 0, 0, 2, false, 0, false);
        byte[] headers = CrxHeaderTest.buildMinimalHeader(1, 4, 7, CrxHeader.CodecVersion.V1);

        assertThatThrownBy(() -> CrxLossyDecoder.decode(
                headers, 100, 100, 14, CrxHeader.CodecVersion.V1, cmp1))
                .isInstanceOf(Cr3Exception.class)
                .hasMessageContaining("File has 2 wavelet levels, 4 planes.");
    }

    @Test
    @DisplayName("decode throws Cr3Exception when CMP1 is null")
    void decodeNullCmp1() {
        byte[] data = new byte[100];
        assertThatThrownBy(() -> CrxLossyDecoder.decode(
                data, 100, 100, 14, CrxHeader.CodecVersion.V1, null))
                .isInstanceOf(Cr3Exception.class)
                .hasMessageContaining("CMP1 box is missing");
    }

    @Test
    @DisplayName("decode throws Cr3Exception when CMP1 indicates lossless")
    void decodeLosslessCmp1() {
        Cmp1Box cmp1 = new Cmp1Box((short) 1, 100, 100,
                100, 100, 14, 4, 0, 0, 0, false, 0, false);
        byte[] data = new byte[100];
        assertThatThrownBy(() -> CrxLossyDecoder.decode(
                data, 100, 100, 14, CrxHeader.CodecVersion.V1, cmp1))
                .isInstanceOf(Cr3Exception.class)
                .hasMessageContaining("lossless");
    }
}
