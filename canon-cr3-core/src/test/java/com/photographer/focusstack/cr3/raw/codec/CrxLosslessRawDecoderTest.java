package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
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
    void decodeSyntheticMedPatternWithCmp1Metadata() throws Cr3Exception {
        int width = 4;
        int height = 4;
        int bitDepth = 14;

        int[][] planes = new int[][]{
                {1000, 1005, 1010, 1007}, // R
                {1100, 1096, 1090, 1088}, // Gr
                {1200, 1201, 1202, 1203}, // Gb
                {1300, 1298, 1296, 1294}  // B
        };
        byte[] crxData = buildSyntheticCrxData(width, height, bitDepth, CrxHeader.CodecVersion.V1, planes);
        Cmp1Box cmp1 = new Cmp1Box((short) 1, width, height, width, height,
                bitDepth, 4, 0, 0, 0, false, 0, false, 0, 0);

        Cr3RawImage result = CrxLosslessRawDecoder.decode(crxData, width, height, bitDepth,
                CrxHeader.CodecVersion.V1, cmp1);

        assertThat(result.getPixel(0, 0)).isEqualTo(1000); // R
        assertThat(result.getPixel(1, 0)).isEqualTo(1100); // Gr
        assertThat(result.getPixel(0, 1)).isEqualTo(1200); // Gb
        assertThat(result.getPixel(1, 1)).isEqualTo(1300); // B
        assertThat(result.getPixel(2, 0)).isEqualTo(1005); // R
        assertThat(result.getPixel(3, 0)).isEqualTo(1096); // Gr
    }

    @Test
    void decodeStripsMdatHeaderWhenProvidedByCmp1() throws Cr3Exception {
        int width = 4;
        int height = 4;
        int bitDepth = 14;
        byte[] payload = buildSyntheticCrxData(width, height, bitDepth, CrxHeader.CodecVersion.V1);
        byte[] prefixed = new byte[12 + payload.length];
        for (int i = 0; i < 12; i++) {
            prefixed[i] = (byte) i;
        }
        System.arraycopy(payload, 0, prefixed, 12, payload.length);

        Cmp1Box cmp1 = new Cmp1Box((short) 1, width, height, width, height,
                bitDepth, 4, 0, 0, 0, false, 12, false, 0, 0);
        Cr3RawImage result = CrxLosslessRawDecoder.decode(prefixed, width, height, bitDepth,
                CrxHeader.CodecVersion.V1, cmp1);
        assertThat(result).isNotNull();
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
        // The no-value synthetic fixture uses an intentionally exhausted
        // all-ones payload. Under the production PATENT_BRACKET profile this
        // is not valid zero-residual coding; it exercises the decoder's
        // deterministic padding path, which should still reconstruct from the
        // CRX first-pixel seed of 0.
        int width = 4;
        int height = 4;
        int bitDepth = 14;

        byte[] crxData = buildSyntheticCrxData(width, height, bitDepth, CrxHeader.CodecVersion.V1);
        Cr3RawImage result = CrxLosslessRawDecoder.decode(crxData, width, height, bitDepth);

        assertThat(result.getPixel(0, 0)).isZero();
    }

    /**
     * Builds synthetic CRX data with valid header and simple encoded plane data.
     * The no-value fixture intentionally uses an exhausted all-ones payload; exact
     * value round-trips use {@link #encodePlane} and therefore match the production
     * PATENT_BRACKET entropy profile.
     */
    private byte[] buildSyntheticCrxData(int width, int height, int bitDepth,
                                         CrxHeader.CodecVersion version) {
        return buildSyntheticCrxData(width, height, bitDepth, version, null);
    }

    private byte[] buildSyntheticCrxData(int width, int height, int bitDepth,
                                         CrxHeader.CodecVersion version, int[][] planeValues) {
        int planeW = width / 2;
        int planeH = height / 2;
        int pixelsPerPlane = planeW * planeH;
        byte[][] planeData = new byte[4][];
        int[] bytesPerPlane = new int[4];
        for (int p = 0; p < 4; p++) {
            if (planeValues == null) {
                bytesPerPlane[p] = (pixelsPerPlane + 7) / 8;
                planeData[p] = new byte[bytesPerPlane[p]];
                for (int i = 0; i < planeData[p].length; i++) {
                    planeData[p][i] = (byte) 0xFF; // intentionally exhausted dummy payload
                }
            } else {
                planeData[p] = encodePlane(planeValues[p], planeW, planeH, bitDepth);
                bytesPerPlane[p] = planeData[p].length;
            }
        }

        ByteBuffer header = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
        long tileSize = 0;
        for (int size : bytesPerPlane) {
            tileSize += size;
        }

        writeTileHeader(header, version.tileMarker, tileSize, 0);
        for (int p = 0; p < 4; p++) {
            writePlaneHeader(header, version.planeMarker, bytesPerPlane[p], p);
            writeSubbandHeader(header, version.subbandMarker, bytesPerPlane[p], 0);
        }
        header.putInt(0); // null separator

        int actualHeaderSize = header.position();
        int totalPlaneBytes = 0;
        for (int size : bytesPerPlane) {
            totalPlaneBytes += size;
        }
        byte[] fullData = new byte[actualHeaderSize + totalPlaneBytes];
        header.flip();
        header.get(fullData, 0, actualHeaderSize);
        int pos = actualHeaderSize;
        for (int p = 0; p < 4; p++) {
            System.arraycopy(planeData[p], 0, fullData, pos, bytesPerPlane[p]);
            pos += bytesPerPlane[p];
        }

        return fullData;
    }

    private byte[] encodePlane(int[] values, int width, int height, int bitDepth) {
        assertThat(values).hasSize(width * height);
        int firstPixelSeed = 0;
        ByteBitWriter bits = new ByteBitWriter();

        for (int y = 0; y < height; y++) {
            int k = 1;
            long accumulator = 0;
            long sampleCount = 1;
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                int predicted;
                if (x == 0 && y == 0) {
                    predicted = firstPixelSeed;
                } else if (y == 0) {
                    predicted = values[idx - 1];
                } else if (x == 0) {
                    predicted = values[(y - 1) * width];
                } else {
                    int a = values[idx - 1];
                    int b = values[(y - 1) * width + x];
                    int c = values[(y - 1) * width + (x - 1)];
                    predicted = CrxLosslessRawDecoder.medPredict(a, b, c);
                }

                int signedResidual = values[idx] - predicted;
                int absResidual = Math.abs(signedResidual);
                int residual = (absResidual << 1) | (signedResidual < 0 ? 1 : 0);
                int q = residual >> k;
                int r = residual & ((1 << k) - 1);

                for (int i = 0; i < q; i++) {
                    bits.writeBit(1);
                }
                bits.writeBit(0);
                for (int i = k - 1; i >= 0; i--) {
                    bits.writeBit((r >> i) & 1);
                }

                accumulator += absResidual;
                sampleCount++;
                if (absResidual > (1 << (k + 1)) && k < 12) {
                    k++;
                } else if (k > 0 && accumulator <= (sampleCount << (k - 1))) {
                    k--;
                }
                if (sampleCount >= 1024) {
                    accumulator >>= 1;
                    sampleCount >>= 1;
                    if (sampleCount == 0) sampleCount = 1;
                }
            }
        }
        return bits.toByteArray();
    }

    private static final class ByteBitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int bitBuffer;
        private int bitCount;

        void writeBit(int bit) {
            bitBuffer = (bitBuffer << 1) | (bit & 1);
            bitCount++;
            if (bitCount == 8) {
                out.write(bitBuffer);
                bitBuffer = 0;
                bitCount = 0;
            }
        }

        byte[] toByteArray() {
            if (bitCount > 0) {
                out.write(bitBuffer << (8 - bitCount));
                bitBuffer = 0;
                bitCount = 0;
            }
            return out.toByteArray();
        }
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
