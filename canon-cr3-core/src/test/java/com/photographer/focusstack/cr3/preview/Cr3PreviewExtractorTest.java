package com.photographer.focusstack.cr3.preview;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.FtypBox;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.testutil.Cr3TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link Cr3PreviewExtractor}.
 */
class Cr3PreviewExtractorTest {

    @Nested
    @DisplayName("Track type detection")
    class TrackTypeDetection {

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
    }

    @Nested
    @DisplayName("JPEG extraction from synthetic CR3")
    class JpegExtraction {

        @Test
        @DisplayName("extractPreview should decode embedded JPEG from CR3 data")
        void extractPreviewFromCr3() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            Cr3Container container = Cr3Parser.parse(input);
            BufferedImage preview = Cr3PreviewExtractor.extractPreview(input, container);

            assertThat(preview).isNotNull();
            assertThat(preview.getWidth()).isGreaterThan(0);
            assertThat(preview.getHeight()).isGreaterThan(0);
        }

        @Test
        @DisplayName("extractThumbnail should decode embedded JPEG from CR3 data")
        void extractThumbnailFromCr3() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            Cr3Container container = Cr3Parser.parse(input);
            BufferedImage thumbnail = Cr3PreviewExtractor.extractThumbnail(input, container);

            assertThat(thumbnail).isNotNull();
            assertThat(thumbnail.getWidth()).isGreaterThan(0);
            assertThat(thumbnail.getHeight()).isGreaterThan(0);
        }

        @Test
        @DisplayName("extractPreviewBytes should return valid JPEG bytes")
        void extractPreviewBytesFromCr3() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            Cr3Container container = Cr3Parser.parse(input);
            byte[] jpegBytes = Cr3PreviewExtractor.extractPreviewBytes(input, container);

            assertThat(jpegBytes).isNotNull();
            assertThat(jpegBytes.length).isGreaterThan(0);
            // JPEG starts with SOI marker 0xFF 0xD8
            assertThat(jpegBytes[0]).isEqualTo((byte) 0xFF);
            assertThat(jpegBytes[1]).isEqualTo((byte) 0xD8);
        }

        @Test
        @DisplayName("extractThumbnailBytes should return valid JPEG bytes")
        void extractThumbnailBytesFromCr3() throws Exception {
            byte[] cr3Data = Cr3TestData.buildMinimalCr3WithJpeg();
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(cr3Data));

            Cr3Container container = Cr3Parser.parse(input);
            byte[] jpegBytes = Cr3PreviewExtractor.extractThumbnailBytes(input, container);

            assertThat(jpegBytes).isNotNull();
            assertThat(jpegBytes.length).isGreaterThan(0);
            // JPEG starts with SOI marker
            assertThat(jpegBytes[0]).isEqualTo((byte) 0xFF);
            assertThat(jpegBytes[1]).isEqualTo((byte) 0xD8);
        }
    }

    @Nested
    @DisplayName("Error cases")
    class ErrorCases {

        @Test
        @DisplayName("extractPreview should throw when no preview track exists")
        void extractPreviewNoTrack() {
            Cr3Container container = buildContainerWithTracks(
                    new Cr3Track(0, Cr3Track.TrackType.CRX_RAW, 0, 1000, 5472, 3648, "CRAW")
            );
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[2000]));
            assertThatThrownBy(() -> Cr3PreviewExtractor.extractPreview(input, container))
                    .isInstanceOf(Cr3Exception.class)
                    .hasMessageContaining("No JPEG preview");
        }

        @Test
        @DisplayName("extractThumbnail should throw when no thumbnail track exists")
        void extractThumbnailNoTrack() {
            Cr3Container container = buildContainerWithTracks(
                    new Cr3Track(0, Cr3Track.TrackType.JPEG_PREVIEW, 0, 1000, 1620, 1080, "jpeg")
            );
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[2000]));
            assertThatThrownBy(() -> Cr3PreviewExtractor.extractThumbnail(input, container))
                    .isInstanceOf(Cr3Exception.class)
                    .hasMessageContaining("No JPEG thumbnail");
        }

        @Test
        @DisplayName("extractPreviewBytes should throw when no preview track exists")
        void extractPreviewBytesNoTrack() {
            Cr3Container container = buildContainerWithTracks(
                    new Cr3Track(0, Cr3Track.TrackType.CRX_RAW, 0, 1000, 5472, 3648, "CRAW")
            );
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[2000]));
            assertThatThrownBy(() -> Cr3PreviewExtractor.extractPreviewBytes(input, container))
                    .isInstanceOf(Cr3Exception.class)
                    .hasMessageContaining("No JPEG preview");
        }

        @Test
        @DisplayName("should throw when track data size is zero")
        void extractPreviewZeroSize() {
            Cr3Container container = buildContainerWithTracks(
                    new Cr3Track(0, Cr3Track.TrackType.JPEG_PREVIEW, 0, 0, 1620, 1080, "jpeg")
            );
            var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(new byte[100]));
            assertThatThrownBy(() -> Cr3PreviewExtractor.extractPreviewBytes(input, container))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("no data");
        }
    }

    private static Cr3Container buildContainerWithTracks(Cr3Track... tracks) {
        FtypBox ftyp = new FtypBox("crx ", 1, List.of("crx ", "isom"));
        return new Cr3Container(ftyp, null, null, List.of(), List.of(tracks));
    }
}
