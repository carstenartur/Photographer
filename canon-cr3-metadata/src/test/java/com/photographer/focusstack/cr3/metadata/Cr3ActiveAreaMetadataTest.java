package com.photographer.focusstack.cr3.metadata;

import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.CompressionMode;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Cr3ActiveAreaMetadataTest {
    @ParameterizedTest
    @CsvSource({
            "0, 0, 7, 5, 8, 6, 8, 6",
            "1, 2, 6, 4, 7, 5, 6, 3",
            "4, 3, 4, 3, 5, 4, 1, 1",
            "2, 1, 65535, 65535, 8, 6, 6, 5",
            "2, 1, 2147483647, 2147483647, 8, 6, 6, 5"
    })
    void publishesExclusiveActiveEdgesFromInclusiveIad1(
            int left, int top, int right, int bottom,
            int expectedRight, int expectedBottom, int expectedWidth, int expectedHeight) throws Exception {
        var geometry = activeArea(left, top, right, bottom);

        Cr3Metadata metadata = read(geometry);

        assertThat(metadata.activeAreaLeft()).isEqualTo(left);
        assertThat(metadata.activeAreaTop()).isEqualTo(top);
        assertThat(metadata.activeAreaRight()).isEqualTo(expectedRight);
        assertThat(metadata.activeAreaBottom()).isEqualTo(expectedBottom);
        assertThat(metadata.visibleWidth()).isEqualTo(expectedWidth);
        assertThat(metadata.visibleHeight()).isEqualTo(expectedHeight);
        assertThat(geometry.activeRight()).isEqualTo(right);
        assertThat(geometry.activeBottom()).isEqualTo(bottom);
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 0, 0", "5, 2, 4, 4", "2, 4, 6, 3", "8, 0, 9, 5", "0, 6, 7, 7"})
    void missingOrInvalidActiveAreaFallsBackOnBothSensorDimensions(int left, int top, int right, int bottom)
            throws Exception {
        Cr3Metadata metadata = read(activeArea(left, top, right, bottom));

        assertThat(metadata.visibleWidth()).isEqualTo(8);
        assertThat(metadata.visibleHeight()).isEqualTo(6);
    }

    @Test
    void missingIad1KeepsTheFullSensorDimensions() throws Exception {
        Cr3Metadata metadata = read(null);

        assertThat(metadata.visibleWidth()).isEqualTo(8);
        assertThat(metadata.visibleHeight()).isEqualTo(6);
        assertThat(metadata.activeAreaRight()).isZero();
        assertThat(metadata.activeAreaBottom()).isZero();
    }

    private static Iad1Box activeArea(int left, int top, int right, int bottom) {
        return new Iad1Box(8, 6, true, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, left, top, right, bottom);
    }

    private static Cr3Metadata read(Iad1Box geometry) throws Exception {
        var track = new Cr3Track(0, Cr3Track.TrackType.CRX_RAW, 0, 0, 8, 6, "CRAW",
                CompressionMode.UNKNOWN, null, geometry);
        var container = new Cr3Container(null, null, null, List.of(), List.of(track));
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[0]))) {
            return Cr3MetadataReader.read(input, container);
        }
    }
}
