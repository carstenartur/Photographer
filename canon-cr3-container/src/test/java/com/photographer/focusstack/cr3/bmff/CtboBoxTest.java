package com.photographer.focusstack.cr3.bmff;

import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CtboBoxTest {

    @Test
    void parseCtboWithEntries() throws Exception {
        // 4 bytes count + 2 entries * (4+8+8) = 4 + 40 = 44 bytes payload
        ByteBuffer payload = ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(2); // count
        // entry 1
        payload.putInt(1);
        payload.putLong(1000L);
        payload.putLong(500L);
        // entry 2
        payload.putInt(2);
        payload.putLong(2000L);
        payload.putLong(1024L);

        byte[] boxBytes = Cr3TestData.buildBox("CTBO", payload.array());
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("CTBO", boxBytes.length, 0);

        CtboBox box = CtboBox.parse(input, isoBox);

        assertThat(box.entries()).hasSize(2);
        assertThat(box.entries().get(0).index()).isEqualTo(1);
        assertThat(box.entries().get(0).offset()).isEqualTo(1000L);
        assertThat(box.entries().get(0).size()).isEqualTo(500L);
        assertThat(box.entries().get(1).index()).isEqualTo(2);
        assertThat(box.entries().get(1).offset()).isEqualTo(2000L);
        assertThat(box.entries().get(1).size()).isEqualTo(1024L);
    }

    @Test
    void parseCtboEmpty() throws Exception {
        ByteBuffer payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(0);
        byte[] boxBytes = Cr3TestData.buildBox("CTBO", payload.array());
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("CTBO", boxBytes.length, 0);

        CtboBox box = CtboBox.parse(input, isoBox);

        assertThat(box.entries()).isEmpty();
    }

    @Test
    void rejectWrongBoxType() throws Exception {
        var isoBox = new IsoBox("XXXX", 12, 0);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[12]));

        assertThatThrownBy(() -> CtboBox.parse(input, isoBox))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CTBO");
    }

    @Test
    void rejectOverflowingEntryCount() throws Exception {
        // Box declares count=1000 but only has 4 bytes of payload (just the count itself)
        ByteBuffer payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(1000); // count far exceeds available payload
        byte[] boxBytes = Cr3TestData.buildBox("CTBO", payload.array());
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("CTBO", boxBytes.length, 0);

        assertThatThrownBy(() -> CtboBox.parse(input, isoBox))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entry count");
    }

    @Test
    void rejectNegativeEntryCount() throws Exception {
        // Negative count from a malformed/corrupt box
        ByteBuffer payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(-1);
        byte[] boxBytes = Cr3TestData.buildBox("CTBO", payload.array());
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("CTBO", boxBytes.length, 0);

        assertThatThrownBy(() -> CtboBox.parse(input, isoBox))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entry count");
    }
}
