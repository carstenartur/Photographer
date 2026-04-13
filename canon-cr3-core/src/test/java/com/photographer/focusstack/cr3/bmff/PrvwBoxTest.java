package com.photographer.focusstack.cr3.bmff;

import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrvwBoxTest {

    @Test
    void parsePreviewBox() throws Exception {
        byte[] jpegData = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        ByteBuffer payload = ByteBuffer.allocate(4 + 2 + 2 + 2 + 2 + 4 + jpegData.length)
                .order(ByteOrder.BIG_ENDIAN);
        payload.putInt(0);              // unknown (0)
        payload.putShort((short) 1);    // unknown (1)
        payload.putShort((short) 1620); // width
        payload.putShort((short) 1080); // height
        payload.putShort((short) 1);    // unknown (1)
        payload.putInt(jpegData.length);
        payload.put(jpegData);

        byte[] boxBytes = Cr3TestData.buildBox("PRVW", payload.array());
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("PRVW", boxBytes.length, 0);

        PrvwBox box = PrvwBox.parse(input, isoBox);

        assertThat(box.width()).isEqualTo(1620);
        assertThat(box.height()).isEqualTo(1080);
        assertThat(box.jpegData()).isEqualTo(jpegData);
    }

    @Test
    void rejectWrongBoxType() throws Exception {
        var isoBox = new IsoBox("XXXX", 16, 0);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[16]));

        assertThatThrownBy(() -> PrvwBox.parse(input, isoBox))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRVW");
    }
}
