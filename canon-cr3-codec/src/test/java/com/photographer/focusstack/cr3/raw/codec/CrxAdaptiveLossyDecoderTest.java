package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrxAdaptiveLossyDecoderTest {
    private static final int DIRECTORY_BYTES = 872;
    private static final int QP_BYTES = 5;
    private static final int BAND_BYTES = 8;
    private static final int PLANE_BYTES = 10 * BAND_BYTES;
    private static final Cmp1Box CMP1 = new Cmp1Box((short) 0x200, 16, 16, 16, 16,
            14, 4, 0, 0, 3, false, DIRECTORY_BYTES, false);

    @Test
    void checksMapAndBandExtentsBeforeDecodingAnyPlane() {
        assertDoesNotThrow(() -> prepare(directory()));

        ByteBuffer map = directory();
        map.putInt(12, QP_BYTES + 1);
        assertThrows(Cr3Exception.class, () -> prepare(map));

        ByteBuffer band = directory();
        band.putShort(32 + 16, (short) BAND_BYTES);
        assertThrows(Cr3Exception.class, () -> prepare(band));

        ByteBuffer reserved = directory();
        reserved.putShort(32 + 18, (short) 1);
        assertThrows(Cr3Exception.class, () -> prepare(reserved));
    }

    @Test
    void resolvesFirstSubbandAfterTheSharedQuantizationMap() throws Exception {
        var input = CrxTrackInput.fromArray(directory().array());
        var header = CrxAdaptiveLossyDecoder.readDirectory(input, DIRECTORY_BYTES).header();
        var subband = header.tiles().getFirst().planes().getFirst().subbands().getFirst();
        assertEquals(DIRECTORY_BYTES + QP_BYTES, subband.dataOffset());
    }

    private static CrxSensorDecoder prepare(ByteBuffer input) throws Exception {
        return CrxSensorDecoder.prepare(input.array(), 16, 16, 14, CrxHeader.CodecVersion.V2, CMP1);
    }

    private static ByteBuffer directory() {
        int[] gains = {0, 0, 0, 0, 4, 4, 8, 8, 8, 16};
        int tileBytes = QP_BYTES + 4 * PLANE_BYTES;
        var bytes = ByteBuffer.allocate(DIRECTORY_BYTES + tileBytes);
        bytes.putShort((short) 0xff11).putShort((short) 16).putInt(tileBytes).putInt(0x4000)
                .putInt(QP_BYTES).putInt(0);
        for (int plane = 0; plane < 4; plane++) {
            bytes.putShort((short) 0xff12).putShort((short) 8).putInt(PLANE_BYTES)
                    .putInt((plane << 28) | 0x0800_0000);
            for (int band = 0; band < 10; band++) {
                bytes.putShort((short) 0xff13).putShort((short) 16).putInt(BAND_BYTES)
                        .putShort((short) (band << 12)).putShort((short) gains[band])
                        .putInt(gains[band] == 0 ? 1 : 0).putInt(0);
            }
        }
        bytes.putInt(0);
        return bytes.putInt(300).put((byte) 0x94);
    }
}
