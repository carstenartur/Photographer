package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import java.nio.ByteBuffer;
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
    void preparesEveryLowFrequencyQuantizerBeforeSelectingTiles() {
        int bandBytes = 8;
        int planeBytes = 10 * bandBytes;
        int tileBytes = 4 * planeBytes;
        int directoryBytes = 2 * (12 + 4 * (12 + 10 * 12));
        var payload = ByteBuffer.allocate(directoryBytes + 2 * tileBytes);
        for (int tile = 0; tile < 2; tile++) {
            record(payload, 0xff01, tileBytes, tile << 28);
            for (int plane = 0; plane < 4; plane++) {
                record(payload, 0xff02, planeBytes, plane << 28);
                for (int band = 0; band < 10; band++) {
                    int quantizer = band == 0 && (tile != 1 || plane != 3) ? 4 : 0;
                    record(payload, 0xff03, bandBytes, (band << 28) | (quantizer << 19));
                }
            }
        }
        var cmp = new Cmp1Box((short) 1, 32, 16, 16, 16,
                14, 4, 0, 0, 3, true, directoryBytes, false);
        assertThatThrownBy(() -> CrxSensorDecoder.prepare(payload.array(), 32, 16, 14,
                CrxHeader.CodecVersion.V1, cmp))
                .isInstanceOf(Cr3Exception.class)
                .hasMessageContaining("Unsupported low-frequency C-RAW quantizer");
    }

    private static void record(ByteBuffer bytes, int marker, int size, int flags) {
        bytes.putShort((short) marker).putShort((short) 8).putInt(size).putInt(flags);
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
