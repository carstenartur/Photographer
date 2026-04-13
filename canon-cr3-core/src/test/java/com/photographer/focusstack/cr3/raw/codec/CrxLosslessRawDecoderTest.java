package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link CrxLosslessRawDecoder}.
 */
class CrxLosslessRawDecoderTest {

    @Test
    void medPredictCGreaterThanMax() {
        // c >= max(a, b) → min(a, b)
        assertThat(CrxLosslessRawDecoder.medPredict(100, 200, 250)).isEqualTo(100);
    }

    @Test
    void medPredictCLessThanMin() {
        // c <= min(a, b) → max(a, b)
        assertThat(CrxLosslessRawDecoder.medPredict(100, 200, 50)).isEqualTo(200);
    }

    @Test
    void medPredictCBetween() {
        // min < c < max → a + b - c
        assertThat(CrxLosslessRawDecoder.medPredict(100, 200, 150)).isEqualTo(150);
    }

    @Test
    void medPredictAllEqual() {
        assertThat(CrxLosslessRawDecoder.medPredict(128, 128, 128)).isEqualTo(128);
    }

    @Test
    void decodeSyntheticSingleTile() throws Cr3Exception {
        int width = 4;
        int height = 4;
        int bitDepth = 14;

        byte[] crxData = buildSyntheticCrxData(width, height, bitDepth, CrxHeader.CodecVersion.V1);
        Cr3RawImage result = CrxLosslessRawDecoder.decode(crxData, width, height, bitDepth);

        assertThat(result).isNotNull();
        assertThat(result.width()).isEqualTo(width);
        assertThat(result.height()).isEqualTo(height);
        assertThat(result.bitDepth()).isEqualTo(bitDepth);
        assertThat(result.bayerPattern()).isEqualTo(Cr3RawImage.BayerPattern.RGGB);
        assertThat(result.data()).hasSize(width * height);
    }

    @Test
    void decodeProducesNonNegativePixels() throws Cr3Exception {
        int width = 4;
        int height = 4;
        int bitDepth = 14;

        byte[] crxData = buildSyntheticCrxData(width, height, bitDepth, CrxHeader.CodecVersion.V1);
        Cr3RawImage result = CrxLosslessRawDecoder.decode(crxData, width, height, bitDepth);

        for (int pixel : result.data()) {
            assertThat(pixel).isGreaterThanOrEqualTo(0);
            assertThat(pixel).isLessThanOrEqualTo(result.maxValue());
        }
    }

    @Test
    void decodeRejectsEmptyData() {
        assertThatThrownBy(() -> CrxLosslessRawDecoder.decode(new byte[0], 4, 4, 14))
                .isInstanceOf(Cr3Exception.class);
    }

    @Test
    void decodeWithCodecVersionV2() throws Cr3Exception {
        int width = 4;
        int height = 4;
        int bitDepth = 14;

        byte[] crxData = buildSyntheticCrxData(width, height, bitDepth, CrxHeader.CodecVersion.V2);
        Cr3RawImage result = CrxLosslessRawDecoder.decode(crxData, width, height, bitDepth,
                CrxHeader.CodecVersion.V2);

        assertThat(result).isNotNull();
        assertThat(result.data()).hasSize(width * height);
    }

    @Test
    void decodePreservesSymmetryForUniformInput() throws Cr3Exception {
        // With all zero residuals, each pixel should be the MED prediction
        // For the first pixel of each plane: prediction = midpoint (1<<(14-1)) = 8192
        int width = 4;
        int height = 4;
        int bitDepth = 14;
        int midpoint = 1 << (bitDepth - 1);

        byte[] crxData = buildSyntheticCrxData(width, height, bitDepth, CrxHeader.CodecVersion.V1);
        Cr3RawImage result = CrxLosslessRawDecoder.decode(crxData, width, height, bitDepth);

        // First pixel (R at [0,0]) should be the midpoint prediction
        assertThat(result.getPixel(0, 0)).isEqualTo(midpoint);
    }

    /**
     * Builds synthetic CRX data with valid header and simple encoded plane data.
     * All pixels encoded as zero residual (Rice-Golomb of 0 with k=0 = single 1-bit).
     */
    private byte[] buildSyntheticCrxData(int width, int height, int bitDepth,
                                          CrxHeader.CodecVersion version) {
        int planeW = width / 2;
        int planeH = height / 2;
        int pixelsPerPlane = planeW * planeH;
        int bytesPerPlane = (pixelsPerPlane + 7) / 8;

        ByteBuffer header = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
        long totalPlaneSize = bytesPerPlane;
        long tileSize = 4 * totalPlaneSize;

        writeTileHeader(header, version.tileMarker, tileSize, 0);
        for (int p = 0; p < 4; p++) {
            writePlaneHeader(header, version.planeMarker, totalPlaneSize, p);
            writeSubbandHeader(header, version.subbandMarker, totalPlaneSize, 0);
        }
        header.putInt(0); // null separator

        int actualHeaderSize = header.position();

        byte[] planeData = new byte[bytesPerPlane];
        for (int i = 0; i < planeData.length; i++) {
            planeData[i] = (byte) 0xFF; // all 1-bits = zero residuals
        }

        byte[] fullData = new byte[actualHeaderSize + (int) (4 * totalPlaneSize)];
        header.flip();
        header.get(fullData, 0, actualHeaderSize);
        for (int p = 0; p < 4; p++) {
            System.arraycopy(planeData, 0, fullData, actualHeaderSize + p * bytesPerPlane,
                    bytesPerPlane);
        }

        return fullData;
    }

    private static void writeTileHeader(ByteBuffer buf, int marker, long dataSize, int index) {
        buf.putShort((short) marker);
        buf.putShort((short) 8);
        buf.putInt((int) dataSize);
        buf.put((byte) ((index & 0xF) << 4));
        buf.put(new byte[3]);
    }

    private static void writePlaneHeader(ByteBuffer buf, int marker, long dataSize, int index) {
        buf.putShort((short) marker);
        buf.putShort((short) 8);
        buf.putInt((int) dataSize);
        buf.put((byte) ((index & 0xF) << 4));
        buf.put(new byte[3]);
    }

    private static void writeSubbandHeader(ByteBuffer buf, int marker, long dataSize, int index) {
        buf.putShort((short) marker);
        buf.putShort((short) 8);
        buf.putInt((int) dataSize);
        buf.put((byte) ((index & 0xF) << 4));
        buf.put(new byte[3]);
    }
}
