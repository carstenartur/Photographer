package com.photographer.focusstack.cr3.bmff;

import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Cmp1BoxTest {

    private static byte[] buildCmp1Payload(int imageWidth, int imageHeight,
                                            int tileWidth, int tileHeight,
                                            int bitsPerSample, int numPlanes, int cfaLayout,
                                            int encodingType, int waveletLevels,
                                            boolean multipleHTiles, int mdatHeaderSize,
                                            boolean hasExtendedHeader) {
        // CMP1 payload is at least 33 bytes
        ByteBuffer buf = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) 0);           // unknown1 (+0)
        buf.putShort((short) 0x30);        // headerSize (+2)
        buf.putShort((short) 0x100);       // version (+4)
        buf.putShort((short) 0);           // padding (+6)
        buf.putInt(imageWidth);            // +8
        buf.putInt(imageHeight);           // +12
        buf.putInt(tileWidth);             // +16
        buf.putInt(tileHeight);            // +20
        buf.put((byte) bitsPerSample);     // +24
        int packed1 = ((numPlanes & 0xF) << 4) | (cfaLayout & 0xF);
        buf.put((byte) packed1);           // +25
        int packed2 = ((encodingType & 0xF) << 4) | (waveletLevels & 0xF);
        buf.put((byte) packed2);           // +26
        int tileFlags = multipleHTiles ? 0x80 : 0x00;
        buf.put((byte) tileFlags);         // +27
        buf.putInt(mdatHeaderSize);        // +28
        int extHeaderByte = hasExtendedHeader ? 0x80 : 0x00;
        buf.put((byte) extHeaderByte);     // +32
        return buf.array();
    }

    @Test
    void parseLosslessRgb() throws Exception {
        byte[] payload = buildCmp1Payload(6000, 4000, 6000, 4000, 14, 4, 0, 0, 0, false, 0, false);
        byte[] boxBytes = Cr3TestData.buildBox("CMP1", payload);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("CMP1", boxBytes.length, 0);

        Cmp1Box box = Cmp1Box.parse(input, isoBox);

        assertThat(box.imageWidth()).isEqualTo(6000);
        assertThat(box.imageHeight()).isEqualTo(4000);
        assertThat(box.bitsPerSample()).isEqualTo(14);
        assertThat(box.waveletLevels()).isEqualTo(0);
        assertThat(box.isLossless()).isTrue();
        assertThat(box.cfaLayoutName()).isEqualTo("RGGB");
    }

    @Test
    void parseLossyCraw() throws Exception {
        byte[] payload = buildCmp1Payload(6000, 4000, 512, 512, 14, 4, 0, 0, 3, true, 100, false);
        byte[] boxBytes = Cr3TestData.buildBox("CMP1", payload);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("CMP1", boxBytes.length, 0);

        Cmp1Box box = Cmp1Box.parse(input, isoBox);

        assertThat(box.waveletLevels()).isEqualTo(3);
        assertThat(box.isLossless()).isFalse();
        assertThat(box.multipleHTiles()).isTrue();
    }

    @Test
    void cfaLayoutNames() throws Exception {
        for (int i = 0; i < 4; i++) {
            byte[] payload = buildCmp1Payload(100, 100, 100, 100, 14, 4, i, 0, 0, false, 0, false);
            byte[] boxBytes = Cr3TestData.buildBox("CMP1", payload);
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
            var isoBox = new IsoBox("CMP1", boxBytes.length, 0);
            Cmp1Box box = Cmp1Box.parse(input, isoBox);
            assertThat(box.cfaLayout()).isEqualTo(i);
        }
    }

    @Test
    void rejectWrongBoxType() throws Exception {
        var isoBox = new IsoBox("XXXX", 16, 0);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[16]));

        assertThatThrownBy(() -> Cmp1Box.parse(input, isoBox))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CMP1");
    }
}
