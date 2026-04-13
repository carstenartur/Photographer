package com.photographer.focusstack.cr3.bmff;

import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CncvBoxTest {

    @Test
    void parseVersionString() throws Exception {
        String version = "CanonCR3_001/00.09.00/00.00.00";
        byte[] payload = version.getBytes(StandardCharsets.US_ASCII);
        byte[] boxBytes = Cr3TestData.buildBox("CNCV", payload);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("CNCV", boxBytes.length, 0);

        CncvBox box = CncvBox.parse(input, isoBox);

        assertThat(box.versionString()).isEqualTo(version);
        assertThat(box.isCraw()).isTrue();
        assertThat(box.isRollBurst()).isFalse();
    }

    @Test
    void trimNullBytes() throws Exception {
        byte[] payload = new byte[30];
        byte[] versionBytes = "CanonCR3_001".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(versionBytes, 0, payload, 0, versionBytes.length);
        byte[] boxBytes = Cr3TestData.buildBox("CNCV", payload);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("CNCV", boxBytes.length, 0);

        CncvBox box = CncvBox.parse(input, isoBox);

        assertThat(box.versionString()).isEqualTo("CanonCR3_001");
        assertThat(box.isCraw()).isTrue();
    }

    @Test
    void rollBurstDetection() throws Exception {
        String version = "CanonCR3_001/01.09.00/00.00.00";
        byte[] payload = version.getBytes(StandardCharsets.US_ASCII);
        byte[] boxBytes = Cr3TestData.buildBox("CNCV", payload);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("CNCV", boxBytes.length, 0);

        CncvBox box = CncvBox.parse(input, isoBox);

        assertThat(box.isRollBurst()).isTrue();
    }

    @Test
    void rejectWrongBoxType() throws Exception {
        byte[] boxBytes = Cr3TestData.buildBox("XXXX", new byte[4]);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("XXXX", boxBytes.length, 0);

        assertThatThrownBy(() -> CncvBox.parse(input, isoBox))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CNCV");
    }
}
