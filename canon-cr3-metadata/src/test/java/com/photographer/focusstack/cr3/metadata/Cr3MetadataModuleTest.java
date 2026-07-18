package com.photographer.focusstack.cr3.metadata;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.FtypBox;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.bmff.IsoBox;
import com.photographer.focusstack.cr3.container.CompressionMode;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Track;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent smoke test for metadata-to-container integration. */
class Cr3MetadataModuleTest {

    @Test
    void mapsRawTrackGeometryWithoutCodecOrProcessingModules() throws Exception {
        Cmp1Box cmp1 = new Cmp1Box((short) 1, 5632, 3710,
                2816, 3710, 14, 4, 0, 0, 0,
                true, 0, false);
        Iad1Box iad1 = new Iad1Box(5632, 3710, true,
                0, 0, 5632, 3710,
                0, 0, 96, 3710,
                0, 0, 5632, 32,
                96, 32, 5632, 3710);
        Cr3Track raw = new Cr3Track(2, Cr3Track.TrackType.CRX_BIG,
                0, 1, 5632, 3710, "CRAW", CompressionMode.RAW_LOSSLESS, cmp1, iad1);
        Cr3Container container = new Cr3Container(
                new FtypBox("crx ", 1, List.of("crx ", "isom")),
                null, new IsoBox("mdat", 9, 0), List.of(), List.of(raw));

        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[0]))) {
            Cr3Metadata metadata = Cr3MetadataReader.read(input, container);
            assertThat(metadata.sensorWidth()).isEqualTo(5632);
            assertThat(metadata.sensorHeight()).isEqualTo(3710);
            assertThat(metadata.bitDepth()).isEqualTo(14);
            assertThat(metadata.activeAreaLeft()).isEqualTo(96);
            assertThat(metadata.activeAreaTop()).isEqualTo(32);
            assertThat(metadata.visibleWidth()).isEqualTo(5536);
            assertThat(metadata.visibleHeight()).isEqualTo(3678);
        }
    }
}
