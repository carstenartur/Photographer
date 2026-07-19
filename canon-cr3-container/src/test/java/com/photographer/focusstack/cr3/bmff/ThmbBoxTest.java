package com.photographer.focusstack.cr3.bmff;

import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThmbBoxTest {

    @Test
    void parseThumbnailVersion0() throws Exception {
        byte[] jpegData = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9}; // minimal JPEG
        ByteBuffer payload = ByteBuffer.allocate(4 + 2 + 2 + 4 + 4 + jpegData.length)
                .order(ByteOrder.BIG_ENDIAN);
        payload.put((byte) 0);    // version
        payload.put(new byte[3]); // flags
        payload.putShort((short) 160);   // width
        payload.putShort((short) 120);   // height
        payload.putInt(jpegData.length); // jpegSize
        payload.putShort((short) 0);     // unk1 (version 0)
        payload.putShort((short) 0);     // unk2 (version 0)
        payload.put(jpegData);

        byte[] boxBytes = Cr3TestData.buildBox("THMB", payload.array());
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("THMB", boxBytes.length, 0);

        ThmbBox box = ThmbBox.parse(input, isoBox);

        assertThat(box.version()).isEqualTo(0);
        assertThat(box.width()).isEqualTo(160);
        assertThat(box.height()).isEqualTo(120);
        assertThat(box.jpegData()).isEqualTo(jpegData);
    }

    @Test
    void rejectWrongBoxType() throws Exception {
        var isoBox = new IsoBox("XXXX", 16, 0);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[16]));

        assertThatThrownBy(() -> ThmbBox.parse(input, isoBox))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("THMB");
    }
}
