package com.photographer.focusstack.cr3.preview;

import com.photographer.focusstack.cr3.bmff.FtypBox;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Cr3PreviewExtractor}.
 */
class Cr3PreviewExtractorTest {

    @Test
    @DisplayName("hasPreview should return true when preview track exists")
    void hasPreviewTrue() {
        Cr3Container container = buildContainerWithTracks(
                new Cr3Track(0, Cr3Track.TrackType.JPEG_THUMBNAIL, 0, 100, 160, 120, "jpeg"),
                new Cr3Track(1, Cr3Track.TrackType.JPEG_PREVIEW, 100, 200, 1620, 1080, "jpeg")
        );
        assertThat(Cr3PreviewExtractor.hasPreview(container)).isTrue();
    }

    @Test
    @DisplayName("hasPreview should return false when no preview track")
    void hasPreviewFalse() {
        Cr3Container container = buildContainerWithTracks(
                new Cr3Track(0, Cr3Track.TrackType.JPEG_THUMBNAIL, 0, 100, 160, 120, "jpeg")
        );
        assertThat(Cr3PreviewExtractor.hasPreview(container)).isFalse();
    }

    @Test
    @DisplayName("hasThumbnail should return true when thumbnail track exists")
    void hasThumbnailTrue() {
        Cr3Container container = buildContainerWithTracks(
                new Cr3Track(0, Cr3Track.TrackType.JPEG_THUMBNAIL, 0, 100, 160, 120, "jpeg")
        );
        assertThat(Cr3PreviewExtractor.hasThumbnail(container)).isTrue();
    }

    @Test
    @DisplayName("hasThumbnail should return false when no thumbnail track")
    void hasThumbnailFalse() {
        Cr3Container container = buildContainerWithTracks(
                new Cr3Track(0, Cr3Track.TrackType.CRX_RAW, 0, 1000, 5472, 3648, "CRAW")
        );
        assertThat(Cr3PreviewExtractor.hasThumbnail(container)).isFalse();
    }

    private static Cr3Container buildContainerWithTracks(Cr3Track... tracks) {
        FtypBox ftyp = new FtypBox("crx ", 1, List.of("crx ", "isom"));
        return new Cr3Container(ftyp, null, null, List.of(), List.of(tracks));
    }
}
