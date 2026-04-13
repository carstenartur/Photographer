package com.photographer.focusstack.cr3.bmff;

import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CctpBoxTest {

    private static byte[] buildCcdtPayload(long imageType, int dualPixelFlag, int trakIndex) {
        ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(imageType);
        buf.putInt(dualPixelFlag);
        buf.putInt(trakIndex);
        return buf.array();
    }

    @Test
    void parseEmptyCctp() throws Exception {
        // CCTP with no CCDT children (12-byte header after CCTP box header)
        byte[] cctpPayload = new byte[12]; // 3 longs of CCTP header
        byte[] cctpBytes = Cr3TestData.buildBox("CCTP", cctpPayload);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cctpBytes));
        // CCTP is a container so build IsoBox with no children
        var isoBox = new IsoBox("CCTP", cctpBytes.length, 0, List.of());

        CctpBox box = CctpBox.parse(input, isoBox);

        assertThat(box.entries()).isEmpty();
    }

    @Test
    void parseCctpWithCcdtChildren() throws Exception {
        byte[] ccdtPayload1 = buildCcdtPayload(0L, 0, 1);
        byte[] ccdtBytes1 = Cr3TestData.buildBox("CCDT", ccdtPayload1);
        byte[] ccdtPayload2 = buildCcdtPayload(1L, 0, 2);
        byte[] ccdtBytes2 = Cr3TestData.buildBox("CCDT", ccdtPayload2);

        // Build IsoBox for CCTP with CCDT children
        long offset1 = 0;
        long offset2 = ccdtBytes1.length;
        var ccdt1 = new IsoBox("CCDT", ccdtBytes1.length, offset1);
        var ccdt2 = new IsoBox("CCDT", ccdtBytes2.length, offset2);
        var cctpIso = new IsoBox("CCTP", 100, 0, List.of(ccdt1, ccdt2));

        byte[] allData = Cr3TestData.concat(ccdtBytes1, ccdtBytes2);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(allData));

        CctpBox box = CctpBox.parse(input, cctpIso);

        assertThat(box.entries()).hasSize(2);
        assertThat(box.entries().get(0).imageType()).isEqualTo(0L);
        assertThat(box.entries().get(0).trakIndex()).isEqualTo(1);
        assertThat(box.entries().get(1).imageType()).isEqualTo(1L);
        assertThat(box.entries().get(1).trakIndex()).isEqualTo(2);
    }

    @Test
    void rejectWrongBoxType() throws Exception {
        var isoBox = new IsoBox("XXXX", 20, 0, List.of());
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[20]));

        assertThatThrownBy(() -> CctpBox.parse(input, isoBox))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CCTP");
    }
}
