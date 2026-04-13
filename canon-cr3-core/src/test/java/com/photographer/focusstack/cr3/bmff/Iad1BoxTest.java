package com.photographer.focusstack.cr3.bmff;

import com.photographer.focusstack.cr3.container.SensorCropGeometry;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Iad1BoxTest {

    private static byte[] buildIad1Payload48(int imageWidth, int imageHeight,
                                              int cropLeft, int cropTop, int cropRight, int cropBottom,
                                              int activeLeft, int activeTop, int activeRight, int activeBottom) {
        ByteBuffer buf = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) 0);             // +0
        buf.putShort((short) 0);             // +2
        buf.putShort((short) imageWidth);    // +4
        buf.putShort((short) imageHeight);   // +6
        buf.putShort((short) 1);             // +8
        buf.putShort((short) 2);             // +10: slicedFlag=2 -> isBigImage
        buf.putShort((short) 1);             // +12
        buf.putShort((short) 0);             // +14
        buf.putShort((short) cropLeft);      // +16
        buf.putShort((short) cropTop);       // +18
        buf.putShort((short) cropRight);     // +20
        buf.putShort((short) cropBottom);    // +22
        buf.putShort((short) 0);             // leftOb +24
        buf.putShort((short) 0);             // leftOb +26
        buf.putShort((short) 0);             // leftOb +28
        buf.putShort((short) 0);             // leftOb +30
        buf.putShort((short) 0);             // topOb +32
        buf.putShort((short) 0);             // topOb +34
        buf.putShort((short) 0);             // topOb +36
        buf.putShort((short) 0);             // topOb +38
        buf.putShort((short) activeLeft);    // +40
        buf.putShort((short) activeTop);     // +42
        buf.putShort((short) activeRight);   // +44
        buf.putShort((short) activeBottom);  // +46
        return buf.array();
    }

    @Test
    void parseFullIad1Box() throws Exception {
        byte[] payload = buildIad1Payload48(6000, 4000, 10, 8, 5990, 3992, 20, 16, 5980, 3984);
        byte[] boxBytes = Cr3TestData.buildBox("IAD1", payload);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("IAD1", boxBytes.length, 0);

        Iad1Box box = Iad1Box.parse(input, isoBox);

        assertThat(box.imageWidth()).isEqualTo(6000);
        assertThat(box.imageHeight()).isEqualTo(4000);
        assertThat(box.isBigImage()).isTrue();
        assertThat(box.cropLeft()).isEqualTo(10);
        assertThat(box.activeLeft()).isEqualTo(20);
        assertThat(box.activeRight()).isEqualTo(5980);
    }

    @Test
    void toSensorCropGeometry() throws Exception {
        byte[] payload = buildIad1Payload48(6000, 4000, 0, 0, 0, 0, 20, 16, 5980, 3984);
        byte[] boxBytes = Cr3TestData.buildBox("IAD1", payload);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("IAD1", boxBytes.length, 0);

        Iad1Box box = Iad1Box.parse(input, isoBox);
        SensorCropGeometry geometry = box.toSensorCropGeometry(6000, 4000);

        assertThat(geometry.sensorWidth()).isEqualTo(6000);
        assertThat(geometry.sensorHeight()).isEqualTo(4000);
        assertThat(geometry.activeLeft()).isEqualTo(20);
        assertThat(geometry.activeTop()).isEqualTo(16);
        assertThat(geometry.activeRight()).isEqualTo(5980);
        assertThat(geometry.activeBottom()).isEqualTo(3984);
    }

    @Test
    void rejectWrongBoxType() throws Exception {
        var isoBox = new IsoBox("XXXX", 24, 0);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[24]));

        assertThatThrownBy(() -> Iad1Box.parse(input, isoBox))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IAD1");
    }

    @Test
    void toSensorCropGeometryClampsOverflowingValues() throws Exception {
        // Simulate firmware bug: active area extends past sensor edges
        byte[] payload = buildIad1Payload48(6000, 4000, 0, 0, 0, 0, 20, 16, 6010, 4005);
        byte[] boxBytes = Cr3TestData.buildBox("IAD1", payload);
        var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(boxBytes));
        var isoBox = new IsoBox("IAD1", boxBytes.length, 0);

        Iad1Box box = Iad1Box.parse(input, isoBox);
        SensorCropGeometry geometry = box.toSensorCropGeometry(6000, 4000);

        // Right and bottom should be clamped to sensor dimensions
        assertThat(geometry.activeRight()).isEqualTo(6000);
        assertThat(geometry.activeBottom()).isEqualTo(4000);
        assertThat(geometry.activeLeft()).isEqualTo(20);
        assertThat(geometry.activeTop()).isEqualTo(16);
    }
}
